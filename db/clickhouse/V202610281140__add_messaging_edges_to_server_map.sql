-- Service map 에 비동기(메시징) 간선을 그린다 (#259 문제 2, 쇼핑몰 monimo-shop#54 멘토 피드백 D).
--
-- 문제 : Service map 이 그리는 간선 종류가 셋(SERVICE · DB · EXTERNAL)뿐이라 비동기 구간이 아예 안 보인다.
-- spans.span_kind 에는 PRODUCER · CONSUMER 가 처음부터 다 있는데(Enum8 다섯 값) 집계가
-- WHERE span_kind = 'CLIENT' 로 CLIENT 만 읽어서 버렸다. 실측 2026-10-28 : 스팬 495만건 중
-- PRODUCER · CONSUMER 가 0건이고(쇼핑몰이 전부 동기 MVC, ADR #25) 그래서 아무도 못 알아챘다.
-- 데이터는 monimo-shop#54 가 만든다 (쇼핑몰 서비스 둘 사이에 Kafka 토픽 하나).
--
-- 왜 우리 Kafka 로는 안 되나 : Collector · Ingester 가 Kafka 를 쓰지만 둘 다 OTEL_TRACES_EXPORTER=none 이다
-- (#213 : 자기 스팬이 자기 입력으로 돌아오는 되먹임). 그 둘을 켜는 안은 버렸다.
--
-- 간선 모양 : order -> kafka 와 kafka -> inventory 로 둘로 쪼개고 Kafka 를 노드 하나로 둔다.
-- 쪼개는 이유는 스팬이 끊겨 있어서다. 동기 CLIENT 스팬 하나에는 부른 쪽(service_name)과 받는 쪽(peer_service)이
-- 같이 있는데, 비동기는 PRODUCER 스팬이 받는 쪽을 모르고(큐에 넣기만 한다) CONSUMER 스팬이 보낸 쪽을 모른다.
-- 어느 스팬에도 "order -> inventory" 가 안 적혀 있다.
-- 토픽으로 짝지어 하나로 합치는 안과 trace_id 로 잇는 안은 버렸다 : MV 는 INSERT 가 들어오는 순간 그 묶음만
-- 보는 트리거라 다른 스팬을 찾아볼 수 없고, 1분 창 안에서 보낸 쪽과 읽은 쪽의 짝이 안 맞을 수도 있다
-- (59초에 보내고 다음 분에 읽으면 둘이 갈린다). 되돌림 : 토픽이 여럿이 되어 Kafka 노드 하나로 뭉쳐
-- 읽기 어려워지면 토픽마다 노드를 두는 쪽으로 간다.
--
-- CONSUMER 는 부른 쪽과 받는 쪽을 뒤집어 읽는다 : CONSUMER 스팬의 service_name 은 "읽은 서비스" 라
-- 그대로 쓰면 caller 자리에 들어가 화살표가 거꾸로 그려진다.
--
-- 노드 이름을 attributes['messaging.system'] 에서 가져오는 이유 : Kafka 계측 모듈은 server.address 를
-- 안 넣을 수 있어서 peer_address 가 빈 글자일 수 있다. messaging.system 은 OTel 규약이 반드시 넣는 값이다.
-- 비었을 때를 대비해 'messaging' 으로 떨어뜨린다 (빈 이름 노드가 생기면 화면이 노드를 못 잇는다).
--
-- MESSAGING 을 Enum 맨 뒤(3)에 붙이는 이유 : 앞 셋의 숫자가 그대로 남아야 쌓인 줄을 다시 안 써도 된다.
--
-- 순서는 db/clickhouse/README.md 대로 MODIFY COLUMN 이 먼저, MV MODIFY QUERY 가 나중이다
-- (거꾸로 하면 MV 가 아직 없는 Enum 값을 쓰려다 실패한다).
-- MV SELECT 는 V202609221907 과 WHERE 한 줄 · caller/callee/kind 세 줄만 다르다.
-- 컬럼 수와 순서는 server_map_1m 과 정확히 같아야 한다 : ALTER MODIFY QUERY 는 타깃 스키마를 검사하지 않아서
-- 빠뜨리면 그 컬럼이 조용히 타입 기본값으로 채워진다.
ALTER TABLE monimo.server_map_1m
    MODIFY COLUMN callee_kind Enum8('SERVICE' = 0, 'DB' = 1, 'EXTERNAL' = 2, 'MESSAGING' = 3);

ALTER TABLE monimo.mv_server_map_1m MODIFY QUERY
SELECT
    toStartOfMinute(start_time)                                        AS ts_min,
    if(span_kind = 'CONSUMER',
       if(attributes['messaging.system'] != '', attributes['messaging.system'], 'messaging'),
       service_name)                                                   AS caller_service,
    multiIf(span_kind = 'CONSUMER', service_name,
            span_kind = 'PRODUCER',
                if(attributes['messaging.system'] != '', attributes['messaging.system'], 'messaging'),
            peer_service != '', peer_service,
            peer_address)                                              AS callee_service,
    CAST(multiIf(span_kind IN ('PRODUCER', 'CONSUMER'), 'MESSAGING',
                 peer_service != '', 'SERVICE',
                 attributes['db.system'] != '', 'DB',
                 'EXTERNAL'), 'Enum8(\'SERVICE\' = 0, \'DB\' = 1, \'EXTERNAL\' = 2, \'MESSAGING\' = 3)') AS callee_kind,
    count()                                                            AS cnt,
    countIf(status_code = 'ERROR')                                     AS err_cnt,
    sum(duration_ns)                                                   AS sum_duration_ns
FROM monimo.spans
WHERE span_kind IN ('CLIENT', 'PRODUCER', 'CONSUMER')
GROUP BY ts_min, caller_service, callee_service, callee_kind;
