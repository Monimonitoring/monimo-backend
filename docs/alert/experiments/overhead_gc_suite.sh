#!/usr/bin/env bash
# overhead_gc.sh 를 off · on · dump 로 REPS 번 번갈아 돌린다. 반복마다 순서를 한 칸 돌려 한 변형이 늘 먼저 오지 않게 한다 (쇼핑몰 #51 과 같은 방식)
# 사용 (레포 루트): INTERNAL_TOKEN=… docs/alert/experiments/overhead_gc_suite.sh [REPS=5] [RATE=40] [SECS=300]
#   한 회차 약 7분 → 기본 15회차 약 1시간 45분. 끝나면 overhead_gc_analyze.py 를 돌린다
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPS=${1:-5}; RATE=${2:-40}; SECS=${3:-300}
ORDER=(off on dump)
for ((i = 1; i <= REPS; i++)); do
  for ((j = 0; j < 3; j++)); do
    v=${ORDER[$(((i - 1 + j) % 3))]}
    "$HERE/overhead_gc.sh" "$v" "r$i-$v" "$RATE" "$SECS"
  done
done
python3 "$HERE/overhead_gc_analyze.py" "${OUT:-/tmp/monimo-exp/overhead}"
