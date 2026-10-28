#!/usr/bin/env python3
"""outbox_poll.sh 결과를 단계별 지연 · 유휴 DB 비용 · 몰림으로 정리한다 (100-outbox-polling.md).

사용: python3 docs/alert/experiments/outbox_poll_analyze.py <OUT 폴더>   → 화면과 <OUT>/summary.md

시계 : created_at 은 PG 의 now()(Docker VM 시계), 가짜 Slack 수신 · updated_at 은 호스트 시계(Notifier JVM).
       manifest 의 PG_MINUS_HOST_MS 로 created_at 을 호스트 시계로 옮긴다. 선점 시각은 완료 때 lease_until 이 지워져 남지 않는다
지연 : 생김 → 가짜 Slack 수신(= 그룹 대기 + 폴링 대기 + 선점 + 전송), 수신 → 완료 기록(updated_at)
"""
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

out = []


def say(line=""):
    print(line)
    out.append(line)


def pct(xs, p):
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def env(path):
    return dict(l.split("=", 1) for l in path.read_text().splitlines() if "=" in l)


def receipts(root):
    """rule_name → 처음 받은 시각(ms), 메시지 수"""
    first, messages = {}, []
    for line in (root / "slack.log").read_text().splitlines():
        stamp, _, body = line.partition("\t")
        t = int(datetime.fromisoformat(stamp).timestamp() * 1000)
        names = set(re.findall(r"poll-[a-z]+-\d+", body))
        messages.append((t, names))
        for n in names:
            first.setdefault(n, t)
    return first, messages


def cputime(s):  # ps -o cputime : [[hh:]mm:]ss.cc
    parts = [float(p) for p in s.strip().split(":")] if s.strip() not in ("", "0") else [0.0]
    sec = 0.0
    for p in parts:
        sec = sec * 60 + p
    return sec


def main(root):
    root = Path(root)
    m = env(root / "manifest.env")
    off = int(m["PG_MINUS_HOST_MS"])
    first, messages = receipts(root)

    say("# outbox 폴링 발송 경로 실측 (outbox_poll.sh)")
    say()
    say(f"PG {m['PG_VERSION']} · backend `{m['BACKEND_COMMIT']}` · 단계마다 {m['N']}건 · 시계 차이(PG - 호스트) {off}ms (왕복 {m['CLOCK_RTT_MS']}ms)")
    say()
    say("## 한 건씩 넣었을 때 (ms)")
    say()
    say("| 단계 | 설정 | 건수 | 생김 → 수신 중앙값 | p95 | 최소 ~ 최대 | 그룹 대기 외 지연 중앙값 · 최대 | 수신 → 완료 중앙값 |")
    say("|---|---|---|---|---|---|---|---|")
    setting = {"default": ("폴링 1초 · 그룹 대기 10초", 10000), "nowait": ("폴링 1초 · 그룹 대기 0초", 0),
               "fast": ("폴링 0.2초 · 그룹 대기 0초", 0)}
    for phase, (label, wait) in setting.items():
        f = root / f"rows-{phase}.tsv"
        if not f.exists():
            continue
        lat, fin, bad = [], [], 0
        for row in f.read_text().splitlines():
            _id, name, status, created, _claimed, updated, _att = row.split("\t")
            if status != "SENT" or name not in first:
                bad += 1
                continue
            c = int(created) - off
            lat.append(first[name] - c)
            fin.append(int(updated) - first[name])
        extra = [x - wait for x in lat]
        note = "" if not bad else f" (SENT 아님 · 수신 없음 {bad})"
        say(f"| {phase} | {label} | {len(lat)}{note} | {statistics.median(lat):,.0f} | {pct(lat, .95):,.0f} | {min(lat):,.0f} ~ {max(lat):,.0f} "
            f"| {statistics.median(extra):,.0f} · {max(extra):,.0f} | {statistics.median(fin):,.0f} |")

    say()
    say(f"## 큐가 빈 채 {m['IDLE_SECS']}초 (pg_stat_database monimo 차이 ÷ 초)")
    say()
    say("| 단계 | 트랜잭션/초 | 읽은 줄(tup_returned)/초 | 버퍼 접근/초 | Notifier CPU 초 / 분 |")
    say("|---|---|---|---|---|")
    base = None
    for phase in ("none", "poll1s", "poll200ms"):
        f = root / f"idle-{phase}.txt"
        if not f.exists():
            continue
        d = env(f)
        secs = int(d["ms"]) / 1000
        b = [int(x) for x in d["before"].split("\t")]
        a = [int(x) for x in d["after"].split("\t")]
        rate = [(y - x) / secs for x, y in zip(b, a)]
        cpu = (cputime(d["cpu_after"]) - cputime(d["cpu_before"])) / secs * 60
        diff = "" if base is None else f" (없음 대비 +{rate[0] - base[0]:.1f})"
        base = base or rate
        say(f"| {phase} | {rate[0]:.1f}{diff} | {rate[1]:,.0f} | {rate[3]:,.0f} | {cpu:.2f} |")

    f = root / "burst.tsv"
    if f.exists():
        rows = [r.split("\t") for r in f.read_text().splitlines()]
        sent0 = int(m["BURST_SENT_MS"])
        got = [first[r[1]] for r in rows if r[1] in first]
        burst_msgs = [t for t, names in messages if any(n.startswith("poll-burst-") for n in names)]
        say()
        say("## 몰림 : 10 서비스 × 20건을 한 문장으로 (폴링 1초 · 그룹 대기 10초)")
        say()
        say(f"- SENT {sum(r[3] == 'SENT' for r in rows)}/{len(rows)} · 가짜 Slack 수신 {len(got)}건이 메시지 {len(burst_msgs)}개로")
        say(f"- 넣은 순간 → 첫 수신 {min(got) - sent0:,}ms · 마지막 수신 {max(got) - sent0:,}ms")
    (root / "summary.md").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__))
