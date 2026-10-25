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

- **개정 (2026-10-26, 6절)** : 승조가 ④ 를 「전체 범위」 로 골라 위 표의 「건드리지 않는 것 · 범위 밖」 이 바뀐다. 무엇까지 넣을지는 7절 「무엇을 정해야 하나」 와 8절 결정을 따른다. 처음 쓴 표는 지우지 않고 남긴다

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

- 승조가 5 를 읽고 남긴 것 (2026-10-26, 원문은 `prompts.md`)
  - ② 「영어 이름만 사용하기」
  - ③ 「대응표는 B 방식으로」
  - ④ 「수정 범위는 전체 범위로 하는 것이 좋을 거 같다. A 로 진행하면 나중에 또 수정이 안 되었다고 피드백이 올 수 있기 때문에」

- **Q. ⑤ README 입구 모양은 무슨 말이냐?**
  - A. 처음 보는 사람이 README 를 열었을 때 **맨 위 한 화면에 무엇을 둘지**다. 지금은 맨 위가 「켜는 법」 이라서 무엇을 왜 만들었는지가 안 보인다
  - A. 선택지는 그 한 화면을 **어디에 만드나**다
    - A : 지금 README 맨 위에 소개 · 그림 · 숫자 · 결정을 새로 얹고, 켜는 법 · 환경변수 · 포트는 그 아래에 그대로 둔다 (파일 하나, 길어진다)
    - B : README 에는 소개 · 그림 · 숫자 · 결정만 남기고, 켜는 법 · 환경변수 · 포트는 `docs/dev-guide.md` 같은 새 파일로 옮긴다 (짧아지지만 팀원이 보던 자리가 바뀐다)
    - C : 지금 README 를 두고 틀린 줄 넷만 고친다

- **Q. ⑥ 은 무슨 말이냐?**
  - A. 우리 문서는 시스템을 세 덩어리로 나눠 **A평면 · B평면 · C평면** 이라고 부른다 (ADR `#13`)
    - A평면 = 감시 대상 쇼핑몰 · B평면 = 우리 APM(수집 → 저장 → 화면 · 경보) · C평면 = 바깥에서 APM 을 감시하는 파수꾼
    - 그리고 「B평면 자기 감시(`#208`)」 처럼 이슈 제목에도 쓴다
  - A. 문제는 현업에서 「평면(plane)」 이 **이미 다른 뜻으로 굳은 말**이라는 것이다
    - 쿠버네티스 · 서비스 메시에서 control plane 은 「설정 · 관리하는 쪽」, data plane 은 「실제 요청이 지나가는 쪽」 이다
    - 그래서 면접관이 「B평면」 을 보면 그 뜻으로 읽으려다 막힌다
  - A. 선택지는 이 말을 **버릴지, 어디까지 버릴지**다
    - A : 밖에 보이는 글(README · 대응표 · 레포 소개)에서는 「Monitored app · APM · Watchdog」 으로 바꾸고 팀 안에서는 그대로 둔다
    - B : 그대로 두고 대응표에서 뜻만 설명한다
    - C : ADR 을 새로 써서 팀 전체가 이 말을 그만 쓴다
  - A. ④ 를 「전체」 로 고르면 ⑥ 은 사실상 C 쪽이 된다 : 문서 전체에서 평면을 바꾸면 팀 안에서도 바뀌기 때문이다

- 승조가 답을 듣고 고른 것 (2026-10-26)
  - ⑤ 「5번은 A방법」
  - ⑥ 「6번은 C로 다시 작성하는 방향으로」

- AI 가 덧붙인 것 : ④ 「전체」 의 크기 (2026-10-26, `grep -o` 로 셈, 다섯 말 `수집기 · 적재 처리기 · 파수꾼 · [ABC]평면 · 꼬리표` 만)

| 자리 | 횟수 |
|---|---|
| backend `docs/design` | 626 |
| backend `docs/seungjo` | 1,091 |
| backend `AGENTS.md` · `README.md` | 126 · 32 |
| backend `docs/alert` · `docs/kafka-reprocess` | 9 · 27 |
| backend 코드 주석(`*.kt`) | 258 |
| monimo-shop · monimo-deploy | 253 · 82 |
| monimo-web · monimo-watchdog | 66 · 32 |
| 합 | 약 2,600 |

---

## 7. 2차 정리

- 선택지를 다 본 뒤의 상태다
- **아직 결정이 아니다**
  - 결정은 8 에서 승조가 내린다

- 좁혀진 선택지 (6 에서 승조가 고른 것)
  - ② **영어 이름만** : `Collector` · `Ingester` · `Detector` · `Notifier` · `Watchdog` · `Attribute` · `Severity` · `Service map` · `System`. 스팬 · 트레이스 · 샘플링은 그대로
  - ③ **`docs/design/glossary.md`** 한 장 + README 셋 · `00-index.md` 에서 링크
  - ④ **전체 범위** : README · 레포 소개 · AGENTS · 설계 문서 · 이슈 폴더까지
  - ⑤ **지금 README 위에 입구를 얹는다** : 소개 · 구조 그림 · 숫자 · 핵심 결정 · 켜 보는 길, 그 아래에 지금 내용
  - ⑥ **ADR 을 새로 써서 「평면」 을 팀 전체에서 그만 쓴다** : 새 번호 `#73`, ADR `#13` 을 「`#73` 으로 대체」 로 표시

- 결정을 가르는 기준
  - **읽는 사람이 바꿔 읽지 않아도 되나** : ② · ④ · ⑥ 을 고른 이유다 (승조 : 「A 로 하면 나중에 또 수정이 안 됐다고 피드백이 올 수 있다」)
  - **기록이 바뀌지 않나** : 전체 범위에서 이 기준과 부딪히는 자리가 생긴다 (아래 ①)
  - **리뷰할 수 있는 크기인가** : 약 2,600 곳이라 한 PR 로는 못 본다 (아래 ④)

- 무엇을 정해야 하나 (목록) : 「전체」 안에서 아직 안 정한 넷과 ⑥ 의 둘
  - ① **원문 인용** : `prompts.md` 와 ADR 안의 결정 프롬프트 원문 · 노션에서 옮긴 원문은 「원문 그대로」 가 규칙이다
    - 바꾸면 승조가 실제로 쓴 말이 사라진다
    - 선택지 : 인용은 그대로 두고 그 밖만 바꾼다 / 인용까지 바꾼다
  - ② **남의 파트 문서** : `docs/alert`(ukong) · `monimo-web` · `monimo-watchdog`(재범) · AGENTS 의 다른 파트 줄
    - 선택지 : 승조가 고친다 / 담당에게 대응표만 넘기고 각자 고친다 / 승조가 고치고 담당이 리뷰한다
  - ③ **코드 주석 258 곳** : 문서는 아니지만 면접관이 코드를 열면 보인다
    - 선택지 : 이번에 같이 / 후속 이슈로
  - ④ **나눠 올리기** : 선택지 : 레포마다 PR 하나 / backend 는 README · 설계 · 이슈 폴더 · 주석으로 더 나눔
  - ⑤ **ADR `#73` 의 팀 합의** : 평면을 팀 전체에서 그만 쓰는 것이라 ADR `#13` 처럼 팀이 쓰는 말을 바꾼다
    - 선택지 : 승조가 확정하고 공지 / 팀에 먼저 묻고 확정
  - ⑥ **평면 세 개의 새 이름** : 예 : A = `Monitored app`(감시 대상) · B = `APM`(우리가 만드는 것) · C = `Watchdog`(바깥 감시), 「B평면 자기 감시」 = `Self-monitoring`
  - 이슈 제목 · PR 제목 · 커밋 메시지는 GitHub 기록이라 바꾸지 않는다 (대응표에 「옛 이름」 칸을 두면 검색이 이어진다)

- 승조 답 (2026-10-26, 원문 그대로)
  - ① 「1번은 그대로 두되 사용했던 용어만 영어로 수정하자」
  - ② 「남의 파트도 그대로 두되 사용한 용어만 영어로 수정하자」
  - ③ 「코드 주석부분도 같이 진행하자」
  - ④ 「진행은 레포별로 진행하자」
  - ⑤ 「내가 확정하고 공지하는 방향으로」
  - ⑥ 「편면의 새 이름들은 예를 들어준 대로 진행해줘」
- 답을 받고 AI 가 더 센 것 : backend `*.kt` 에서 다섯 말이 나오는 줄 239 중 **주석이 170 줄, 문자열이 69 줄**이다
  - 문자열에는 테스트 이름(`When("수집기 한 대만 ...")`)과 **API 응답 메시지**(`"이 수집기에 연결된 에이전트가 아닙니다."`)가 섞여 있다
  - 응답 메시지는 API 명세(노션 정본)에 적힌 문구라 주석과 성격이 다르다 : 8 에서 같이 정한다

### 여기서 나온 면접 질문

1. 왜 문서에 우리말 조어를 썼다가 영어 이름으로 바꿨나 : 누구를 위한 문서였고 무엇이 바뀌었나
2. 「Ingester」 라는 이름은 어디서 가져왔고, Jaeger ingester 와 우리 것은 무엇이 같고 무엇이 다른가
3. 「B평면」 이라는 말을 왜 버렸나 : control plane · data plane 과 무엇이 부딪혔나
4. 수천 곳의 이름을 바꿀 때 기록(ADR · 프롬프트 원문)은 왜 그대로 두었나 (또는 왜 바꿨나)
5. README 첫 화면에 무엇을 두었고, 왜 켜는 법보다 구조 · 숫자 · 결정을 먼저 두었나

---

## 8. 구현 프롬프트 (사람 차례)

- **이번 8 은 AI 가 승조의 선택을 문장으로 옮긴 초안을 승조가 확정한 것이다** (2026-10-26, `docs/seungjo/9-eks-foundation` 과 같은 방식)
  - 승조가 고른 것 : 6절 ② ~ ⑥ · 7절 답 여섯 · 바꾸는 방법(조어는 일괄 치환, 셋은 문맥) · 범위(문서 + 주석, 코드 안 문자열은 후속 이슈) · 이슈 나누기
  - 승조 확정 원문 : 「정해준대로 진행하자 8절을 우리가 정한대로 스크립트를 수정해줘 먼저」 (`prompts.md`)
  - AI 가 붙인 것 : 원문 파일 머리 한 줄 · 담당 리뷰를 머지 조건으로 안 거는 것 · 공지 먼저 머지 나중 · About 바꾸는 때 · 되돌림 조건

```
구현

용어는 영어 이름만 쓴다
- Collector · Ingester · Detector · Notifier · API server · Watchdog · Attribute · Severity · Service map · Inspector · System
- 스팬 · 트레이스 · 샘플링은 OTel 표준이라 그대로 둔다
- 한국어 병기는 안 한다 : 처음만 병기하면 문서마다 「처음」 이 달라 규칙이 흐려진다
- 우리 말 유지 + 대응표만 두는 것은 제외 : 읽는 사람이 계속 바꿔 읽어야 해서 피드백을 안 받은 것과 같다

대응표는 docs/design/glossary.md 한 장에 둔다
- 칸은 옛 이름 · 새 이름 · 뜻 · 코드 위치
- README 셋과 00-index.md 에서 링크한다
- 옛 이름 칸이 있어야 이슈 제목 · 커밋에 남은 옛 이름으로도 찾을 수 있다

범위는 전체로 한다 : 일부만 고치면 나중에 또 안 고쳐졌다는 피드백이 온다
- README · 레포 소개(About) · AGENTS · 설계 문서 · 레포마다 docs/seungjo · 코드 주석까지
- 바꿀 말 : 수집기 · 적재 처리기 · 파수꾼 · 평면 · 꼬리표 · 하한 · 묶음 · 서버맵 · 인스펙터 · 관통 · 명부
- 탐지 · 알림 · 등급 · 입구 · 금고 는 모듈이나 그 물건을 가리킬 때만 바꾼다 : 「알림 60초」 처럼 보통 말로도 쓰여서 통째로 바꾸면 뜻이 틀어진다
- 프롬프트 원문 · ADR 안의 인용은 문장은 그대로 두고 용어만 영어로 바꾼다
  - 바꾼 파일 머리에 「용어만 glossary 기준으로 바꿈 (#240)」 한 줄을 남긴다 : 원문 규칙을 깼다는 것을 숨기지 않기 위해서
- 남의 파트 문서(alert · web · watchdog)도 내용은 그대로 두고 용어만 바꾼다
  - 담당에게 PR 리뷰를 요청하되 머지 조건으로 걸지는 않는다
- 이번에는 문서와 코드 주석만 바꾼다
- 테스트 이름 · 화면 문구 · Slack 경보 문구 · 스크립트 단계 제목은 후속 이슈에서 레포별 PR 로 바꾼다 : 코드 안 문자열이라 레포마다 테스트 · 빌드를 다시 돌려야 하고, 화면 · 경보는 담당 파트가 사용자에게 보이는 글이다
- API 응답 메시지 · 로그 문구는 그대로 둔다 : 응답 메시지는 노션 API 명세가 정본이라 같이 고쳐야 하고, 로그 문구는 검색 · 경보에 걸려 있을 수 있다
- 이슈 제목 · PR 제목 · 커밋 메시지는 GitHub 기록이라 안 바꾼다
- 바꾸는 방법 : 뜻이 하나인 조어는 스크립트로 일괄 치환하고, 셋(알림 · 탐지 · 등급 같은 보통 말 · 평면 · 원문 인용)은 문맥을 보고 손으로 고친다
  - 마지막에 diff 를 보고 grep 으로 남은 것을 센다

README 는 지금 README 위에 입구를 얹는다
- 맨 위 : 한 줄 소개 · 구조 그림 · 숫자(오버헤드 · 수집 지연 · 결정 수) · 핵심 결정 · 켜 보는 길
- 켜는 법 · 환경변수 · 포트는 그 아래에 그대로 둔다 : 팀원이 쓰던 자리와 링크가 안 깨진다
- dev-guide 로 떼는 것은 제외 : 팀원이 보던 자리가 옮겨지고 그 자리를 가리키는 링크를 다 고쳐야 한다
- 틀린 줄은 그 레포 PR 에서 같이 고친다 (backend 셋 · shop 하나 · deploy 레포 소개의 Helm)

평면은 ADR #73 으로 팀 전체에서 그만 쓴다 (아래 ADR 초안)
- 내가 확정하고 슬랙에 공지한다 : 옛 이름 → 새 이름 표 · 이유 · 영향받는 파트
- 공지가 먼저, backend PR 머지가 나중이다 : 팀원이 PR 에서 처음 알게 되지 않게

진행은 레포별로 한다 : 한 번에 하면 리뷰가 안 된다
- backend 먼저 : glossary 와 ADR #73 이 backend 에 있어서 다른 레포가 그걸 가리킨다
- backend 는 PR 하나지만 커밋을 나눈다 : glossary · ADR → README → AGENTS · 설계 → docs/seungjo → 주석
- 나머지는 shop → deploy → web → watchdog 순서로 레포마다 PR 하나
- 레포 소개(About) 다섯 줄은 backend PR 이 머지될 때 같이 바꾼다 : 밖에서 가장 먼저 보이는 줄이다

이슈는 둘로 나눈다
- #240 : 문서 + 주석. 레포별 PR (backend → shop → deploy → web → watchdog)
- 후속 이슈 하나 : 테스트 이름 · 화면 문구 · Slack 경보 문구 · 스크립트 단계 제목
  - #240 PR 마다 남긴 문자열을 「파일:줄」 목록으로 모아 그 이슈 본문에 붙인다
  - 그 이슈에서도 레포별 PR 로 간다
- #240 은 문서 PR 들이 다 머지되면 닫는다 : 후속 PR 까지 묶으면 이슈가 오래 열려 있게 된다

끝났다는 것
- 레포마다 바꿀 말 grep 이 0
  - 남겨도 되는 것 : glossary 의 옛 이름 칸 · ADR #13 과 #73 본문 · #240 이슈 폴더 · AGENTS §5 의 지난 이력 줄 · API 응답 메시지 · 로그 문구 · 후속 이슈로 넘긴 코드 안 문자열
  - 남긴 것은 PR 본문에 「파일:줄 · 남긴 이유」 목록으로 붙인다
- 주석을 바꾼 레포는 빌드가 그대로 통과한다 (backend ./gradlew build 등)
- 후속 이슈가 만들어져 있다 : 코드 안 문자열 목록(파일:줄)을 붙여서
- README 첫 화면(위 40줄 안)에 한 줄 소개 · 구조 그림 · 숫자 셋 · 켜 보는 길 링크가 있다

되돌림
- 팀원이 새 이름을 못 따라와 대화와 문서가 갈리면 glossary 에 「대화에서 쓰는 말」 칸을 더한다
- 멘토 리뷰에서 이름이 또 안 통하면 그 이름만 glossary 에서 바꾸고 레포에 다시 적용한다
```

ADR #73 초안 (`01-decisions.md` 에 옮길 모양, 공지 전까지는 `[제안]`)

```
## `#73` [제안] 「평면」 을 그만 쓴다 : A = Monitored app · B = APM · C = Watchdog · B 자기 감시 = Self-monitoring (`#13` 대체, `#240`)

2026-10-26 | 승조 확정 예정 · 슬랙 공지 뒤 [확정]
- 채택 : A평면 → Monitored app · B평면 → APM · C평면 → Watchdog · B평면 자기 감시 → Self-monitoring. 문서 · 주석은 `#240` 에서, 테스트 이름 · 화면 · 경보 문구는 그 후속 이슈에서 바꾼다
- 기각 : ① 그대로 두고 glossary 에서 뜻만 설명 : 사유 = 매번 설명이 필요한 말은 이름으로 실패한 것이다 ② 밖에 보이는 글(README · 레포 소개)에서만 버림 : 사유 = 안팎 이름이 갈려 팀 문서와 README 를 오갈 때 다시 바꿔 읽어야 하고, 일부만 고치면 또 안 고쳐졌다는 피드백이 온다
- 사유 : 현업에서 plane 은 control plane · data plane 으로 굳은 말이다(쿠버네티스 · Istio). 면접관 · 멘토일수록 그 뜻으로 읽으려다 막힌다. 멘토 피드백(2026-10)이 용어를 직접 짚었다
- `#13` 과의 관계 : `#13` 의 되돌림 조건(「새 표기로도 이해하지 못하면 폐기」)이 왔다. 다만 `#13` 이 말한 「만들 것 / 안 만들 것 2분법」 대신 현업 이름을 붙이는 쪽을 골랐다 : 세 덩어리 구분 자체는 설계에 계속 필요하다(`#01` 의 감시 대상 · APM · 바깥 감시)
- 되돌림 : ① 팀원이 새 이름을 못 따라와 대화와 문서가 계속 갈리면 glossary 에 「대화에서 쓰는 말」 칸을 더한다 ② 세 덩어리를 한 단어로 부를 일이 다시 잦아지면(예 : 「B 쪽」 이 대화에 자주 나오면) 평면 대신 layer 같은 현업 말로 다시 묶는다
```

---

## 9. 구현과 확인 (2026-10-26)

### 한 것

| 무엇 | 어디 |
|---|---|
| 용어 정본 | `docs/design/glossary.md` (옛 이름 · 새 이름 · 뜻 · 코드 위치 · 현업 출처), `00-index.md` 에서 링크 |
| 평면 폐기 | ADR `#73` **[제안]** (채택 · 기각 · 사유 · `#13` 과의 관계 · 되돌림), `#13` 제목에 「`#73` 으로 대체」 |
| README 입구 | 위 40줄 안에 한 줄 소개 · 구조 그림 · 숫자 셋(오버헤드 · 수집 지연 · 결정 73) · 핵심 결정 다섯 · 켜 보는 길. 틀린 줄 셋 고침 |
| 일괄 치환 | 뜻이 하나인 조어 11개. 문서는 전체, 코드는 주석 부분만. 영어 뒤 한글 조사는 한 칸 띄움 |
| 문맥 수정 | 평면 · 하한 · 묶음 · 관통 · 명부 + 모듈을 가리키는 탐지 · 알림 · 등급 · 입구 · 금고. 파일 묶음별 worker 다섯이 나눠 했다 |
| 원문 표시 | 원문 인용이 든 파일(`prompts` · `research` · `decision` · `01-decisions`) 머리에 「용어만 바꿈 (`#240`)」 한 줄 |
| 후속 | 코드 안 문자열은 `#241` (레포별 줄 목록 붙임) |

### 숫자

| 확인 | 값 |
|---|---|
| backend 바뀐 파일 | 245 (문서 · 설계 37 · `docs/seungjo` 53 · 코드 주석 117 등) |
| 일괄 치환 | 2,653 곳 (md) + 106 곳 (설계 산출물 html · json) |
| 코드 파일에서 주석 밖이 바뀐 줄 | **0** (바뀐 줄마다 첫 차이가 주석 안인지 검사) |
| `./gradlew build` | 통과, 테스트 **870 건 실패 0** (`#236` 때와 같은 수) |
| 평면 · 하한 · 묶음 · 관통 · 명부 grep | 51 줄 남음 : 전부 허용 자리 (ADR `#13` · `#73` 본문, 로그 문자열 인용 1, 코드 안 문자열 → `#241`) |
| 다른 레포 | shop 44 · deploy 22 · web 51 · watchdog 9 파일. shop `./gradlew test` · web `build` · `test` · watchdog `pytest` 9 건 · deploy `bash -n` · `kustomize` 전부 통과 |

### 안 건드린 것

- `db/*/V*.sql` : Flyway 가 적용한 파일이라 한 글자만 바뀌어도 checksum 이 어긋나 기동이 멈춘다 (glossary 「일부러 안 바꾼 것」)
- 이슈 · PR 제목 · 커밋 메시지
- API 응답 메시지 · 로그 문구 · 테스트 이름 · 화면 · 경보 문구 : `#241`

### 승조가 확인할 것 (worker 가 판단한 곳)

- ADR `#01` 의 옛 A평면은 「제품 도식 전체」 뜻이라 Monitored app 이 아니라 **APM** 으로 바꿨다 (`#13` 이 A 를 감시 대상으로 다시 정의하기 전의 뜻)
- 「묶음」 이 System 이 아닌 곳은 뜻마다 다른 말로 : 인증서 → 「두 벌」, Kafka · ClickHouse 보내는 단위 → `batch`, DB 연결 → 「연결 풀」, 알림 그룹핑 → 「그룹」, 결정 번호 묶음 → 「항목」 · 「모음」, deploy 의 적용 순서 → 「단계」
- 「평면 스팬 목록」(트리가 아닌 목록) → `flat`
- 「알림」 은 사람 파트 이름(「알림 파트(ukong)」)과 보통 말(「알림 60초」)을 그대로 뒀다
- shop 의 「입구」(손님이 들어오는 gateway)는 클러스터 Ingress 가 아니라 그대로 뒀다

### 나머지 순서

- 레포 소개(About) 다섯 줄 : backend PR 머지 때
- ADR `#73` [확정] : 승조 슬랙 공지 뒤, 그다음 backend PR 머지
