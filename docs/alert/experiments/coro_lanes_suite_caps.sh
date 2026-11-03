#!/usr/bin/env bash
# 코루틴 레인의 바쁜 채널 상한을 64 · 128 · 256 · 512 로 바꿔 같은 입력(느린 채널 1000 + 정상 1)을 3회씩. 결과는 coro-lanes-caps/
# 넣자마자 ANALYZE (선점 쿼리의 통계 시점 변수를 뺀다, 120-coroutine-lanes.md §5). 전제: OUT=$PWD/docs/alert/experiments/coro-lanes-caps coro_lanes_run.sh setup
set -uo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); RUN="$HERE/coro_lanes_run.sh"
export ANALYZE=1 OUT="$HERE/coro-lanes-caps" SLACK_LOG="$HERE/coro-lanes-caps/slack.log"   # setup 도 OUT=coro-lanes-caps 로
CAPS=${CAPS:-"64 128 256 512"}; N=${N:-1000}
for R in 1 2 3; do
  for C in $CAPS; do bash "$RUN" run "C$C" "$N" "$R" || echo "실패: C$C $N $R"; done
done
python3 "$HERE/coro_lanes_analyze.py" "$OUT"
