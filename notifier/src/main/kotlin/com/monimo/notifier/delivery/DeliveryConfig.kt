package com.monimo.notifier.delivery

import com.monimo.notifier.channel.DeliveryHttpProperties
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val laneThreads = AtomicInteger()

@Configuration
@EnableConfigurationProperties(DeliveryProperties::class)
class DeliveryConfig {

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun retryPolicy(props: DeliveryProperties, http: DeliveryHttpProperties): RetryPolicy {
        // 한 레인은 선점 하나(최대 배치)를 순서대로 보낸다(#250). 임대가 "배치 × 호출 시간 한도"보다 짧으면 뒤쪽 작업은 호출 전에 임대가 끝나
        // 다른 워커가 같은 작업을 또 가져간다 → 기동할 때 막는다
        val worstBatch = http.requestTimeout.plus(http.connectTimeout).multipliedBy(props.batchSize.toLong())
        require(props.lease > worstBatch) { "lease(${props.lease})는 batch × (connect + request timeout) = $worstBatch 보다 길어야 합니다" }
        return RetryPolicy(props.retryBase, props.retryCap, props.maxAttempts, props.maxAge)
    }

    // 발송 대기 큐를 마지막으로 읽는 데 성공한 뒤 흐른 초 (ADR #54). 폴링이 1초라 평소엔 1초 안쪽
    @Bean
    fun deliveryPollAge(registry: MeterRegistry) =
        LastSuccessAge(registry, "monimo.notifier.delivery.poll.age", "발송 대기 큐를 마지막으로 PG 에서 읽는 데 성공한 뒤 흐른 시간")

    // 채널별 발송 레인 (#250). 스레드는 바쁜 채널 수만큼만 생기고(최대 max-busy-channels + 한 선점의 채널 수) 쉬면 60초 뒤 사라진다.
    // 종료 때는 진행 중인 호출(최대 connect + request timeout)을 마저 끝내고 기록하게 기다린다
    @Bean(destroyMethod = "close")
    fun channelLanes(props: DeliveryProperties, http: DeliveryHttpProperties): ChannelLanes {
        val executor = ThreadPoolExecutor(
            0, Int.MAX_VALUE, 60L, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(r, "delivery-lane-${laneThreads.incrementAndGet()}") },
        )
        return ChannelLanes(executor, props.maxBusyChannels, http.connectTimeout.plus(http.requestTimeout).plusSeconds(1))
    }

    @Bean
    fun channelCircuitBreaker(props: DeliveryProperties, clock: Clock) =
        ChannelCircuitBreaker(props.circuitFailureThreshold, props.circuitOpenDuration, clock)
}

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "monimo.notifier.delivery", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class DeliveryScheduler(private val worker: DeliveryWorker) {
    // fixedDelay: 앞 바퀴가 끝난 뒤 간격을 둔다 → 한 인스턴스 안에서 바퀴가 겹치지 않는다
    @Scheduled(fixedDelayString = "\${monimo.notifier.delivery.poll-interval:1s}")
    fun tick() {
        while (worker.pollOnce() > 0) Unit  // 쌓여 있으면 바로 다음 배치. 바쁜 채널은 빠지므로 다른 채널 줄만 이어서 잡는다
    }
}
