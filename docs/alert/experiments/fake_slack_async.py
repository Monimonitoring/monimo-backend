#!/usr/bin/env python3
# 실험용 가짜 Slack (asyncio). 연결 수천 개를 동시에 붙잡고 있어도 스레드가 늘지 않게 이벤트 루프 하나로 받는다
# (fake_slack_hold.py 는 요청마다 스레드 하나라 느린 채널 수천 개를 흉내 내면 가짜 쪽이 먼저 병목이 된다).
#  - POST /slow/<i>  : 받는 즉시 기록하고 HOLD 초 뒤에 200 ("느린 성공" — 요청 한도 5초 안)
#  - POST /normal    : 받는 즉시 기록하고 바로 200
# 기록 한 줄 = "수신 시각(UTC epoch 초)\t경로". 사용: fake_slack_async.py <port> <log> <hold초>
import asyncio, sys, time

PORT = int(sys.argv[1]); LOG = sys.argv[2]; HOLD = float(sys.argv[3])
out = open(LOG, 'a', buffering=1)


async def handle(reader, writer):
    try:
        while True:  # keep-alive : 같은 연결로 다음 요청이 올 수 있다
            head = await reader.readuntil(b'\r\n\r\n')
            lines = head.decode('latin-1').split('\r\n')
            path = lines[0].split(' ')[1]
            length = 0
            for h in lines[1:]:
                if h.lower().startswith('content-length:'):
                    length = int(h.split(':', 1)[1])
            if length:
                await reader.readexactly(length)
            out.write(f"{time.time():.6f}\t{path}\n")
            if path.startswith('/slow/'):
                await asyncio.sleep(HOLD)
            writer.write(b'HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: text/plain\r\n\r\nok')
            await writer.drain()
    except (asyncio.IncompleteReadError, ConnectionResetError):
        pass
    finally:
        writer.close()


async def main():
    server = await asyncio.start_server(handle, '0.0.0.0', PORT, backlog=8192)
    async with server:
        await server.serve_forever()

asyncio.run(main())
