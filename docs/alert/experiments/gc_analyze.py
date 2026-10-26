#!/usr/bin/env python3
"""GC 경보 실험 한 회차(gc_run.sh 결과 폴더)를 읽어 블로그 표 재료를 낸다.

사용: python3 docs/alert/experiments/gc_analyze.py <폴더>   → 화면과 <폴더>/analysis.md 에 같이 쓴다

1. 전송 간격      실제 점 간격, 점이 없는 1분 · 점이 둘인 1분
2. 1분 표         옛 계산(develop: metrics_1m 마지막 값끼리 빼기) · 새 계산(#239: 원본 시각으로 나누고 빈 1분 채우기)
                  · 지금 API 응답 · GC 로그 pause 합(달력 1분)
3. 점 사이 대조   두 원본 점 사이에 늘어난 GC 시간 vs 같은 구간의 GC 로그 pause 합 (GC 종류별)
4. GC 로그        pause 종류별 횟수 · 합 · 최대, 블로그에 옮길 대표 줄
5. k6 · 경보 타임라인

두 계산 규칙은 GcTimeService(develop · #239)를 그대로 옮겼다. 그쪽이 바뀌면 같이 고친다.
"""
import csv
import json
import re
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

SUM, COUNT = "jvm.gc.duration.sum", "jvm.gc.duration.count"
MINUTE, MAX_GAP = 60, 150

lines_out = []


def say(line=""):
    print(line)
    lines_out.append(line)


def read_csv(path):
    if not path.exists() or path.stat().st_size == 0:
        return []
    with path.open() as f:
        return list(csv.DictReader(f))


def read_manifest(path):
    result = {}
    for line in path.read_text().splitlines() if path.exists() else []:
        if "=" in line and not line.startswith("#"):
            key, value = line.split("=", 1)
            result[key] = value.split("#")[0].strip()
    return result


def hhmm(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%H:%M")


def hms(t):
    return datetime.fromtimestamp(t, timezone.utc).strftime("%H:%M:%S.%f")[:-3]


def ts(text):
    # PG csv(2026-10-26 05:00:00.123+00) · docker logs -t(…Z, 나노초) 를 epoch 초로
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp() if text else None


def floor_min(t):
    return int(t) // MINUTE * MINUTE


def pct(values, p):
    ordered = sorted(values)
    return ordered[max(0, -(-len(ordered) * p // 100) - 1)] if ordered else None


def fmt(v, digits=1):
    return "-" if v is None else f"{v:.{digits}f}"


# ---------- 두 계산 ----------

def old_calc(m1, frm):
    """develop: 1분 마지막 값끼리 시간순으로 뺀다. 간격은 안 본다. 음수(재시작)는 버린다"""
    series = defaultdict(list)
    for r in m1:
        series[(r["agent_id"], r["metric_name"], r["series_hash"])].append((int(r["ts_min"]), float(r["last"])))
    result = defaultdict(float)
    for (agent, metric, _), points in series.items():
        before = None
        for minute, value in sorted(points):
            if before is not None and value >= before and minute >= frm:
                result[(agent, metric, minute)] += value - before
            before = value
    return result


def new_calc(raw, frm, to):
    """#239: 1분마다 마지막 점과 그 시각. 증가량 × 60 / 실제 간격, 사이 빈 1분도 같은 값. 150초 넘거나 음수면 버린다"""
    series = defaultdict(dict)
    for r in raw:
        t, v = int(r["ts"]), float(r["value"])
        minutes = series[(r["agent_id"], r["metric_name"], r["series_hash"])]
        last = minutes.get(floor_min(t))
        if last is None or t >= last[0]:
            minutes[floor_min(t)] = (t, v)
    result = defaultdict(float)
    for (agent, metric, _), minutes in series.items():
        points = sorted(minutes.items())
        for (b_min, (b_t, b_v)), (c_min, (c_t, c_v)) in zip(points, points[1:]):
            elapsed, increase = c_t - b_t, c_v - b_v
            if elapsed <= 0 or elapsed > MAX_GAP or increase < 0:
                continue
            for minute in range(b_min + MINUTE, c_min + 1, MINUTE):
                if frm <= minute < to:
                    result[(agent, metric, minute)] += increase * MINUTE / elapsed
    return result


# ---------- GC 로그 ----------

LOG_LINE = re.compile(
    r"^\[(?P<time>[^\]]+)\]\[[^\]]*\]\[\w+\s*\]\[(?P<tags>[^\]]+?)\s*\] "
    r"(?:GC\((?P<id>\d+)\) )?(?P<what>.+?)"
    r"(?: (?P<before>\d+[KMG])->(?P<after>\d+[KMG])\((?P<cap>\d+[KMG])\))?(?: (?P<ms>\d+\.\d+)ms)?$"
)
# 우리 에이전트(GcInfo.duration)가 어느 GC 이름으로 셀지. Remark · Cleanup 은 JDK 17 에서 어느 쪽에 들어가는지 3번 대조로 확인한다
GC_NAME = {"Young": "G1 Young Generation", "Full": "G1 Old Generation"}


def read_gc_logs(folder):
    pauses, init = [], []
    for path in sorted(folder.glob("gclog/gc-*.log")):
        for line in path.read_text(errors="replace").splitlines():
            m = LOG_LINE.match(line)
            if not m:
                continue
            tags = m["tags"].strip()
            if tags == "gc,init" or m["what"].startswith("Using "):
                init.append(m["what"])
            if tags != "gc" or not m["what"].startswith("Pause ") or m["ms"] is None:
                continue
            kind = m["what"].split()[1]
            pauses.append({
                "t": datetime.strptime(m["time"], "%Y-%m-%dT%H:%M:%S.%f%z").timestamp(),
                "kind": kind, "what": m["what"], "ms": float(m["ms"]),
                "heap": f'{m["before"]}->{m["after"]}({m["cap"]})' if m["before"] else "",
                "line": line,
            })
    return pauses, init


# ---------- 본문 ----------

def main(folder):
    man = read_manifest(folder / "manifest.env")
    start, end = int(man.get("START", 0)), int(man.get("END", 0))
    warmup, tail = int(man.get("WARMUP", 0)), int(man.get("TAIL", 0))
    threshold = float(man.get("THRESHOLD", 6000))
    frm, to = floor_min(start - warmup), floor_min(end + tail)
    load_from, load_to = floor_min(start) + MINUTE, floor_min(end)   # 부하가 1분 내내 걸린 1분만

    raw, m1 = read_csv(folder / "raw.csv"), read_csv(folder / "m1.csv")
    api = json.loads((folder / "gc-api.json").read_text()).get("data", []) if (folder / "gc-api.json").exists() else []
    pauses, init = read_gc_logs(folder)
    health = {int(r["ts_min"]): r for r in read_csv(folder / "health.csv")}
    old, new = old_calc(m1, frm), new_calc(raw, frm, to)

    say(f"# GC 실험 {man.get('NAME', folder.name)}")
    say()
    say(f"- 조건: 힙 {man.get('HEAP_MB')}MB · k6 RATE={man.get('RATE')} {man.get('DURATION')} · 전송 {man.get('EXPORT_MS')}ms · 기준 GT {threshold:g}ms (규칙 {man.get('RULE_ID') or '안 건드림'})")
    say(f"- 부하 {hms(start)} ~ {hms(end)} UTC · 판정에 쓰는 1분 {hhmm(load_from)} ~ {hhmm(load_to - MINUTE)} ({(load_to - load_from) // MINUTE}개)")
    say(f"- 커밋 backend {man.get('BACKEND_COMMIT')} · shop {man.get('SHOP_COMMIT')} · Docker {man.get('DOCKER_CPUS')}CPU {man.get('DOCKER_MEM_GB')}GB · order 메모리 제한 {man.get('ORDER_MEM_LIMIT')}(0=없음)")
    if init:
        picked = [i for i in init if re.match(r"(Using G1|Version|CPUs|Memory|Heap (Min|Initial|Max)|Heap Region)", i)]
        say("- JVM: " + " · ".join(dict.fromkeys(picked)))
    say()

    agents = sorted({r["agent_id"] for r in raw} | {r["agent_id"] for r in m1})
    for agent in agents:
        report_agent(agent, raw, old, new, api, health, pauses, frm, to, load_from, load_to, threshold)
    report_gc_log(pauses, load_from, load_to)
    report_k6(folder)
    report_alerts(folder)

    (folder / "analysis.md").write_text("\n".join(lines_out) + "\n")
    print(f"\n→ {folder / 'analysis.md'}")


def report_agent(agent, raw, old, new, api, health, pauses, frm, to, load_from, load_to, threshold):
    mine = [r for r in raw if r["agent_id"] == agent]
    say(f"## 파드 {agent}")
    say()

    # 1. 전송 간격 : 한 파드는 모든 시계열을 같은 순간에 보낸다 → 서로 다른 ts 만 본다
    stamps = sorted({int(r["ts"]) for r in mine if frm <= int(r["ts"]) < to})
    gaps = [b - a for a, b in zip(stamps, stamps[1:])]
    per_minute = defaultdict(int)
    for t in stamps:
        per_minute[floor_min(t)] += 1
    minutes = list(range(frm, to, MINUTE))
    say("### 1. 전송 간격")
    if gaps:
        say(f"- 점 {len(stamps)}개 · 간격 중앙값 {statistics.median(gaps):g}초 · 최소 {min(gaps)} · 최대 {max(gaps)}")
        seconds = defaultdict(int)
        for t in stamps:
            seconds[t % MINUTE] += 1
        say("- 전송 시각(매 분 몇 초 : 점 수): " + " · ".join(f":{s:02d} {n}" for s, n in sorted(seconds.items())))
    say(f"- 점이 없는 1분 {sum(1 for m in minutes if per_minute[m] == 0)}개 · 점이 둘 이상인 1분 {sum(1 for m in minutes if per_minute[m] >= 2)}개 (전체 {len(minutes)}개)")
    say()

    # 2. 1분 표
    api_ms = {(r["agent_key"], int(ts(r["ts_min"]))): r["gc_time_ms"] for r in api}
    log_ms = defaultdict(float)
    for p in pauses:
        log_ms[floor_min(p["t"])] += p["ms"]
    rows = []
    for m in minutes:
        o = old.get((agent, SUM, m))
        n = new.get((agent, SUM, m))
        rows.append({
            "m": m, "phase": "부하" if load_from <= m < load_to else ("데우기" if m < load_from else "꼬리"),
            "points": per_minute[m],
            "old": None if o is None else o * 1000, "new": None if n is None else n * 1000,
            "api": api_ms.get((agent, m)), "log": log_ms.get(m),
            "count": new.get((agent, COUNT, m)),
            "p95": float(health[m]["p95_ms"]) if m in health else None,
            "requests": health[m]["requests"] if m in health else "-",
        })
    say("### 2. 1분 값 (ms)")
    say("| 1분(UTC) | 구간 | 점 | 옛 계산 | 새 계산 | API | GC 로그 합 | 횟수(새) | 요청 수 | 요청 p95 |")
    say("|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        mark = " ⚠" if r["old"] is not None and r["new"] and r["old"] > r["new"] * 1.5 else ""
        say(f"| {hhmm(r['m'])} | {r['phase']} | {r['points']} | {fmt(r['old'])}{mark} | {fmt(r['new'])} | {fmt(r['api'])} | {fmt(r['log'])} | {fmt(r['count'], 0)} | {r['requests']} | {fmt(r['p95'])} |")
    say("- ⚠ = 옛 계산이 새 계산의 1.5배 넘음(2분치 몰림 후보) · `-` = 값 없음(판정 불가) · GC 로그 합은 달력 1분, 계산 값은 점 사이 구간이라 정확히 같지 않다")
    say("- 요청 p95 는 서비스 전체 서버 스팬(service_health_1m)이라 파드가 여럿이면 이 파드만의 값이 아니다")
    say()

    load = [r for r in rows if r["phase"] == "부하"]
    say("### 2-1. 부하 구간 요약")
    say("| 계산 | 값 있는 1분 | 빈 1분 | 중앙값 | p95 | 최대 | 기준 초과 1분 | 최장 연속 초과 |")
    say("|---|---|---|---|---|---|---|---|")
    for label, key in (("옛 계산", "old"), ("새 계산", "new"), ("GC 로그 합", "log")):
        values = [r[key] for r in load if r[key] is not None]
        streak = best = 0
        for r in load:   # 빈 1분은 판정 불가라 연속을 끊는다 (상태머신 resetStreaks)
            streak = streak + 1 if r[key] is not None and r[key] > threshold else 0
            best = max(best, streak)
        say(f"| {label} | {len(values)} | {len(load) - len(values)} | {fmt(statistics.median(values) if values else None)} | {fmt(pct(values, 95))} | {fmt(max(values) if values else None)} | {sum(1 for v in values if v > threshold)} | {best} |")
    say(f"- p95 는 {len(load)}개 표본의 nearest-rank 값이다 (탐색용)")
    have_api = [r for r in rows if r["api"] is not None]
    if have_api:
        same = lambda key: sum(1 for r in have_api if r[key] is not None and abs(r[key] - r["api"]) < 0.01)
        say(f"- API 응답 {len(have_api)}개 중 옛 계산과 같음 {same('old')} · 새 계산과 같음 {same('new')} → 떠 있는 API server 가 어느 쪽인지")
    say()

    # 3. 점 사이 대조 : 원본 두 점 사이에 늘어난 GC 시간 vs 그 구간 (앞 점, 뒤 점] 의 로그 pause 합
    say("### 3. 원본 점 사이 대조 (부하 구간)")
    if not pauses:
        say("- GC 로그가 없다 (gc_run.sh 로 돌린 회차가 아니면 해당 없음)")
        say()
        return
    by_name = defaultdict(list)
    for r in mine:
        if r["metric_name"] == SUM:
            by_name[(r["gc_name"], r["series_hash"])].append((int(r["ts"]), float(r["value"])))
    say("| GC 이름 (action) | 구간 수 | 에이전트 합 ms | 로그 합 ms | 차이 | 1ms 넘게 어긋난 구간 |")
    say("|---|---|---|---|---|---|")
    for (name, _), points in sorted(by_name.items()):
        points.sort()
        kinds = [k for k, v in GC_NAME.items() if v == name]
        agent_total = log_total = 0.0
        intervals = off = 0
        for (a_t, a_v), (b_t, b_v) in zip(points, points[1:]):
            if not (load_from <= a_t and b_t < load_to) or b_v < a_v:
                continue
            delta = (b_v - a_v) * 1000
            logged = sum(p["ms"] for p in pauses if p["kind"] in kinds and a_t < p["t"] <= b_t)
            agent_total, log_total, intervals = agent_total + delta, log_total + logged, intervals + 1
            off += abs(delta - logged) > 1
        say(f"| {name} | {intervals} | {agent_total:.1f} | {log_total:.1f} | {agent_total - log_total:+.1f} | {off} |")
    other = defaultdict(float)
    for p in pauses:
        if p["kind"] not in GC_NAME and load_from <= p["t"] < load_to:
            other[p["kind"]] += p["ms"]
    if other:
        say(f"- 이름에 안 묶은 pause 합: " + " · ".join(f"{k} {v:.1f}ms" for k, v in other.items())
            + " → Young 차이가 이 값과 비슷하면 Young 쪽에 들어간 것이다")
    say("- 원본 점 시각은 초 단위라 경계에 걸린 pause 는 옆 구간으로 갈 수 있다. 합계 차이를 먼저 본다")
    say()


def report_gc_log(pauses, load_from, load_to):
    say("## 4. GC 로그 (부하 구간)")
    load = [p for p in pauses if load_from <= p["t"] < load_to]
    if not load:
        say("- 없음")
        say()
        return
    say("| pause | 횟수 | 합 ms | 평균 ms | 최대 ms |")
    say("|---|---|---|---|---|")
    groups = defaultdict(list)
    for p in load:
        groups[re.sub(r"\s*\(G1 [^)]*\)|\s*\(System[^)]*\)", "", p["what"])].append(p["ms"])
    for what, values in sorted(groups.items(), key=lambda kv: -sum(kv[1])):
        say(f"| {what} | {len(values)} | {sum(values):.1f} | {statistics.mean(values):.2f} | {max(values):.2f} |")
    young = sorted((p for p in load if p["kind"] == "Young"), key=lambda p: p["ms"])
    picks = []
    if young:
        picks += [("Young 중앙값", young[len(young) // 2]), ("Young 최대", young[-1])]
    picks += [(f"{p['kind']} 첫 줄", p) for p in {p["kind"]: p for p in reversed(load) if p["kind"] != "Young"}.values()]
    say()
    say("대표 줄 (블로그 2장 해설용):")
    say("```")
    for label, p in picks:
        say(f"# {label}")
        say(p["line"])
    say("```")
    say()


def report_k6(folder):
    say("## 5. k6")
    path = folder / "k6-summary.json"
    try:
        metrics = json.loads(path.read_text())["metrics"]
        d, f = metrics["http_req_duration"], metrics["http_req_failed"]
        it, reqs = metrics["iterations"], metrics["http_reqs"]
        dropped = metrics.get("dropped_iterations", {}).get("count", 0)
        say(f"- 주문 iteration {it['count']} ({it['rate']:.1f}/s) · HTTP 요청 {reqs['count']} ({reqs['rate']:.1f}/s) · dropped_iterations {dropped}")
        say(f"- HTTP p95 {d['p(95)']:.1f}ms · 최대 {d['max']:.1f}ms · 실패율 {f.get('value', f.get('rate', 0)) * 100:.2f}%")
    except (OSError, KeyError, ValueError) as e:
        say(f"- k6-summary.json 을 못 읽었다({e.__class__.__name__}) — k6.txt 를 본다")
    say()


def report_alerts(folder):
    say("## 6. 경보 타임라인 (fired_at = 판정 버킷 끝 기준 +초)")
    events = read_csv(folder / "alert-events.csv")
    if not events:
        say("- GC_TIME 사건 없음")
        say()
        return
    dumps, outbox, history = read_csv(folder / "thread-dumps.csv"), read_csv(folder / "outbox.csv"), read_csv(folder / "history.csv")
    log_lines = (folder / "detector.log").read_text().splitlines() if (folder / "detector.log").exists() else []
    for e in events:
        base, created = ts(e["fired_at"]), ts(e["created_at"])
        say(f"- 사건 {e['id']} {e['agent_key']} 관측 {e['observed_value']} (기준 {e['threshold']}) → 지금 {e['state']}")
        rows = [("판정 버킷 끝(fired_at)", base), ("사건 기록(created_at)", created)]
        for line in log_lines:
            stamp, _, text = line.partition(" ")
            if "발화 → 스레드 덤프" in text and (e["agent_key"] or "") in text and ts(stamp) >= created - 1:
                rows.append(("Detector 로그: " + text.split("GC_TIME ", 1)[-1][:60], ts(stamp)))
                break
        for d in dumps:
            t = int(d["requested_ms"]) / 1000
            if d["agent_id"] == e["agent_key"] and created - 1 <= t <= created + 60:
                rows.append((f"덤프 저장(requested_at, 스레드 {d['thread_count']})", t))
                break
        for o in (o for o in outbox if o["alert_event_id"] == e["id"]):
            rows.append((f"발송 큐 {o['transition']} 생성", ts(o["created_at"])))
            rows.append((f"발송 큐 {o['transition']} {o['status']}(시도 {o['attempt_count']})", ts(o["updated_at"])))
        for h in (h for h in history if h["alert_event_id"] == e["id"]):
            rows.append((f"발송 이력 {h['result']}(sent_at)", ts(h["sent_at"])))
        if e["resolved_at"]:
            rows.append(("복구(resolved_at)", ts(e["resolved_at"])))
        for label, t in sorted(rows, key=lambda r: r[1]):
            say(f"  - {hms(t)}  +{t - base:6.1f}s  {label}")
    say("- 발송 이력 sent_at 은 Slack API 가 성공을 돌려준 시각이다. 사람이 본 시각이 아니다")
    say()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(Path(sys.argv[1]))
