# 발송 레인을 스레드에서 코루틴으로 (실측)

> 2026-11-03 로컬 실측 (`#271`). `#250` 의 채널별 발송 레인(바쁜 채널마다 스레드 하나, 상한 8)을 코루틴 레인으로 바꿔 같은 입력으로 비교했다.
> 결론: **같은 지연에서 스레드 · 메모리가 크게 적어서** 코루틴 레인을 기본으로 하고 상한을 8 → 256 으로 올렸다. 지연이 더 빨라서가 아니다.

## 1. 문제

`#250` 레인은 **바쁜 채널 하나 = 레인 스레드 하나**다. 스레드가 끝없이 늘지 않게 바쁜 채널 상한(`max-busy-channels`)을 8 로 묶었다.
그 결과 느린 채널이 8개를 넘으면 나머지 채널은 레인이 빌 때까지 선점되지 못한다. 느린 채널(4초 응답) 100개 뒤에 줄 선 정상 채널 알림은 **51초**, 1000개면 **504초** 뒤에 나갔다(§4). 가짜 Slack 수신 시각을 보면 요청은 **10건씩 약 5초 간격**으로 나간다(`T8-n100-r2/slack.tsv`). `pollOnce()` 는 바쁜 채널이 상한보다 적은지만 보고 한 번에 batch-size(10)줄을 선점하므로 바쁜 채널이 10개가 되고, 10 ≥ 8 이라 다음 선점을 쉬다가 4초 뒤 10개가 함께 끝나면 다음 1초 주기에 다시 10줄을 잡는다. 100 ÷ 10 × 약 5초 ≈ 50초 · 1000 ÷ 10 × 약 5초 ≈ 500초.

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
- 스택 예약과 커밋이 15 배 차이 나는 것은 JVM 이 스레드마다 `ThreadStackSize` 2,040 KB(리눅스 aarch64) 를 가상 주소로 **예약**만 하고 실제로 닿은 페이지만 물리 메모리로 **커밋**되기 때문이다(TU 1000: 스레드당 예약 약 2 MB · 커밋 약 121 KB).
- **코루틴 레인의 스레드 97개 중 레인 몫은 10개다.** 스레드 덤프를 이름으로 묶으면(넣고 3초 뒤, CU N=1000) 85개 = 쉬어도 있는 50개(톰캣 두 포트 24 · JVM · Hikari · 스케줄러 26) + 코루틴 디스패처 10 + JDK HttpClient `Worker` 24 + `SelectorManager` 1. 늘어나는 쪽은 HttpClient `Worker` 다. 응답을 처리하는 HttpClient 기본 실행기가 상한 없는 캐시드 풀이라 응답이 몰리면 늘고, N=3000 진단 회차의 가장 많은 순간에는 40개였다(스레드 레인은 같은 순간 `delivery-lane` 1,722 · `Worker` 49). `HttpClient.newBuilder().executor(...)` 로 크기를 정하면 묶을 수 있다(이번에는 안 바꿈).
- **CPU · 문맥 교환은 일관된 감소를 확인하지 못했다.** N=1000 에서 CPU 9.1 대 7.6초, 자발 전환 23.5 천 대 24.7 천. 한 건 보낼 때마다 일어나는 JDBC 왕복(채널 조회 · 결과 기록 · 커밋)이 소켓 read 에서 스레드를 재우므로, 응답 대기 한 번의 잠듦을 없앤 것만으로는 전환 수가 줄지 않았다. 코루틴이 아낀 것은 "기다리는 동안 붙잡혀 있던 스레드"이지 커널 왕복 자체가 아니다.
- JVM 은 힙 옵션 없이 컨테이너 기본값으로 떴다 : CPU 2 · 메모리 1 GB 라 **Serial GC · 최대 힙 256 MB(25%) · 시작 힙 16 MB** (`-XX:+PrintFlagsFinal`). RSS 차이를 NMT(넣고 3초 뒤)로 나누면 N=1000 에서 스레드 스택 커밋 134 대 9 MB(차이 125 MB), 힙 커밋 133 대 78 MB(차이 55 MB). RSS 최고값 차이 152 MB 의 약 80% 가 스택 커밋 차이로 설명되고 나머지는 힙이다. 힙 쪽 차이가 스레드 객체 몫인지 GC 가 힙을 줄이지 않은 몫인지는 나누지 않았다.
- 지연 차이 "1초 미만"은 회차가 고른 N ≤ 1000 에서만 말한다(N=1000 TU 2.98 · 2.79 · 2.54초, CU 2.57 · 2.76 · 2.57초). N=3000 은 체크포인트가 겹친 회차가 있어 세 번으로는 결론을 내지 않는다.

## 5. N=3000 의 지연 편차는 레인이 아니라 PostgreSQL 통계 시점 때문이었다

`ANALYZE` 없이 돌린 첫 묶음에서 N=3000 은 CU 가 정상 채널 중앙 19.4초(세 회차 8.8 · 33.8 · 19.4)로 TU 11.0초(8.0 · 11.0 · 12.2)보다 느렸다(`coro-lanes-results/summary.md`). 원인을 좇았다.

1. 느린 채널 요청이 **나간 시각**부터 늦었다(마지막 요청 CU 16 ~ 41초, TU 8 ~ 12초). 응답 대기가 아니라 선점이 느렸다.
2. 0.5초 간격 스레드 덤프 60장(`coro-lanes-diag/stacks.txt`): CU 의 스케줄러 스레드는 120 표본 중 **26 번 PG 응답 대기**, TU 는 3 번. 레인 스레드는 둘 다 대부분 `commit` 에서 기다렸다.
3. PG 느린 쿼리 로그(`log_min_duration_statement` 50ms): CU 회차에서 `claim()` 의 첫 쿼리 `cancelResolvedBeforeSend` 가 20 번, **중앙 1,083ms · 최대 3,171ms**. TU 회차에서는 한 번도 50ms 를 넘지 않았다.
4. 원인은 **통계가 찍힌 시점의 상태**였다. 실험 스크립트는 회차마다 outbox 를 `TRUNCATE` 로 비우지만 `TRUNCATE` 는 통계(`pg_stats`)를 지우지 않아, 직전 회차가 끝난 상태(전부 SENT)의 통계가 남을 수 있다. 따로 띄운 PG 17.11(autovacuum 끔)에 같은 표 · 인덱스를 만들고 PENDING 3000줄에 대해 통계 시점만 바꿔 실제 쿼리를 `EXPLAIN ANALYZE` 했다(2026-11-04, 3회 중앙값, 도구 `experiments/outbox_stats_timing.sh`, 결과 `experiments/outbox-stats-timing.txt`).

| 통계를 찍은 상태 | 그때 `pg_stats` status | `cancelResolvedBeforeSend` |
|---|---|---|
| 빈 표 | (없음) | 0.71ms |
| PENDING 3000줄 | `{PENDING}` 1.0 | 0.55ms |
| **전부 SENT → `TRUNCATE` → PENDING 3000줄** (실험 회차와 같은 순서) | `{SENT}` 1.0 | **1,057ms** |
| **SENT 이력 3000줄이 남은 채 → PENDING 3000줄 추가** (운영과 같은 모양) | `{SENT}` 1.0 | **1,060ms** |
| 바로 위에서 `ANALYZE` | `{PENDING,SENT}` 0.5 · 0.5 | 0.56ms |

통계가 "PENDING 이 거의 없다"고 말하면 플래너는 PENDING FIRING 쪽(f)을 바깥에 둔 중첩 루프를 고르고, 안쪽은 부분 인덱스 `notification_outbox_due` 로 PENDING 3000줄을 매번 훑어 RESOLVED 를 찾는다(0.35ms × 3000). 통계가 맞으면 거의 없는 RESOLVED 쪽(r)에서 출발해 0줄이면 바로 끝난다. 넣자마자 `ANALYZE` 한 회차 12개(`coro-lanes-analyzed`)에서는 이 쿼리가 느린 쿼리 로그에 한 번도 잡히지 않았고 §4 처럼 TU · CU 가 같아졌다. 첫 묶음에서 CU 회차만 연달아 걸린 이유는 회차별 `pg_stats` 를 남기지 않아 확인하지 못했다.

> 2026-11-04 바로잡음 : 11/3 판의 "EXPLAIN 1,052ms · ANALYZE 뒤 0.24ms" 와 통계 시점 표(0.65 · 0.39 · 1,057.6ms), 쿼리 후보 측정(1.4ms · 0.45ms)은 손으로 잰 것이라 원본 · 스크립트가 남지 않았다. 통계 시점 표는 위 스크립트로 다시 재서 바꿨고, 쿼리 후보 수치는 다시 재기 전까지 쓰지 않는다.

이것은 레인과 별개의 **실제 위험**이고 운영에서 그대로 생긴다(표의 넷째 줄). 평소 outbox 는 대부분 SENT 이력이라 통계가 그 상태이고, 경보가 몰려 PENDING 수천 줄이 한꺼번에 생기면 통계가 따라올 때까지 선점 한 번이 1초를 넘을 수 있다. `ANALYZE` 를 넣는 것은 실험 보정일 뿐 운영 해결책이 아니다. 고칠 방향은 위의 빠른 계획처럼 RESOLVED 대기 줄에서 출발하게 하는 것이고, 수정은 이 PR 밖에서 한다(AGENTS §6).

고칠 방향을 실행 계획 수준에서 확인했다(2026-11-04, `experiments/outbox_cancel_candidate.sh` → `outbox-cancel-candidate.txt`). 후보는 RESOLVED 대기 줄을 `MATERIALIZED` CTE 로 먼저 뽑고 그 줄에서 FIRING 짝을 찾는다(잠금 · 조건은 원래 쿼리와 같다). 같은 나쁜 통계(SENT 이력 3000 에서 `ANALYZE` → FIRING PENDING 3000 추가)에서 3회 :

| 상황 | 원래 쿼리 | 후보 | 취소된 줄 |
|---|---|---|---|
| RESOLVED 대기 0줄 | 1,066 ~ 1,155ms | 0.45 ~ 0.62ms | 0 = 0 |
| RESOLVED 대기 100줄 (짝 FIRING 100) | 1,136 ~ 1,159ms | 62 ~ 65ms | 200 = 200 |

RESOLVED 100줄일 때 62ms 가 남는 것은 후보에서도 짝 찾기가 부분 인덱스 `notification_outbox_due` 를 훑기 때문이라(계획에 `rows=3050 loops=100`) 고칠 PR 에서 더 다듬을 여지가 있다. 여러 워커가 동시에 돌 때의 잠금 경합은 보지 않았다.

## 6. 결정

- 코루틴 레인을 기본으로(`lane-mode=COROUTINE`), 바쁜 채널 상한 8 → **256**. 스레드 레인은 `THREAD` 로 남겨 되돌릴 수 있게 했다.
- 근거: 같은 지연에서 바쁜 채널이 늘어도 스레드가 거의 늘지 않는다(1000 채널에 97개). 상한 8 은 "스레드를 아끼려고 지연을 받아들인" 값이었는데 그 교환이 필요 없어졌다.
- **스레드 레인 상한만 256 으로 올려도 됐다.** 스레드 하나의 커밋 스택이 약 121 KB 라 256개면 커밋 약 31 MB(예약 약 510 MB)로 1 GB 컨테이너에서 문제가 없다. 지금 규모에서 코루틴을 고른 이유는 운영상 필요가 아니라 구조다 : 바쁜 채널 상한이 스레드 수에 묶여 있던 결합을 끊어, 상한을 올릴 때마다 스레드 · 스택 비용을 다시 따지지 않아도 되게 했다. 이 작업의 다른 성과는 §5 의 선점 쿼리 위험을 찾은 것이다.
- `limitedParallelism(8)` 은 레인의 DB 일만 묶는다. 같은 Hikari 풀을 선점 스케줄러와 시험 발송 API(톰캣 요청)도 쓰므로 동시에 잡히는 연결은 8을 넘을 수 있다. 연결 획득 대기(`hikaricp.connections.pending`)는 재지 않았다.
- 실패 처리는 두 레인이 같은 코드를 쓴다 : 연결 2초 · 응답 5초 한도 → Unknown 으로 재시도, 429 는 `Retry-After`, 재시도 간격은 지수 증가, 실패가 이어지는 채널은 서킷 브레이커, 종료 때 진행 중 호출은 한도만큼 기다린 뒤 취소하고 남은 줄은 임대가 끝나면 회수. 이 실패 상황들을 코루틴 레인으로 따로 재지는 않았다. Slack 초당 1건 제한은 웹훅(채널)마다라, 레인이 채널 안에서 차례로 보내는 한 상한 256 이 늘리는 것은 채널 사이 동시성이지 한 웹훅의 동시 요청이 아니다. Notifier 여러 대는 선점이 `SKIP LOCKED` 로 나뉘지만 "바쁜 채널" 제외는 인스턴스마다라 같은 채널을 동시에 보낼 수 있다(레인 방식과 무관한 기존 한계, `70-slow-channel.md` §8).
- 레인 스레드 8개(`limitedParallelism(8)`)가 결과 기록의 새 병목이 되는지는 상한 256 에서 따로 재지 않았다. 다만 상한 없는 N=1000 에서 전부 끝난 시각이 CU 6.57초 대 TU 6.99초로, 스레드 1,079개가 연결 10개를 다투는 쪽보다 늦지 않았다. 결과 기록의 상한은 레인 스레드보다 Hikari 연결 수다.
- 256 은 처음에 고른 값이다. 지금 채널 수(손으로 만드는 Slack 채널, 많아야 수십)보다 넉넉하고, 잰 범위(CU 3000 에서 RSS +202 MB) 안에서 메모리가 문제되지 않는 쪽으로 잡았다. 바쁜 채널 하나가 소켓 하나 · 힙 몇십 KB 를 쓰므로 상한 자체는 남긴다.
- **상한별 실측 (2026-11-04)** : 코루틴 레인 상한을 64 · 128 · 256 · 512 로 바꿔 느린 채널 1000 + 정상 1 을 3회씩(넣자마자 `ANALYZE`, `experiments/coro_lanes_suite_caps.sh` → `coro-lanes-caps/summary.md`). 중앙값 :

| 상한 | 정상 채널 도착 | 스레드 최고 | RSS 증가 | 스택 커밋 |
|---|---|---|---|---|
| 64 | 59.7초 | 108 | +75 MB | 7.6 MB |
| 128 | 30.8초 | 95 | +71 MB | 7.9 MB |
| **256 (기본)** | **14.1초** | 96 | +79 MB | 8.2 MB |
| 512 | 6.0초 | 111 | +93 MB | 9.2 MB |
| 상한 없음 (§4 CU) | 2.6초 | 97 | +106 MB | 9 MB |

  정상 알림 지연은 대략 "앞에 선 느린 채널 수 ÷ 상한 × 4초"로 상한에 반비례하고, 스레드는 상한과 무관하게 100개 안팎, RSS 증가는 64 → 512 에서 +75 → +93 MB 다. 즉 코루틴 레인에서는 상한을 올리는 비용이 메모리 수십 MB 수준이라, 상한은 스레드가 아니라 "몰린 느린 웹훅 뒤에서 얼마나 기다려도 되나"로 정하면 된다. 지금 기본값 256 이면 느린 웹훅 1000개가 한꺼번에 몰릴 때 정상 알림이 약 14초 늦는다. 회차마다 정상 알림이 1건이라 p95 · p99 는 이 측정으로 말할 수 없고, Hikari 연결 대기도 재지 않았다. 슬로 쿼리 로그(50ms)에 잡힌 쿼리는 회차마다 0 ~ 1건이었다.
- 지금 규모(채널 몇 개)에서는 두 방식의 차이가 없다(N=10 표). 이득은 느린 채널이 8개를 넘을 때 생긴다.

## 7. 확인하지 못한 범위

Notifier 1대 · 로컬 Docker(맥북, CPU 2 · 1 GB 제한) · 느린 응답 4초 고정 · 채널당 1건. 느린 채널 수천 개는 실제 장애를 모사한 것이 아니라 "바쁜 채널이 상한을 넘을 때 무엇이 늘어나는가"를 보려는 부하다. 운영에서 알림이 늦어진 사례는 아직 없다. 실제 Slack 에서 더 흔한 429(초당 1건 제한) · 재시도 누적은 재지 않았다. 응답이 한도(5초)를 넘어 Unknown → 재시도로 쌓이는 경우, 채널마다 여러 건(레인 안 순서), Notifier 여러 대, Dispatchers.IO 를 다른 코드가 같이 쓰는 경우는 재지 않았다. N=3000 은 회차 간 편차가 커서(체크포인트 겹침) 세 회차로는 차이를 말하지 않는다. 가상 스레드는 비교하지 않았다. 쓰려면 백엔드 다섯 모듈 · CI · 이미지의 JVM 을 함께 올려야 하고(지금 Java 17), 그래도 DB 연결 수 한도는 그대로이며, 21 에서는 `synchronized` 안 blocking 의 pinning 이 남아 있다(24, JEP 491 에서 해소). 코루틴은 Notifier 한 모듈 안에서 끝나고 `lane-mode` 하나로 되돌릴 수 있어 범위가 작은 쪽을 골랐다.

## 8. 재현

```bash
./gradlew :notifier:bootJar
docs/alert/experiments/coro_lanes_run.sh setup
docs/alert/experiments/coro_lanes_suite.sh             # N 10 · 100 · 1000 · 3000, T8 · TU · CU → coro-lanes-results/summary.md
docs/alert/experiments/coro_lanes_suite_analyzed.sh    # N 1000 · 3000 을 넣자마자 ANALYZE → coro-lanes-analyzed/summary.md
DIAG=1 OUT=$PWD/docs/alert/experiments/coro-lanes-diag docs/alert/experiments/coro_lanes_run.sh run CU 3000 9   # 0.5초 간격 덤프
python3 docs/alert/experiments/coro_lanes_stacks.py docs/alert/experiments/coro-lanes-diag/CU-n3000-r9
docs/alert/experiments/coro_lanes_run.sh teardown
docs/alert/experiments/outbox_stats_timing.sh 3 > docs/alert/experiments/outbox-stats-timing.txt   # §5 통계 시점 표 (Docker 만 있으면 된다)
docs/alert/experiments/outbox_cancel_candidate.sh                  # §5 쿼리 후보 비교 (Docker 만 있으면 된다)
OUT=$PWD/docs/alert/experiments/coro-lanes-caps docs/alert/experiments/coro_lanes_run.sh setup
docs/alert/experiments/coro_lanes_suite_caps.sh                    # §6 상한 64 · 128 · 256 · 512 → coro-lanes-caps/summary.md
docs/alert/experiments/coro_lanes_run.sh teardown
```

스레드 덤프 · 전체 로그(173 MB)는 레포에 올리지 않았다. 요약(`summary.md` · `stacks.txt`)은 글로, 회차별 원본(meta · proc · slack · outbox · nmt · pg-slow)은 결과 폴더마다 `raw.tar.gz` 하나로 올렸다. `tar xzf raw.tar.gz` 로 풀면 `coro_lanes_analyze.py` 를 그대로 다시 돌릴 수 있다.
