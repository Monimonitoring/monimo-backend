# 알림 실험 도구

| 파일 | 하는 일 |
|---|---|
| `fake_slack_hold.py <port> <log> <hold>` | 가짜 Slack. 받는 즉시 수신 시각 · 본문을 `<log>` 에 한 줄(fsync). `<hold>` 파일이 있으면 그 안의 초만큼 응답을 늦춘다 → "외부는 받았는데 발신자는 결과를 모르는" 구간을 만든다 |
| `enqueue.sh <tag> <n>` | Detector 가 넣었을 사건 + FIRING 발송 작업을 n 개 넣는다 (채널 2 · shop-order, 한 그룹) |
| `slow_channel.sh` | 느린 채널(:18997, 응답 4초)이 정상 채널(:18999, 채널 2) 발송을 늦추는지 (`70-slow-channel.md`). 채널 3 이 느린 채널 주소로 있어야 한다 |
| `notifier.sh <name>` | notifier jar 를 JVM 으로 직접 띄운다. `kill -9 $(cat $OUT/notifier.pid)` 로 실제 프로세스를 죽인다 |
| `gc_run.sh <이름> [힙MB] [RATE] [DURATION]` | GC 경보 실험 한 회차 (`80-gc-time.md`). 쇼핑몰 order 를 `gc_order_override.yml`(G1 · Xms=Xmx · GC 로그 · 전송 60초)로 다시 띄우고 데우기 → GC_TIME 규칙 켜기 → k6 → 꼬리 대기 → 모으기 → 규칙 끄기 · order 원래대로 |
| `gc_collect.sh <폴더> <from초> <to초>` | 그 구간의 gc-time 응답 · `metrics_raw` 원본 점 · `metrics_1m` 마지막 값 · GC_TIME 사건 · 발송 큐 · 이력 · 자동 덤프 · Detector 로그를 모은다 |
| `gc_analyze.py <폴더>` | 옛 계산(develop) · 새 계산(`#239`) · API · GC 로그를 1분마다 나란히, 원본 점 사이 GC 로그 대조, 대표 로그 줄, k6, 경보 타임라인(판정 버킷 끝 기준 +초) → `<폴더>/analysis.md` |
| `gc_replay.py <폴더> [기준ms]` | 쌓인 GC 원본 전체를 옛 · 새 계산으로 다시 만들고 같은 입력에 N=1 · N=3 상태머신을 돌린다 (최종 조회 재평가) |
| `gc_figure.py <폴더> <기준ms> <제목>` | 모은 폴더 하나를 1분 GC · 요청 p95 · 경보 시각 한 장으로 |

| `overhead_gc.sh <off\|on\|dump> <이름> [RATE] [SECS]` | 에이전트 · 스레드 덤프가 order JVM 의 GC · 요청 지연에 주는 비용 한 회차. 쇼핑몰 `perf/overhead/`(#36 · #51 CPU · 메모리) 출발선에 `overhead_gc_override.yml`(G1 · 힙 고정 · GC · safepoint 로그)을 얹고, dump 는 측정 중 30초마다 Collector 에 덤프를 직접 요청한다 |
| `overhead_gc_suite.sh [REPS] [RATE] [SECS]` | 세 변형을 순서를 돌려 가며 REPS 번 → `overhead_gc_analyze.py` |
| `overhead_gc_analyze.py <폴더>` | 변형별 GC 횟수 · 합 · 최대 · 할당 속도, 요청 p50 · p95 · p99, 덤프 창(off · on 은 같은 자리 창) p95, Safepoint "ThreadDump" 정지, 주문당 CPU → `<폴더>/summary.md` |

결과와 블로그 근거 정리는 `gc-results/README.md`, 에이전트 · 덤프 비용은 `gc-results/2026-10-28-overhead/` · `../90-overhead-gc.md`.

GC 실험 전제: Detector · API server 가 GC_TIME 평가(`#223`) · 간격 보정(`#239`)을 담은 이미지, 쇼핑몰 `~/monimo-shop`(`SHOP` 로 바꿈), GC_TIME 규칙 id 7(`RULE_ID`). API 응답이 어느 계산인지는 `gc_analyze.py` 가 맞춰 보여 준다.

전제: 로컬 compose(PostgreSQL), 채널 2 의 webhook_url = `http://127.0.0.1:18999/hook`, `./gradlew :notifier:bootJar`.
실패 지점은 코드 훅 없이 가짜 Slack 의 응답 지연으로 통제한다 (운영 코드에 실험용 분기를 넣지 않는다).
