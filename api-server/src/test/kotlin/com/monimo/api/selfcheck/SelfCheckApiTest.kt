package com.monimo.api.selfcheck

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.alert.channel.AlertChannel
import com.monimo.api.alert.channel.AlertChannelRepository
import com.monimo.api.alert.channel.ChannelType
import com.monimo.api.alert.rule.AlertOperator
import com.monimo.api.alert.rule.AlertRule
import com.monimo.api.alert.rule.AlertRuleRepository
import com.monimo.api.alert.rule.Severity
import com.monimo.api.config.Application
import com.monimo.api.config.ApplicationRepository
import com.monimo.api.support.TestInfraConfig
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

// 가짜 관리 포트 한 대. /actuator/metrics/{게이지} 에 정해 둔 값으로 답한다. value 가 null 이면 404 (게이지 없음 · 모듈 죽음)
class FakeGauge : AutoCloseable {
    @Volatile var value: Double? = 0.0

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/actuator/metrics/") { ex ->
            val v = value
            val bytes = if (v == null) ByteArray(0) else """{"name":"x","measurements":[{"statistic":"VALUE","value":$v}]}""".toByteArray()
            ex.sendResponseHeaders(if (v == null) 404 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl get() = "http://127.0.0.1:${server.address.port}"
    override fun close() = server.stop(0)
}

class FakeGauges : AutoCloseable {
    val detector = FakeGauge()
    val collector = FakeGauge()
    val notifier = FakeGauge()
    override fun close() { detector.close(); collector.close(); notifier.close() }
}

@TestConfiguration(proxyBeanMethods = false)
class SelfCheckTestConfig {
    @Bean(destroyMethod = "close") fun fakeGauges() = FakeGauges()

    // 실제 HTTP 읽기(HttpGaugeReader)를 쓰되 주소만 게이지 이름으로 가짜 셋에 돌린다
    @Bean @Primary
    fun testGaugeReader(fakes: FakeGauges, props: SelfCheckProperties, objectMapper: ObjectMapper): GaugeReader {
        val http = HttpGaugeReader(props.gaugeTimeout, objectMapper)
        return GaugeReader { _, gauge ->
            val fake = when {
                gauge.startsWith("monimo.detector") -> fakes.detector
                gauge.startsWith("monimo.notifier") -> fakes.notifier
                else -> fakes.collector
            }
            http.read(fake.baseUrl, gauge)
        }
    }
}

// 자기 점검 문 (#226 · #267). 게이지는 가짜 관리 포트 3대, 발송 큐는 진짜 PG 표에 줄을 넣어 보고, PG 는 Testcontainers 라 마지막 Given 에서 멈출 수 있다
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // PG 기한 2초는 Hikari 를 3초로 줄인 것보다 짧다 : 기한이 먼저 와야 "timeout" 이 나온다
    properties = [
        "monimo.internal-token=test-internal-token",
        "spring.datasource.hikari.connection-timeout=3000",
        "monimo.self-check.postgres-timeout=2s",
    ],
)
@Import(TestInfraConfig::class, SelfCheckTestConfig::class)
class SelfCheckApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    fakes: FakeGauges,
    postgres: PostgreSQLContainer<*>,
    applications: ApplicationRepository,
    rules: AlertRuleRepository,
    channels: AlertChannelRepository,
    dataSource: DataSource,
) : BehaviorSpec({

    val jdbc = JdbcTemplate(dataSource)

    // 발송 큐 줄 하나에 필요한 부모 : 서비스 → 규칙 → 사건, 채널. 표는 알림 파트 것이라 읽을 때처럼 SQL 로 넣는다
    fun outboxRow(status: String, createdAgoSec: Long): Long {
        val now = Instant.now()
        val app = applications.save(Application(UUID.randomUUID(), "self-check-${UUID.randomUUID()}", null, null, now, now))
        val rule = rules.save(AlertRule(UUID.randomUUID(), app, "5xx", "5XX_RATE", AlertOperator.GT, BigDecimal("5"), 60, Severity.CRITICAL, true, 1, now, now))
        val channel = channels.save(AlertChannel(UUID.randomUUID(), "slack-${UUID.randomUUID()}", ChannelType.SLACK, mapOf("webhook_url" to "https://hooks.slack.com/services/T/B/x"), true, now, now))
        val eventUuid = UUID.randomUUID()
        val eventId = jdbc.queryForObject(
            """
            INSERT INTO alert_events (alert_event_uuid, alert_rule_id, fingerprint, state, observed_value, fired_at,
                                      rule_version, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, ?, 'FIRING', 9.9, ?, 1, '5XX_RATE', 'GT', 5, 60, 'CRITICAL') RETURNING id
            """.trimIndent(),
            Long::class.java, eventUuid, rule.id, eventUuid.toString().replace("-", ""), Timestamp.from(now),
        )!!
        val created = Timestamp.from(now.minusSeconds(createdAgoSec))
        return jdbc.queryForObject(
            """
            INSERT INTO notification_outbox (alert_event_id, transition, alert_channel_id, payload, status, next_attempt_at, created_at, updated_at)
            VALUES (?, 'FIRING', ?, '{}'::jsonb, ?, ?, ?, ?) RETURNING id
            """.trimIndent(),
            Long::class.java, eventId, channel.id, status, Timestamp.from(now), created, Timestamp.from(now),
        )!!
    }

    fun call(token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/self-check", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }
    fun data(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)["data"]

    Given("PG 가 붙어 있고 Detector · Collector · Notifier 게이지가 작고 발송 큐는 비어 있다") {
        fakes.detector.value = 12.3
        fakes.collector.value = 8.0
        fakes.notifier.value = 3.0

        When("내부 토큰으로 부르면") {
            val response = call()
            val d = data(response)

            Then("200 이고 다섯 다 ok, PG 는 ms 가 있고 게이지 값이 그대로 오고, 큐는 가장 오래된 줄 없음 · 실패 0") {
                response.statusCode.value() shouldBe 200
                d["postgres"]["ok"].asBoolean() shouldBe true
                d["postgres"]["ms"].asLong() shouldBeLessThan 2000
                d["detector"]["ok"].asBoolean() shouldBe true
                d["detector"]["age_sec"].asDouble() shouldBe 12.3
                d["detector"]["max_sec"].asLong() shouldBe 180
                d["collector"]["ok"].asBoolean() shouldBe true
                d["collector"]["age_sec"].asDouble() shouldBe 8.0
                d["notifier"]["ok"].asBoolean() shouldBe true
                d["notifier"]["age_sec"].asDouble() shouldBe 3.0
                d["outbox"]["ok"].asBoolean() shouldBe true
                d["outbox"]["oldest_pending_sec"].isNull shouldBe true
                d["outbox"]["failed_recent"].asLong() shouldBe 0
                d["outbox"]["max_sec"].asLong() shouldBe 180
            }
        }

        When("토큰 없이 부르면") {
            Then("401") {
                call(token = null).statusCode.value() shouldBe 401
            }
        }
    }

    Given("Detector 게이지가 180초를 넘고 Collector 게이지는 못 읽고 Notifier 게이지도 180초를 넘는다") {
        fakes.detector.value = 251.0
        fakes.collector.value = null
        fakes.notifier.value = 412.0

        When("부르면") {
            val response = call()
            val d = data(response)

            Then("HTTP 는 200 그대로, Detector · Notifier 는 ok=false 에 값, Collector 는 ok=false 에 unreachable") {
                response.statusCode.value() shouldBe 200
                d["detector"]["ok"].asBoolean() shouldBe false
                d["detector"]["age_sec"].asDouble() shouldBe 251.0
                d["collector"]["ok"].asBoolean() shouldBe false
                d["collector"]["age_sec"].isNull shouldBe true
                d["collector"]["error"].asText() shouldBe "unreachable"
                d["notifier"]["ok"].asBoolean() shouldBe false
                d["notifier"]["age_sec"].asDouble() shouldBe 412.0
            }
        }
    }

    Given("발송 큐에 5분 전부터 PENDING 인 줄과 방금 FAILED 가 된 줄이 있다 (Notifier 게이지는 작다)") {
        fakes.detector.value = 5.0
        fakes.collector.value = 5.0
        fakes.notifier.value = 2.0
        outboxRow("PENDING", createdAgoSec = 300)
        outboxRow("FAILED", createdAgoSec = 60)

        When("부르면") {
            val d = data(call())

            Then("notifier 는 ok 인데 outbox 는 ok=false, 가장 오래된 줄 300초 이상, 최근 실패 1") {
                d["notifier"]["ok"].asBoolean() shouldBe true
                d["outbox"]["ok"].asBoolean() shouldBe false
                d["outbox"]["oldest_pending_sec"].asLong() shouldBeGreaterThanOrEqualTo 300
                d["outbox"]["failed_recent"].asLong() shouldBe 1
            }
        }

        When("PENDING 줄만 SENT 로 바뀌고 FAILED 는 남은 채 부르면") {
            jdbc.update("UPDATE notification_outbox SET status = 'SENT', updated_at = now() WHERE status = 'PENDING'")
            val d = data(call())

            // 웹훅이 틀리면 줄이 75초 안에 FAILED 가 되어 PENDING 나이로는 못 잡는다. FAILED 가 있으면 그것만으로 ok=false
            Then("가장 오래된 줄은 없지만 최근 실패 1 이라 outbox 는 아직 ok=false") {
                d["outbox"]["ok"].asBoolean() shouldBe false
                d["outbox"]["oldest_pending_sec"].isNull shouldBe true
                d["outbox"]["failed_recent"].asLong() shouldBe 1
            }
        }

        When("FAILED 줄도 SENT 로 바뀐 뒤 부르면") {
            jdbc.update("UPDATE notification_outbox SET status = 'SENT', updated_at = now()")
            val d = data(call())

            Then("outbox 는 다시 ok, 가장 오래된 줄 없음, 실패 0") {
                d["outbox"]["ok"].asBoolean() shouldBe true
                d["outbox"]["oldest_pending_sec"].isNull shouldBe true
                d["outbox"]["failed_recent"].asLong() shouldBe 0
            }
        }
    }

    Given("PostgreSQL 이 멈췄다") {
        fakes.detector.value = 5.0
        fakes.collector.value = 5.0
        fakes.notifier.value = 5.0
        postgres.stop()

        When("부르면") {
            val started = System.currentTimeMillis()
            val response = call()
            val took = System.currentTimeMillis() - started
            val d = data(response)

            // 실패 모양은 환경마다 다르다 : 로컬은 재시도하다 기한에 걸려 timeout, CI 러너는 풀의 연결이 즉시 닫혀 예외 이름이 온다.
            // Watchdog 에게 중요한 것은 "기한 안에 200 으로 답하고 ok=false" 뿐이라 둘 다 통과시킨다
            Then("기한 안에 200 으로 답하고 postgres · outbox(PG 를 읽는 둘)만 ok=false 에 error 가 있고, 게이지 셋은 그대로 ok") {
                response.statusCode.value() shouldBe 200
                took shouldBeLessThan 10_000
                d["postgres"]["ok"].asBoolean() shouldBe false
                d["postgres"]["error"].isNull shouldBe false
                d["outbox"]["ok"].asBoolean() shouldBe false
                d["outbox"]["error"].isNull shouldBe false
                d["detector"]["ok"].asBoolean() shouldBe true
                d["collector"]["ok"].asBoolean() shouldBe true
                d["notifier"]["ok"].asBoolean() shouldBe true
            }
        }
    }
})
