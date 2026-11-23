package com.monimo.notifier.delivery

import com.monimo.notifier.support.FakeSlackServer
import com.monimo.notifier.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID

@TestConfiguration(proxyBeanMethods = false)
class CoroutineLanesTestConfig {
    // 느린 채널 여럿을 동시에 붙잡고 있어야 하므로 가짜 Slack 의 처리 스레드를 넉넉히
    @Bean(destroyMethod = "close") fun coroutineLanesSlack() = FakeSlackServer(threads = 64).apply { slowMillis = 1_500 }
}

// 코루틴 레인 (docs/alert/120-coroutine-lanes.md). 스레드 레인과 같은 규칙(laneRules)을 지키고,
// 레인 스레드(2개)보다 많은 채널을 동시에 기다릴 수 있는지를 더 본다
@SpringBootTest
@Import(TestInfraConfig::class, CoroutineLanesTestConfig::class)
@TestPropertySource(
    properties = [
        "monimo.notifier.delivery.enabled=false",
        "monimo.notifier.delivery.group-wait=0s",
        "monimo.notifier.delivery.lane-mode=COROUTINE",
        "monimo.notifier.delivery.lane-threads=2",
        "monimo.notifier.delivery.max-busy-channels=100",
        "monimo.notifier.http.connect-timeout=500ms",
        "monimo.notifier.http.request-timeout=3s",
    ],
)
class CoroutineLanesTest(
    worker: DeliveryWorker,
    lanes: DeliveryLanes,
    jdbc: JdbcTemplate,
    fake: FakeSlackServer,
) : BehaviorSpec({

    laneRules(worker, lanes, jdbc, fake)(this)

    Given("느린 채널 20개(각 1.5초)에 1건씩, 레인 스레드는 2개") {
        val ruleId = jdbc.queryForObject("SELECT id FROM alert_rules ORDER BY id LIMIT 1", Long::class.java)!!
        val ids = (1..20).map { i ->
            val channel = jdbc.queryForObject(
                "INSERT INTO alert_channels (name, type, config, enabled) VALUES (?, 'SLACK', ?::jsonb, true) RETURNING id",
                Long::class.java, "many-$i-${UUID.randomUUID()}", """{"webhook_url":"${fake.url("slow")}"}""",
            )!!
            val event = jdbc.queryForObject(
                """
                INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at,
                    rule_version, metric_kind, operator, threshold, window_sec, severity)
                VALUES (?, ?, ?, 'FIRING', now(), 1, '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
                """,
                Long::class.java, UUID.randomUUID(), ruleId, UUID.randomUUID().toString().replace("-", ""),
            )!!
            jdbc.queryForObject(
                """
                INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
                VALUES (?, 'FIRING', ?, ?::jsonb, 'PENDING', now() - interval '1 second', now() - interval '1 second', now())
                RETURNING id
                """,
                Long::class.java, event, channel, """{"transition":"FIRING","rule_name":"many","service_name":"svc-$i","severity":"CRITICAL"}""",
            )!!
        }

        When("선점해 레인에 맡기면") {
            val begin = System.nanoTime()
            while (worker.pollOnce() > 0) Unit
            val start = System.nanoTime()
            fun sent() = jdbc.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE status = 'SENT' AND id = ANY(?)", Long::class.java, ids.toTypedArray(),
            )!!
            while (sent() < 20 && System.nanoTime() - start < 20_000_000_000) Thread.sleep(20)
            val tookMs = Duration.ofNanos(System.nanoTime() - begin).toMillis()

            Then("스레드 2개로 20개 채널을 동시에 기다린다 — 차례로였다면 20 × 1.5초 / 2 = 15초") {
                sent() shouldBe 20L
                tookMs shouldBeLessThan 5_000L
                lanes.busyChannels() shouldBe emptySet()
            }
        }
    }
})

// 레인 자체의 규칙 (스프링 없이). gate.await 처럼 스레드를 붙잡는 일 둘을 동시에 돌리므로 레인 스레드는 2개 이상
class CoroutineLanesUnitTest : BehaviorSpec(
    laneUnitRules { CoroutineLanes(maxBusy = 2, shutdownWait = Duration.ofSeconds(5), dbParallelism = 4) },
)
