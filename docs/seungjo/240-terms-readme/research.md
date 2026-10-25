# 리서치 : 용어를 현업 말로 · README 를 처음 보는 사람의 입구로

- 날짜 2026-10-26 / 관련 이슈 `#240` (멘토 피드백 A · B)
- 쓴 도구 : 레포 다섯 grep(용어 횟수 · README 절 · 화면 문구), `docs/design` 직접 확인

- **읽는 순서가 곧 쓰는 순서다**
  - 1 → 2 를 쓰고 멈춰서 승조가 3 을 묻는다. 답하고 4 를 쓴다
  - 5 를 쓰고 멈춰서 승조가 6 을 묻는다. 답하고 7 을 쓴다
  - 8 은 승조가 쓴다
- **3 · 6 · 8 은 사람 차례다**
  - 비어 있으면 아직 거기까지 온 것이다
  - 대신 채우지 않는다

---

## 1. 이번 작업은 무엇을 하는 일인가

- **지금 무엇이 잘못됐나** : 문서는 `수집기` · `적재 처리기` · `파수꾼` · `B평면` 처럼 우리끼리 만든 말로 쓰였고, backend README 는 켜는 법 설명서라서 처음 보는 사람이 무엇을 왜 만들었는지 못 읽는다
- **그래서 무엇을 만드나** : 우리 말 → 현업 용어 대응표 한 장, 그리고 그 용어로 다시 쓴 README 입구(구조 · 흐름 · 결정 · 실측 숫자)
- **안 하면 어떻게 되나** : 면접관 · 멘토가 한 일을 못 알아보고, 멘토 리뷰 요청(피드백 F)에 붙일 링크가 없다

| | |
|---|---|
| 건드리는 모듈 · 파일 | `README.md` (backend · shop · deploy), 대응표 한 장(자리는 5 에서 정한다), `AGENTS.md` §5 · §6 |
| 건드리지 않는 것 | 코드 식별자(이미 영어) · `01-decisions.md` 의 지난 ADR 본문(기록) |
| 이 이슈 범위 밖 (후속) | `monimo-web` 화면 문구 · `monimo-watchdog` README(담당 파트에 알리기만) · 멘토 피드백 C · D · E |

**조사 들어가기 전에 내가 알던 것 (세 줄)**

- 
- 
- 

**알고 싶었던 것**

- ① 우리 말마다 현업에서 실제로 쓰는 이름이 있나, 없는 것은 무엇인가
- ② 영어로 바꾸나, 한국어에 영어를 붙이나(`수집기(Collector)`), 어디까지 바꾸나
- ③ 대응표를 어디에 두나 : README 절 · `docs/design` 새 파일 · `00-index.md`
- ④ 이미 쓴 문서(AGENTS 61회 · 설계 231회)는 어디까지 고치나
- ⑤ README 입구에 무엇을 몇 줄로 두면 5분 안에 읽히나
- ⑥ 「평면」 용어를 이참에 버리나 (ADR `#13` 되돌림 조건)

## 2. 작업하기 전에 알아야 하는 것

### 2.1 이름이 둘로 갈린 자리

- **코드는 이미 영어다** : 폴더 `collector/` · `ingester/` · `detector/` · `notifier/` · `api-server/`, 서비스 이름 `monimo-collector` 등
- **문서만 우리 말이다** : README 폴더 표가 `collector/` 옆에 「수집기」, `ingester/` 옆에 「적재 처리기」를 붙였다
- 우리 말이 생긴 이유는 **읽는 사람이 팀원과 승조 자신**이었기 때문이다
  - 리서치 틀 1절이 「용어를 쓰지 않고 쓴다」를 규칙으로 둔다 (`docs/seungjo/TEMPLATE/research.md`)
  - 공부할 때는 그 규칙이 맞다. 밖에 보여 주는 README 는 읽는 사람이 다르다
- 횟수 (2026-10-26, `grep -o` 로 셈)

| 우리 말 | backend README | shop README | deploy README | backend AGENTS | `docs/design/*.md` |
|---|---|---|---|---|---|
| 수집기 | 17 | 14 | 5 | 61 | 231 |
| 적재 처리기 | 11 | 0 | 3 | 36 | 100 |
| 파수꾼 | 2 | 1 | 0 | 13 | 54 |
| 평면 (A · B · C) | 2 | 0 | 0 | 6 | 37 |
| 묶음 | 0 | 0 | 6 | 15 | 52 |
| 꼬리표 | 0 | 0 | 0 | 11 | 32 |
| 하한 | 0 | 0 | 0 | 19 | 18 |

- README 셋만 보면 바꿀 자리는 수십 군데다. AGENTS · 설계까지 가면 수백 군데라서 ④ 가 범위를 가른다

### 2.2 우리 말과 현업 이름 (AI 가 알던 것, 5 에서 출처로 확인한다)

- 「확신」 칸은 AI 가 아는 정도다. **높음도 5 에서 공식 문서로 다시 확인한다**

| 우리 말 | 코드 · 표 이름 | 현업에서 부르는 이름 | 어디서 그렇게 부르나 | 확신 |
|---|---|---|---|---|
| 수집기 | `collector` | **Collector** | OpenTelemetry Collector · Pinpoint Collector · Jaeger collector | 높음 |
| 적재 처리기 | `ingester` | **Ingester** | Jaeger ingester (Kafka 에서 읽어 저장소에 씀 : 우리와 모양이 같다) · Grafana Tempo · Loki | 높음 |
| 탐지 | `detector` | Alert rule evaluation · Detector | Prometheus 규칙 평가 · Splunk Observability 의 detector | 중간 |
| 알림 | `notifier` | **Notifier** · Alert manager | Prometheus Alertmanager | 높음 |
| API 서버 | `api-server` | Query service · API server | Jaeger query | 중간 |
| 파수꾼 (C평면) | `monimo-watchdog` | **Watchdog** · Dead man's switch · Meta-monitoring | kube-prometheus 의 늘 울리는 `Watchdog` 경보 | 중간 |
| 카나리 | canary | Synthetic transaction · Heartbeat | 합성 모니터링 | 중간 |
| A평면 | 쇼핑몰 | Monitored application · Target | (평면이라는 말은 현업에 이 뜻으로 없다 : 2.5) | 높음 |
| B평면 자기 감시 | `#208` | **Self-monitoring** · Dogfooding | | 높음 |
| 꼬리표 | `attributes` | **Attribute** (OTel) · Label (Prometheus) · Tag (Datadog) | | 높음 |
| 로그 하한 | `MONIMO_COLLECTOR_LOG_MIN_LEVEL` | **Minimum log level** · Log level filter | | 높음 |
| 등급 | `severity_number` · `level` | **Severity** · Log level | OTel 로그 데이터 모델 | 높음 |
| 묶음 | `applications.system` | 확인 필요 (Service group? Environment? Namespace?) | | 낮음 |
| 서버맵 | `ServerMap` (화면 49회) | **Service map** · ServerMap | Datadog · New Relic · AWS X-Ray 는 Service map, Pinpoint 는 ServerMap | 높음 |
| 인스펙터 | `Inspector` (화면 23회) | **Inspector** | Pinpoint 그대로 | 높음 |
| 명부 | `agents` 표 | Agent registry · Agent list | | 중간 |
| 고아 스팬 | | Orphan span · Missing parent span | Jaeger UI 는 부모 없는 스팬을 따로 표시한다 | 중간 |
| 관통 | `check-pipeline.sh` | **End-to-end(E2E) 확인** · Smoke test | | 높음 |
| 표본 · 샘플링 비율 | `sampling_rate` | Sampling rate · Head sampling | OTel | 높음 |
| 롤업 | `metrics_1m` 등 | Rollup · Downsampling | | 높음 |
| 금고 | Secrets Manager | Secret store · Vault | | 높음 |
| 입구 | Traefik + NLB | Ingress · Entry point | | 높음 |
| 준비 Job | `kafka-init` · `*-migrate` | Init job · Migration job | | 높음 |

- 이 표에서 보이는 것 셋
  - **코드 이름이 이미 현업 이름인 것이 대부분이다** : 새로 지을 필요 없이 코드 이름을 문서에 그대로 쓰면 된다
  - **현업에 짝이 없는 것은 「평면」 과 「묶음」 둘이다**
  - **「카나리」 는 현업에서 뜻이 둘이다** : 배포 쪽 카나리 배포와 감시 쪽 합성 요청. 우리는 뒤엣것이라 처음 보는 사람이 헷갈린다

### 2.3 README 셋이 지금 어떤 모양인가

- backend README 260줄의 절 (줄 수는 대략)

| 절 | 줄 | 처음 보는 사람에게 |
|---|---|---|
| 첫 다섯 줄 (한 줄 소개 · 기술 · 상태) | 5 | 무엇인지는 나오는데 **왜 · 얼마나가 없다** |
| 폴더 구성 | 10 | 있음 |
| 로컬 실행 (전체 켜기 · 인프라) | 90 | 팀원용 |
| 모듈 규칙 · 저장소 연결 · 로그인 · 테스트 | 60 | 팀원용 |
| 환경변수 · 포트 | 75 | 팀원용 |
| 관련 문서 · 기여 규칙 · AI 와 일한 방법 | 20 | 설계로 가는 링크가 **맨 아래에 한 줄** |

- 260줄 가운데 처음 보는 사람을 위한 줄은 첫 다섯 줄뿐이다. 나머지는 이미 팀에 들어온 사람의 설명서다
- **틀린 줄이 있다** (2026-10-26 확인)
  - backend 6행 「ClickHouse 적재는 아직」 : 스팬은 `#58`, 지표 · 로그는 `#64` 부터 ClickHouse 에 쌓인다
  - backend 230행 쇼핑몰 compose 가 「(예정)」 : 이미 있다 (shop README 「한 번에 켜기」)
  - backend 231행 「api-server · detector · notifier 는 컨테이너 프로필은 구현 때 추가」 : 이미 있다 (같은 README 59행 `--profile api-server ...`)
  - shop 상태 「Phase 1a 주문 · 결제 골격」 : 재고 · 게이트웨이(1b)와 오버헤드 측정까지 들어와 있다
- **보여 줄 거리는 이미 레포 안에 있다** : README 에서 가는 길이 없을 뿐이다

| 거리 | 어디에 |
|---|---|
| 결정 기록 72개 (채택 · 기각 · 사유 · 되돌림) | `docs/design/01-decisions.md` |
| 고장 나면 어떻게 되나 | `docs/design/30-failure-modes.md` |
| 에이전트 오버헤드 실측 : CPU 1코어 대비 +1.4 ~ +2.1%p · 메모리 +80 ~ +141MB | shop `docs/seungjo/36-agent-overhead/` · ADR `#56` |
| 수집 지연 실측 : 중앙 2.65초 · p95 5.1초 | backend `AGENTS.md` §6 |
| AI 와 일한 방식 · AI 가 틀린 것 | `docs/seungjo/harness.md` · 이슈 폴더 22개 |
| 로컬 쿠버네티스 · ArgoCD 로 한 번에 배포 (약 40초) | deploy `docs/seungjo/20-local-argocd/` |

### 2.4 데이터는 어디로 흐르나 (README 입구에 들어갈 그림의 재료)

```
쇼핑몰 4개 (gateway · order · payment · inventory)
  └ OTel Java Agent ──OTLP gRPC 4317 (mTLS)──▶ Collector
                                                 │ 헬스체크 거르기 · 샘플링 · 로그 레벨 거르기
                                                 ▼
                                               Kafka `raw` ──실패──▶ `raw.dlq` (30일)
                                                 ▼
                                               Ingester ──▶ ClickHouse (spans · metrics_raw · logs → 1분 롤업)
                                                                 │
                    Web ◀── API server ◀───────────────────────┘
                                 ▲
                    Detector (규칙 평가) ──▶ Notifier (Slack · 이메일 · 웹훅 · PagerDuty)

Watchdog (스택 바깥, Lambda) : 합성 요청을 흘려 끝까지 도착했는지 본다
Self-monitoring : 같은 에이전트를 우리 모듈 다섯에도 붙인다
```

| 질문 | 답 |
|---|---|
| 실패한 데이터는 어디에 쌓이나 | Kafka `raw.dlq` (`#96`) |
| 얼마나 보관하나 | `raw` 7일 · `raw.dlq` 30일 |
| 누가 꺼내나 | 다시 넣는 절차는 `docs/kafka-reprocess/` |

- 그림 속 이름을 전부 영어로 쓴 것은 2.2 표의 「현업 이름」 칸을 그대로 넣어 본 것이다. 확정이 아니다

### 2.5 자주 헷갈리는 것

| 헷갈리는 것 | 실제 |
|---|---|
| 「현업 용어로」 = 전부 영어로 | 아니다. 「스팬」 · 「트레이스」 · 「샘플링」 은 한국 현업도 그대로 쓰는 말이다. **바꿀 것은 우리가 지은 말**이다 |
| 스팬도 조어라 바꿔야 한다 | 아니다. OpenTelemetry 표준 용어(span)다 |
| 「평면」 은 control plane · data plane 의 그 평면이다 | 아니다. 쿠버네티스 · 서비스 메시에서 평면은 「제어하는 쪽 / 데이터가 지나는 쪽」 이다. 우리 A · B · C 평면은 「감시 대상 / APM / 바깥 감시」 라는 다른 뜻이라 **현업 사람일수록 잘못 읽는다** |
| 카나리 = 카나리 배포 | 우리는 배포가 아니라 **스택을 끝까지 지나가는 가짜 요청**이다 (FR-8) |
| ADR 본문도 다 고쳐야 한다 | 그때의 기록이다. 다시 쓰면 그 결정을 내린 맥락이 흐려진다. 대응표에서 이어 주는 방법이 있다 (④) |
| README 를 길게 쓰면 잘 보인다 | 첫 화면에서 안 보이면 읽히지 않는다. 지금도 링크가 260줄의 맨 아래에 있어서 못 찾는 것이다 |
| 서버맵 · 서비스맵 둘 중 하나가 틀렸다 | 둘 다 쓰인다. Pinpoint 는 ServerMap, 대부분의 APM 은 Service map. 멘토는 「서비스맵」 이라고 불렀다 |

---

## 3. 1차 질문 (사람 차례)

- 승조가 2 를 읽고 모르는 것을 여기에 적는다
  - 답은 바로 아래에 붙인다
- **비어 있으면 아직 안 물은 것이다. 대신 채우지 않는다**

- **Q. 2.1 ~ 2.5 를 각각 간단히 요약하면?** (2026-10-26)
  - A. 2.1 : 코드는 이미 영어고 문서만 우리 말이다. README 셋만 고치면 수십 곳, AGENTS · 설계까지면 수백 곳
  - A. 2.2 : 대부분은 코드 이름이 곧 현업 이름이다. 짝이 없는 것은 「평면」 · 「묶음」 둘, 「카나리」 는 뜻이 둘이라 헷갈린다
  - A. 2.3 : backend README 260줄 중 처음 보는 사람용은 첫 다섯 줄뿐이고 틀린 줄이 넷이다. 보여 줄 거리는 있는데 가는 길이 없다
  - A. 2.4 : 입구 그림의 재료 : 에이전트 → Collector → Kafka → Ingester → ClickHouse → API server → Web, Detector → Notifier, 바깥의 Watchdog
  - A. 2.5 : 바꿀 것은 우리가 지은 말뿐이다. 스팬 · 트레이스는 그대로, 「평면」 은 현업 사람이 더 잘못 읽고, ADR 본문은 기록이라 안 고친다
- 승조 : 「이해했다 다음으로 넘어가자」 : 더 묻지 않았다

---

## 4. 1차 정리

- 지금은 2 까지 읽고 요약 질문 하나를 한 상태의 정리다

- 확실한 것 :
  - 고칠 대상은 **우리가 지은 말**이다. 코드 이름 · OTel 표준 용어는 그대로 둔다
  - 코드 이름 대부분이 현업 이름과 같아서 새로 지을 말은 거의 없다
  - README 에는 사실과 다른 줄이 넷 있고, 이건 용어와 상관없이 고쳐야 한다
  - 처음 보는 사람이 볼 거리(결정 · 실측 · 배포)는 레포 안에 이미 있다
- 아직 모르는 것 :
  - 2.2 의 현업 이름이 공식 문서에서도 그런가 (5 에서 확인)
  - ② 바꾸는 방식 · ③ 대응표 자리 · ④ 고치는 범위 · ⑤ README 입구의 모양 · ⑥ 「평면」 을 버릴지

---

## 5. 선택지와 설명

### 조사 프롬프트

- 원문은 같은 폴더의 [`prompts.md`](prompts.md) 「5절 조사 프롬프트」 (웹 조사 서브에이전트 1개)

### 2.2 표를 공식 문서로 다시 본 결과 (①)

| 우리 말 | 현업 이름 | 근거 | 결과 |
|---|---|---|---|
| 수집기 | **Collector** | [OTel Collector](https://opentelemetry.io/docs/collector/architecture/) · [Pinpoint](https://pinpoint-apm.gitbook.io/pinpoint/getting-started/installation) · [Jaeger](https://www.jaegertracing.io/docs/architecture/) | 확인 |
| 적재 처리기 | **Ingester** | [Jaeger](https://www.jaegertracing.io/docs/architecture/) : 「Ingests spans from Kafka and writes them into a storage backend」 · [Loki](https://grafana.com/docs/loki/latest/get-started/components/) | 확인. **Jaeger ingester 는 우리와 하는 일이 똑같다** |
| 탐지 | Detector · Alerting rules · Monitor | [Splunk detector](https://docs.splunk.com/observability/alerts-detectors-notifications/alerts-detectors-notifications.html) · [Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/alerting_rules/) · Datadog monitor | 확인. 이름은 제품마다 다르고 Detector 도 쓰인다 |
| 알림 | Notifier · **Alertmanager** | [Prometheus Alertmanager](https://prometheus.io/docs/alerting/latest/alertmanager/) | Notifier 는 공식 컴포넌트 이름으로는 못 찾았다. 일반 영어로는 통한다 |
| 파수꾼 | **Watchdog** · Dead man's switch · **Meta-monitoring** | [Watchdog runbook](https://runbooks.prometheus-operator.dev/runbooks/general/watchdog/) · [Loki meta-monitoring](https://grafana.com/docs/loki/next/operations/meta-monitoring/) | 확인 |
| 카나리 | **Canary** (합성 감시 뜻) · Synthetic monitoring | [AWS CloudWatch Synthetics](https://docs.amazonaws.cn/en_us/AmazonCloudWatch/latest/monitoring/CloudWatch_Synthetics_Canaries.html) 가 일정 스크립트를 canaries 라 부른다 · [Datadog Synthetics](https://docs.datadoghq.com/getting_started/synthetics/) | 확인. **우리 쓰임이 현업에도 있다** (2.5 의 걱정이 절반만 맞았다) |
| B평면 자기 감시 | Self-monitoring · Stack Monitoring · Internal telemetry | [Elastic](https://www.elastic.co/docs/deploy-manage/monitor) · [OTel Collector](https://opentelemetry.io/docs/collector/internal-telemetry/) | 확인 |
| 꼬리표 | **Attribute** (OTel) · Label · Tag | [OTel Glossary](https://opentelemetry.io/docs/concepts/glossary/) | 확인. 우리는 OTel 을 받으니 Attribute |
| 등급 · 로그 하한 | **Severity** · Minimum log level | [OTel Logs Data Model](https://opentelemetry.io/docs/specs/otel/logs/data-model/) | Severity 는 확인. 「min level」 이라는 정식 옵션 이름은 OTel 에 없고 조건식으로 거른다 |
| 서버맵 | **Service map** · ServerMap · Trace map | [Datadog](https://docs.datadoghq.com/tracing/services/services_map/) · [New Relic](https://docs.newrelic.com/docs/new-relic-solutions/new-relic-one/ui-data/service-maps/service-maps/) · [Pinpoint](https://github.com/pinpoint-apm/pinpoint) | 확인 |
| 인스펙터 | **Inspector** | [Pinpoint](https://pinpoint-apm.gitbook.io/pinpoint/documents/new-inspector) | 확인 |
| 고아 스팬 | (정해진 이름 없음) | Grafana 「root span not yet received」 · Jaeger 「invalid parent span IDs」 | **표준 용어가 없다.** 「Orphan span」 은 풀어 쓰는 말로만 쓴다 |
| 롤업 | **Rollup** · Downsampling · Recording rules | [Datadog rollup](https://docs.datadoghq.com/dashboards/functions/rollup/) · [Prometheus](https://prometheus.io/docs/prometheus/latest/configuration/recording_rules/) | 확인 |
| 평면 | (짝 없음) | [Kubernetes](https://kubernetes.io/docs/concepts/overview/components/) · [Istio](https://istio.io/latest/docs/ops/deployment/architecture/) 는 control plane · data plane 을 정해진 짝으로 쓴다 | 확인. **우리 A · B · C 평면과 뜻이 부딪힌다** |
| 묶음 | **System** | [Backstage System](https://backstage.io/docs/features/software-catalog/system-model/) · [Datadog Software Catalog](https://docs.datadoghq.com/internal_developer_portal/catalog/entity_model/) | 확인. **우리 표 이름(`applications.system`)이 이미 현업 이름이다** |

- 결론 : 2.2 의 「짝이 없는 것 둘」 중 **「묶음」 은 짝이 있었다(System)**. 진짜로 짝이 없는 것은 「평면」 하나와, 표준 이름이 없는 「고아 스팬」 이다

### 나온 선택지

#### ② 어떻게 바꾸나

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 영어 이름만** (`Collector`) | 코드 · 화면 · 로그와 같은 이름 하나. 면접관이 바로 안다 | 한국어 문장 사이에 영어가 많아진다 | README 셋 기준 수십 곳 |
| B. 처음 한 번 병기 뒤 영어 (`Collector(수집기)` → 그다음부터 `Collector`) | 팀원이 익숙한 말에서 넘어오기 쉽다 | 「처음」 이 문서마다 달라 규칙이 흐려진다 | A 와 비슷 |
| C. 우리 말 유지 + 대응표만 추가 | 고칠 곳이 거의 없다 | **멘토 피드백을 안 받은 것과 같다.** 읽는 사람이 표를 오가야 한다 | 표 하나 |

- A 와 B 는 크기가 같다. 차이는 「팀원이 우리 말로 기억하는 것」을 얼마나 챙기나다
- C 는 피드백의 핵심(읽는 사람이 바꿔 읽어야 한다)을 그대로 남긴다

#### ③ 대응표를 어디에 두나

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| A. backend README 안 「용어」 절 | 첫 화면에서 바로 보인다 | README 가 길어진다. 다른 레포는 링크로 가야 한다 | 작다 |
| **B. `docs/design/glossary.md` 새 파일 + README 셋 · `00-index.md` 에서 링크** | 한 곳에서 고치면 된다. 지난 ADR 의 우리 말도 여기서 풀린다 | 한 번 더 눌러야 한다 | 작다 |
| C. `00-index.md` 머리에 | 설계 문서 입구와 붙는다 | 그 파일은 AI 가 읽는 요약(stage · next) 자리라 사람이 읽기 어렵다 | 작다 |

#### ④ 어디까지 고치나

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. README 셋 + GitHub 레포 소개(About) 다섯 줄** | 밖에서 처음 보는 자리를 다 덮는다 | AGENTS · 설계에는 우리 말이 남는다 | 수십 곳 |
| B. A + AGENTS 두 곳(backend · shop) | 팀원 · AI 가 읽는 규칙도 같은 말이 된다 | AGENTS 는 61 + 회, 한 번에 고치면 리뷰가 어렵다 | 백 곳 안팎 |
| C. 전부 (설계 문서까지) | 이름이 하나로 통일된다 | 231 + 회. ADR 은 기록이라 고치면 그때의 맥락이 흐려진다 | 수백 곳 |
| D. A + 「앞으로 새로 쓰는 글은 현업 이름」 규칙을 AGENTS 에 한 줄 | 지금은 작게, 앞으로는 같은 말로 | 옛 글과 새 글이 한동안 섞인다 | A + 한 줄 |

- **GitHub 레포 소개도 우리 말이고 틀린 것도 있다** (아래 「우리 데이터로 확인한 것」)
  - 레포 목록 · 검색에서 가장 먼저 보이는 줄이라 README 보다 먼저 읽힌다

#### ⑤ README 입구 모양

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 지금 README 위에 입구 절을 얹고 팀원용은 아래로** | 링크 · 북마크가 안 깨진다. 한 파일 | README 가 더 길어진다 (지금 260줄) | 중간 |
| B. README 는 입구만, 켜는 법 · 환경변수 · 포트는 `docs/dev-guide.md` 로 | 첫 화면이 짧고 깔끔하다 | 팀원이 쓰던 자리가 옮겨진다. 다른 문서의 README 절 링크를 고쳐야 한다 | 크다 |
| C. 지금 README 는 두고 틀린 줄만 고침 | 가장 작다 | 피드백 B 를 안 받은 것과 같다 | 작다 |

- 입구에 넣을 것 (A · B 공통, 다른 프로젝트 첫 화면에서 가져옴)
  - 한 줄 소개 : 무엇을 · 누구를 위해
  - 구조 그림 하나 (2.4)
  - 숫자 : 오버헤드 · 수집 지연 · 결정 72개
  - 핵심 결정 몇 개와 링크
  - 바로 켜 보는 길 (전체 켜기로 가는 링크)

#### ⑥ 「평면」 을 버리나

| 방법 | 얻는 것 | 포기하는 것 | 구현 크기 |
|---|---|---|---|
| **A. 밖에 보이는 글에서 버린다** : 감시 대상(Monitored app) · APM · Watchdog(Meta-monitoring) · Self-monitoring 으로 | control plane · data plane 과 부딪히지 않는다. ADR `#13` 되돌림 조건(「이해 못 하면 폐기」)에 맞다 | 팀이 쓰던 말과 이슈 제목(`B평면 #208`)이 남는다 | README 몇 곳 + 대응표 |
| B. 그대로 두고 대응표에서 설명 | 고칠 곳이 없다 | 현업 사람일수록 잘못 읽는다 | 표 한 줄 |
| C. ADR 을 새로 써서 팀 전체에서 버린다 | 이름이 완전히 하나가 된다 | 팀 합의가 필요하고 이 이슈 범위를 넘는다 | 크다 |

- **결정을 가르는 축**

| 결정 | 읽는 사람이 바로 아나 | 고칠 곳 수 | 지난 기록이 그대로인가 | 팀원이 따라오나 |
|---|---|---|---|---|
| ② A 영어만 | 예 | 수십 | 예 | 바뀐다 |
| ② B 병기 | 예 | 수십 | 예 | 쉽다 |
| ③ B glossary | 한 번 더 누른다 | 1 | 예 | 쉽다 |
| ④ A · D | 밖은 예 | 수십 | 예 | 섞인다 |
| ④ C 전부 | 예 | 수백 | **아니오** | 바뀐다 |
| ⑤ A 얹기 | 예 | 중간 | 예 | 그대로 |
| ⑤ B 나누기 | 예 | 큼 | 예 | 자리가 옮겨진다 |
| ⑥ A 밖에서만 | 예 | 적음 | 예 | 섞인다 |

### 우리 데이터로 확인한 것

| 확인 | 값 |
|---|---|
| GitHub 레포 소개(About) 다섯 줄 | 다섯 다 우리 말(`수집기·적재 처리기` · `파수꾼 카나리` 등). **deploy 는 「Helm 차트 · 환경별 values」 라고 적혀 있는데 우리는 Helm 을 안 쓴다** (plan.md) : 틀렸다 |
| 화면(monimo-web)이 쓰는 이름 | `ServerMap` 49회 · `서버맵` 26회 · `Inspector` 23회 · `인스펙터` 12회 : 화면은 이미 Pinpoint 이름을 쓴다 |
| 조직 소개 레포(`.github`) | 없다. 조직 첫 화면에 소개가 없다 |
| 묶음의 표 이름 | `applications.system` (주석 「서비스가 속한 시스템」) : 이미 System |

### 남이 어떻게 하나 (README 첫 화면)

| 프로젝트 | 첫 화면 | 출처 |
|---|---|---|
| SigNoz | 배지 → 한 문단 소개 → 설치 길 → 기능별 스크린샷 → 경쟁 제품 비교 | [README](https://github.com/SigNoz/signoz) |
| Jaeger | 배지 → 「a Distributed Tracing System」 → docker 한 줄 → 구조 그림(Mermaid) | [README](https://github.com/jaegertracing/jaeger) |
| Grafana Tempo | 배지 → 한 줄 소개 → 쓰는 이유 → 스크린샷 · 기능 → 시작하기 | [README](https://github.com/grafana/tempo) |
| Pinpoint | 배지 → 릴리스 · 데모 → 소개 → 기능 스크린샷(ServerMap · Inspector) | [README](https://github.com/pinpoint-apm/pinpoint) |

- 공통 : **한 줄 소개 → 바로 해 보는 길 → 그림 · 스크린샷**. 켜는 법 세부 · 환경변수는 첫 화면에 없다
- 우리와 다른 점 : 이들은 제품 README 라 「왜 이렇게 만들었나」 가 없다. 우리는 포트폴리오라 **결정 · 실측이 차별점**이 된다 (이 줄은 AI 의 해석)

### AI 가 틀렸거나 덜 맞았던 것 (남겨 둔다)

- 2.2 에서 「묶음」 을 「짝 없음 · 확신 낮음」 으로 적었다 : **현업에 System 이라는 같은 개념이 있고 우리 표 이름도 이미 그렇다**
- 2.2 · 2.5 에서 「카나리 = 보통 배포로 읽힌다」 로 적었다 : **AWS CloudWatch Synthetics 가 우리와 같은 뜻으로 canary 를 쓴다.** 두 뜻이 다 현업 말이다
- 2.2 에서 Tempo 를 ingester 예로 들었다 : **Tempo 3.0 은 ingester 를 없앴다.** 예로 들려면 Jaeger · Loki 로 든다
- 2.2 에서 「Jaeger UI 는 부모 없는 스팬을 따로 표시한다」 고 적었다 : 공식 문서로는 확인 못 했다. 고아 스팬은 표준 이름이 없다
- 2.2 에서 Notifier 를 「확신 높음」 으로 적었다 : Prometheus 의 공식 이름은 Alertmanager 다. Notifier 는 일반 영어로 통할 뿐이다

### 라이브러리 · 레포를 직접 열어 확인한 것

| 확인 | 결과 |
|---|---|
| `gh repo list Monimonitoring` | 레포 다섯의 소개 줄 (위 표) |
| `db/postgres/config/V202610151408__alter_applications_add_system.sql` | `applications.system` 주석 |
| `monimo-web/src` grep | 화면 이름 횟수 (위 표) |

### 확인 못 한 것

- Healthchecks.io 공식 문서는 직접 못 열고 미러로만 봤다 (파수꾼 README 를 고칠 때 다시 본다)
- 고아 스팬의 현업 표기 : 제품마다 문구가 달라 하나로 못 정했다
- 면접관 · 멘토가 README 에서 실제로 무엇을 먼저 보는지 : 남의 README 모양만 봤고, 읽는 사람 쪽 근거는 없다. 멘토 리뷰(피드백 F)에서 확인할 수 있다

---

## 6. 2차 질문 (사람 차례)

---

## 7. 2차 정리

---

## 8. 구현 프롬프트 (사람 차례)
