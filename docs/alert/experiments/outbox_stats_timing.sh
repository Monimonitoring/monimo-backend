#!/usr/bin/env bash
# 선점 쿼리(cancelResolvedBeforeSend)가 '통계를 찍은 시점'에 따라 얼마나 달라지는지 재현
# docs/alert/120-coroutine-lanes.md §5. 표 · 인덱스는 V202609281823__create_notification_outbox.sql 과 같다(외래 키는 이 쿼리의 계획에 쓰이지 않아 뺐다).
# 쿼리는 OutboxRowRepository.cancelResolvedBeforeSend 그대로, 트랜잭션 안에서 돌리고 ROLLBACK 한다.
# 사용: docs/alert/experiments/outbox_stats_timing.sh [반복 횟수=3] > 결과 파일   (Docker 필요, 컨테이너는 끝나면 지운다)
REPEAT=${1:-3}
PG_IMAGE=${PG_IMAGE:-postgres:17.11-alpine}
for i in $(seq 1 "$REPEAT"); do
echo "##### 시도 $i"
(
set -euo pipefail
C=exp-outbox-stats
docker rm -f $C >/dev/null 2>&1 || true
docker run -d --name $C -e POSTGRES_PASSWORD=x "$PG_IMAGE" -c autovacuum=off >/dev/null
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
SQL
fill(){ # $1 = status
  echo "INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
        SELECT i, 'FIRING', i, '{}'::jsonb, '$1', now(), now(), now() FROM generate_series(1,3000) i;" | q; }
explain(){ # 실제 쿼리를 트랜잭션 안에서 돌리고 되돌린다
  echo "== $1"
  q <<'SQL' | grep -E "Nested Loop|Hash Join|Merge Join|Index Scan|Seq Scan|Execution Time" | head -8
BEGIN;
EXPLAIN (ANALYZE, COSTS OFF)
WITH pairs AS (
    SELECT f.id AS fid, r.id AS rid
    FROM notification_outbox f
    JOIN notification_outbox r ON r.alert_event_id = f.alert_event_id AND r.alert_channel_id = f.alert_channel_id
    WHERE f.transition = 'FIRING' AND f.status = 'PENDING' AND f.attempt_count = 0
      AND r.transition = 'RESOLVED' AND r.status = 'PENDING'
    FOR UPDATE OF f, r SKIP LOCKED
)
UPDATE notification_outbox SET status = 'CANCELLED', last_error = 'x', updated_at = now()
WHERE id IN (SELECT fid FROM pairs UNION SELECT rid FROM pairs);
ROLLBACK;
SQL
  echo "SELECT 'pg_stats status = ' || coalesce(most_common_vals::text,'(없음)') || ' ' || coalesce(most_common_freqs::text,'') FROM pg_stats WHERE tablename='notification_outbox' AND attname='status';" | q
}
# 1) 빈 표에서 통계 → PENDING 3000 넣음
echo "ANALYZE notification_outbox;" | q; fill PENDING; explain "1) 빈 표일 때 찍은 통계 + PENDING 3000"
# 2) PENDING 3000 상태에서 통계
echo "ANALYZE notification_outbox;" | q; explain "2) PENDING 3000 일 때 찍은 통계"
# 3) 실험과 같은 순서: 전부 SENT 에서 통계 → TRUNCATE → PENDING 3000
echo "TRUNCATE notification_outbox RESTART IDENTITY;" | q; fill SENT; echo "ANALYZE notification_outbox;" | q
echo "TRUNCATE notification_outbox RESTART IDENTITY;" | q; fill PENDING; explain "3) 전부 SENT 일 때 찍은 통계 → TRUNCATE → PENDING 3000 (실험 회차와 같은 순서)"
# 4) 운영과 같은 모양: SENT 이력 3000 이 남은 채 통계 → PENDING 3000 추가
echo "TRUNCATE notification_outbox RESTART IDENTITY;" | q
echo "INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
      SELECT 100000+i, 'FIRING', i, '{}'::jsonb, 'SENT', now(), now(), now() FROM generate_series(1,3000) i;" | q
echo "ANALYZE notification_outbox;" | q; fill PENDING; explain "4) SENT 이력 3000 상태에서 찍은 통계 + PENDING 3000 추가 (운영 모양)"
# 5) 4) 뒤에 ANALYZE
echo "ANALYZE notification_outbox;" | q; explain "5) 4) 뒤 ANALYZE"
docker exec $C postgres --version
docker rm -f $C >/dev/null
)
done
