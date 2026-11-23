#!/usr/bin/env python3
# 진단 회차(DIAG=1)의 0.5초 간격 스레드 덤프를 모아, 스케줄러 스레드와 레인 스레드가 어디서 시간을 썼는지 센다.
# 사용: coro_lanes_stacks.py <회차폴더>...
import re, sys, glob, collections

for d in sys.argv[1:]:
    sched, lane = collections.Counter(), collections.Counter()
    files = sorted(glob.glob(d + '/diag-*.txt'))
    for f in files:
        for b in re.split(r'\n\n(?=")', open(f).read()):
            m = re.match(r'"([^"]+)"', b)
            if not m:
                continue
            name = m.group(1)
            frames = [l.strip()[3:] for l in b.split('\n') if l.strip().startswith('at ')]
            if not frames:
                continue
            if name.startswith('scheduling'):
                if any('org.postgresql' in x for x in frames) and any('NioSocketImpl' in x for x in frames[:6]):
                    k = 'PG 응답 대기 (소켓 read)'
                elif any('HikariPool.getConnection' in x for x in frames):
                    k = 'Hikari 연결 대기'
                elif 'park' in frames[0].lower():
                    k = '쉼 (다음 주기 대기 또는 일 없음)'
                else:
                    k = '그 밖 (CPU 에서 실행 중 등)'
                sched[k] += 1
            elif name.startswith(('DefaultDispatcher', 'delivery-lane')) and 'Unsafe.park' not in frames[0]:
                key = [x.split('(')[0] for x in frames if 'monimo' in x or 'hikari' in x][:1]
                lane[key[0] if key else frames[0].split('(')[0]] += 1
    print(f'== {d} (덤프 {len(files)}장)')
    print(' 스케줄러 스레드 (scheduling-* 두 개 중 바쁜 쪽 포함, 표본 수):')
    for k, v in sched.most_common():
        print(f'   {v:4d}  {k}')
    print(' 레인 스레드 중 깨어 있던 것 (표본 수, 가장 가까운 우리 코드 · Hikari 프레임):')
    for k, v in lane.most_common(5):
        print(f'   {v:4d}  {k}')
