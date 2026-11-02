#!/usr/bin/env bash
# 스레드 레인(#250) 대 코루틴 레인 — 같은 입력으로 (docs/alert/120-coroutine-lanes.md)
#
# 격리 환경(프로젝트 compose 와 따로): 네트워크 exp-coro, PG exp-coro-pg(호스트 25433), 가짜 Slack exp-coro-slack, Notifier exp-coro-notifier.
# Notifier 는 리눅스 컨테이너(Temurin 17, CPU 2 · 메모리 1 GB)에서 띄워 /proc 으로 스레드 · RSS · 문맥 교환을 읽는다.
#
# 한 회차: Notifier 를 새로 띄움 → 정상 채널로 20건 데우기 → 기준선 3초 → 느린 채널 N 개에 1건씩 + 정상 채널 1건을 한 번에 넣음
#        → 넣고 3초 뒤 NMT · 스레드 목록 → 전부 SENT 가 될 때까지 기다림 → 모음
# 느린 채널은 받고 4초 뒤 200(요청 한도 5초 안 = 실패가 아니라 느린 성공). 정상 채널 id 가 가장 커서 선점 순서가 맨 뒤다
#
# 사용: coro_lanes_run.sh setup              (한 번: 네트워크 · PG · 마이그레이션 · 채널 · 가짜 Slack)
#       coro_lanes_run.sh run <변형> <N> <회>  변형 = T8(스레드, 바쁜 채널 8 — 지금 기본값) · TU(스레드, 상한 없음) · CU(코루틴, 상한 없음, 레인 스레드 8)
#       coro_lanes_run.sh teardown
# 전제: ./gradlew :notifier:bootJar
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd); ROOT=$(cd "$HERE/../../.." && pwd)
OUT=${OUT:-$ROOT/docs/alert/experiments/coro-lanes-results}
JAR=$ROOT/notifier/build/libs/notifier-0.0.1-SNAPSHOT.jar
MAXN=${MAXN:-3000}; HOLD=${HOLD:-4}
q(){ docker exec exp-coro-pg psql -U monimo -d monimo -tAqc "$1"; }

setup(){
  docker network create exp-coro >/dev/null 2>&1 || true
  docker run -d --name exp-coro-pg --network exp-coro -p 25433:5432 \
    -e POSTGRES_DB=monimo -e POSTGRES_USER=monimo -e POSTGRES_PASSWORD=monimo postgres:17.11-alpine -c max_connections=200 >/dev/null
  until docker exec exp-coro-pg pg_isready -U monimo -d monimo >/dev/null 2>&1; do sleep 0.5; done; sleep 2
  docker run --rm --network exp-coro -v "$ROOT/db/postgres:/flyway/sql:ro" \
    -e FLYWAY_URL=jdbc:postgresql://exp-coro-pg:5432/monimo -e FLYWAY_USER=monimo -e FLYWAY_PASSWORD=monimo \
    -e FLYWAY_LOCATIONS=filesystem:/flyway/sql -e FLYWAY_OUT_OF_ORDER=true -e FLYWAY_CONNECT_RETRIES=10 flyway/flyway:11.7.2-alpine migrate | tail -1
  q "INSERT INTO applications (name) VALUES ('exp-svc')"
  q "INSERT INTO alert_rules (application_id, name, metric_kind, operator, threshold, window_sec, severity)
     SELECT id, 'exp', '5XX_RATE', 'GT', 1, 60, 'CRITICAL' FROM applications WHERE name = 'exp-svc'"
  # 느린 채널 1..MAXN 을 먼저, 정상 채널을 마지막에 만든다 → 정상 채널 id 가 가장 크다
  q "INSERT INTO alert_channels (name, type, config, enabled)
     SELECT 'slow-' || i, 'SLACK', jsonb_build_object('webhook_url', 'http://exp-coro-slack:18990/slow/' || i), true FROM generate_series(1, $MAXN) i"
  q "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('normal', 'SLACK', '{\"webhook_url\":\"http://exp-coro-slack:18990/normal\"}', true)"
  mkdir -p "$OUT"
  docker run -d --name exp-coro-slack --network exp-coro -v "$HERE:/exp:ro" -v "$OUT:/out" python:3.12-alpine \
    python3 /exp/fake_slack_async.py 18990 /out/slack.log "$HOLD" >/dev/null
  echo "setup 끝: 느린 채널 $MAXN 개 + 정상 채널 1개, 느린 응답 ${HOLD}초"
}

teardown(){ docker rm -f exp-coro-notifier exp-coro-slack exp-coro-pg >/dev/null 2>&1 || true; docker network rm exp-coro >/dev/null 2>&1 || true; }

run(){
  local V=$1 N=$2 R=$3; local D=$OUT/$V-n$N-r$R; rm -rf "$D"; mkdir -p "$D"
  local MODE=THREAD MAXBUSY=8
  case $V in T8) ;; TU) MAXBUSY=100000 ;; CU) MODE=COROUTINE MAXBUSY=100000 ;; *) echo "변형?"; exit 1 ;; esac
  docker rm -f exp-coro-notifier >/dev/null 2>&1 || true
  q "TRUNCATE notification_history, notification_outbox, alert_events RESTART IDENTITY CASCADE"
  docker run -d --name exp-coro-notifier --network exp-coro --cpus 2 --memory 1g \
    -v "$JAR:/app.jar:ro" -v "$HERE:/exp:ro" -v "$D:/out" \
    -e POSTGRES_HOST=exp-coro-pg -e POSTGRES_PORT=5432 \
    eclipse-temurin:17-jdk java -XX:NativeMemoryTracking=summary -jar /app.jar --spring.profiles.active=local \
      --monimo.notifier.delivery.group-wait=0s \
      --monimo.notifier.delivery.lane-mode=$MODE --monimo.notifier.delivery.max-busy-channels=$MAXBUSY --monimo.notifier.delivery.lane-threads=8 \
      --logging.level.org.hibernate.SQL=info --logging.level.org.hibernate.orm.jdbc.bind=info >/dev/null
  until docker logs exp-coro-notifier 2>&1 | grep -qE "Started .* in|APPLICATION FAILED"; do sleep 0.5; done
  local CH_NORMAL; CH_NORMAL=$(q "SELECT id FROM alert_channels WHERE name = 'normal'")
  local RULE; RULE=$(q "SELECT id FROM alert_rules WHERE name = 'exp'")
  enqueue(){ # $1 = "채널id 서비스" 줄들을 만드는 SELECT
    q "WITH src AS ($1), e AS (
         INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at, rule_version, metric_kind, operator, threshold, window_sec, severity)
         SELECT gen_random_uuid(), $RULE, md5(random()::text) || md5(random()::text), 'FIRING', now(), 1, '5XX_RATE', 'GT', 1, 60, 'CRITICAL' FROM src
         RETURNING id),
       pair AS (SELECT e.id, s.ch, s.svc FROM (SELECT id, row_number() OVER (ORDER BY id) rn FROM e) e JOIN (SELECT ch, svc, row_number() OVER () rn FROM src) s USING (rn))
       INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
       SELECT id, 'FIRING', ch, jsonb_build_object('transition','FIRING','rule_name','exp','service_name',svc,'severity','CRITICAL'), 'PENDING', now(), now(), now() FROM pair"
  }
  # 데우기: 정상 채널로 서로 다른 서비스 20건 (JIT · 연결 풀 · HttpClient 를 깨워 둔다)
  enqueue "SELECT $CH_NORMAL AS ch, 'warm-' || i AS svc FROM generate_series(1, 20) i"
  until [ "$(q "SELECT count(*) FROM notification_outbox WHERE status = 'SENT'")" = 20 ]; do sleep 0.5; done
  q "TRUNCATE notification_history, notification_outbox, alert_events RESTART IDENTITY CASCADE"
  docker exec -d exp-coro-notifier bash /exp/coro_lanes_sampler.sh /out/proc.tsv
  sleep 3
  local PGSINCE; PGSINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  local T0; T0=$(q "SELECT extract(epoch from clock_timestamp())")
  enqueue "SELECT id AS ch, 'svc-' || id AS svc FROM alert_channels WHERE name LIKE 'slow-%' AND split_part(name, '-', 2)::int <= $N
           UNION ALL SELECT $CH_NORMAL, 'normal-svc'"
  if [ -n "${DIAG:-}" ]; then  # 진단: 넣은 뒤 0.5초마다 스레드 덤프 (스케줄러 스레드가 어디서 시간을 쓰는지)
    ( for i in $(seq -w 1 60); do docker exec exp-coro-notifier jcmd 1 Thread.print > "$D/diag-$i.txt" 2>&1; sleep 0.5; done ) &
  fi
  # ANALYZE=1 : 넣자마자 통계를 새로 만든다. 빈 표에 수천 줄이 한꺼번에 들어오면 autoanalyze 가 따라오기 전까지
  # 선점 첫 쿼리(cancelResolvedBeforeSend)가 중첩 루프로 풀려 한 번에 1초가 걸린다 — 레인과 무관한 변수라 비교에서 뺀다
  [ -n "${ANALYZE:-}" ] && q "ANALYZE notification_outbox"
  sleep 3
  docker exec exp-coro-notifier jcmd 1 VM.native_memory summary > "$D/nmt.txt" 2>&1 || true
  docker exec exp-coro-notifier jcmd 1 Thread.print > "$D/threads.txt" 2>&1 || true
  local total=$((N + 1)) waited=0
  until [ "$(q "SELECT count(*) FROM notification_outbox WHERE status = 'SENT'")" = "$total" ]; do
    sleep 0.5; waited=$((waited + 1)); [ $waited -gt 2400 ] && { echo "시간 초과"; break; }
  done
  sleep 1
  docker exec exp-coro-notifier rm -f /tmp/sampling; sleep 0.5
  q "SELECT alert_channel_id = $CH_NORMAL, extract(epoch from created_at), extract(epoch from updated_at), status FROM notification_outbox" > "$D/outbox.tsv"
  # 가짜 Slack 은 setup 때의 결과 폴더에 기록한다 (OUT 을 바꿔 돌려도 같은 파일)
  awk -v t0="$T0" '$1 >= t0' "${SLACK_LOG:-$ROOT/docs/alert/experiments/coro-lanes-results/slack.log}" > "$D/slack.tsv"
  docker logs exp-coro-notifier > "$D/notifier.log" 2>&1
  docker logs --since "$PGSINCE" exp-coro-pg > "$D/pg-slow.log" 2>&1   # log_min_duration_statement 를 켜 두면 느린 쿼리
  printf '{"variant":"%s","n":%s,"rep":%s,"t0":%s,"mode":"%s","max_busy":%s,"hold":%s,"normal_channel":%s,"analyze":"%s"}\n' \
    "$V" "$N" "$R" "$T0" "$MODE" "$MAXBUSY" "$HOLD" "$CH_NORMAL" "${ANALYZE:-}" > "$D/meta.json"
  docker rm -f exp-coro-notifier >/dev/null
  echo "$V N=$N 회=$R 끝 → $D"
}

case ${1:-} in
  setup) setup ;;
  teardown) teardown ;;
  run) run "$2" "$3" "$4" ;;
  *) echo "사용: $0 setup | run <T8|TU|CU> <N> <회> | teardown"; exit 1 ;;
esac
