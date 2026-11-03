#!/usr/bin/env bash
# N = 1000 · 3000 을 "넣자마자 ANALYZE" 로 다시 (coro_lanes_run.sh 의 ANALYZE 설명). 결과는 coro-lanes-analyzed/
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); RUN="$HERE/coro_lanes_run.sh"
export ANALYZE=1 OUT="$HERE/coro-lanes-analyzed"
for R in 1 2 3; do
  for N in 1000 3000; do
    case $R in 2) VS="CU TU" ;; *) VS="TU CU" ;; esac
    for V in $VS; do bash "$RUN" run "$V" "$N" "$R" || echo "실패: $V $N $R"; done
  done
done
python3 "$HERE/coro_lanes_analyze.py" "$OUT"
