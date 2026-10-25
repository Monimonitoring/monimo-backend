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

- **Q.**
  - A.

---

## 4. 1차 정리

---

## 5. 선택지와 설명

---

## 6. 2차 질문 (사람 차례)

---

## 7. 2차 정리

---

## 8. 구현 프롬프트 (사람 차례)
