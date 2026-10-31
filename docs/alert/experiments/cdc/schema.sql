-- CDC 실험 표. 실제 alert 스키마를 줄여 옮겼다 (사건 · 발행 이벤트 · 소비자 둘의 결과)
CREATE TABLE alert_events (
    id          bigserial PRIMARY KEY,
    state       text        NOT NULL,
    fired_at    timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);
-- CDC 로 꺼내는 발행 이벤트. INSERT 만 한다 (Debezium Outbox Event Router 의 전제). 컬럼 이름은 그 기본값
CREATE TABLE alert_event_outbox (
    id            uuid        PRIMARY KEY,
    aggregatetype text        NOT NULL,
    aggregateid   text        NOT NULL,
    type          text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- 비교용 : 같은 이벤트를 폴링 릴레이가 꺼내 Kafka 에 싣는다. 실은 줄은 published_at 을 채운다
CREATE TABLE alert_event_outbox_poll (
    seq           bigserial   PRIMARY KEY,
    id            uuid        NOT NULL UNIQUE,
    aggregateid   text        NOT NULL,
    type          text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    published_at  timestamptz
);
CREATE INDEX alert_event_outbox_poll_unpublished ON alert_event_outbox_poll (seq) WHERE published_at IS NULL;
-- 소비자 1 : 발송 작업을 만든다. 같은 이벤트를 다시 받아도 작업은 하나 (event_id · 채널 유일)
CREATE TABLE delivery_jobs (
    id          bigserial   PRIMARY KEY,
    event_id    uuid        NOT NULL,
    channel_id  int         NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (event_id, channel_id)
);
-- 소비자 2 : 사건 이력
CREATE TABLE event_history (
    event_id     uuid        PRIMARY KEY,
    aggregate_id text        NOT NULL,
    type         text        NOT NULL,
    received_at  timestamptz NOT NULL DEFAULT now()
);
