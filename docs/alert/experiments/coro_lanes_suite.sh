#!/usr/bin/env bash
# coro_lanes_run.sh 를 변형 · N · 회차로 돌린다. 회차마다 변형 순서를 돌려 시간에 따른 치우침(데몬 · 캐시)을 나눈다.
# T8(지금 기본값)은 N=1000 에서 한 회차가 약 9분이라 1회만, N=3000 은 계산으로 갈음한다(3000 / 8 × 4초 ≈ 25분).
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); RUN="$HERE/coro_lanes_run.sh"
for R in 1 2 3; do
  for N in 10 100 1000 3000; do
    case $R in 1) VS="T8 TU CU" ;; 2) VS="TU CU T8" ;; 3) VS="CU T8 TU" ;; esac
    for V in $VS; do
      if [ "$V" = T8 ] && { [ "$N" = 3000 ] || { [ "$N" = 1000 ] && [ "$R" != 1 ]; }; }; then continue; fi
      bash "$RUN" run "$V" "$N" "$R" || echo "실패: $V $N $R"
    done
  done
done
python3 "$HERE/coro_lanes_analyze.py" "${OUT:-$HERE/coro-lanes-results}"
