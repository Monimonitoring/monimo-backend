#!/usr/bin/env python3
"""Detector 의 기록 트랜잭션을 흉내 낸다 : 사건 INSERT + 발행 이벤트 INSERT 를 한 PG 트랜잭션에 (101-outbox-cdc.md).

사용: producer.py <cdc|poll> <건수> <로그> [--rate R] [--gap-max S] [--mode normal|rollback|crash]
  cdc    alert_event_outbox (Debezium 이 읽는다) / poll  alert_event_outbox_poll (폴링 릴레이가 읽는다)
  --rate      초당 건수(고르게). 없으면 --gap-max 초 안에서 무작위로 쉰다(한 건씩 · 위상 흩뜨리기)
  --mode      rollback : 홀수 번째는 이벤트까지 넣고 ROLLBACK / crash : 커밋 직후 프로세스를 끝낸다(1건만)
로그 한 줄 = event_id · 결과(committed · rolledback · crash-pending) · 커밋이 돌아온 호스트 시각 ms
"""
import argparse
import json
import os
import random
import time
import uuid

import psycopg

p = argparse.ArgumentParser()
p.add_argument("table", choices=["cdc", "poll"])
p.add_argument("n", type=int)
p.add_argument("log")
p.add_argument("--rate", type=float)
p.add_argument("--gap-max", type=float, default=0)
p.add_argument("--mode", default="normal", choices=["normal", "rollback", "crash"])
a = p.parse_args()

conn = psycopg.connect(os.environ.get("CDC_PG", "postgresql://monimo:monimo@localhost:25432/cdc"))
log = open(a.log, "a", buffering=1)
start = time.time()
for i in range(a.n):
    if a.rate:
        time.sleep(max(0.0, start + i / a.rate - time.time()))
    elif a.gap_max and i:
        time.sleep(random.uniform(0, a.gap_max))
    event_id = uuid.uuid4()
    with conn.transaction() as tx:
        cur = conn.cursor()
        agg = cur.execute("INSERT INTO alert_events (state) VALUES ('FIRING') RETURNING id").fetchone()[0]
        payload = json.dumps({"event_id": str(event_id), "aggregate_id": str(agg), "type": "FIRED"})
        if a.table == "cdc":
            cur.execute("INSERT INTO alert_event_outbox (id, aggregatetype, aggregateid, type, payload) VALUES (%s, 'alert_event', %s, 'FIRED', %s)",
                        (event_id, str(agg), payload))
        else:
            cur.execute("INSERT INTO alert_event_outbox_poll (id, aggregateid, type, payload) VALUES (%s, %s, 'FIRED', %s)",
                        (event_id, str(agg), payload))
        if a.mode == "rollback" and i % 2 == 1:
            log.write(f"{event_id}\trolledback\t{int(time.time() * 1000)}\n")
            raise psycopg.Rollback(tx)  # 이벤트까지 넣은 뒤 되돌린다. with 블록 밖으로는 예외가 나가지 않는다
        if a.mode == "crash":
            log.write(f"{event_id}\tcrash-pending\t{int(time.time() * 1000)}\n")
    if a.mode == "crash":
        os._exit(0)  # 커밋은 끝났고, 그 뒤 아무것도 하지 않고 죽는다
    if not (a.mode == "rollback" and i % 2 == 1):
        log.write(f"{event_id}\tcommitted\t{int(time.time() * 1000)}\n")
