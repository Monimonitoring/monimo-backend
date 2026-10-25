# 프롬프트 원문 : `#240` 용어 · README (멘토 피드백 A · B)

## 시작 (2026-10-26, 승조)

- 멘토 피드백 원문 둘 (붙여 넣은 그대로)

```
용어 : 적재기, 수집기, 스팬어쩌고 이런 용어 실제 현업언어로 개선하기
readme잘정리해놓기
서비스맵도 좀 복잡하게
GC 메모리분석, 서버, 매트릭을 어떻게 뽑았는지
```

```
설계 잘 정리해서 면접관에게 어필 가능하도록 정리를 잘하기
서비스 맵도 복잡하게 가능할 거니 한 번 고려하면 좋을 거 같다 (서비스 여러 개)
모니터링 서비스를 다른 서비스에 붙였을 때 
해당 서비스에 메모리 및 다른 자원에 얼마나 영향을 끼치는 지
줄일거라면 어떻게 줄일 수 있었는 지
GC 설정 메모리 관리 어떻게 덤프떳길래 정보는 어떻게 했는지  

설계문서 잘 작성해서 멘토들에게 한 번 요청하기
```

- 진행 지시

```
리허설은 화요일 혹은 수요일에 진행하고 
지금은 멘토 피드백 A랑 B를 진행하자
```

## 2절 뒤 (2026-10-26, 승조)

```
그래서 이번 이슈에서 research속 2절부분을 2.1~2.5까지 각각 간단 요약을 해준다면?
```

```
이해했다 다음으로 넘어가자
```

## 5절 조사 프롬프트 (AI → 웹 조사 서브에이전트 1개, 원문 요지)

- 아래 14가지의 현업 이름을 **공식 문서 링크와 함께** 확인하고, 확인 못 한 것은 표시하라
  1. Collector : OpenTelemetry Collector · Pinpoint Collector · Jaeger collector
  2. Ingester : Jaeger ingester(Kafka 에서 읽어 저장소에 쓰나) · Grafana Tempo · Loki
  3. 경보 규칙 평가 : Prometheus 규칙 평가 · Splunk Observability detector · Datadog monitor
  4. Notifier · Prometheus Alertmanager
  5. 바깥에서 감시 스택을 감시 : kube-prometheus `Watchdog` · dead man's switch · meta-monitoring · Healthchecks.io
  6. synthetic monitoring 과 canary : AWS CloudWatch Synthetics 가 스크립트를 canary 라 부르는지
  7. 관측 백엔드의 self-monitoring
  8. OTel attributes · Prometheus labels · Datadog tags
  9. OTel SeverityNumber · SeverityText, 최소 로그 레벨 필터 이름
  10. Service map : Datadog · New Relic · AWS X-Ray, Pinpoint ServerMap · Inspector
  11. orphan span · missing parent
  12. rollup · downsampling
  13. 쿠버네티스 · 서비스 메시의 control plane · data plane 뜻
  14. 서비스 묶음 이름 : Backstage 의 System 등
- 덧붙여 SigNoz · Pinpoint · Jaeger · Tempo 같은 오픈소스 APM 의 README 첫 화면에 무엇이 있는지 3 ~ 5줄
- 한국어 표로, URL 을 지어내지 말 것

## 5절 뒤 (2026-10-26, 승조)

```
선택지
2.는 영어이름만 사용하기
3. 대응표는 B 방식으로 
4. 수정범위는 전체 범위로 하는 것이 좋을 거 같다 A로 진행하면 나중에 또 수정이 안되었다고 피드백이 올 수 있기 때문에
5. ⑤ README 입구 모양 이건 무슨 말이냐?
6. 6번도 무슨 말인지 잘 모르겠다
```

```
5번은 A방법
6번은 C로 다시 작성하는 방향으로 진행하는 것으로 research.md 부분 수정하자
```
