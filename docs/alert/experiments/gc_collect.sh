#!/usr/bin/env bash
# GC 실험 한 회차의 재료를 <폴더> 에 모은다 : gc-time 응답 · metrics_raw 원본 점 · metrics_1m 1분 마지막 값 ·
# GC_TIME 사건 · 발송 큐 · 발송 이력 · 자동 덤프 · Detector 로그 · order 컨테이너 상태
# 사용: docs/alert/experiments/gc_collect.sh <폴더> <from 초> <to 초>   (gc_run.sh 가 부른다. 다시 모을 때도 같은 인자)
# 값: SERVICE(shop-order) API(http://localhost:8080) MONIMO_INTERNAL_TOKEN CH_URL CH_AUTH PG DETECTOR ORDER
set -euo pipefail
DIR=$1; FROM=$2; TO=$3
SERVICE=${SERVICE:-shop-order}
API=${API:-http://localhost:8080}; TOKEN=${MONIMO_INTERNAL_TOKEN:-local-internal-token}
CH_URL=${CH_URL:-http://localhost:18123}; CH_AUTH=${CH_AUTH:-monimo:monimo}
PG=${PG:-monimo-dev-postgres-1}; DETECTOR=${DETECTOR:-monimo-dev-detector-1}; ORDER=${ORDER:-monimo-shop-order-1}
mkdir -p "$DIR"

iso() { date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ; }
ch() { curl -sf -u "$CH_AUTH" "$CH_URL/?database=monimo" --data-binary "$1"; }
pg() { docker exec -e PGTZ=UTC "$PG" psql -U monimo -d monimo --csv -c "$1"; }
GC_METRICS="'jvm.gc.duration.sum', 'jvm.gc.duration.count'"
EVENTS="SELECT id FROM alert_events WHERE metric_kind = 'GC_TIME' AND fired_at >= to_timestamp($FROM) AND fired_at < to_timestamp($TO)"

echo "== $(iso "$FROM") ~ $(iso "$TO") 를 $DIR 에 모은다"

# 지금 떠 있는 API server 가 준 1분 값. 옛 계산인지 새 계산(#239)인지는 gc_analyze.py 가 맞춰 본다
curl -sf -H "X-Internal-Token: $TOKEN" \
  "$API/api/v1/internal/metrics/gc-time?service_name=$SERVICE&from=$(iso "$FROM")&to=$(iso "$TO")" > "$DIR/gc-api.json"

# 원본 점 : 새 계산이 읽는 것. 앞 300초(첫 1분의 앞 값) · 뒤 150초(마지막 빈 1분 메우기)까지 GcTimeService 와 같게
ch "SELECT agent_id, metric_name, series_hash, attributes['jvm.gc.name'] AS gc_name, attributes['jvm.gc.action'] AS gc_action,
           toUnixTimestamp(ts) AS ts, value
    FROM metrics_raw
    WHERE service_name = '$SERVICE' AND metric_name IN ($GC_METRICS) AND is_counter = 1
      AND ts >= toDateTime($((FROM - 300))) AND ts < toDateTime($((TO + 150)))
    ORDER BY agent_id, metric_name, series_hash, ts
    FORMAT CSVWithNames" > "$DIR/raw.csv"

# 1분 마지막 값 : 옛 계산(develop)이 읽던 것. 시각이 없다
ch "SELECT agent_id, metric_name, series_hash, toUnixTimestamp(ts_min) AS ts_min, argMaxMerge(last_v) AS last
    FROM metrics_1m
    WHERE service_name = '$SERVICE' AND metric_name IN ($GC_METRICS)
      AND ts_min >= toDateTime($((FROM - 300))) AND ts_min < toDateTime($TO)
    GROUP BY agent_id, metric_name, series_hash, ts_min
    HAVING max(is_counter) = 1
    ORDER BY agent_id, metric_name, series_hash, ts_min
    FORMAT CSVWithNames" > "$DIR/m1.csv"

# 같은 1분의 요청 수 · 오류 · p95 (서버 스팬 기준 service_health_1m, 단위 ms). GC 와 같은 시간축에 놓으려고
ch "SELECT toUnixTimestamp(ts_min) AS ts_min, countMerge(cnt) AS requests, sumMerge(err_cnt) AS errors,
           quantilesTDigestMerge(0.5, 0.95, 0.99)(dur_q)[2] / 1e6 AS p95_ms
    FROM service_health_1m
    WHERE service_name = '$SERVICE' AND ts_min >= toDateTime($FROM) AND ts_min < toDateTime($TO)
    GROUP BY ts_min ORDER BY ts_min
    FORMAT CSVWithNames" > "$DIR/health.csv"

# 자동 덤프. requested_at 은 원격 덤프 응답을 받은 뒤 찍힌다 (요청 시작 시각이 아니다)
ch "SELECT agent_id, dump_uuid, requested_by, toUnixTimestamp64Milli(requested_at) AS requested_ms, thread_count
    FROM thread_dumps
    WHERE service_name = '$SERVICE' AND requested_at >= toDateTime64($FROM, 3) AND requested_at < toDateTime64($TO, 3)
    ORDER BY requested_at
    FORMAT CSVWithNames" > "$DIR/thread-dumps.csv"

# fired_at 은 판정한 버킷 끝(논리 시각), created_at 은 기록 처리 시각
pg "SELECT e.id, e.state, a.agent_key, e.observed_value, e.threshold, e.fired_at, e.created_at, e.resolved_at
    FROM alert_events e LEFT JOIN agents a ON a.id = e.agent_id
    WHERE e.id IN ($EVENTS) ORDER BY e.id" > "$DIR/alert-events.csv"
pg "SELECT alert_event_id, transition, status, attempt_count, created_at, updated_at
    FROM notification_outbox WHERE alert_event_id IN ($EVENTS) ORDER BY id" > "$DIR/outbox.csv"
pg "SELECT alert_event_id, result, retry_count, sent_at, created_at
    FROM notification_history WHERE alert_event_id IN ($EVENTS) ORDER BY sent_at" > "$DIR/history.csv"

docker logs -t --since "$(iso "$FROM")" --until "$(iso "$TO")" "$DETECTOR" 2>&1 | grep -E "GC_TIME|gc-time" > "$DIR/detector.log" || true
docker inspect -f '{{json .State}}' "$ORDER" > "$DIR/order-state.json" 2>/dev/null || true

wc -l "$DIR"/*.csv | sed 's/^/   /'
