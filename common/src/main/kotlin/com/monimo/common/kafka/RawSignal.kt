package com.monimo.common.kafka // 이 파일의 소속. 폴더 경로와 같아야 한다. 다른 모듈은 import 해서 쓴다

// Kafka raw 토픽들의 메시지 형식. 개발환경 9단계에서 고정했다 (ADR `#65`) : 이후 변경은 PR + ADR 로만 한다.
// 값 = Collector 가 받은 OTLP Export*ServiceRequest 의 protobuf 바이트 그대로 · 키 = 신호 이름.
//
// 토픽은 신호별로 셋이다 (ADR `#74` 가 `#65` ④ 를 되돌림 ② 로 바꿨다, `#281`) : raw.traces · raw.metrics · raw.logs.
// 토픽 이름만 봐도 어느 신호인지 알지만 **키는 그대로 둔다** : DLQ 되넣기가 키를 보고 원래 토픽을 고르고(`#195`),
// 모르는 키를 독성으로 분류하는 길(ADR `#51`)이 키에 걸려 있다
//
// 아래 글자(토픽 이름 셋 · 키 셋)와 "바이트 그대로" 는 common 시험이 쇼핑몰 실데이터 표본으로 고정한다
// (RawMessageFormatTest · OtlpProtobufRoundTripTest). 글자를 바꾸면 그 시험이 먼저 빨개진다 :
// 토픽 이름은 코드 밖(compose kafka-init · kafka-init Job · 점검 스크립트)에서도 쓰이므로 같이 고쳐야 한다
//
// 키가 traces · metrics · logs 어느 것도 아닐 때. IllegalArgumentException 의 한 종류지만 타입을 따로 둔 이유 :
// Ingester 가 이 예외만 "독성(재시도 소용없음)" 으로 분류한다. IllegalArgumentException 을 통째로 잡으면 변환기의
// 다른 IAE 까지 걸린다. 키는 같은 enum 에서 나오므로 이게 나는 경우는 Collector · Ingester 배포 버전이 어긋났을 때뿐이다
class UnknownRawKeyException(key: String) : IllegalArgumentException("모르는 raw 키: $key")

// 토픽 이름을 const 로 따로 두는 이유 : @KafkaListener(topics = [...]) 는 **컴파일 시점 상수**만 받는다.
// enum 항목의 topic 값(RawSignal.TRACES.topic)은 실행 중에 정해지는 값이라 애너테이션에 못 쓴다
object RawTopics {
  const val TRACES = "raw.traces"
  const val METRICS = "raw.metrics"
  const val LOGS = "raw.logs"

  val ALL = listOf(TRACES, METRICS, LOGS) // 점검 · 시험이 "토픽 셋" 을 한 덩이로 받을 때
}

enum class RawSignal(val key: String, val topic: String) {//val key는 이 클래스에 들어올 매개변수
  TRACES("traces", RawTopics.TRACES), //항목 : TRACES, 위 매개변수 key 값이 traces
  METRICS("metrics", RawTopics.METRICS), //항목 : METRICS, 위 매개변수 key 값이 metrics
  LOGS("logs", RawTopics.LOGS); //항목 : LOGS, 위 매개변수 key 값이 logs

  companion object {//항목 하나가 아닌 RawSignal 클래스 속에서 하나 
    fun fromKey(key: String): RawSignal = // 결과값으로 RawSignal속 항목을 내보내는 함수
      entries.firstOrNull {it.key == key } ?: throw //entries를 하나하나 보면서 key 가 같은 첫 것 찾는
UnknownRawKeyException(key)// 예외 처리. 전용 타입이라 Ingester 가 "데이터가 틀렸다" 로 분류할 수 있다 (ADR #51)

    // 키 → 그 신호의 토픽. **모르는 키면 null** 이고 예외를 던지지 않는다 (fromKey 와 다른 점).
    // 쓰는 곳은 DLQ 되넣기 : "되넣을 곳이 없다" 를 건너뛰기로 다루므로 예외가 아니라 null 이 맞다 (#281).
    // 소비 경로는 그대로 fromKey 를 쓴다 : 거기서는 모르는 키가 독성이라 예외여야 한다 (ADR #51)
    fun topicOfKey(key: String?): String? = entries.firstOrNull { it.key == key }?.topic
  }
}
