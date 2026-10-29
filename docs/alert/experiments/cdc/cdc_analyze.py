#!/usr/bin/env python3
"""cdc_run.sh 결과를 정리한다 (101-outbox-cdc.md). 사용: python3 cdc_analyze.py <OUT>  → 화면과 <OUT>/summary.md

시각은 모두 호스트 시계 : 생산자는 커밋이 돌아온 순간, 소비자는 메시지를 받은 순간
"""
import statistics
import sys
from collections import Counter
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


def prod(path):
    return [l.split("\t") for l in path.read_text().splitlines()] if path.exists() else []


def cons(path):
    rows = [l.split("\t") for l in path.read_text().splitlines()] if path.exists() else []
    return [(r[0], int(r[1]), int(r[4])) for r in rows]


def main(root):
    root = Path(root)
    m = env(root / "manifest.env")
    say("# 경보 사건 발행 : CDC · 폴링 릴레이 → Kafka (cdc_run.sh)")
    say()
    say(f"{m['CONNECT_IMAGE']} · PG {m['PG_VERSION']} (wal_level={m['WAL_LEVEL']}) · backend `{m['BACKEND_COMMIT']}`")

    say()
    say("## E1 · E2 · E4")
    say()
    p1 = prod(root / "e1-prod.log")
    got1 = {r[0] for r in cons(root / "e1-cons.log")}
    committed = {r[0] for r in p1 if r[1] == "committed"}
    rolled = {r[0] for r in p1 if r[1] == "rolledback"}
    say(f"- E1 롤백 : 커밋 {len(committed)} 중 받음 {len(committed & got1)}, 롤백 {len(rolled)} 중 받음 **{len(rolled & got1)}**")
    p2 = {r[0] for r in prod(root / "e2-prod.log")}
    db2 = set((root / "e2-db.txt").read_text().split())
    got2 = {r[0] for r in cons(root / "e2-cons.log")}
    say(f"- E2 커밋 직후 종료 : {len(p2)}번 중 PG 에 남은 이벤트 {len(db2 & p2)}, 그중 받음 **{len(db2 & got2)}**")
    p4 = {r[0] for r in prod(root / "e4-prod.log")}
    c4 = cons(root / "e4-cons.log")
    recv4 = Counter(e for e, _, _ in c4 if e in p4)
    jobs = {l.split("\t")[0]: int(l.split("\t")[1]) for l in (root / "e4-jobs.tsv").read_text().splitlines()}
    twice = [e for e, n in recv4.items() if n > 1]
    say(f"- E4 소비자 종료 : 이벤트 {len(p4)} · 받은 횟수 {sum(recv4.values())}(두 번 받은 이벤트 {len(twice)}) · 발송 작업 {len(jobs)}줄, "
        f"한 이벤트에 2줄 이상 **{sum(1 for n in jobs.values() if n > 1)}**")

    say()
    say("## E3 Connect 강제 종료 → 복구")
    say()
    p3 = prod(root / "e3-prod.log")
    c3 = cons(root / "e3-cons.log")
    commit3 = {r[0]: int(r[2]) for r in p3 if r[1] == "committed"}
    first3 = {}
    for e, t, _ in c3:
        first3.setdefault(e, t)
    cnt3 = Counter(e for e, _, _ in c3)
    kill, start, running = int(m["E3_KILL_MS"]), int(m["E3_START_MS"]), int(m["E3_RUNNING_MS"])
    # 멈춘 동안(kill ~ start) 커밋된 것만. kill 전에 커밋돼 이미 받은 것은 넣지 않는다
    backlog = [e for e, t in commit3.items() if kill <= t < start]
    caught = max(first3[e] for e in backlog if e in first3)
    prod_end = max(commit3.values())
    missing = [e for e in commit3 if e not in first3]
    dup_events = [e for e in commit3 if cnt3[e] > 1]
    lat_before = [first3[e] - t for e, t in commit3.items() if t < kill - 1000 and e in first3]
    say(f"- 넣은 이벤트 {len(commit3)} · 받은 이벤트 {len(first3)} · **누락 {len(missing)}** · 두 번 이상 받은 이벤트 **{len(dup_events)}** (받은 횟수 합 {sum(cnt3.values())})")
    say(f"- kill → start {start - kill:,}ms · start → 커넥터 RUNNING {running - start:,}ms · start → 멈춘 동안 쌓인 {len(backlog)}건을 다 받음 {caught - start:,}ms · 생산 끝 kill 뒤 {(prod_end - kill) / 1000:.0f}초")
    say(f"- 멈추기 전 지연(커밋 → 받음) 중앙값 {statistics.median(lat_before):,.0f}ms · p95 {pct(lat_before, .95):,.0f}ms")
    wal = []
    for l in (root / "e3-wal.tsv").read_text().splitlines():
        f = l.split("\t")
        if len(f) >= 6 and f[4]:
            wal.append((int(f[0]), int(float(f[4])), int(f[5] or 0)))
    if wal:
        peak = max(wal, key=lambda x: x[1])
        before = [w[1] for w in wal if w[0] < kill]
        say(f"- 슬롯이 붙잡은 WAL(restart_lsn → 현재) : 멈추기 전 최대 {max(before) / 1024:,.0f}KB · 멈춘 동안 최대 **{peak[1] / 2**20:,.1f}MB** "
            f"(kill 뒤 {(peak[0] - kill) / 1000:.0f}초) · pg_wal 폴더 최대 {max(w[2] for w in wal) / 2**20:,.0f}MB")
        # 증가율은 kill 순간 이미 붙잡혀 있던 양을 빼고 계산한다
        at_kill = before[-1] if before else 0
        grown = peak[1] - at_kill
        dur = (peak[0] - kill) / 1000
        load = (min(prod_end, peak[0]) - kill) / 1000
        say(f"- kill 순간 {at_kill / 1024:,.0f}KB → 최대 {peak[1] / 2**20:,.1f}MB, 늘어난 양 {grown / 2**20:,.1f}MB : "
            f"kill 부터 최대까지 {dur:.0f}초로 나누면 초당 약 {grown / dur / 1024:,.0f}KB, 생산이 이어진 {load:.0f}초로 나누면 약 {grown / load / 1024:,.0f}KB "
            f"(초당 20건 = 사건 + 이벤트 INSERT 각 20)")

    say()
    say("## P 전달 지연 (커밋 → 소비자가 받음, ms) · 유휴 DB 비용")
    say()
    say("| 경로 | 한 건씩 30 중앙값 | p95 | 최대 | 초당 20건 60초 중앙값 | p95 | 최대 | 유휴 PG 트랜잭션/초 | 유휴 읽은 줄/초 |")
    say("|---|---|---|---|---|---|---|---|---|")
    for path in ("cdc", "cdc50ms", "poll1s", "poll200ms"):
        if not (root / f"idle-{path}.txt").exists():
            continue
        c = {}
        for e, t, _ in cons(root / f"p-{path}-cons.log"):
            c.setdefault(e, t)
        cells = []
        for kind in ("single", "steady"):
            lat = [c[r[0]] - int(r[2]) for r in prod(root / f"p-{path}-{kind}.log") if r[0] in c]
            n = len(prod(root / f"p-{path}-{kind}.log"))
            miss = "" if len(lat) == n else f" (받음 {len(lat)}/{n})"
            cells += [f"{statistics.median(lat):,.0f}{miss}", f"{pct(lat, .95):,.0f}", f"{max(lat):,.0f}"]
        d = env(root / f"idle-{path}.txt")
        secs = int(d["ms"]) / 1000
        b = [int(x) for x in d["before"].split("\t")]
        a = [int(x) for x in d["after"].split("\t")]
        cells += [f"{(a[0] - b[0]) / secs:.1f}", f"{(a[1] - b[1]) / secs:,.0f}"]
        say(f"| {path} | " + " | ".join(cells) + " |")
    (root / "summary.md").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__))
