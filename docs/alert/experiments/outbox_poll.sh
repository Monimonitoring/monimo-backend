#!/usr/bin/env bash
# 지금 발송 경로(DB 폴링 outbox)의 지연 · DB 비용을 잰다 (100-outbox-polling.md). CDC 를 붙일지 정하기 전의 출발선.
#
# 사용 (레포 루트, ./gradlew :notifier:bootJar 뒤): docs/alert/experiments/outbox_poll.sh [N=30] [IDLE_SECS=120]
#   단계 : lat-default   기본 설정(폴링 1초 · 그룹 대기 10초)에서 한 건씩 N 번 → 생김 → 선점 → 완료 → 가짜 Slack 수신
#          lat-nowait    그룹 대기 0초 → 폴링 대기만 남긴다 (CDC 가 줄일 수 있는 몫의 상한)
#          lat-fast      그룹 대기 0초 · 폴링 0.2초 → CDC 대신 폴링 간격을 줄이면
#          idle-*        큐가 빈 채 IDLE_SECS 초 : Notifier 없음 · 폴링 1초 · 폴링 0.2초의 PG 트랜잭션 · 읽은 줄 · Notifier CPU
#          burst         10개 서비스 × 20건을 한 문장으로 → 마지막 수신까지 · 메시지 수
# 전제 : backend compose(PostgreSQL)가 떠 있다. 채널 2 webhook = http://127.0.0.1:18999/hook (가짜 Slack). 넣는 작업은 모두 채널 2 다
#        compose 의 Notifier 컨테이너는 같은 큐를 읽으므로 잠시 멈추고, 끝나면 다시 켠다 (trap)
# 결과 : $OUT/ (manifest.env · rows-*.tsv · slack.log · idle-*.txt · burst.tsv · notifier-*.log) → outbox_poll_analyze.py $OUT
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
N=${1:-30}; IDLE_SECS=${2:-120}
OUT=${OUT:-/tmp/monimo-exp/outbox-poll}
PG=${PG:-monimo-dev-postgres-1}; NOTIFIER_CTR=${NOTIFIER_CTR:-monimo-dev-notifier-1}
JAR=$ROOT/notifier/build/libs/notifier-0.0.1-SNAPSHOT.jar
[ -e "$OUT" ] && { echo "$OUT 가 이미 있다" >&2; exit 1; }
[ -f "$JAR" ] || { echo "$JAR 없음 (./gradlew :notifier:bootJar)" >&2; exit 1; }
mkdir -p "$OUT"

psql() { docker exec -i "$PG" psql -U monimo -d monimo -tAq -F $'\t' "$@"; }
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# 채널 2 가 가짜 Slack 인지 먼저 본다 (진짜 Slack 으로 실험 알림이 나가지 않게)
hook=$(psql -c "SELECT regexp_replace(config->>'webhook_url', '^(https?://[^/]+).*', '\1') FROM alert_channels WHERE id = 2 AND enabled")
[ "$hook" = "http://127.0.0.1:18999" ] || { echo "채널 2 가 가짜 Slack(127.0.0.1:18999)이 아니다: $hook" >&2; exit 1; }
[ "$(psql -c "SELECT count(*) FROM notification_outbox WHERE status IN ('PENDING','IN_FLIGHT')")" = 0 ] \
  || { echo "보낼 작업이 남아 있다. 비운 뒤 다시" >&2; exit 1; }
lsof -iTCP:18999 -sTCP:LISTEN >/dev/null 2>&1 && { echo "18999 를 이미 누가 쓴다" >&2; exit 1; }

was_running=$(docker inspect -f '{{.State.Running}}' "$NOTIFIER_CTR" 2>/dev/null || echo false)
NPID=; SPID=
cleanup() {
  [ -z "$NPID" ] || { kill "$NPID" 2>/dev/null; wait "$NPID" 2>/dev/null; } || true
  [ -z "$SPID" ] || kill "$SPID" 2>/dev/null || true
  [ "$was_running" != true ] || docker start "$NOTIFIER_CTR" >/dev/null
}
trap cleanup EXIT
[ "$was_running" != true ] || docker stop "$NOTIFIER_CTR" >/dev/null

python3 "$HERE/fake_slack_hold.py" 18999 "$OUT/slack.log" "$OUT/no-hold" & SPID=$!

{
  echo "N=$N"; echo "IDLE_SECS=$IDLE_SECS"; echo "BACKEND_COMMIT=$(git -C "$ROOT" rev-parse --short HEAD)"
  echo "NOTIFIER_CTR_WAS_RUNNING=$was_running"
  echo "PG_VERSION=$(psql -c 'SHOW server_version')"
  echo "OUTBOX_ROWS_BEFORE=$(psql -c 'SELECT count(*) FROM notification_outbox')"
} > "$OUT/manifest.env"

# 호스트 시계(가짜 Slack · Notifier) ↔ PG 시계(created_at) 차이. 왕복이 가장 짧은 표본의 가운데로 잰다
clock() {
  for _ in 1 2 3 4 5 6 7 8; do
    a=$(now_ms); d=$(psql -c "SELECT (extract(epoch FROM clock_timestamp()) * 1000)::bigint"); b=$(now_ms)
    echo "$((b - a)) $((d - (a + b) / 2))"
  done | sort -n | head -1 | awk '{print "CLOCK_RTT_MS=" $1 "\nPG_MINUS_HOST_MS=" $2}'
}
clock >> "$OUT/manifest.env"

start_notifier() { # $1 = 이름, 나머지 = 스프링 인자
  local name=$1; shift
  java -jar "$JAR" --spring.profiles.active=local --logging.level.org.hibernate.orm.jdbc.bind=info "$@" \
    > "$OUT/notifier-$name.log" 2>&1 & NPID=$!
  until grep -qE "Started .* in|APPLICATION FAILED" "$OUT/notifier-$name.log"; do sleep 0.5; done
  grep -q "APPLICATION FAILED" "$OUT/notifier-$name.log" && { echo "Notifier 기동 실패: $name" >&2; exit 1; }
  sleep 2
}
stop_notifier() { kill "$NPID"; wait "$NPID" 2>/dev/null || true; NPID=; }

insert_one() { # $1 = 이름표 → outbox id
  psql <<SQL
WITH e AS (
  INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at,
      rule_version, metric_kind, operator, threshold, window_sec, severity)
  VALUES (gen_random_uuid(), 2, md5(random()::text) || md5(random()::text), 'FIRING', 9, date_trunc('minute', now()),
      1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL')
  RETURNING id
)
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT id, 'FIRING', 2,
  jsonb_build_object('transition','FIRING','rule_name','$1','service_name','poll-exp','severity','CRITICAL',
    'metric_kind','5XX_RATE','observed_value',9,'operator','GT','threshold',5,'fired_at', to_char(date_trunc('minute', now()) at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"')),
  'PENDING', now(), now(), now()
FROM e RETURNING id;
SQL
}

# 한 건씩 : 넣고 → 끝날 때까지 기다리고 → 0 ~ 2초 쉬어 폴링 주기와 위상을 흩뜨린다
latency() { # $1 = 단계 이름
  local phase=$1 ids=()
  for i in $(seq 1 "$N"); do
    id=$(insert_one "poll-$phase-$i"); ids+=("$id")
    until [ "$(psql -c "SELECT status FROM notification_outbox WHERE id = $id")" != PENDING ] \
       && [ "$(psql -c "SELECT status FROM notification_outbox WHERE id = $id")" != IN_FLIGHT ]; do sleep 0.2; done
    sleep "$(python3 -c 'import random; print(round(random.uniform(0, 2), 2))')"
  done
  # 선점 시각 = lease_until - lease(90초, 기본값). 완료 시각 = updated_at. 시각은 모두 epoch ms
  psql -c "SELECT o.id, o.payload->>'rule_name', o.status,
      (extract(epoch FROM o.created_at) * 1000)::bigint,
      (extract(epoch FROM o.lease_until - interval '90 seconds') * 1000)::bigint,
      (extract(epoch FROM o.updated_at) * 1000)::bigint, o.attempt_count
    FROM notification_outbox o WHERE o.id IN ($(IFS=,; echo "${ids[*]}")) ORDER BY o.id" > "$OUT/rows-$phase.tsv"
}

idle() { # $1 = 단계 이름. 큐가 빈 채 IDLE_SECS 초 동안 PG 통계 · Notifier CPU 차이
  local phase=$1 q="SELECT xact_commit + xact_rollback, tup_returned, tup_fetched, blks_hit + blks_read FROM pg_stat_database WHERE datname = 'monimo'"
  local cpu0=0 cpu1=0 b a t0 t1
  t0=$(now_ms); b=$(psql -c "$q"); [ -z "$NPID" ] || cpu0=$(ps -o cputime= -p "$NPID")
  sleep "$IDLE_SECS"
  t1=$(now_ms); a=$(psql -c "$q"); [ -z "$NPID" ] || cpu1=$(ps -o cputime= -p "$NPID")
  { echo "ms=$((t1 - t0))"; echo "before=$b"; echo "after=$a"; echo "cpu_before=$cpu0"; echo "cpu_after=$cpu1"; } > "$OUT/idle-$phase.txt"
}

echo "== idle-none : Notifier 없이 ${IDLE_SECS}초 (Detector · API server 가 쓰는 몫)"
idle none

echo "== 기본 설정 (폴링 1초 · 그룹 대기 10초)"
start_notifier default
idle poll1s
latency default
echo "== burst : 10 서비스 × 20건"
t=$(now_ms)
psql <<SQL > "$OUT/burst-ids.txt"
WITH e AS (
  INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at,
      rule_version, metric_kind, operator, threshold, window_sec, severity)
  SELECT gen_random_uuid(), 2, md5(random()::text) || md5(g::text), 'FIRING', 9, date_trunc('minute', now()),
      1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL'
  FROM generate_series(1, 200) g
  RETURNING id
), n AS (SELECT id, row_number() OVER (ORDER BY id) AS k FROM e)
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT id, 'FIRING', 2,
  jsonb_build_object('transition','FIRING','rule_name','poll-burst-' || k,'service_name','poll-burst-svc' || (k % 10),
    'severity','CRITICAL','metric_kind','5XX_RATE','observed_value',9,'operator','GT','threshold',5,
    'fired_at', to_char(date_trunc('minute', now()) at time zone 'UTC','YYYY-MM-DD"T"HH24:MI:SS"Z"')),
  'PENDING', now(), now(), now()
FROM n RETURNING id;
SQL
echo "BURST_SENT_MS=$t" >> "$OUT/manifest.env"
until [ "$(psql -c "SELECT count(*) FROM notification_outbox WHERE payload->>'rule_name' LIKE 'poll-burst-%' AND status IN ('PENDING','IN_FLIGHT')")" = 0 ]; do sleep 0.5; done
psql -c "SELECT o.id, o.payload->>'rule_name', o.payload->>'service_name', o.status,
    (extract(epoch FROM o.created_at) * 1000)::bigint, (extract(epoch FROM o.updated_at) * 1000)::bigint
  FROM notification_outbox o WHERE o.payload->>'rule_name' LIKE 'poll-burst-%' ORDER BY o.id" > "$OUT/burst.tsv"
stop_notifier

echo "== 그룹 대기 0초 (폴링 1초)"
start_notifier nowait --monimo.notifier.delivery.group-wait=0s
latency nowait
stop_notifier

echo "== 그룹 대기 0초 · 폴링 0.2초"
start_notifier fast --monimo.notifier.delivery.group-wait=0s --monimo.notifier.delivery.poll-interval=200ms
idle poll200ms
latency fast
stop_notifier

echo "OUTBOX_ROWS_AFTER=$(psql -c 'SELECT count(*) FROM notification_outbox')" >> "$OUT/manifest.env"
echo "== 끝 : $OUT"
