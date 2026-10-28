#!/usr/bin/env python3
"""비교용 폴링 릴레이 : alert_event_outbox_poll 에서 안 실은 줄을 꺼내 Kafka(alert.events.poll)에 싣는다 (101-outbox-cdc.md).

사용: relay_poll.py <간격 초> [--batch 100]
한 바퀴 = BEGIN · 안 실은 줄 FOR UPDATE SKIP LOCKED · Kafka 전송 후 응답(acks=all)까지 기다림 · published_at · COMMIT.
Kafka 전송 뒤 커밋 전에 죽으면 다음 바퀴에 다시 싣는다(최소 한 번). 키는 사건 id 라 같은 사건은 같은 파티션
"""
import argparse
import os
import time

import psycopg
from kafka import KafkaProducer

p = argparse.ArgumentParser()
p.add_argument("interval", type=float)
p.add_argument("--batch", type=int, default=100)
a = p.parse_args()

conn = psycopg.connect(os.environ.get("CDC_PG", "postgresql://monimo:monimo@localhost:25432/cdc"))
kafka = KafkaProducer(bootstrap_servers=os.environ.get("CDC_KAFKA", "localhost:29092"), acks="all", linger_ms=0)
while True:
    with conn.transaction():
        rows = conn.execute(
            "SELECT seq, aggregateid, payload::text FROM alert_event_outbox_poll WHERE published_at IS NULL "
            "ORDER BY seq LIMIT %s FOR UPDATE SKIP LOCKED", (a.batch,)).fetchall()
        futures = [kafka.send("alert.events.poll", key=agg.encode(), value=payload.encode()) for _, agg, payload in rows]
        for f in futures:
            f.get(timeout=10)
        if rows:
            conn.execute("UPDATE alert_event_outbox_poll SET published_at = now() WHERE seq = ANY(%s)", ([r[0] for r in rows],))
    if not rows:
        time.sleep(a.interval)
