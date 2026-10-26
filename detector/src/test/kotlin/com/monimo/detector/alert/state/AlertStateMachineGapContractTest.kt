package com.monimo.detector.alert.state

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

// 운영 기본 정책(N=3 · M=2 · maxBucketGap 없음, AlertDetectionConfig)에서 "연속" 이 무엇을 세는지 고정 입력으로 확인한다.
// 빈 1분을 판정 불가로 넣는 일은 상태머신이 아니라 평가 주기(GcTimeRunner · settledBucketEnds)가 한다는 것을 보인다
class AlertStateMachineGapContractTest : BehaviorSpec({

    val t0 = Instant.parse("2026-10-16T09:00:00Z")
    val production = AlertPolicy(fireAfter = 3, resolveAfter = 2, maxBucketGap = null)

    fun bucket(minute: Long) = t0.plus(Duration.ofMinutes(minute))
    fun bad(minute: Long) = Evaluation(1, Verdict.Violating(bucket(minute), BigDecimal("7000")))
    fun unknown(minute: Long) = Evaluation(1, Verdict.Unknown(bucket(minute), UnknownReason.NO_DATA))
    fun feed(machine: AlertStateMachine, vararg evaluations: Evaluation): List<Outcome> {
        var state = EvaluationState.initial(1)
        return evaluations.map { machine.apply(state, it).also { outcome -> state = outcome.state } }
    }

    Given("운영 기본 정책") {
        val machine = AlertStateMachine(production)

        When("초과 · 초과 · 판정 불가 · 초과") {
            val outcomes = feed(machine, bad(1), bad(2), unknown(3), bad(4))
            Then("판정 불가가 연속을 끊어 발화하지 않는다") {
                outcomes.last().state.consecutiveBad shouldBe 1
                outcomes.none { it is Outcome.Applied && it.transition is Transition.Fired } shouldBe true
            }
        }

        When("3분째 버킷이 아예 평가되지 않고 초과 세 개만 들어오면 (Detector 가 멈췄다가 maxStaleness 밖을 건너뜀)") {
            val outcomes = feed(machine, bad(1), bad(2), bad(4))
            Then("현재 동작: 간격 검사가 꺼져 있어 세 개를 연속으로 세고 발화한다") {
                outcomes.last().shouldBeInstanceOf<Outcome.Applied>().transition.shouldBeInstanceOf<Transition.Fired>()
            }
        }

        When("한 버킷을 판정 불가로 반영한 뒤 그 버킷 값이 늦게 도착하면") {
            var state = EvaluationState.initial(1)
            state = machine.apply(state, unknown(1)).state
            val late = machine.apply(state, bad(1))
            Then("같은 버킷이라 무시한다 — 처음 판정(판정 불가)이 남는다") {
                late.shouldBeInstanceOf<Outcome.Ignored>().reason shouldBe IgnoreReason.DUPLICATE_BUCKET
            }
        }
    }
})
