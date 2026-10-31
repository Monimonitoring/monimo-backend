#!/usr/bin/env bash
# 에이전트 · 스레드 덤프가 감시 대상 JVM 의 GC 와 요청 지연에 주는 비용 한 회차 (90-overhead-gc.md).
# 쇼핑몰 perf/overhead/(#36 · #51 의 CPU · 메모리 측정)와 같은 출발선에 GC 로그 · 요청별 지연 · 덤프 요청만 더한다.
#
# 사용 (레포 루트): docs/alert/experiments/overhead_gc.sh <변형> <회차이름> [RATE=40] [SECS=300]
#   변형 : off  (에이전트 뗌, 쇼핑몰 no-agent.yml)
#          on   (붙임, 그대로)
#          dump (붙임 + 측정 동안 DUMP_EVERY 초마다 order 스레드 덤프를 Collector 에 직접 요청)
#   값  : OH_HEAP_MB(256) · DUMP_EVERY(30, Extension 이 10초 안의 재요청은 직전 결과를 돌려주므로 그보다 길게)
#         INTERNAL_TOKEN (dump 변형에 필요. Collector 의 MONIMO_INTERNAL_TOKEN 과 같은 값. 결과 폴더에 남기지 않는다)
#         SHOP(~/monimo-shop) · OUT(/tmp/monimo-exp/overhead) · COLLECTOR(http://localhost:8081)
# 전제 : backend Collector 가 떠 있다(docker compose --profile collector up -d --wait). 쇼핑몰은 내려 둔다 (스크립트가 띄우고 내린다)
# 결과 : $OUT/<회차이름>/ (manifest.env · gclog/ · k6.csv · k6-summary.json · cgroup.txt · dumps.tsv)
#        → python3 docs/alert/experiments/overhead_gc_analyze.py $OUT
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
VARIANT=${1:?변형 (off · on · dump)}; NAME=${2:?회차이름}
RATE=${3:-40}; SECS=${4:-300}
WARMUP_SECS=40   # 쇼핑몰 측정과 같다. JIT 전 구간은 버린다
SHOP=${SHOP:-$HOME/monimo-shop}
OH_HEAP_MB=${OH_HEAP_MB:-256}; DUMP_EVERY=${DUMP_EVERY:-30}
COLLECTOR=${COLLECTOR:-http://localhost:8081}
K6_IMAGE=grafana/k6:2.3.0
DIR=${OUT:-/tmp/monimo-exp/overhead}/$NAME
SERVICES="gateway order payment inventory"

case "$VARIANT" in off|on|dump) ;; *) echo "모르는 변형: $VARIANT" >&2; exit 2 ;; esac
[ "$VARIANT" != dump ] || [ -n "${INTERNAL_TOKEN:-}" ] || { echo "dump 변형은 INTERNAL_TOKEN 이 필요하다" >&2; exit 2; }
[ -e "$DIR" ] && { echo "$DIR 가 이미 있다. 다른 이름을 쓴다" >&2; exit 1; }
# 쇼핑몰 run.sh 와 같은 안전장치 : 떠 있는 쇼핑몰(개발 중 포함)을 내려 버리지 않는다
if docker ps --format '{{.Names}}' | grep -q '^monimo-shop-'; then
  echo "쇼핑몰 컨테이너가 이미 떠 있다. 내린 뒤 다시 실행" >&2; exit 1
fi
# 붙임 · 덤프는 Collector 가 있어야 실제로 보낸다. 뗌도 같은 조건(Collector 가 떠 있는 맥북)에서 잰다
curl -fsS --max-time 3 -o /dev/null "${COLLECTOR_MGMT:-http://localhost:18081}/actuator/health" \
  || { echo "Collector 가 안 떠 있다 (docker compose --profile collector up -d --wait)" >&2; exit 1; }

mkdir -p "$DIR/gclog"; chmod 777 "$DIR" "$DIR/gclog"
FILES=(-f "$SHOP/docker-compose.dev.yml" -f "$SHOP/perf/overhead/limits.yml")
[ "$VARIANT" = off ] && FILES+=(-f "$SHOP/perf/overhead/no-agent.yml")
FILES+=(-f "$HERE/overhead_gc_override.yml")
shop() { OH_HEAP_MB=$OH_HEAP_MB OH_GC_DIR=$DIR/gclog docker compose --project-directory "$SHOP" "${FILES[@]}" "$@"; }

DUMP_PID=
cleanup() {
  [ -z "$DUMP_PID" ] || { kill "$DUMP_PID" 2>/dev/null; wait "$DUMP_PID" 2>/dev/null; } || true
  shop down >/dev/null 2>&1 || true
}
trap cleanup EXIT

{
  echo "VARIANT=$VARIANT"; echo "NAME=$NAME"; echo "RATE=$RATE"; echo "SECS=$SECS"; echo "WARMUP_SECS=$WARMUP_SECS"
  echo "OH_HEAP_MB=$OH_HEAP_MB"; echo "DUMP_EVERY=$DUMP_EVERY"; echo "K6_IMAGE=$K6_IMAGE"
  echo "SHOP_COMMIT=$(git -C "$SHOP" rev-parse --short HEAD)"; echo "BACKEND_COMMIT=$(git -C "$HERE" rev-parse --short HEAD)"
} > "$DIR/manifest.env"

echo "== [$NAME] 쇼핑몰을 $VARIANT 로 띄운다 (order 힙 ${OH_HEAP_MB}MB · 서비스마다 CPU 1 · 메모리 1GB)"
shop up -d --wait >"$DIR/compose-up.log" 2>&1 || { tail -20 "$DIR/compose-up.log" >&2; exit 1; }
# 이미지가 체크아웃과 다를 수 있어 실제로 뜬 이미지를 남긴다
echo "ORDER_IMAGE=$(docker inspect monimo-shop-order-1 --format '{{.Image}}' | cut -c8-19)" >> "$DIR/manifest.env"

k6() { # $1 = 길이, 나머지 = k6 인자
  local dur=$1; shift
  docker run --rm --network monimo-dev -v "$SHOP/k6:/scripts:ro" -v "$DIR:/out" \
    -e BASE_URL=http://gateway:8090 -e RATE="$RATE" -e DURATION="$dur" -e K6_CSV_TIME_FORMAT=unix_milli \
    "$K6_IMAGE" run --quiet "$@" /scripts/order.js >>"$DIR/k6.log" 2>&1 || true
}
cgroup() { # 서비스 · CPU 누적(µs) · 메모리(바이트). 쇼핑몰 run.sh 와 같은 읽기
  for s in $SERVICES; do
    docker exec "monimo-shop-$s-1" sh -c \
      'echo "'"$s"' $(awk "/^usage_usec /{print \$2}" /sys/fs/cgroup/cpu.stat) $(cat /sys/fs/cgroup/memory.current)"'
  done
}
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# 덤프 요청 : 측정 시작 DUMP_EVERY/2 초 뒤부터 DUMP_EVERY 초마다. 요청 시각 · 응답 시각 · HTTP 코드 · 응답 크기를 남긴다
dumper() {
  local inst
  inst=$(docker inspect monimo-shop-order-1 --format '{{range .Config.Env}}{{println .}}{{end}}' \
    | sed -n 's/^OTEL_RESOURCE_ATTRIBUTES=.*service\.instance\.id=\([^,]*\).*/\1/p')
  [ -n "$inst" ] || inst=shop-order-local-1
  echo -e "sent_ms\tdone_ms\thttp\tbytes" > "$DIR/dumps.tsv"
  sleep $((DUMP_EVERY / 2))
  while :; do
    local t0 code t1
    t0=$(now_ms)
    code=$(curl -s -o "$DIR/dump-last.txt" -w '%{http_code}' --max-time 15 -X POST "$COLLECTOR/internal/thread-dump" \
      -H 'Content-Type: application/json' -H "X-Internal-Token: $INTERNAL_TOKEN" \
      -d "{\"service\":\"shop-order\",\"instance\":\"$inst\",\"timeout_ms\":10000}" || echo 000)
    t1=$(now_ms)
    echo -e "$t0\t$t1\t$code\t$(wc -c < "$DIR/dump-last.txt" | tr -d ' ')" >> "$DIR/dumps.tsv"
    sleep "$DUMP_EVERY"
  done
}

echo "== 워밍업 ${WARMUP_SECS}초 (버림)"
k6 "${WARMUP_SECS}s"
cgroup > "$DIR/cgroup-before.txt"
echo "MEASURE_FROM_MS=$(now_ms)" >> "$DIR/manifest.env"
if [ "$VARIANT" = dump ]; then dumper & DUMP_PID=$!; fi
echo "== 측정 ${SECS}초 (초당 ${RATE}건)"
k6 "${SECS}s" --summary-export=/out/k6-summary.json --out csv=/out/k6.csv
echo "MEASURE_TO_MS=$(now_ms)" >> "$DIR/manifest.env"
[ -z "$DUMP_PID" ] || { kill "$DUMP_PID" 2>/dev/null; wait "$DUMP_PID" 2>/dev/null || true; DUMP_PID=; }
cgroup > "$DIR/cgroup-after.txt"
rm -f "$DIR/dump-last.txt"

# k6 가 안 돌았거나 요청이 모자라면 이 회차는 쓰지 않는다 (0 이 정상처럼 섞이지 않게)
[ -s "$DIR/k6-summary.json" ] && [ -s "$DIR/k6.csv" ] || { echo "k6 결과가 없다. $DIR/k6.log 확인" >&2; exit 1; }
reqs=$(python3 -c 'import json,sys; print(int(json.load(open(sys.argv[1]))["metrics"]["http_reqs"]["count"]))' "$DIR/k6-summary.json")
[ "$reqs" -ge $((RATE * SECS * 95 / 100)) ] || { echo "k6 요청 수가 모자란다($reqs)" >&2; exit 1; }
gzip -9 "$DIR/k6.csv"
echo "== [$NAME] 끝 : $DIR"
