#!/usr/bin/env python3
"""overhead_gc.sh 회차들을 모아 변형(off · on · dump)별로 GC · 요청 지연 · 덤프 정지를 비교한다 (90-overhead-gc.md).

사용: python3 docs/alert/experiments/overhead_gc_analyze.py <OUT 폴더>   → 화면과 <OUT>/summary.md 에 같이 쓴다

회차마다 (측정 구간 MEASURE_FROM_MS ~ MEASURE_TO_MS 안만)
  GC        order 의 GC 로그 pause 횟수 · 합 · 최대, 할당 속도(MB/s = 이번 GC 직전 사용량 - 직전 GC 직후 사용량, 그 합 ÷ 시간)
  지연      k6 요청별 http_req_duration 의 p50 · p95 · p99 · 최대
  덤프 창   덤프를 보낸 순간부터 응답 + 1초까지 끝난 요청의 p95 · 최대. off · on 회차에도 dump 와 같은 자리
            (측정 시작 DUMP_EVERY/2 초 뒤부터 DUMP_EVERY 초마다, 길이는 dump 회차 창 길이의 중앙값)에 창을 두어 같은 방법으로 잰다
  정지      Safepoint "ThreadDump" 의 Total(도달 + 정지) — 덤프가 JVM 전체를 멈춘 시간
  CPU       네 서비스 cgroup CPU 합 ÷ 주문 수 (쇼핑몰 #36 · #51 과 같은 단위), order 메모리(끝난 순간)
변형별로는 회차 값의 중앙값과 폭(최소 ~ 최대)을 낸다. 회차가 적으면 폭이 겹치는지부터 본다
"""
import csv
import gzip
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

PAUSE = re.compile(r"^\[([^\]]+)\]\[[^\]]+\]\[info\s*\]\[gc\s*\] GC\(\d+\) (Pause .+?) (\d+)([KMG])->(\d+)([KMG])\(\d+[KMG]\) ([\d.]+)ms")
SAFEPOINT = re.compile(r'^\[([^\]]+)\]\[[^\]]+\]\[info\s*\]\[safepoint\s*\] Safepoint "ThreadDump",.* Total: (\d+) ns')
MB = {"K": 1 / 1024, "M": 1, "G": 1024}

out = []


def say(line=""):
    print(line)
    out.append(line)


def ts_ms(stamp):  # 2026-10-26T05:08:41.185+0000
    return int(datetime.strptime(stamp, "%Y-%m-%dT%H:%M:%S.%f%z").timestamp() * 1000)


def pct(xs, p):
    if not xs:
        return None
    xs = sorted(xs)
    k = (len(xs) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(xs) - 1)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


def manifest(d):
    m = {}
    for line in (d / "manifest.env").read_text().splitlines():
        k, _, v = line.partition("=")
        m[k] = v
    return m


def gc(d, lo, hi):
    pauses, dumps = [], []
    for f in sorted((d / "gclog").glob("gc-*.log*")):
        for line in f.read_text(errors="replace").splitlines():
            if (m := PAUSE.match(line)):
                pauses.append((ts_ms(m[1]), m[2].strip(), int(m[3]) * MB[m[4]], int(m[5]) * MB[m[6]], float(m[7])))
            elif (m := SAFEPOINT.match(line)):
                dumps.append((ts_ms(m[1]), int(m[2]) / 1e6))
    pauses.sort()
    inside = [p for p in pauses if lo <= p[0] <= hi]
    alloc, first = 0.0, None
    for prev, cur in zip(pauses, pauses[1:]):
        if lo <= cur[0] <= hi:
            alloc += max(cur[2] - prev[3], 0)
            first = first or prev[0]
    span = (inside[-1][0] - first) / 1000 if inside and first else None
    ms = [p[4] for p in inside]
    return {
        "gc_n": len(ms), "gc_sum": sum(ms), "gc_max": max(ms, default=0),
        "alloc_mbs": alloc / span if span else None,
        "kinds": sorted({p[1] for p in inside}),
        "dump_stw": [t for (at, t) in dumps if lo <= at <= hi],
    }


def requests(d):
    rows = []
    with gzip.open(d / "k6.csv.gz", "rt") as f:
        for r in csv.DictReader(f):
            if r["metric_name"] == "http_req_duration":
                rows.append((int(r["timestamp"]), float(r["metric_value"])))
    return rows


def windows(d, m, length_ms):
    tsv = d / "dumps.tsv"
    if tsv.exists():
        w = [(int(r["sent_ms"]), int(r["done_ms"]) + 1000, r["http"]) for r in csv.DictReader(tsv.open(), delimiter="\t")]
        return w
    # off · on : dump 회차와 같은 자리에 같은 길이의 창
    every = int(m.get("DUMP_EVERY", 30)) * 1000
    t, hi, w = int(m["MEASURE_FROM_MS"]) + every // 2, int(m["MEASURE_TO_MS"]), []
    while t + length_ms <= hi:
        w.append((t, t + length_ms, "-"))
        t += every
    return w


def cpu(d, orders):
    def read(name):
        return {s: (int(c), int(mem)) for s, c, mem in (l.split() for l in (d / name).read_text().splitlines())}
    b, a = read("cgroup-before.txt"), read("cgroup-after.txt")
    total_us = sum(a[s][0] - b[s][0] for s in a)
    return total_us / 1000 / orders, a["order"][1] / 2**20, (a["order"][0] - b["order"][0]) / 1e6


def main(root):
    runs = sorted(p for p in Path(root).iterdir() if (p / "manifest.env").exists() and (p / "k6.csv.gz").exists())
    if not runs:
        sys.exit(f"{root} 에 끝난 회차가 없다")
    # dump 회차 창 길이의 중앙값 → off · on 의 같은 자리 창 길이
    lens = []
    for d in runs:
        if (d / "dumps.tsv").exists():
            lens += [int(r["done_ms"]) + 1000 - int(r["sent_ms"]) for r in csv.DictReader((d / "dumps.tsv").open(), delimiter="\t")]
    win_len = int(statistics.median(lens)) if lens else 2000

    rows = []
    for d in runs:
        m = manifest(d)
        lo, hi = int(m["MEASURE_FROM_MS"]), int(m["MEASURE_TO_MS"])
        g = gc(d, lo, hi)
        req = [(t, v) for t, v in requests(d) if lo <= t <= hi]
        lat = [v for _, v in req]
        w = windows(d, m, win_len)
        in_win = [v for t, v in req if any(a <= t <= b for a, b, _ in w)]
        out_win = [v for t, v in req if not any(a <= t <= b for a, b, _ in w)]
        per_order, order_mem, order_cpu = cpu(d, int(m["RATE"]) * int(m["SECS"]))
        rows.append({
            "name": d.name, "variant": m["VARIANT"], **g,
            "p50": pct(lat, .5), "p95": pct(lat, .95), "p99": pct(lat, .99), "max": max(lat, default=None),
            "win_n": len(w), "win_ok": sum(1 for *_, c in w if c in ("200", "-")),
            "win_p95": pct(in_win, .95), "win_max": max(in_win, default=None), "rest_p95": pct(out_win, .95),
            "cpu_per_order": per_order, "order_cpu_s": order_cpu, "order_mem": order_mem,
        })

    f = lambda v, n=1: "-" if v is None else f"{v:,.{n}f}"
    say("# 에이전트 · 덤프의 GC · 지연 비용 (overhead_gc.sh)")
    say()
    say(f"덤프 창 길이(중앙값) {win_len}ms. off · on 회차도 같은 자리 · 같은 길이 창으로 잰다")
    say()
    say("## 회차")
    say()
    say("| 회차 | 변형 | GC 횟수 | GC 합 ms | GC 최대 ms | 할당 MB/s | p50 | p95 | p99 | 최대 | 창 p95 | 창 최대 | 창 밖 p95 | 덤프 정지 ms | 주문당 CPU ms | order 메모리 MB |")
    say("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in rows:
        stw = ", ".join(f"{x:.2f}" for x in r["dump_stw"]) or "-"
        win = f"{f(r['win_p95'])}" + ("" if r["win_ok"] == r["win_n"] else f" (성공 {r['win_ok']}/{r['win_n']})")
        say(f"| {r['name']} | {r['variant']} | {r['gc_n']} | {f(r['gc_sum'])} | {f(r['gc_max'])} | {f(r['alloc_mbs'])} "
            f"| {f(r['p50'])} | {f(r['p95'])} | {f(r['p99'])} | {f(r['max'])} | {win} | {f(r['win_max'])} | {f(r['rest_p95'])} "
            f"| {stw} | {f(r['cpu_per_order'], 2)} | {f(r['order_mem'], 0)} |")
    say()
    say("## 변형별 중앙값 (최소 ~ 최대)")
    say()
    keys = [("gc_n", "GC 횟수", 0), ("gc_sum", "GC 합 ms", 1), ("gc_max", "GC 최대 ms", 1), ("alloc_mbs", "할당 MB/s", 1),
            ("p95", "요청 p95 ms", 1), ("p99", "요청 p99 ms", 1), ("win_p95", "덤프 창 p95 ms", 1), ("win_max", "덤프 창 최대 ms", 1),
            ("rest_p95", "창 밖 p95 ms", 1), ("cpu_per_order", "주문당 CPU ms", 2), ("order_mem", "order 메모리 MB", 0)]
    variants = [v for v in ("off", "on", "dump") if any(r["variant"] == v for r in rows)]
    say("| | " + " | ".join(f"{v} ({sum(r['variant'] == v for r in rows)}회)" for v in variants) + " |")
    say("|---|" + "---|" * len(variants))
    for k, label, n in keys:
        cells = []
        for v in variants:
            xs = [r[k] for r in rows if r["variant"] == v and r[k] is not None]
            cells.append(f"{f(statistics.median(xs), n)} ({f(min(xs), n)} ~ {f(max(xs), n)})" if xs else "-")
        say(f"| {label} | " + " | ".join(cells) + " |")
    stw = [x for r in rows for x in r["dump_stw"]]
    if stw:
        say()
        say(f"덤프 정지(Safepoint \"ThreadDump\" Total) {len(stw)}번 : 중앙값 {pct(stw, .5):.2f}ms · 최대 {max(stw):.2f}ms")
    kinds = sorted({k for r in rows for k in r["kinds"]})
    say()
    say(f"측정 구간의 pause 종류 : {', '.join(kinds) or '없음'}")
    (Path(root) / "summary.md").write_text("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else sys.exit(__doc__))
