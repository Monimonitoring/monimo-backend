package com.monimo.detector.alert.evaluate

import com.monimo.detector.support.MutableClock
import com.monimo.detector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

private val C0: Instant = Instant.parse("2026-10-29T10:00:00Z")

// 조회 파트 cpu-heap 대신 쓰는 가짜. (파드 키, 1분 시작) 마다 CPU % · heap % 를 정해 두면 그 줄만 돌려준다 — 안 정한 1분은 줄이 없다
class FakeCpuHeap : CpuHeapClient {
    @Volatile var fail = false
    val byMinute = mutableMapOf<Pair<String, Instant>, Pair<Double?, Double?>>()

    override fun fetch(from: Instant, to: Instant): List<CpuHeapBucket> {
        if (fail) throw CpuHeapQueryException("주입한 장애: cpu-heap 500")
        return byMinute.filterKeys { (_, start) -> !start.isBefore(from) && start.isBefore(to) }
            .map { (k, v) -> CpuHeapBucket("ch-order", k.first, k.second, v.first?.toBigDecimal(), v.second?.toBigDecimal()) }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class CpuHeapTestConfig {
    @Bean @Primary fun testClock() = MutableClock(C0)
    @Bean @Primary fun fakeCpuHeap() = FakeCpuHeap()
    @Bean @Primary fun fakeThreadDump() = FakeThreadDump()
    @Bean @Primary fun directAutoDumpLauncher(dumps: FakeThreadDump) = AutoDumpLauncher(dumps, DirectExecutorService())
}

// 실제 PG + 가짜 cpu-heap · 가짜 덤프 문. 한 서비스에 CPU · HEAP 규칙을 하나씩 걸고 판정 · 사건은 파드마다 (docs/alert/110-cpu-heap.md). N=3 · M=2
@SpringBootTest(properties = ["monimo.alert.schedule.enabled=false"])
@Import(TestInfraConfig::class, CpuHeapTestConfig::class)
class CpuHeapRunnerTest(
    runner: CpuHeapRunner,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeCpuHeap,
    dumps: FakeThreadDump,
) : BehaviorSpec({

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('ch-order') RETURNING id", Long::class.java)!!
    val channelId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('ch-ch', 'SLACK', '{}'::jsonb, true) RETURNING id", Long::class.java,
    )!!
    fun rule(name: String, kind: String, threshold: Int): Long {
        val id = jdbc.queryForObject(
            """
            INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
            VALUES (?, ?, ?, ?, 'GT', ?, 60, 'WARNING') RETURNING id
            """,
            Long::class.java, UUID.randomUUID(), appId, name, kind, threshold,
        )!!
        jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", id, channelId)
        return id
    }
    val cpuRule = rule("주문 CPU 과다", "CPU", 80)
    val heapRule = rule("주문 HEAP 과다", "HEAP", 90)

    fun register(key: String, status: String = "UNKNOWN") = jdbc.queryForObject(
        "INSERT INTO agents (application_id, agent_key, status) VALUES (?, ?, ?) RETURNING agent_uuid", UUID::class.java, appId, key, status,
    )!!
    val podA = register("ch-a")
    register("ch-b")
    register("ch-old", status = "DOWN")
    fun agentId(key: String) = jdbc.queryForObject("SELECT id FROM agents WHERE agent_key = ?", Long::class.java, key)!!

    fun minute(m: Long): Instant = C0.plus(Duration.ofMinutes(m))
    fun put(key: String, m: Long, cpu: Double?, heap: Double?) { fake.byMinute[key to minute(m)] = cpu to heap }
    // m 분 버킷이 끝나고 settle(30초)이 지난 시각으로 시계를 옮긴 뒤 한 주기 돈다
    fun tickAfter(m: Long): CpuHeapRunner.Summary {
        clock.now = minute(m + 1).plusSeconds(35)
        return runner.runOnce()
    }
    fun events(ruleId: Long, key: String, state: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_events WHERE alert_rule_id = ? AND agent_id = ? AND state = ?", Int::class.java, ruleId, agentId(key), state,
    )!!
    fun stateRows(key: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_evaluation_states WHERE agent_id = ?", Int::class.java, agentId(key),
    )!!
    fun unknownStreak(ruleId: Long, key: String) = jdbc.queryForObject(
        "SELECT consecutive_unknown FROM alert_evaluation_states WHERE alert_rule_id = ? AND agent_id = ?", Int::class.java, ruleId, agentId(key),
    )!!
    fun lastFiringPayload(ruleId: Long, key: String) = jdbc.queryForObject(
        """
        SELECT o.payload::text FROM notification_outbox o JOIN alert_events e ON e.id = o.alert_event_id
        WHERE e.alert_rule_id = ? AND e.agent_id = ? AND o.transition = 'FIRING' ORDER BY o.id DESC LIMIT 1
        """,
        String::class.java, ruleId, agentId(key),
    )!!

    Given("파드 A 는 CPU 95% 로 3분, B 는 heap 95% 로 3분 (서로 다른 지표만 넘는다)") {
        (0L..2L).forEach { put("ch-a", it, 95.0, 40.0); put("ch-b", it, 10.0, 95.0) }

        When("매 분 한 주기씩 돌면") {
            val summaries = (0L..2L).map { tickAfter(it) }

            Then("세 번째 버킷에서 A 는 CPU 만, B 는 HEAP 만 발화한다 (N=3, 파드 × 규칙마다 따로)") {
                summaries.map { it.fired } shouldBe listOf(0, 0, 2)
                events(cpuRule, "ch-a", "FIRING") shouldBe 1
                events(heapRule, "ch-a", "FIRING") shouldBe 0
                events(cpuRule, "ch-b", "FIRING") shouldBe 0
                events(heapRule, "ch-b", "FIRING") shouldBe 1
            }

            Then("발화한 파드마다 스레드 덤프를 한 번 부른다") {
                dumps.calls.size shouldBe 2
                dumps.calls shouldContain podA
                summaries.last().dumps shouldBe 2
            }

            Then("알림 payload 에 지표 종류 · 파드 이름표가 실린다") {
                val payload = lastFiringPayload(heapRule, "ch-b")
                payload shouldContain "\"metric_kind\": \"HEAP\""
                payload shouldContain "\"agent_key\": \"ch-b\""
            }

            Then("DOWN 파드는 판정하지 않는다 (상태 줄을 만들지 않는다)") {
                stateRows("ch-old") shouldBe 0
            }
        }
    }

    Given("A 의 CPU 는 정상으로 돌아오고, heap limit 을 안 보내 heap_pct 가 null 이다") {
        (3L..4L).forEach { put("ch-a", it, 20.0, null); put("ch-b", it, 10.0, 95.0) }

        When("두 주기를 돌면") {
            val summaries = (3L..4L).map { tickAfter(it) }

            Then("A 의 CPU 경보는 두 번째에서 해제된다 (M=2)") {
                summaries.map { it.resolved } shouldBe listOf(0, 1)
                events(cpuRule, "ch-a", "RESOLVED") shouldBe 1
            }

            Then("null 인 heap 은 0% 로 치지 않고 판정 불가로 센다") {
                unknownStreak(heapRule, "ch-a") shouldBe 2
            }
        }
    }

    Given("B 가 사라지고 A 가 새로 heap 과다인데 덤프 문이 실패한다") {
        dumps.fail = true
        (5L..7L).forEach { put("ch-a", it, 20.0, 99.0) }

        When("세 주기를 돌면") {
            val summaries = (5L..7L).map { tickAfter(it) }

            Then("덤프 실패와 상관없이 A 의 HEAP 사건 · 알림은 나간다 (실패는 덤프 스레드 로그에만 남는다)") {
                summaries.last().fired shouldBe 1
                summaries.last().dumps shouldBe 1
                summaries.last().dumpsDropped shouldBe 0
                events(heapRule, "ch-a", "FIRING") shouldBe 1
            }

            Then("행이 아예 없는 B 의 1분도 판정 불가로 센다 (발화 중 경보를 그 자리에서 닫지 않는다)") {
                unknownStreak(heapRule, "ch-b") shouldBe 3
                events(heapRule, "ch-b", "RESOLVED") shouldBe 0
            }
        }
    }

    Given("cpu-heap 조회가 실패하면") {
        dumps.fail = false
        fake.fail = true

        When("한 주기를 돌면") {
            val summary = tickAfter(8)

            Then("사건을 만들지 않고 판정 불가로 센다 (정상으로 치환하지 않는다)") {
                summary.queryFailures shouldBe 1
                summary.fired shouldBe 0
            }
        }
    }
})

