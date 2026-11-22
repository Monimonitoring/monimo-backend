package com.monimo.common

import com.monimo.common.kafka.RawHeaders
import com.monimo.common.kafka.RawSignal
import com.monimo.common.kafka.RawTopics
import com.monimo.common.kafka.UnknownRawKeyException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest

// raw 토픽들의 메시지 **봉투 겉면** 약속을 못박는 시험 (개발환경 9단계 · ADR `#65` · 토픽 셋은 ADR `#74`).
//
// 값(protobuf 바이트)은 `OtlpProtobufRoundTripTest` 가 본다. 여기서 보는 것은 글자들이다 :
// 토픽 이름 · 신호 키 셋 · 헤더 이름. 붙이는 쪽(Collector)과 꺼내는 쪽(Ingester)이 **다른 모듈**이라
// 한쪽에서 글자를 바꾸면 빌드는 통과하는데 돌 때 조용히 어긋난다. 그 조용한 어긋남을 막는 자리다.
//
// 토픽 이름은 코드 밖에서도 쓰인다 : compose 의 `kafka-init` 이 그 이름으로 토픽을 만들고,
// 점검 스크립트와 운영 Job 이 같은 글자를 본다. 그래서 글자가 바뀌면 코드 밖까지 같이 고쳐야 한다는
// 사실을 시험으로 드러내 둔다 (바꾸려면 이 시험이 먼저 빨개진다)
class RawMessageFormatTest : BehaviorSpec({

    Given("raw 토픽 메시지 약속") {

        When("토픽 이름을 보면") {
            Then("신호별로 셋이다 : compose kafka-init 과 kafka-init Job 이 만드는 이름과 같아야 한다 (ADR `#74`)") {
                RawTopics.ALL shouldBe listOf("raw.traces", "raw.metrics", "raw.logs")
            }

            Then("신호마다 자기 토픽을 들고 있다 : Collector 가 보낼 곳을 키가 아니라 이것으로 고른다") {
                RawSignal.TRACES.topic shouldBe "raw.traces"
                RawSignal.METRICS.topic shouldBe "raw.metrics"
                RawSignal.LOGS.topic shouldBe "raw.logs"
            }

            Then("키로 되넣을 토픽을 고를 수 있다 : DLQ 되넣기가 쓰는 길 (#281)") {
                RawSignal.entries.forEach { RawSignal.topicOfKey(it.key) shouldBe it.topic }
            }

            Then("모르는 키는 null 이다 : 되넣기가 예외가 아니라 건너뛰기로 다룬다") {
                RawSignal.topicOfKey("spans") shouldBe null
                RawSignal.topicOfKey(null) shouldBe null
            }
        }

        When("신호 키를 보면") {
            Then("traces · metrics · logs 셋이고 글자가 이것뿐이다") {
                RawSignal.entries.map { it.key } shouldBe listOf("traces", "metrics", "logs")
            }

            Then("키 글자로 신호를 되찾을 수 있다 : Ingester 가 어느 protobuf 로 풀지 정하는 방법") {
                RawSignal.entries.forEach { RawSignal.fromKey(it.key) shouldBe it }
            }

            Then("모르는 키는 전용 예외다 : Ingester 가 독성으로 분류해 재시도하지 않는다 (ADR `#51`)") {
                shouldThrow<UnknownRawKeyException> { RawSignal.fromKey("spans") }
            }
        }

        When("봉투 메모(헤더) 이름을 보면") {
            Then("network.peer.address 다 : OTel 의미 규약의 Stable 속성 글자 그대로 (ADR `#63`)") {
                RawHeaders.PEER_ADDRESS shouldBe "network.peer.address"
            }
        }

        When("신호 키와 protobuf 타입의 짝을 보면") {
            Then("키 셋이 OTLP Export 요청 셋과 하나씩 맞는다") {
                // 값은 "Collector 가 받은 Export*ServiceRequest 그대로" 다. 그 셋 말고 다른 타입은 이 토픽에 안 들어온다.
                // 빈 요청을 풀어 보는 것으로 "이 키는 이 타입" 짝을 코드로 적어 둔다
                RawSignal.entries.forEach { signal ->
                    val parsed = when (signal) {
                        RawSignal.TRACES -> ExportTraceServiceRequest.parseFrom(ExportTraceServiceRequest.getDefaultInstance().toByteArray())
                        RawSignal.METRICS -> ExportMetricsServiceRequest.parseFrom(ExportMetricsServiceRequest.getDefaultInstance().toByteArray())
                        RawSignal.LOGS -> ExportLogsServiceRequest.parseFrom(ExportLogsServiceRequest.getDefaultInstance().toByteArray())
                    }
                    parsed.serializedSize shouldBe 0
                }
            }
        }
    }
})
