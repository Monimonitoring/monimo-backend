#!/usr/bin/env python3
"""발행 이벤트 소비자 (101-outbox-cdc.md). 역할 둘을 같은 코드로 : 발송 작업 만들기 · 사건 이력.

사용: consumer.py <토픽> <그룹> <delivery|history> <로그> [--from earliest|latest] [--crash-after K] [--idle-exit S] [--ready 파일]
  메시지마다 PG 에 한 줄을 ON CONFLICT DO NOTHING 으로 넣고 커밋한 뒤에 Kafka 오프셋을 커밋한다 (재처리돼도 작업은 하나)
  --crash-after K  K 번째 메시지의 PG 커밋 뒤 · 오프셋 커밋 전에 프로세스를 끝낸다
  --idle-exit S    S 초 동안 새 메시지가 없으면 끝낸다
  --ready 파일     파티션을 배정받으면 이 파일을 만든다 (생산을 시작해도 된다는 신호)
로그 한 줄 = event_id · 받은 호스트 시각 ms · 파티션 · 오프셋 · 새로 넣었나(1/0)
"""
import argparse
import json
import os
import time
from pathlib import Path

import psycopg
from kafka import KafkaConsumer
from kafka.structs import OffsetAndMetadata

p = argparse.ArgumentParser()
p.add_argument("topic")
p.add_argument("group")
p.add_argument("role", choices=["delivery", "history"])
p.add_argument("log")
p.add_argument("--from", dest="offset_reset", default="earliest", choices=["earliest", "latest"])
p.add_argument("--crash-after", type=int)
p.add_argument("--idle-exit", type=float)
p.add_argument("--ready")
a = p.parse_args()

conn = psycopg.connect(os.environ.get("CDC_PG", "postgresql://monimo:monimo@localhost:25432/cdc"), autocommit=True)
consumer = KafkaConsumer(a.topic, group_id=a.group, bootstrap_servers=os.environ.get("CDC_KAFKA", "localhost:29092"),
                         enable_auto_commit=False, auto_offset_reset=a.offset_reset, max_poll_records=100)
log = open(a.log, "a", buffering=1)
seen, last = 0, time.time()
while True:
    batch = consumer.poll(timeout_ms=200)
    if a.ready and consumer.assignment() and not Path(a.ready).exists():
        Path(a.ready).touch()
    if not batch:
        if a.idle_exit and time.time() - last > a.idle_exit:
            break
        continue
    for tp, records in batch.items():
        for r in records:
            now = int(time.time() * 1000)
            ev = json.loads(r.value)
            with conn.transaction():
                if a.role == "delivery":
                    new = conn.execute("INSERT INTO delivery_jobs (event_id, channel_id) VALUES (%s, 1) ON CONFLICT DO NOTHING",
                                       (ev["event_id"],)).rowcount
                else:
                    new = conn.execute("INSERT INTO event_history (event_id, aggregate_id, type) VALUES (%s, %s, %s) ON CONFLICT DO NOTHING",
                                       (ev["event_id"], ev["aggregate_id"], ev["type"])).rowcount
            log.write(f"{ev['event_id']}\t{now}\t{tp.partition}\t{r.offset}\t{new}\n")
            seen += 1
            if a.crash_after and seen == a.crash_after:
                os._exit(1)  # PG 에는 넣었고 오프셋은 커밋 전
            # 이 메시지 다음 오프셋만 커밋한다. 인자 없는 commit() 은 poll 로 받아 온 배치 끝까지 커밋해서,
            # 배치 중간에 죽으면 남은 메시지를 다시 받지 못한다 (첫 실행에서 실제로 2건을 잃었다)
            consumer.commit({tp: OffsetAndMetadata(r.offset + 1, None, -1)})
    last = time.time()
consumer.close()
