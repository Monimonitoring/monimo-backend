# 용어 대응표 (glossary)

> 문서 · 주석은 2026-10 부터 **현업에서 쓰는 영어 이름**으로 쓴다 (`#240`, 평면은 ADR `#73` 확정).
> 옛 이름은 이슈 제목 · 커밋 · 지난 기록에 남아 있다. 옛 이름으로 찾다가 여기로 오면 새 이름으로 다시 찾으면 된다.

- 스팬 · 트레이스 · 샘플링 · 롤업 · 카나리 · 스레드 덤프는 표준 용어를 소리 나는 대로 적은 것이라 그대로 쓴다
- 코드 안 문자열(테스트 이름 · 화면 메뉴와 제목 · Slack 경보 · 스크립트 출력 · API 응답 메시지 · 로그 문구 · 시드 표시 이름)도 같은 이름을 쓴다 (`#241`). 화면은 이름만 영어고 안내 문장은 한국어다
- 기계가 읽는 이름(지표 이름 · 에러 `code` · URL · 라벨 이름 · 환경변수)은 처음부터 영어라 바꾸지 않았다. 응답 메시지를 바꾸면 노션 API 명세의 문구도 같이 고친다

## 구성 요소

| 옛 이름 | 새 이름 | 뜻 | 코드 위치 | 현업에서 같은 이름 |
|---|---|---|---|---|
| 수집기 | **Collector** | 에이전트가 보낸 OTLP 를 받아 거르고(헬스체크 · 샘플링 · 로그 레벨) Kafka `raw` 에 싣는다 | `collector/` · `monimo-collector` | [OTel Collector](https://opentelemetry.io/docs/collector/architecture/) · [Pinpoint Collector](https://pinpoint-apm.gitbook.io/pinpoint/getting-started/installation) · [Jaeger collector](https://www.jaegertracing.io/docs/architecture/) |
| 적재 처리기 · 적재기 | **Ingester** | Kafka `raw` 를 읽어 ClickHouse 에 쓴다. 실패는 `raw.dlq` | `ingester/` · `monimo-ingester` | [Jaeger ingester](https://www.jaegertracing.io/docs/architecture/) (Kafka → 저장소, 같은 일) · [Loki ingester](https://grafana.com/docs/loki/latest/get-started/components/) |
| API 서버 | **API server** | 화면용 조회 · 설정 · 규칙 · 채널 · 내부 문 | `api-server/` | [Jaeger query](https://www.jaegertracing.io/docs/architecture/) 와 비슷한 자리 |
| 탐지 (모듈) | **Detector** | 주기적으로 조회해 경보 규칙을 평가한다 | `detector/` | [Splunk detector](https://docs.splunk.com/observability/alerts-detectors-notifications/alerts-detectors-notifications.html) · Prometheus alerting rules |
| 알림 (모듈) | **Notifier** | 경보를 Slack · 이메일 · 웹훅 · PagerDuty 로 보낸다 | `notifier/` | [Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/) 가 하는 일의 보내기 부분 |
| 파수꾼 | **Watchdog** | 스택 바깥(Lambda)에서 카나리를 흘려 APM 이 살아 있는지 본다 | `monimo-watchdog` | [kube-prometheus Watchdog](https://runbooks.prometheus-operator.dev/runbooks/general/watchdog/) · [meta-monitoring](https://grafana.com/docs/loki/next/operations/meta-monitoring/) · dead man's switch |
| 서버맵 | **Service map** | 서비스를 점, 호출을 선으로 그린 지도 (화면 컴포넌트 이름은 `ServerMap`) | `monimo-web` | [Datadog Service Map](https://docs.datadoghq.com/tracing/services/services_map/) · Pinpoint ServerMap |
| 인스펙터 | **Inspector** | 파드 하나의 CPU · 메모리 · GC · 스레드 | `monimo-web` | [Pinpoint Inspector](https://pinpoint-apm.gitbook.io/pinpoint/documents/new-inspector) |
| 명부 · 파드 명부 | **Agent registry** | 붙어 있는 에이전트(파드) 목록. PG `agents` 표 | `ingester/` 가 쓰고 Detector 가 상태를 바꾼다 | |
| 입구 | **Ingress** | 클러스터 밖에서 화면 · API 로 들어오는 문 (Traefik + NLB) | `monimo-deploy` | Kubernetes Ingress |
| 금고 | **Secrets Manager** · secret store | 비밀값을 두는 곳 | `monimo-deploy` | AWS Secrets Manager |

## 시스템 구분 (ADR `#73`, 옛 `#13` 의 평면)

| 옛 이름 | 새 이름 | 뜻 |
|---|---|---|
| A평면 | **Monitored app** | 감시 대상. 지금은 쇼핑몰 넷(`monimo-shop`). 우리가 만들지 않는 쪽이라는 뜻 |
| B평면 | **APM** | 우리가 만드는 것 전체 : Collector → Kafka → Ingester → ClickHouse → API server → Web, Detector → Notifier |
| B평면 자기 감시 | **Self-monitoring** | 같은 에이전트를 우리 모듈 다섯에도 붙여 APM 이 자기 자신을 본다 (`#208`) |
| C평면 | **Watchdog** | APM 바깥에서 APM 을 감시한다 |

- 「평면」 을 버린 이유 : 현업에서 plane 은 [control plane](https://kubernetes.io/docs/concepts/overview/components/) · [data plane](https://istio.io/latest/docs/ops/deployment/architecture/) 으로 굳은 말이라 그 뜻으로 잘못 읽힌다

## 데이터 · 설정

| 옛 이름 | 새 이름 | 뜻 | 코드 위치 |
|---|---|---|---|
| 꼬리표 | **Attribute** | 스팬 · 지표 · 로그에 붙는 key-value | `attributes` 컬럼 · [OTel Glossary](https://opentelemetry.io/docs/concepts/glossary/) |
| 등급 (로그) | **severity** · log level (문장 안에서는 소문자) | 로그 심각도. 번호(`severity_number`)가 정본 | `common/log/LogSeverity.kt` · ADR `#59` |
| 로그 하한 · 하한 | **Minimum log level** | 이 레벨 미만 로그는 Collector 가 Kafka 전에 버린다 | `MONIMO_COLLECTOR_LOG_MIN_LEVEL` · `collector/filter/LogLevelFilter.kt` |
| 묶음 | **System** | 샘플링 비율을 같이 쓰는 서비스 무리 (`shop` · `monimo`) | `applications.system` · ADR `#72` · [Backstage System](https://backstage.io/docs/features/software-catalog/system-model/) |
| 고아 스팬 | **orphan span** | 부모 스팬이 저장되지 않은 스팬. 현업에 정해진 이름은 없다 | |
| 관통 · 관통 확인 | **E2E 확인** (end-to-end) | 한쪽 끝에서 보낸 신호가 저장소까지 도착하는지 본다 | `scripts/check-pipeline.sh` |
| 묶음 (System 이 아닌 뜻) | 뜻마다 다른 말 | 인증서 → 「두 벌」 · Kafka · ClickHouse 로 한 번에 보내는 단위 → `batch` · DB 연결 → 「연결 풀」 · 경보 그룹핑 → 「그룹」 · 결정 번호 → 「항목 N」 · deploy 적용 순서 → 「단계」 | |
| 컬렉터 | **Collector** | 소리대로 적은 옛 표기 | |

## 일부러 안 바꾼 것

| 말 | 이유 |
|---|---|
| 스팬 · 트레이스 · 샘플링 | OpenTelemetry 표준 용어 |
| 롤업 · 카나리 · 스레드 덤프 | 현업 용어(rollup · canary · thread dump)를 소리대로 적은 것. 카나리는 [AWS CloudWatch Synthetics](https://docs.amazonaws.cn/en_us/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Canaries.html) 와 같은 뜻(합성 요청)이다 |
| 알림 파트 · 탐지 파트 (사람 파트 이름) | 모듈이 아니라 팀 안의 담당 이름이다 (`AGENTS.md` §4) |
| 알림 · 탐지 · 등급 (보통 말) | 「알림 60초」 · 「이상 탐지」 처럼 모듈이 아닌 뜻으로 쓰일 때는 그대로 둔다 |
| `db/*/V*.sql` 안의 우리 말 | Flyway 가 이미 적용한 파일이라 한 글자라도 바꾸면 checksum 이 어긋나 기동이 멈춘다 |
