#!/usr/bin/env bash
# 경보 사건 발행을 CDC(Debezium) · 폴링 릴레이로 Kafka 에 싣는 격리 실험 한 벌 (101-outbox-cdc.md).
# 사용: PY=<kafka-python · psycopg 가 있는 python> docs/alert/experiments/cdc/cdc_run.sh
#   E1 롤백        이벤트까지 넣고 ROLLBACK 한 사건이 Kafka 에 나오지 않는가 (커밋 20 · 롤백 20)
#   E2 커밋 직후 종료  커밋하자마자 생산자가 죽어도 커밋된 이벤트가 전달되는가 (20번)
#   E3 Connect 강제 종료 → 복구  초당 20건을 넣는 중 30초에 docker kill, 60초 멈춤, 다시 켬 → 누락 · 중복 · 따라잡는 시간 · WAL 보관량
#   E4 소비자가 작업 저장 뒤 오프셋 커밋 전에 종료  다시 받아도 발송 작업은 하나인가
#   P  전달 지연 · 유휴 DB 비용 : CDC / 폴링 릴레이 1초 / 0.2초 → 같은 소비자. 한 건씩 30번 + 초당 20건 60초
# 이 폴더의 compose(프로젝트 monimo-cdc)를 지우고 새로 띄운다. 팀 compose 는 건드리지 않는다
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
PY=${PY:?kafka-python · psycopg 가 있는 python}
OUT=${OUT:-/tmp/monimo-exp/cdc}
[ -e "$OUT" ] && { echo "$OUT 가 이미 있다" >&2; exit 1; }
mkdir -p "$OUT"
dc() { docker compose -f "$HERE/compose.yml" "$@"; }
sql() { docker exec -i monimo-cdc-pg-1 psql -U monimo -d cdc -tAq -F $'\t' "$@"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
prod() { "$PY" "$HERE/producer.py" "$@"; }
CONS="$HERE/consumer.py"   # 백그라운드로 띄울 때는 함수가 아니라 python 을 직접 띄운다 (kill 이 python 에 닿게)
ids() { cut -f1 "$1" | sed "s/.*/'&'::uuid/" | paste -sd, -; }  # 생산자 로그의 event_id 들 → SQL IN 목록
wait_ready() { until [ -e "$1" ]; do sleep 0.2; done; }
BG=()
cleanup() { for p in "${BG[@]:-}"; do [ -z "$p" ] || kill "$p" 2>/dev/null || true; done; }
trap cleanup EXIT

connector_running() {
  curl -fs localhost:28083/connectors/alert-outbox/status 2>/dev/null | grep -q '"tasks":\[{"id":0,"state":"RUNNING"'
}

echo "== 환경 새로 (down -v → up)"
dc down -v >/dev/null 2>&1 || true
dc up -d --wait >"$OUT/compose-up.log" 2>&1
curl -fs -X POST -H 'Content-Type: application/json' --data @"$HERE/connector.json" localhost:28083/connectors >/dev/null
until connector_running; do sleep 1; done
# 소비자가 먼저 구독해도 배정을 받도록 두 토픽을 미리 만든다 (파티션 3, 키 = 사건 id)
for t in alert.events.cdc alert.events.poll; do
  docker exec monimo-cdc-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
    --topic "$t" --partitions 3 --replication-factor 1 >/dev/null
done
{
  echo "CONNECT_IMAGE=$(docker inspect monimo-cdc-connect-1 --format '{{.Config.Image}}')"
  echo "PG_VERSION=$(sql -c 'SHOW server_version')"; echo "WAL_LEVEL=$(sql -c 'SHOW wal_level')"
  echo "BACKEND_COMMIT=$(git -C "$HERE" rev-parse --short HEAD)"
} > "$OUT/manifest.env"

echo "== E1 롤백"
prod cdc 40 "$OUT/e1-prod.log" --mode rollback
"$PY" "$CONS" alert.events.cdc e1 history "$OUT/e1-cons.log" --idle-exit 10

echo "== E2 커밋 직후 종료 × 20"
for _ in $(seq 1 20); do prod cdc 1 "$OUT/e2-prod.log" --mode crash || true; done
sql -c "SELECT id FROM alert_event_outbox WHERE id IN ($(ids "$OUT/e2-prod.log"))" > "$OUT/e2-db.txt"
"$PY" "$CONS" alert.events.cdc e2 history "$OUT/e2-cons.log" --idle-exit 10

echo "== E3 Connect 강제 종료 → 복구 (초당 20건 · 120초, 30초에 kill · 90초에 start)"
"$PY" "$CONS" alert.events.cdc e3 delivery "$OUT/e3-cons.log" --from latest --ready "$OUT/e3.ready" & BG+=($!)
wait_ready "$OUT/e3.ready"
( while :; do
    echo "$(now_ms)	$(sql -c "SELECT pg_current_wal_lsn(), restart_lsn, confirmed_flush_lsn,
        pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn), (SELECT sum(size) FROM pg_ls_waldir()) FROM pg_replication_slots WHERE slot_name = 'alert_outbox'")"
    sleep 2
  done > "$OUT/e3-wal.tsv" ) & WAL_PID=$!; BG+=($WAL_PID)
t0=$(now_ms)
prod cdc 2400 "$OUT/e3-prod.log" --rate 20 & PROD_PID=$!; BG+=($PROD_PID)
sleep 30; echo "E3_KILL_MS=$(now_ms)" >> "$OUT/manifest.env"; docker kill monimo-cdc-connect-1 >/dev/null
sleep 60; echo "E3_START_MS=$(now_ms)" >> "$OUT/manifest.env"; docker start monimo-cdc-connect-1 >/dev/null
until connector_running; do sleep 0.5; done; echo "E3_RUNNING_MS=$(now_ms)" >> "$OUT/manifest.env"
wait "$PROD_PID"
echo "E3_FROM_MS=$t0" >> "$OUT/manifest.env"
# 넣은 것을 다 받을 때까지 (최대 120초)
want=$(grep -c committed "$OUT/e3-prod.log")
for _ in $(seq 1 240); do
  got=$(cut -f1 "$OUT/e3-cons.log" | sort -u | wc -l | tr -d ' ')
  [ "$got" -ge "$want" ] && break; sleep 0.5
done
sleep 15; kill "$WAL_PID" "${BG[0]}" 2>/dev/null || true; BG=()

echo "== E4 소비자 : 작업 저장 뒤 · 오프셋 커밋 전 종료"
"$PY" "$CONS" alert.events.cdc e4 delivery "$OUT/e4-cons.log" --from latest --crash-after 10 --ready "$OUT/e4.ready" & C1=$!
wait_ready "$OUT/e4.ready"
prod cdc 30 "$OUT/e4-prod.log" --rate 10
wait "$C1" || true
echo "E4_RESTART_MS=$(now_ms)" >> "$OUT/manifest.env"
"$PY" "$CONS" alert.events.cdc e4 delivery "$OUT/e4-cons.log" --idle-exit 30
sql -c "SELECT event_id, count(*) FROM delivery_jobs WHERE event_id IN ($(ids "$OUT/e4-prod.log")) GROUP BY 1" > "$OUT/e4-jobs.tsv"

idle() { # $1 = 이름. 60초 동안 pg_stat_database(cdc) 차이
  local q="SELECT xact_commit + xact_rollback, tup_returned, blks_hit + blks_read FROM pg_stat_database WHERE datname = 'cdc'" b a t0
  t0=$(now_ms); b=$(sql -c "$q"); sleep 60; a=$(sql -c "$q")
  { echo "ms=$(( $(now_ms) - t0 ))"; echo "before=$b"; echo "after=$a"; } > "$OUT/idle-$1.txt"
}
for path in cdc cdc50ms poll1s poll200ms; do
  echo "== P $path"
  topic=alert.events.cdc; table=cdc; RELAY=
  case $path in
    cdc50ms) curl -fs -X PUT -H 'Content-Type: application/json' localhost:28083/connectors/alert-outbox/config \
               --data "$(python3 -c 'import json,sys; c=json.load(open(sys.argv[1]))["config"]; c["poll.interval.ms"]="50"; print(json.dumps(c))' "$HERE/connector.json")" >/dev/null
             sleep 3; until connector_running; do sleep 0.5; done ;;
    poll1s) topic=alert.events.poll; table=poll; "$PY" "$HERE/relay_poll.py" 1 & RELAY=$! ;;
    poll200ms) topic=alert.events.poll; table=poll; "$PY" "$HERE/relay_poll.py" 0.2 & RELAY=$! ;;
  esac
  [ -z "$RELAY" ] || BG+=($RELAY)
  sleep 3
  idle "$path"
  "$PY" "$CONS" "$topic" "p-$path" history "$OUT/p-$path-cons.log" --from latest --ready "$OUT/p-$path.ready" & C=$!; BG+=($C)
  wait_ready "$OUT/p-$path.ready"
  prod "$table" 30 "$OUT/p-$path-single.log" --gap-max 2
  prod "$table" 1200 "$OUT/p-$path-steady.log" --rate 20
  sleep 10; kill "$C" 2>/dev/null || true
  [ -z "$RELAY" ] || kill "$RELAY" 2>/dev/null || true
  BG=()
done
echo "== 끝 : $OUT  (환경은 그대로 둔다. 내리려면 docker compose -f $HERE/compose.yml down -v)"
