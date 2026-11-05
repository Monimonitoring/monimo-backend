package com.monimo.detector.alert.record

import com.monimo.detector.alert.state.AlertPolicy
import com.monimo.detector.alert.state.AlertStateMachine
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

// FN-28 N · M 은 규칙별 컬럼이 없어 전역 설정으로 시작한다 (2026-09-27 결정, docs/alert/10-state-machine.md §4)
@ConfigurationProperties("monimo.alert.evaluation")
data class AlertEvaluationProperties(
    val fireAfter: Int = 3,
    val resolveAfter: Int = 2,
    // 반영한 두 버킷 사이가 1분(버킷 하나)보다 벌어지면 연속을 끊는다. 평가 주기는 1분 버킷을 빠짐없이 차례로 넣으므로
    // (값이 없으면 판정 불가로) 이 간격이 벌어지는 경우는 Detector 가 maxStaleness(3분)보다 오래 멈춰 버킷 평가 자체가 빠졌을 때뿐이다.
    // 예전 기본값 null 은 그 빈 구간을 사이에 두고도 연속으로 세어, 실제로는 3분 연속 초과한 적이 없는데 "3분 연속" 으로 발화했다
    // (DetectorPauseStreakTest). null 로 두면 검사를 끈다
    val maxBucketGap: Duration? = Duration.ofMinutes(1),
    // AGENT_DOWN 은 판정 자체가 이미 90초 침묵을 기다리므로 N 을 따로 둔다 (제안 · 팀 확인 전, docs/alert/40-agent-down.md D14)
    val agentDownFireAfter: Int = 1,
)

// metric_kind 별 상태머신. 따로 정한 종류가 아니면 전역 N · M 을 쓴다
class AlertStateMachines(
    private val default: AlertStateMachine,
    private val byKind: Map<String, AlertStateMachine> = emptyMap(),
) {
    fun forKind(metricKind: String): AlertStateMachine = byKind[metricKind] ?: default
}

@Configuration
@EnableConfigurationProperties(AlertEvaluationProperties::class)
class AlertDetectionConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun alertStateMachines(props: AlertEvaluationProperties) = AlertStateMachines(
        default = AlertStateMachine(AlertPolicy(props.fireAfter, props.resolveAfter, props.maxBucketGap)),
        byKind = mapOf(
            AGENT_DOWN to AlertStateMachine(AlertPolicy(props.agentDownFireAfter, props.resolveAfter, props.maxBucketGap)),
        ),
    )

    companion object {
        const val AGENT_DOWN = "AGENT_DOWN"
    }

    @Bean
    fun transitionHook(): TransitionHook = TransitionHook.NONE
}
