#!/usr/bin/env bash
# cancelResolvedBeforeSend 원래 쿼리 대 후보(RESOLVED 대기 줄을 먼저 MATERIALIZED 로 뽑고 출발) — 같은 "나쁜 통계"에서 시간 · 취소 건수 비교
# 120-coroutine-lanes.md §5 의 고칠 방향 확인용. 실행 계획 · 결과 건수만 본다(동시 워커 · 잠금 경합은 보지 않는다). 결과 outbox-cancel-candidate.txt
# 사용: docs/alert/experiments/outbox_cancel_candidate.sh   (Docker 필요)
set -euo pipefail
TMP=$(mktemp)
C=exp-outbox-candidate
docker rm -f $C >/dev/null 2>&1 || true
docker run -d --name $C -e POSTGRES_PASSWORD=x postgres:17.11-alpine -c autovacuum=off >/dev/null
until docker exec $C pg_isready -U postgres >/dev/null 2>&1; do sleep 0.5; done; sleep 1
q(){ docker exec -i $C psql -U postgres -qAt -v ON_ERROR_STOP=1; }
q <<'SQL'
CREATE TABLE notification_outbox (
  id BIGSERIAL PRIMARY KEY, alert_event_id BIGINT NOT NULL,
  transition VARCHAR(10) NOT NULL CHECK (transition IN ('FIRING','RESOLVED')),
  alert_channel_id BIGINT NOT NULL, payload JSONB NOT NULL,
  status VARCHAR(12) NOT NULL, attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL, lease_until TIMESTAMPTZ, claim_token UUID, last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
  UNIQUE (alert_event_id, transition, alert_channel_id));
CREATE INDEX notification_outbox_due ON notification_outbox (next_attempt_at) WHERE status IN ('PENDING','IN_FLIGHT');
-- 나쁜 통계: SENT 이력 3000 상태에서 ANALYZE
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT 100000+i, 'FIRING', i, '{}', 'SENT', now(), now(), now() FROM generate_series(1,3000) i;
ANALYZE notification_outbox;
-- 그 뒤 경보 폭주: FIRING PENDING 3000
INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
SELECT i, 'FIRING', i, '{}', 'PENDING', now(), now(), now() FROM generate_series(1,3000) i;
SQL
ORIG="WITH pairs AS (
    SELECT f.id AS fid, r.id AS rid
    FROM notification_outbox f
    JOIN notification_outbox r ON r.alert_event_id = f.alert_event_id AND r.alert_channel_id = f.alert_channel_id
    WHERE f.transition = 'FIRING' AND f.status = 'PENDING' AND f.attempt_count = 0
      AND r.transition = 'RESOLVED' AND r.status = 'PENDING'
    FOR UPDATE OF f, r SKIP LOCKED
)
UPDATE notification_outbox SET status = 'CANCELLED', last_error = 'x', updated_at = now()
WHERE id IN (SELECT fid FROM pairs UNION SELECT rid FROM pairs)"
CAND="WITH res AS MATERIALIZED (
    SELECT id, alert_event_id, alert_channel_id FROM notification_outbox
    WHERE transition = 'RESOLVED' AND status = 'PENDING'
), pairs AS (
    SELECT f.id AS fid, r.id AS rid
    FROM res
    JOIN notification_outbox r ON r.id = res.id AND r.status = 'PENDING'
    JOIN notification_outbox f ON f.alert_event_id = res.alert_event_id AND f.alert_channel_id = res.alert_channel_id
        AND f.transition = 'FIRING' AND f.status = 'PENDING' AND f.attempt_count = 0
    FOR UPDATE OF f, r SKIP LOCKED
)
UPDATE notification_outbox SET status = 'CANCELLED', last_error = 'x', updated_at = now()
WHERE id IN (SELECT fid FROM pairs UNION SELECT rid FROM pairs)"
run(){ # $1 이름 $2 쿼리
  printf "BEGIN;\nEXPLAIN (ANALYZE, COSTS OFF) %s;\nROLLBACK;\nBEGIN;\n%s RETURNING id;\nROLLBACK;\n" "$2" "$2" | q > "$TMP"
  echo "  $1: $(grep 'Execution Time' "$TMP" | sed 's/Execution Time: //') · 취소 $(grep -c '^[0-9]\+$' "$TMP")줄"
}
echo "== RESOLVED 0줄 (FIRING PENDING 3000, 나쁜 통계)"; for i in 1 2 3; do run 원래 "$ORIG"; run 후보 "$CAND"; done
echo "INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
      SELECT i, 'RESOLVED', i, '{}', 'PENDING', now(), now(), now() FROM generate_series(1,100) i;" | q
echo "== RESOLVED 100줄 (짝 있는 FIRING 100 → 200줄 취소돼야 함, 나쁜 통계)"; for i in 1 2 3; do run 원래 "$ORIG"; run 후보 "$CAND"; done
echo "== 후보 실행 계획 (RESOLVED 100줄)"; printf "BEGIN;\nEXPLAIN (ANALYZE, COSTS OFF) %s;\nROLLBACK;\n" "$CAND" | q | grep -E "Nested|Scan|Execution" | head -8
docker rm -f $C >/dev/null
