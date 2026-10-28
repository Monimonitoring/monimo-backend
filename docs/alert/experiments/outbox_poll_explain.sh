#!/usr/bin/env bash
# Notifier 선점 쿼리(OutboxRowRepository.lockDue)가 끝난 이력이 쌓여도 같은 비용인지 (100-outbox-polling.md).
# 끝난 줄(SENT) H 개 + 보낼 줄 10 개를 한 트랜잭션에 넣고 EXPLAIN (ANALYZE, BUFFERS) 를 REPEAT 번 → ROLLBACK. 표에는 아무것도 남지 않는다
# 사용: docs/alert/experiments/outbox_poll_explain.sh <결과 파일> [H=0 100000]...
set -euo pipefail
OUTF=${1:?결과 파일}; shift
SIZES=("${@:-0}"); [ $# -gt 0 ] || SIZES=(0 100000)
PG=${PG:-monimo-dev-postgres-1}; REPEAT=${REPEAT:-5}
: > "$OUTF"
for H in "${SIZES[@]}"; do
  echo "## 끝난 줄 +$H (기존 줄 포함 총 $(docker exec -i "$PG" psql -U monimo -d monimo -tAq -c 'SELECT count(*) FROM notification_outbox') + $H + 10)" >> "$OUTF"
  {
    echo "BEGIN;"
    cat <<SQL
WITH e AS (
  INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at,
      rule_version, metric_kind, operator, threshold, window_sec, severity)
  SELECT gen_random_uuid(), 2, md5('explain-' || g), 'FIRING', 9, now() - interval '1 day',
      1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL'
  FROM generate_series(1, $H + 10) g
  RETURNING id
), n AS (SELECT id, row_number() OVER (ORDER BY id) AS k FROM e)
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT id, 'FIRING', 2, jsonb_build_object('rule_name', 'explain-' || k, 'service_name', 'explain-svc' || (k % 10)),
  CASE WHEN k <= $H THEN 'SENT' ELSE 'PENDING' END, now() - interval '1 minute', now() - interval '1 minute', now()
FROM n;
ANALYZE notification_outbox;
SQL
    for _ in $(seq 1 "$REPEAT"); do
      cat <<'SQL'
EXPLAIN (ANALYZE, BUFFERS, TIMING OFF, SUMMARY ON)
WITH ready AS (
    SELECT DISTINCT alert_channel_id, COALESCE(payload->>'service_name', '') AS svc
    FROM notification_outbox
    WHERE (status = 'PENDING' AND next_attempt_at <= now() AND created_at <= now() - interval '10 seconds')
       OR (status = 'IN_FLIGHT' AND lease_until < now())
)
SELECT o.* FROM notification_outbox o
JOIN ready r ON r.alert_channel_id = o.alert_channel_id AND r.svc = COALESCE(o.payload->>'service_name', '')
WHERE ((o.status = 'PENDING' AND o.next_attempt_at <= now()) OR (o.status = 'IN_FLIGHT' AND o.lease_until < now()))
  AND NOT (o.transition = 'RESOLVED' AND EXISTS (
      SELECT 1 FROM notification_outbox f
      WHERE f.alert_event_id = o.alert_event_id AND f.alert_channel_id = o.alert_channel_id
        AND f.transition = 'FIRING' AND f.status IN ('PENDING', 'IN_FLIGHT')))
ORDER BY o.alert_channel_id, COALESCE(o.payload->>'service_name', ''), o.id
LIMIT 10
FOR UPDATE OF o SKIP LOCKED;
SQL
    done
    echo "ROLLBACK;"
  } | docker exec -i "$PG" psql -U monimo -d monimo -q >> "$OUTF" 2>&1
  echo >> "$OUTF"
done
echo "== 끝 : $OUTF"
