# CPU · HEAP — 파드가 CPU 를 다 쓰거나 힙이 찼을 때

> 개인 설계 · 팀 미확정. 2026-10-29 작성. 이슈 `#262`.
> 재료: 조회 `#252` `GET /api/v1/internal/metrics/cpu-heap`(API 명세 53번) · 인증 설정 `#164` `POST /api/v1/internal/thread-dumps` · `agents` 표.
> 판정 구조는 GC_TIME(`80-gc-time.md`)과 같다. 여기에는 다른 점만 적는다.

## 1. 무엇을 보나

| 규칙 `metric_kind` | 값 | 단위 | 응답 필드 |
|---|---|---|---|
| `CPU` | 그 1분 JVM 프로세스 CPU 사용률 평균 | % (0 ~ 100) | `cpu_pct` = `jvm.cpu.recent_utilization` 1분 평균 × 100 |
| `HEAP` | 그 1분 힙 사용률 | % (0 ~ 100) | `heap_pct` = heap 풀 used 합 / limit 합, 풀마다 1분 마지막 값 |

CPU · HEAP · GC 는 셋 다 **경보가 열리는 순간 그 파드의 스레드 덤프**를 뜨려고 본다. CPU 는 "무엇이 CPU 를 쓰고 있나", HEAP 은 "힙이 차는 동안 무엇을 하고 있었나" 를 덤프로 남긴다.

## 2. 판정 — GC_TIME 과 같은 것 · 다른 것

같은 것: 규칙은 서비스에 · 사건은 파드마다(`AlertTarget.Agent`), `window_sec` 안 씀(1분 버킷마다), 전역 N=3 · M=2, 조회 실패는 최근 버킷 하나만 QUERY_FAILED, DOWN 파드 · `agents` 에 아직 없는 파드는 판정 안 함, 발화 직후 자동 덤프 한 번(기록 트랜잭션 밖 · 평가 스레드 밖(`#248` `AutoDumpLauncher`), 실패해도 경보는 나감).

| 항목 | 정한 것 | 이유 |
|---|---|---|
| 조회 | 한 주기에 `cpu-heap` 을 **한 번**, 전 서비스 | 한 응답에 CPU · HEAP 이 같이 온다. 규칙 종류마다 따로 부르지 않는다 |
| 러너 | `CpuHeapRunner` 하나가 `CPU` · `HEAP` 규칙을 함께 | 지표 고르기(`cpu_pct` / `heap_pct`)만 다르다 |
| 단위 | 규칙 `threshold` 는 **%** | 응답 단위와 같게 |
| null | 그 지표가 null 인 1분 = 판정 불가(NO_DATA) | 조회가 "값이 안 온 쪽은 null" 로 준다. 예 : limit 을 안 보낸 파드는 `heap_pct` 가 null. 0% 로 치면 힙이 찬 파드를 정상으로 본다 |
| 빈 1분 | 행이 없으면 판정 불가 | 조회가 150초 안의 빈 1분은 앞 값으로 채워 준다(현재값이라 가능). 그보다 긴 공백은 전송이 실제로 빠진 것 |
| 같은 파드에 여러 경보 | 덤프는 경보마다 부른다 | API server 가 같은 파드의 동시 요청 · 쿨다운(5분)을 하나로 묶는다(`#164`) |

### 기준값 (자리값 · 실측 전)

규칙 값이라 코드에 박지 않았다. 시험 · 문서의 예시는 아래이고 **근거는 실측이 아니다.**

- `CPU GT 80` : 1분 평균이 80% 를 3분 넘게 = 여유가 없는 상태가 이어진다
- `HEAP GT 90` : 힙 사용률은 GC 직전에 높게 찍히는 톱니 모양이라 한 번 높은 것은 정상이다. "1분 마지막 값" 이 3분 연속 90% 를 넘는 것은 GC 뒤에도 내려오지 않는다는 뜻에 가깝다

쇼핑몰 부하 실측(평소 · 고부하 · 힙 축소) 뒤 조정한다. 힙 압박은 GC_TIME 이 먼저 잡을 가능성이 크다 — 두 경보가 언제 어느 쪽이 먼저 열리는지는 아직 재지 않았다.

## 3. 구현

| 파일 | 하는 일 |
|---|---|
| `detector/.../evaluate/CpuHeapClient.kt` | `CpuHeapClient` 인터페이스 + HTTP 구현. null 은 null 로 옮긴다, 200 이 아니면 예외 |
| `detector/.../evaluate/CpuHeapRunner.kt` | 규칙 · 파드 읽기 → 조회 한 번 → 파드 × 규칙 × 완료 버킷 판정 → 발화 시 덤프 |
| `EvaluationScheduler` | 서비스 단위 → AGENT_DOWN → GC_TIME → CPU · HEAP 순서로 한 주기 |

Notifier 는 안 바꿨다. 파드 단위 규칙의 `(파드 …)` 문구는 `#223` 이 이미 `agent_key` 로 붙인다.

## 4. 확인한 것 · 안 한 것

- 테스트 : `CpuHeapRunnerTest` 9건(실제 PG + 가짜 조회 · 덤프), `HttpCpuHeapClientTest` 3건(계약 · null · 500). Detector 전체 108건 통과(`#248` 과 합친 뒤)
- **실측 E2E 는 아직이다.** 쇼핑몰 → 수집 → `cpu-heap` → Detector → Slack · 덤프를 한 번 관통시켜야 한다
- `GcTimeRunner` 와 코드가 거의 같다. `#249`(덤프를 평가 스레드 밖으로)를 먼저 합쳐 `dump()` 는 둘 다 `AutoDumpLauncher` 에 맡기게 맞췄다. 두 러너를 하나로 합치는 정리는 따로 한다
