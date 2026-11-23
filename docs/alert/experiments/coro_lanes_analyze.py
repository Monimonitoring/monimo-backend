#!/usr/bin/env python3
# coro_lanes_run.sh 결과 폴더들을 회차마다 한 줄로, 변형 · N 마다 중앙값으로 → <결과폴더>/summary.md
# 사용: coro_lanes_analyze.py <결과폴더>
import json, os, re, statistics, sys, collections

ROOT = sys.argv[1]
CLK_TCK = 100  # /proc/1/stat 의 틱 단위 (리눅스 USER_HZ)


def load(d):
    meta = json.load(open(os.path.join(d, 'meta.json')))
    t0 = meta['t0']
    rows = [l.split() for l in open(os.path.join(d, 'proc.tsv')) if l.strip()]
    rows = [[float(x) for x in r] for r in rows if len(r) == 7]
    before = [r for r in rows if r[0] < t0]
    after = [r for r in rows if r[0] >= t0]
    base = before[-1]
    end = after[-1]
    slack = [l.split('\t') for l in open(os.path.join(d, 'slack.tsv')) if l.strip()]
    normal = [float(t) for t, p in slack if p.strip() == '/normal']
    slow = sorted(float(t) for t, p in slack if p.strip().startswith('/slow/'))
    outbox = [l.split('|') for l in open(os.path.join(d, 'outbox.tsv')) if l.strip()]
    done = max(float(o[2]) for o in outbox)
    sent = sum(1 for o in outbox if o[3].strip() == 'SENT')
    # 넣고 3초 뒤 스레드 목록 : 이름 앞부분으로 묶어 센다
    # 스레드 목록은 커서 레포에 올리지 않는다 (raw.tar.gz 에 없다). 없으면 그 칸만 비운다
    tp = os.path.join(d, 'threads.txt')
    names = re.findall(r'^"([^"]+)"', open(tp).read(), re.M) if os.path.exists(tp) else None
    groups = collections.Counter(re.sub(r'[-#]?\d+$', '', n).rstrip('-') for n in names or [])
    nmt = open(os.path.join(d, 'nmt.txt')).read()
    m = re.search(r'Thread \(reserved=(\d+)KB, committed=(\d+)KB\)', nmt)
    total = re.search(r'Total: reserved=(\d+)KB, committed=(\d+)KB', nmt)
    return dict(
        v=meta['variant'], n=meta['n'], rep=meta['rep'],
        normal=(normal[0] - t0) if normal else None,
        slow_first=(slow[0] - t0) if slow else None, slow_last_recv=(slow[-1] - t0) if slow else None,
        drain=done - t0, sent=sent,
        thr_base=int(base[1]), thr_peak=int(max(r[1] for r in after)),
        rss_base=base[2] / 1024, rss_peak=max(r[2] for r in after) / 1024,
        cpu=(end[4] - base[4]) / CLK_TCK,
        vcs=int(end[5] - base[5]), ncs=int(end[6] - base[6]),
        thr_nmt=len(names) if names is not None else None, stack_res=int(m.group(1)) / 1024 if m else None, stack_com=int(m.group(2)) / 1024 if m else None,
        nmt_com=int(total.group(2)) / 1024 if total else None,
        lane=groups.get('delivery-lane', 0) if names is not None else None,
        io=groups.get('DefaultDispatcher-worker', 0) if names is not None else None,
        http=sum(c for g, c in groups.items() if g.startswith('HttpClient')) if names is not None else None,
    )


runs = []
for name in sorted(os.listdir(ROOT)):
    d = os.path.join(ROOT, name)
    if os.path.isdir(d) and os.path.exists(os.path.join(d, 'meta.json')) and not name.endswith('-r0'):
        try:
            runs.append(load(d))
        except Exception as e:  # 깨진 회차는 표에서 빼고 이유를 남긴다
            print(f"건너뜀 {name}: {e}", file=sys.stderr)

f = lambda x, p=2: '-' if x is None else (f"{x:.{p}f}" if isinstance(x, float) else str(x))
cols = ['v', 'n', 'rep', 'sent', 'normal', 'slow_first', 'slow_last_recv', 'drain', 'thr_base', 'thr_peak', 'thr_nmt', 'lane', 'io', 'http',
        'stack_res', 'stack_com', 'nmt_com', 'rss_base', 'rss_peak', 'cpu', 'vcs', 'ncs']
head = ['변형', 'N', '회', 'SENT', '정상 도착(초)', '느린 첫 수신', '느린 끝 수신', '전부 끝(초)', '스레드 기준', '스레드 최고', '스레드(3초)', '레인', 'IO', 'HttpClient',
        '스택 예약 MB', '스택 커밋 MB', 'NMT 커밋 MB', 'RSS 기준 MB', 'RSS 최고 MB', 'CPU 초', '자발 전환', '비자발 전환']
order = {'T8': 0, 'TU': 1, 'CU': 2}
rank = lambda v: order.get(v, 100 + int(v[1:]) if v[1:].isdigit() else 99)  # C<상한> 은 상한 순
runs.sort(key=lambda r: (r['n'], rank(r['v']), r['rep']))
lines = ['# 스레드 레인 대 코루틴 레인 — 회차별', '', '| ' + ' | '.join(head) + ' |', '|' + '---|' * len(head)]
for r in runs:
    lines.append('| ' + ' | '.join(f(r[c], 1 if c.startswith(('stack', 'nmt', 'rss')) else 2) for c in cols) + ' |')

lines += ['', '# 변형 · N 별 중앙값', '']
keys = ['normal', 'drain', 'thr_peak', 'stack_res', 'stack_com', 'rss_peak', 'cpu', 'vcs', 'ncs']
h2 = ['변형', 'N', '회수', '정상 도착(초)', '전부 끝(초)', '스레드 최고', '스택 예약 MB', '스택 커밋 MB', 'RSS 최고 MB', 'CPU 초', '자발 전환', '비자발 전환']
lines += ['| ' + ' | '.join(h2) + ' |', '|' + '---|' * len(h2)]
by = collections.defaultdict(list)
for r in runs:
    by[(r['n'], rank(r['v']), r['v'])].append(r)
for (n, _, v), rs in sorted(by.items()):
    med = lambda k: statistics.median([x[k] for x in rs if x[k] is not None]) if any(x[k] is not None for x in rs) else None
    lines.append('| ' + ' | '.join([v, str(n), str(len(rs))] + [f(med(k), 1 if k.startswith(('stack', 'rss')) else 2) for k in keys]) + ' |')

open(os.path.join(ROOT, 'summary.md'), 'w').write('\n'.join(lines) + '\n')
print('\n'.join(lines))
