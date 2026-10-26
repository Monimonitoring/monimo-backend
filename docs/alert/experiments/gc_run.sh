#!/usr/bin/env bash
# GC 경보 실험 한 회차 (80-gc-time.md). 쇼핑몰 order 를 실험 설정(G1 · 힙 고정 · GC 로그 · 전송 간격)으로 다시 띄우고
# → 데우기 → GC_TIME 규칙 켜기 → k6 부하 → 꼬리 대기(정착 30초 + 뒤 점 150초 + 복구 2분) → gc_collect.sh 로 모으기
# → 규칙 끄기 · order 를 원래 설정으로 되돌리기
#
# 사용 (레포 루트): docs/alert/experiments/gc_run.sh <이름> [힙MB=512] [RATE=30] [DURATION=15m]
#   예) gc_run.sh A-normal 512 30 15m   ·   gc_run.sh B-pressure 128 30 15m
# 값: EXPORT_MS(60000 = 운영 기본 · 10000 = agent.properties 그대로)  WARMUP(120초)  TAIL(300초)
#     RULE_ID(7) · THRESHOLD(6000) : 그 GC_TIME 규칙을 이 기준으로 부하 시작 때 켜고 끝나면 끈다. RULE_ID= 로 비우면 규칙을 안 건드린다
#     ALIGN=<초> : 60초 전송이 매 분 그 초에 오도록 order 를 띄우는 순간을 맞춘다(분 경계 넘나듦 재현, 예 59).
#                  기동 → 첫 전송이 약 63초(LEAD, 2026-10-26 1회 실측)라 그만큼 앞서 띄운다. 맞았는지는 gc_analyze.py 1번이 보여 준다
#     KEEP=1 이면 끝난 뒤에도 order 를 실험 설정으로 둔다 · SHOP(~/monimo-shop) · OUT(/tmp/monimo-exp)
# 결과: $OUT/gc-<이름>/ → 이어서 python3 docs/alert/experiments/gc_analyze.py <그 폴더>
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
NAME=$1; HEAP_MB=${2:-512}; RATE=${3:-30}; DURATION=${4:-15m}
SHOP=${SHOP:-$HOME/monimo-shop}
EXPORT_MS=${EXPORT_MS:-60000}; WARMUP=${WARMUP:-120}; TAIL=${TAIL:-300}
RULE_ID=${RULE_ID-7}; THRESHOLD=${THRESHOLD:-6000}
PG=${PG:-monimo-dev-postgres-1}; ORDER=${ORDER:-monimo-shop-order-1}
DIR=${OUT:-/tmp/monimo-exp}/gc-$NAME
[ -e "$DIR" ] && { echo "$DIR 가 이미 있다. 다른 이름을 쓰거나 지운다" >&2; exit 1; }
# order 는 app 사용자, k6 는 12345 로 돌아 호스트 폴더에 쓸 수 있게 연다
mkdir -p "$DIR/gclog"; chmod 777 "$DIR" "$DIR/gclog"

shop() { docker compose --project-directory "$SHOP" -f "$SHOP/docker-compose.dev.yml" "$@"; }
pgq() { docker exec "$PG" psql -U monimo -d monimo -qAt -c "$1"; }
rule() { [ -z "$RULE_ID" ] || pgq "UPDATE alert_rules SET $1, version = version + 1, updated_at = now() WHERE id = $RULE_ID AND metric_kind = 'GC_TIME'"; }

STATS_PID=
cleanup() {
  [ -z "$STATS_PID" ] || { kill "$STATS_PID" 2>/dev/null; wait "$STATS_PID" 2>/dev/null; } || true
  rule "enabled = false" || true
  if [ "${KEEP:-0}" != 1 ]; then echo "== order 를 원래 설정으로 되돌린다"; shop up -d --no-deps --force-recreate --wait order >/dev/null; fi
}
trap cleanup EXIT

if [ -n "${ALIGN:-}" ]; then
  echo "== 전송이 매 분 :${ALIGN} 초에 오도록 띄우는 순간을 기다린다"
  while [ $(( ($(date -u +%s) + ${LEAD:-63}) % 60 )) -ne "$ALIGN" ]; do sleep 0.5; done
fi
echo "== order 를 실험 설정으로 띄운다 (힙 ${HEAP_MB}MB · 전송 ${EXPORT_MS}ms)"
GC_HEAP_MB=$HEAP_MB GC_EXPORT_MS=$EXPORT_MS GC_LOG_DIR=$DIR/gclog \
  shop -f "$HERE/gc_order_override.yml" up -d --no-deps --force-recreate --wait order >/dev/null
ls "$DIR"/gclog/gc-*.log >/dev/null 2>&1 || { echo "GC 로그가 안 생겼다 — docker logs $ORDER 확인" >&2; exit 1; }
grep -hE "Using |Heap Max Capacity" "$DIR"/gclog/gc-*.log | sed 's/^/   /'

{
  echo "NAME=$NAME"; echo "HEAP_MB=$HEAP_MB"; echo "RATE=$RATE"; echo "DURATION=$DURATION"; echo "EXPORT_MS=$EXPORT_MS"
  echo "WARMUP=$WARMUP"; echo "TAIL=$TAIL"; echo "RULE_ID=$RULE_ID"; echo "THRESHOLD=$THRESHOLD"
  echo "BACKEND_COMMIT=$(git -C "$HERE" rev-parse --short HEAD)$(git -C "$HERE" diff --quiet HEAD || echo -dirty)"
  echo "SHOP_COMMIT=$(git -C "$SHOP" rev-parse --short HEAD)$(git -C "$SHOP" diff --quiet HEAD || echo -dirty)"
  for c in "$ORDER" monimo-dev-api-server-1 monimo-dev-detector-1 monimo-dev-notifier-1; do
    echo "IMAGE_${c//-/_}=$(docker inspect -f '{{.Image}}' "$c" 2>/dev/null | cut -c8-19)"
  done
  echo "DOCKER_CPUS=$(docker info -f '{{.NCPU}}')"
  echo "DOCKER_MEM_GB=$(docker info -f '{{.MemTotal}}' | awk '{printf "%.1f", $1/1073741824}')"
  echo "ORDER_MEM_LIMIT=$(docker inspect -f '{{.HostConfig.Memory}}' "$ORDER")"   # 0 = 제한 없음
} > "$DIR/manifest.env"
# Detector 설정(주기 · 정착 대기 등). 토큰 · 비밀번호 · 웹훅 줄은 남기지 않는다
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' monimo-dev-detector-1 | grep -E "^MONIMO_" \
  | grep -viE "TOKEN|SECRET|PASSWORD|WEBHOOK|KEY" > "$DIR/detector-env.txt" || true

# 컨테이너 CPU · 메모리를 10초마다 (힙과 컨테이너 메모리를 나눠 보려고)
( while :; do echo "$(date -u +%s),$(docker stats --no-stream --format '{{.CPUPerc}},{{.MemUsage}}' "$ORDER")"; sleep 10; done ) > "$DIR/stats.csv" 2>/dev/null &
STATS_PID=$!

echo "== 데우기 ${WARMUP}초 (이 구간은 판정에 안 쓴다)"
sleep "$WARMUP"

rule "threshold = $THRESHOLD, enabled = true"
START=$(date -u +%s); echo "START=$START" >> "$DIR/manifest.env"
echo "== k6 RATE=$RATE DURATION=$DURATION"
docker run --rm --network monimo-dev -v "$SHOP/k6:/scripts:ro" -v "$DIR:/out" \
  -e BASE_URL=http://gateway:8090 -e RATE="$RATE" -e DURATION="$DURATION" \
  grafana/k6:2.3.0 run --summary-export /out/k6-summary.json /scripts/order.js 2>&1 | tee "$DIR/k6.txt" \
  || echo "k6 가 0 이 아닌 코드로 끝났다 — k6.txt 확인 (check 실패 · 중단일 수 있다)"
END=$(date -u +%s); echo "END=$END" >> "$DIR/manifest.env"

echo "== 꼬리 ${TAIL}초 (마지막 1분 판정 · 복구까지 기다린다)"
sleep "$TAIL"

"$HERE/gc_collect.sh" "$DIR" $((START - WARMUP)) $((END + TAIL))
echo "== 끝: python3 docs/alert/experiments/gc_analyze.py $DIR"
