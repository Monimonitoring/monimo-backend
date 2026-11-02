# 발송 레인을 스레드에서 코루틴으로 (실측)

> 2026-11-03 로컬 실측 (`#271`). `#250` 의 채널별 발송 레인(바쁜 채널마다 스레드 하나, 상한 8)을 코루틴 레인으로 바꿔 같은 입력으로 비교했다.
> 결론: **같은 지연에서 스레드 · 메모리가 크게 적어서** 코루틴 레인을 기본으로 하고 상한을 8 → 256 으로 올렸다. 지연이 더 빨라서가 아니다.

## 1. 문제

`#250` 레인은 **바쁜 채널 하나 = 레인 스레드 하나**다. 스레드가 끝없이 늘지 않게 바쁜 채널 상한(`max-busy-channels`)을 8 로 묶었다.
그 결과 느린 채널이 8개를 넘으면 나머지 채널은 레인이 빌 때까지 선점되지 못한다. 느린 채널(4초 응답) 100개 뒤에 줄 선 정상 채널 알림은 **51초**, 1000개면 **504초** 뒤에 나갔다(§4, 계산값 100 ÷ 8 × 4초 = 50초 · 1000 ÷ 8 × 4초 = 500초와 맞는다).

상한을 올리면 지연은 사라지지만 기다리는 채널 수만큼 OS 스레드가 생긴다. 이 비용을 코루틴이 얼마나 줄이는지가 질문이다.

## 2. 바꾼 것

| | 스레드 레인 (`ChannelLanes`, `#250`) | 코루틴 레인 (`CoroutineLanes`) |
|---|---|---|
| 레인 하나 | 레인 스레드 하나 (`delivery-lane-N`) | 코루틴 하나 |
| Slack 호출 | `HttpClient.send()` — 응답까지 레인 스레드가 잠든다 | `HttpClient.sendAsync().await()` — 코루틴만 멈추고 스레드는 놓는다 |
| DB 일 (채널 조회 · 결과 기록) | 레인 스레드에서 | `Dispatchers.IO.limitedParallelism(8)` 스레드에서. Hikari 연결 10개를 넘지 않게 |
| 지키는 약속 | 채널 안은 차례로 하나씩 · 바쁜 채널은 다음 선점에서 빠짐 · 상한이면 쉼 · 종료 때 진행 중 호출을 마저 끝냄 | 같음 (`laneRules` · `laneUnitRules` 를 두 레인이 같이 돈다) |

- `send()` 와 `sendAsync()` 는 안쪽이 같다. JDK 17 `HttpClientImpl.send` 는 `sendAsync(...)` 를 부르고 `cf.get()` 으로 기다릴 뿐이다(`HttpClientImpl.java` 878 · 879행). 소켓은 두 경우 모두 `HttpClient-1-SelectorManager` 스레드 하나가 epoll 로 지켜본다(덤프에서 `EPollSelectorImpl.doSelect`). 다른 것은 **부른 스레드가 `cf.get()` 에서 잠드느냐**뿐이다.
- 트랜잭션은 `claimer.finish` 한 번의 안에서 시작해 끝나고 그 안에 suspend 지점이 없다. 그래서 `@Transactional` 의 ThreadLocal 이 코루틴이 스레드를 옮겨 다니는 것과 부딪히지 않는다. `OutboxClaimer` 가 선점과 결과 기록을 짧은 트랜잭션 둘로 나누고 외부 호출을 그 사이, 트랜잭션 밖에 둔 구조 덕분이다.
- 설정 `monimo.notifier.delivery.lane-mode` = `COROUTINE`(기본) · `THREAD`(되돌릴 때), `lane-threads` = 8, `max-busy-channels` = 256.

## 3. 실험

- 격리 환경 (`experiments/coro_lanes_run.sh setup`): 네트워크 `exp-coro`, PG 17, asyncio 가짜 Slack(연결 수천 개를 이벤트 루프 하나로 받는다), Notifier 는 **리눅스 컨테이너 · Temurin 17 · CPU 2 · 메모리 1 GB** 에서 jar 로. 맥 호스트가 아니라 리눅스라 `/proc` 을 읽을 수 있다.
- 한 회차: Notifier 새로 띄움 → 정상 채널로 20건 데우기 → 기준선 3초 → **느린 채널 N 개에 1건씩 + 정상 채널 1건**을 한 트랜잭션에 넣음 → 3초 뒤 NMT · 스레드 목록 → 전부 SENT 까지. 느린 채널은 받고 4초 뒤 200(요청 한도 5초 안 = 느린 성공), 정상 채널 id 가 가장 커서 선점 순서가 맨 뒤다. `group-wait` 는 0초(레인 대기만 보이게).
- 변형: **T8** 스레드 · 상한 8(`#250` 기본값) / **TU** 스레드 · 상한 없음 / **CU** 코루틴 · 상한 없음 · 레인 스레드 8.
- 0.2초마다 `/proc/1/status`(Threads · VmRSS · VmHWM) · `/proc/1/stat`(user+sys 틱, 끝난 스레드 포함) · `/proc/1/task/*/status`(문맥 교환 합, 살아 있는 스레드만) 를 적는다(`coro_lanes_sampler.sh`). 스레드 스택은 `-XX:NativeMemoryTracking=summary` 의 `Thread` 항목.
- N = 10 · 100 · 1000 · 3000, 각 3회(변형 순서를 회차마다 돌림). T8 은 N=1000 이 한 회차 9분이라 1회, N=3000 은 계산으로 갈음. N=1000 · 3000 은 넣자마자 `ANALYZE` 한 회차를 따로 3회씩 더 돌렸다(§5).

## 4. 결과 (중앙값)

| N | 변형 | 정상 채널 도착 | 전부 끝 | 스레드 최고 | 스택 예약 / 커밋 | RSS 기준 → 최고 | CPU | 자발 · 비자발 문맥 교환 |
|---|---|---|---|---|---|---|---|---|
| 10 | T8 | 5.63초 | 5.63초 | 70 | 134 / 7 MB | 382 → 414 MB | 0.54초 | 724 · 82 |
| 10 | TU | 0.68초 | 4.70초 | 71 | 132 / 7 MB | 385 → 419 MB | 0.42초 | 745 · 56 |
| 10 | CU | 0.65초 | 4.65초 | 69 | 130 / 7 MB | 396 → 424 MB | 0.52초 | 859 · 46 |
| 100 | T8 | **51.17초** | 51.17초 | 74 | 132 / 7 MB | 387 → 431 MB | 3.40초 | 5,097 · 686 |
| 100 | TU | 0.63초 | 4.64초 | 165 | 321 / 19 MB | 393 → 463 MB | 1.50초 | 3,140 · 479 |
| 100 | CU | 0.98초 | 4.98초 | 80 | 146 / 8 MB | 400 → 450 MB | 1.67초 | 3,306 · 492 |
| 1000 | T8 (1회) | **503.54초** | 503.54초 | 79 | 132 / 7 MB | 387 → 463 MB | 25.63초 | 43,091 · 5,182 |
| 1000 | TU | 2.79초 | 6.99초 | **1,079** | **2,127 / 131 MB** | 382 → **637 MB** | 9.12초 | 23,461 · 3,409 |
| 1000 | CU | 2.57초 | 6.57초 | **97** | **162 / 9 MB** | 399 → **502 MB** | 7.63초 | 24,729 · 3,340 |
| 3000 | TU | 10.39초 | 14.39초 | 1,859 | 2,304 / 142 MB | 386 → 811 MB | 20.49초 | 62,154 · 12,389 |
| 3000 | CU | 7.01초 | 17.77초 | 204 | 164 / 9 MB | 391 → 592 MB | 18.92초 | 67,596 · 14,142 |

N=1000 · 3000 행은 `ANALYZE` 회차(`experiments/coro-lanes-analyzed/summary.md`). 나머지는 `experiments/coro-lanes-results/summary.md`. 회차별 값은 각 `summary.md`.

- **지연은 TU 와 CU 가 같다.** N=1000 에서 2.79 대 2.57초. N=3000 의 세 회차는 TU 7.5 · 31.5 · 10.4초, CU 6.1 · 28.2 · 7.0초로 둘 다 2회차가 크게 튀었고 그 2회차는 둘 다 PG 체크포인트(16:27:00 ~ 16:27:59)와 겹쳤다(그 회차 느린 쿼리는 둘 다 `COMMIT`, 중앙 76 · 84ms). 코루틴이 빠르다고 말할 근거는 없다.
- **스레드 · 메모리는 크게 다르다.** N=1000 에서 스레드 1,079 대 97, 스택 **예약** 2,127 대 162 MB, **커밋** 131 대 9 MB, RSS 증가 +254 대 +102 MB. N=3000 에서 TU 는 스레드가 1,859(기다리는 채널보다 적은 것은 앞 레인이 끝나 스레드를 다시 쓴 몫), RSS +424 MB 로 메모리 1 GB 컨테이너의 절반 가까이를 썼다.
- 스택 예약과 커밋이 15 배 차이 나는 것은 JVM 이 스레드마다 `ThreadStackSize` 2,048 KB 를 가상 주소로 **예약**만 하고 실제로 닿은 페이지만 물리 메모리로 **커밋**되기 때문이다(TU 1000: 스레드당 예약 약 2 MB · 커밋 약 121 KB).
- **CPU · 문맥 교환은 비슷하다.** N=1000 에서 CPU 9.1 대 7.6초, 자발 전환 23.5 천 대 24.7 천. 한 건 보낼 때마다 일어나는 JDBC 왕복(채널 조회 · 결과 기록 · 커밋)이 소켓 read 에서 스레드를 재우므로, 응답 대기 한 번의 잠듦을 없앤 것만으로는 전환 수가 줄지 않았다. 코루틴이 아낀 것은 "기다리는 동안 붙잡혀 있던 스레드"이지 커널 왕복 자체가 아니다.
- RSS 의 CU 증가 +102 MB 가 무엇인지(힙의 코루틴 · HTTP 연결 버퍼 · JDBC 등)는 나누지 않았다.

## 5. N=3000 에서 코루틴이 느려 보였던 것 — 레인과 무관한 원인

`ANALYZE` 없이 돌린 첫 묶음에서 N=3000 은 CU 가 정상 채널 21.3초(세 회차 8.8 · 33.8 · 19.4)로 TU 9.5초보다 느렸다. 원인을 좇았다.

1. 느린 채널 요청이 **나간 시각**부터 늦었다(마지막 요청 CU 16 ~ 41초, TU 8 ~ 12초). 응답 대기가 아니라 선점이 느렸다.
2. 0.5초 간격 스레드 덤프 60장(`coro-lanes-diag/stacks.txt`): CU 의 스케줄러 스레드는 120 표본 중 **26 번 PG 응답 대기**, TU 는 3 번. 레인 스레드는 둘 다 대부분 `commit` 에서 기다렸다.
3. PG 느린 쿼리 로그(`log_min_duration_statement` 50ms): CU 회차에서 `claim()` 의 첫 쿼리 `cancelResolvedBeforeSend` 가 20 번, **중앙 1,083ms · 최대 3,171ms**. TU 회차에서는 한 번도 50ms 를 넘지 않았다.
4. 같은 3000 줄 상태에서 `EXPLAIN ANALYZE`: 통계가 낡으면 outbox 자기 조인을 **중첩 루프**(바깥 3000 줄 × 안쪽 인덱스 스캔이 매번 3000 줄을 걸러 냄)로 풀어 **1,052ms**, `ANALYZE` 뒤에는 고유 인덱스(`alert_event_id, transition, alert_channel_id`)로 **0.24ms**.

즉 빈 표에 수천 줄이 한꺼번에 들어온 직후 autoanalyze(`naptime` 1분)가 언제 도느냐에 따라 회차가 갈렸다. 넣자마자 `ANALYZE` 하면 `cancelResolvedBeforeSend` 는 어느 회차에서도 느린 쿼리에 잡히지 않았고, §4 처럼 TU · CU 가 같아졌다. CU 만 계속 걸린 이유까지는 밝히지 못했다(autoanalyze 시점과 회차 시작의 겹침으로 추정, 확인 안 함).

이것은 레인과 별개의 **실제 위험**이다. 경보가 몰려 outbox 에 수천 줄이 한꺼번에 생기면, autoanalyze 가 따라올 때까지 선점 한 번이 1초 넘게 걸려 모든 채널의 발송이 느려질 수 있다. 운영 표는 비어 있다가 갑자기 차지는 않으므로 실험만큼 나쁘지 않을 수 있지만 재지 않았다(AGENTS §6).

## 6. 결정

- 코루틴 레인을 기본으로(`lane-mode=COROUTINE`), 바쁜 채널 상한 8 → **256**. 스레드 레인은 `THREAD` 로 남겨 되돌릴 수 있게 했다.
- 근거: 같은 지연에서 바쁜 채널이 늘어도 스레드가 거의 늘지 않는다(1000 채널에 97개). 상한 8 은 "스레드를 아끼려고 지연을 받아들인" 값이었는데 그 교환이 필요 없어졌다.
- 256 은 측정한 값이 아니라 고른 값이다. 지금 채널 수(손으로 만드는 Slack 채널, 많아야 수십)보다 넉넉하고, 잰 범위(CU 3000 에서 RSS +202 MB) 안에서 메모리가 문제되지 않는 쪽으로 잡았다. 바쁜 채널 하나가 소켓 하나 · 힙 몇십 KB 를 쓰므로 상한 자체는 남긴다.
- 지금 규모(채널 몇 개)에서는 두 방식의 차이가 없다(N=10 표). 이득은 느린 채널이 8개를 넘을 때 생긴다.

## 7. 확인하지 못한 범위

Notifier 1대 · 로컬 Docker(맥북, CPU 2 · 1 GB 제한) · 느린 응답 4초 고정 · 채널당 1건. 응답이 한도(5초)를 넘어 Unknown → 재시도로 쌓이는 경우, 채널마다 여러 건(레인 안 순서), Notifier 여러 대, Dispatchers.IO 를 다른 코드가 같이 쓰는 경우는 재지 않았다. N=3000 은 회차 간 편차가 커서(체크포인트 겹침) 세 회차로는 차이를 말하지 않는다. 가상 스레드(Java 21)는 프로젝트가 Java 17 이라 비교하지 않았다.

## 8. 재현

```bash
./gradlew :notifier:bootJar
docs/alert/experiments/coro_lanes_run.sh setup
docs/alert/experiments/coro_lanes_suite.sh             # N 10 · 100 · 1000 · 3000, T8 · TU · CU → coro-lanes-results/summary.md
docs/alert/experiments/coro_lanes_suite_analyzed.sh    # N 1000 · 3000 을 넣자마자 ANALYZE → coro-lanes-analyzed/summary.md
DIAG=1 OUT=$PWD/docs/alert/experiments/coro-lanes-diag docs/alert/experiments/coro_lanes_run.sh run CU 3000 9   # 0.5초 간격 덤프
python3 docs/alert/experiments/coro_lanes_stacks.py docs/alert/experiments/coro-lanes-diag/CU-n3000-r9
docs/alert/experiments/coro_lanes_run.sh teardown
```

스레드 덤프 · 전체 로그(173 MB)는 레포에 올리지 않았다. 요약(`summary.md` · `stacks.txt`)은 글로, 회차별 원본(meta · proc · slack · outbox · nmt · pg-slow)은 결과 폴더마다 `raw.tar.gz` 하나로 올렸다. `tar xzf raw.tar.gz` 로 풀면 `coro_lanes_analyze.py` 를 그대로 다시 돌릴 수 있다.
