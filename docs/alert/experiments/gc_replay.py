#!/usr/bin/env python3
"""쌓인 GC 원본 전체를 옛 계산 · 새 계산으로 다시 만들고, 같은 입력에 상태머신(N · M · 판정 불가 끊김)을 돌린다.

사용: python3 docs/alert/experiments/gc_replay.py <폴더> [기준ms=6000]
  <폴더>/raw.csv  service_name, agent_id, metric_name, series_hash, gc_name, ts, value   (metrics_raw, is_counter=1)
  <폴더>/m1.csv   service_name, agent_id, metric_name, series_hash, ts_min, last         (metrics_1m 마지막 값)

"최종 조회 재평가"다. 당시 Detector가 본 입력(늦게 온 점이 그때는 없었을 수 있다)과 다를 수 있다.
상태머신은 AlertStateMachine 을 옮겼다: 위반 N번 연속이면 발화, FIRING 중 정상 M번이면 해제, 판정 불가는 연속을 0 으로(사건은 안 닫음).
Detector는 매 1분 끝을 빠짐없이 판정하므로(GcTimeRunner · settledBucketEnds) 값이 없는 1분은 판정 불가로 넣는다.
"""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from gc_analyze import SUM, floor_min, new_calc, old_calc, pct  # noqa: E402
from datetime import datetime, timezone  # noqa: E402

MINUTE = 60


def at(t, fmt="%m-%d %H:%M"):
    return datetime.fromtimestamp(t, timezone.utc).strftime(fmt)


def replay(values, minutes, threshold, fire_after, resolve_after):
    bad = good = fired = resolved = 0
    firing = False
    for m in minutes:
        v = values.get(m)
        if v is None:                       # 판정 불가: 연속만 지운다
            bad = good = 0
        elif v > threshold:
            bad, good = bad + 1, 0
            if not firing and bad >= fire_after:
                firing, fired = True, fired + 1
        else:
            bad, good = 0, good + 1
            if firing and good >= resolve_after:
                firing, resolved = False, resolved + 1
    return fired, resolved


def main(folder, threshold):
    raw = list(csv.DictReader((folder / "raw.csv").open()))
    m1 = list(csv.DictReader((folder / "m1.csv").open()))
    pods = sorted({(r["service_name"], r["agent_id"]) for r in raw})
    print(f"기준 GT {threshold:g}ms · N=1 / N=3 · M=2 · 판정 불가는 연속을 끊음\n")
    print("| 파드 | 처음 ~ 끝 점(UTC) | 1분 수(꺼져 있던 시간 포함) | 새: 값 없음 | 새: 채운 1분 | 옛: 값 없음 | 옛>새×1.5 | 새 중앙값 · p99 · 최대 ms | 옛 최대 ms | 기준 초과 1분 (새/옛) | 발화 N=1 (새/옛) | 발화 N=3 (새/옛) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    examples = []
    for service, agent in pods:
        mine_raw = [r for r in raw if r["service_name"] == service and r["agent_id"] == agent]
        mine_m1 = [r for r in m1 if r["service_name"] == service and r["agent_id"] == agent]
        stamps = sorted({int(r["ts"]) for r in mine_raw})
        frm, to = floor_min(stamps[0]) + MINUTE, floor_min(stamps[-1])
        minutes = list(range(frm, to, MINUTE))
        has_point = {floor_min(t) for t in stamps}
        new = {m: v * 1000 for (_, metric, m), v in new_calc(mine_raw, frm, to).items() if metric == SUM}
        old = {m: v * 1000 for (_, metric, m), v in old_calc(mine_m1, frm).items() if metric == SUM and m < to}
        filled = sum(1 for m in new if m not in has_point)
        lumped = [m for m in minutes if m in old and m in new and old[m] > new[m] * 1.5 and old[m] - new[m] > 1]
        lumped_no_new = [m for m in minutes if m in old and m not in new and old[m] > 1]
        nv = list(new.values())
        print(
            f"| {agent} | {at(frm)} ~ {at(to)} | {len(minutes)} | {len(minutes) - len(new)} | {filled} | {len(minutes) - len(old)} "
            f"| {len(lumped)} (+새 없음 {len(lumped_no_new)}) | {statistics.median(nv):.0f} · {pct(nv, 99):.0f} · {max(nv):.0f} | {max(old.values()):.0f} "
            f"| {sum(v > threshold for v in nv)}/{sum(v > threshold for v in old.values())} "
            f"| {replay(new, minutes, threshold, 1, 2)[0]}/{replay(old, minutes, threshold, 1, 2)[0]} "
            f"| {replay(new, minutes, threshold, 3, 2)[0]}/{replay(old, minutes, threshold, 3, 2)[0]} |"
        )
        for m in sorted(lumped + lumped_no_new, key=lambda m: -old[m])[:3]:
            before = max((t for t in stamps if t < m), default=None)
            examples.append((agent, m, old[m], new.get(m), before, min((t for t in stamps if t >= m), default=None)))
    print("\n옛 계산이 크게 튄 1분 (파드마다 위 3개): 1분 · 옛 ms · 새 ms · 그 1분 앞 마지막 점 → 그 1분 첫 점")
    for agent, m, o, n, before, after in examples:
        gap = "" if before is None or after is None else f"{at(before, '%H:%M:%S')} → {at(after, '%H:%M:%S')} (간격 {after - before}초)"
        print(f"- {agent} {at(m)} : 옛 {o:.0f} · 새 {'-' if n is None else f'{n:.0f}'} · {gap}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    main(Path(sys.argv[1]), float(sys.argv[2]) if len(sys.argv) > 2 else 6000)
