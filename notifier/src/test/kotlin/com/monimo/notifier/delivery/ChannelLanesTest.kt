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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@TestConfiguration(proxyBeanMethods = false)
class LanesTestConfig {
    @Bean(destroyMethod = "close") fun lanesSlack() = FakeSlackServer().apply { slowMillis = 1_500 }
}

// 채널별 레인 (#250, docs/alert/70-slow-channel.md). 실제 레인 스레드 + 실제 PG + 가짜 Slack.
// 느린 채널은 받고 1.5초 뒤 200 (한도 3초 안 = 실패가 아니라 "느린 성공" — 서킷브레이커가 막지 못하는 경우)
@SpringBootTest
@Import(TestInfraConfig::class, LanesTestConfig::class)
@TestPropertySource(
    properties = [
        "monimo.notifier.delivery.enabled=false",
        "monimo.notifier.delivery.group-wait=0s",
        "monimo.notifier.http.connect-timeout=500ms",
        "monimo.notifier.http.request-timeout=3s",
    ],
)
class ChannelLanesTest(
    worker: DeliveryWorker,
    lanes: ChannelLanes,
    jdbc: JdbcTemplate,
    fake: FakeSlackServer,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('lane-order') RETURNING id", Long::class.java)!!
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, 'lane', '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
        """,
        Long::class.java, appId,
    )!!

    fun channel(mode: String): Long = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES (?, 'SLACK', ?::jsonb, true) RETURNING id",
        Long::class.java, "lane-$mode-${UUID.randomUUID()}", """{"webhook_url":"${fake.url(mode)}"}""",
    )!!

    // 서비스 이름을 다르게 주면 묶이지 않아 그룹 하나 = 호출 하나가 된다
    fun outbox(channelId: Long, service: String): Long {
        val eventId = jdbc.queryForObject(
            """
            INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, fired_at,
                rule_version, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, ?, 'FIRING', now(), 1, '5XX_RATE', 'GT', 1, 60, 'CRITICAL') RETURNING id
            """,
            Long::class.java, UUID.randomUUID(), ruleId, UUID.randomUUID().toString().replace("-", ""),
        )!!
        return jdbc.queryForObject(
            """
            INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
            VALUES (?, 'FIRING', ?, ?::jsonb, 'PENDING', now() - interval '1 second', now() - interval '1 second', now())
            RETURNING id
            """,
            Long::class.java, eventId, channelId,
            """{"transition":"FIRING","rule_name":"lane","service_name":"$service","severity":"CRITICAL"}""",
        )!!
    }

    fun status(id: Long): String = jdbc.queryForObject("SELECT status FROM notification_outbox WHERE id = ?", String::class.java, id)!!

    // 상태가 바뀔 때까지 기다린 시간(ms). 제한 안에 안 바뀌면 제한값을 돌려준다
    fun waitSent(id: Long, limit: Duration): Long {
        val start = System.nanoTime()
        while (status(id) != "SENT" && System.nanoTime() - start < limit.toNanos()) Thread.sleep(20)
        return Duration.ofNanos(System.nanoTime() - start).toMillis()
    }

    Given("느린 채널에 서로 다른 서비스 알림 3건(호출 3번, 각 1.5초), 정상 채널에 1건이 한 선점에 같이 잡힌다") {
        val slow = channel("slow")
        val ok = channel("ok")
        val slowIds = (1..3).map { outbox(slow, "svc-$it") }
        val okId = outbox(ok, "normal-svc")

        When("한 바퀴 돌면") {
            val begin = System.nanoTime()
            val took = worker.pollOnce()
            val returnedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis()
            val okMs = waitSent(okId, Duration.ofSeconds(10))

            Then("선점은 넷을 한 번에 잡고, 보내기를 기다리지 않고 돌아온다") {
                took shouldBe 4
                returnedMs shouldBeLessThan 1_000L
            }

            Then("정상 채널은 느린 채널 셋(4.5초)을 기다리지 않고 바로 나간다") {
                okMs shouldBeLessThan 1_000L
                slowIds.count { status(it) == "SENT" } shouldBe 0
            }

            Then("느린 채널이 보내는 동안 그 채널의 새 줄은 다음 선점에서 빠지고, 다른 채널 줄은 잡힌다") {
                val slowLater = outbox(slow, "svc-late")
                val okLater = outbox(ok, "normal-later")
                worker.pollOnce() shouldBe 1
                waitSent(okLater, Duration.ofSeconds(5)) shouldBeLessThan 1_000L
                status(slowLater) shouldBe "PENDING"
                lanes.busyChannels() shouldBe setOf(slow)
            }

            Then("느린 채널 안에서는 차례로 하나씩 보내 셋 다 SENT 가 된다 (한 채널에 동시 호출 1개)") {
                slowIds.forEach { waitSent(it, Duration.ofSeconds(10)) }
                slowIds.map { status(it) } shouldBe listOf("SENT", "SENT", "SENT")
                lanes.busyChannels() shouldBe emptySet()
            }
        }
    }
})

// 레인 자체의 규칙 (스프링 없이)
class ChannelLanesUnitTest : BehaviorSpec({

    Given("바쁜 채널 상한 2") {
        val gate = CountDownLatch(1)
        val lanes = ChannelLanes(Executors.newCachedThreadPool(), maxBusy = 2, shutdownWait = Duration.ofSeconds(5))

        When("두 채널이 보내는 중이면") {
            lanes.submit(1) { gate.await(5, TimeUnit.SECONDS) }
            lanes.submit(2) { gate.await(5, TimeUnit.SECONDS) }

            Then("새로 선점할 자리가 없다 (pollOnce 가 쉰다)") {
                lanes.hasRoom() shouldBe false
                lanes.busyChannels() shouldBe setOf(1L, 2L)
            }

            Then("끝나면 자리가 돌아온다") {
                gate.countDown()
                val start = System.nanoTime()
                while (lanes.busyChannels().isNotEmpty() && System.nanoTime() - start < 5_000_000_000) Thread.sleep(10)
                lanes.hasRoom() shouldBe true
            }
        }

        When("발송 중 예외가 나면") {
            val done = CountDownLatch(1)
            lanes.submit(3) { try { error("주입한 장애") } finally { done.countDown() } }
            done.await(5, TimeUnit.SECONDS)
            val start = System.nanoTime()
            while (lanes.busyChannels().isNotEmpty() && System.nanoTime() - start < 5_000_000_000) Thread.sleep(10)

            Then("그 채널을 바쁨에서 풀어 다음 선점에 다시 잡힌다") {
                lanes.busyChannels() shouldBe emptySet()
            }
        }

        When("종료한 뒤 맡기면") {
            lanes.close()
            lanes.submit(4) { error("돌면 안 된다") }

            Then("맡지 않고 바쁨으로도 남기지 않는다 — 선점한 줄은 임대가 끝나면 회수된다") {
                lanes.busyChannels() shouldBe emptySet()
            }
        }
    }
})
