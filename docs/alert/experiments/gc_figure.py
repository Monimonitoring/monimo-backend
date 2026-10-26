#!/usr/bin/env python3
"""gc_collect.sh 로 모은 폴더 하나를 같은 시간축 그림으로 그린다 : 1분 GC 시간(새 계산) · 요청 p95 · 경보 시각.

사용: python3 docs/alert/experiments/gc_figure.py <폴더> <기준ms> <제목> [출력 png]
- GC 막대 : metrics_raw 원본 점을 새 계산(#239, 실제 간격으로 60초치)으로 바꾼 1분 값. 값이 없는 1분은 회색 빗금
- 요청 p95 : service_health_1m 의 1분 p95 (오른쪽 축, 로그 눈금). 서비스 전체 서버 스팬 기준
- 세로선 : 판정 버킷 끝(fired_at) · 사건 기록 · 덤프 저장 · Slack 성공(sent_at) · 복구 버킷 끝. 초 단위 실제 시각 그대로
"""
import sys
from datetime import datetime, timezone
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.dates as mdates  # noqa: E402
import matplotlib.pyplot as plt  # noqa: E402

sys.path.insert(0, str(Path(__file__).parent))
from gc_analyze import SUM, floor_min, new_calc, read_csv, ts  # noqa: E402

plt.rcParams["font.family"] = ["Apple SD Gothic Neo"]  # macOS 기본 한글 글꼴
plt.rcParams["axes.unicode_minus"] = False


def when(t):
    return datetime.fromtimestamp(t, timezone.utc)


def main(folder, threshold, title, out):
    raw, health = read_csv(folder / "raw.csv"), read_csv(folder / "health.csv")
    stamps = [int(r["ts"]) for r in raw]
    # gc_collect.sh 는 원본을 앞 300초 · 뒤 150초 더 읽는다 → 그 안쪽이 모은 구간
    frm, to = floor_min(min(stamps) + 300), floor_min(max(stamps) - 150) + 60
    gc = {m: v * 1000 for (_, metric, m), v in new_calc(raw, frm, to).items() if metric == SUM}

    fig, ax = plt.subplots(figsize=(11, 4.6))
    width = 55 / 86400
    for m in range(frm, to, 60):
        if m in gc:
            ax.bar(when(m), gc[m], width=width, align="edge", color="#3b6ea5")
        else:
            ax.bar(when(m), threshold * 0.15, width=width, align="edge", color="none", edgecolor="#999", hatch="///")
    ax.axhline(threshold, color="#c0392b", linestyle="--", linewidth=1)
    ax.text(when(frm), threshold, f" 기준 {threshold:g}ms", color="#c0392b", va="bottom", fontsize=9)
    ax.set_ylabel("1분 GC 시간 (ms)")
    ax.set_ylim(0, max([threshold * 1.3] + list(gc.values())) * 1.15)

    if health:
        ax2 = ax.twinx()
        xs = [when(int(r["ts_min"]) + 30) for r in health if frm <= int(r["ts_min"]) < to]
        ys = [float(r["p95_ms"]) for r in health if frm <= int(r["ts_min"]) < to]
        ax2.plot(xs, ys, color="#e67e22", marker="o", markersize=4, linewidth=1.2)
        ax2.set_yscale("log")
        ax2.set_ylabel("요청 p95 (ms, 로그 눈금)", color="#e67e22")

    marks = []
    for e in read_csv(folder / "alert-events.csv"):
        marks += [(ts(e["fired_at"]), "판정 버킷 끝", "#c0392b"), (ts(e["created_at"]), "사건 기록", "#8e44ad")]
        if e["resolved_at"]:
            marks.append((ts(e["resolved_at"]), "복구 버킷 끝", "#27ae60"))
    # 경보가 부른 덤프만 (손으로 뜬 manual-check 등은 뺀다)
    marks += [(int(d["requested_ms"]) / 1000, "자동 덤프 저장", "#7f8c8d") for d in read_csv(folder / "thread-dumps.csv") if d["requested_by"] == "detector"]
    marks += [(ts(h["sent_at"]), "Slack 성공", "#16a085") for h in read_csv(folder / "history.csv")]
    seen = set()
    for t, label, color in sorted(marks):
        if frm <= t < to + 60:
            ax.axvline(when(t), color=color, linewidth=1.1, linestyle=":" if label != "판정 버킷 끝" else "-",
                       label=None if label in seen else label)
            seen.add(label)
    if seen:
        ax.legend(loc="upper left", fontsize=8, ncol=3)

    ax.xaxis.set_major_formatter(mdates.DateFormatter("%H:%M", tz=timezone.utc))
    ax.set_xlabel(f"{when(frm):%Y-%m-%d} UTC")
    ax.set_title(title, fontsize=11)
    fig.tight_layout()
    fig.savefig(out, dpi=150)
    print(f"→ {out}")


if __name__ == "__main__":
    if len(sys.argv) < 4:
        sys.exit(__doc__)
    folder = Path(sys.argv[1])
    main(folder, float(sys.argv[2]), sys.argv[3], Path(sys.argv[4]) if len(sys.argv) > 4 else folder / "figure.png")
