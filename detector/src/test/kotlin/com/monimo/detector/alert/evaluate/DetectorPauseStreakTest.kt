package com.monimo.detector.alert.evaluate

import com.monimo.detector.support.MutableClock
import com.monimo.detector.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.util.UUID

// GcTimeRunnerTest 와 시각이 겹치지 않게 며칠 뒤에서 시작한다 (같은 Spring 문맥 · 같은 DB 를 쓸 수 있다)
private val P0: Instant = Instant.parse("2026-10-20T10:00:00Z")

// Detector 가 maxStaleness(3분)보다 오래 멈췄다 돌아오면, 그 사이 버킷은 평가 자체가 빠진다 (판정 불가로도 안 들어간다).
// "N 번 연속" 은 실제로 이어진 버킷만 세야 한다 : 멈춤 전 연속을 복귀 뒤 버킷에 이어 붙이면 운영자가 받는 "3분 연속" 문구가 거짓이 된다.
// 실제 PG + 가짜 gc-time. 운영 기본 설정(AlertEvaluationProperties 기본값) 그대로. N=3 · M=2
@SpringBootTest(properties = ["monimo.alert.schedule.enabled=false"])
@Import(TestInfraConfig::class, GcTimeTestConfig::class)
class DetectorPauseStreakTest(
    runner: GcTimeRunner,
    jdbc: JdbcTemplate,
    clock: MutableClock,
    fake: FakeGcTime,
    dumps: FakeThreadDump,
) : BehaviorSpec({

    // 가짜 덤프 문은 GcTimeRunnerTest 와 같은 Spring 문맥의 빈이다. B 의 발화가 부른 덤프를 남겨 두면 그쪽 기대값이 깨진다
    afterSpec { dumps.calls.clear() }

    val appId = jdbc.queryForObject("INSERT INTO applications (name) VALUES ('gap-order') RETURNING id", Long::class.java)!!
    val channelId = jdbc.queryForObject(
        "INSERT INTO alert_channels (name, type, config, enabled) VALUES ('gap-ch', 'SLACK', '{}'::jsonb, true) RETURNING id", Long::class.java,
    )!!
    val ruleId = jdbc.queryForObject(
        """
        INSERT INTO alert_rules (alert_rule_uuid, application_id, name, metric_kind, operator, threshold, window_sec, severity)
        VALUES (?, ?, '주문 GC 과다 (멈춤 뒤)', 'GC_TIME', 'GT', 6000, 60, 'WARNING') RETURNING id
        """,
        Long::class.java, UUID.randomUUID(), appId,
    )!!
    jdbc.update("INSERT INTO alert_rule_channels (alert_rule_id, alert_channel_id) VALUES (?, ?)", ruleId, channelId)
    listOf("gap-a", "gap-b").forEach {
        jdbc.update("INSERT INTO agents (application_id, agent_key, status) VALUES (?, ?, 'UNKNOWN')", appId, it)
    }
    fun agentId(key: String) = jdbc.queryForObject("SELECT id FROM agents WHERE agent_key = ?", Long::class.java, key)!!

    fun minute(m: Long): Instant = P0.plus(Duration.ofMinutes(m))
    fun gc(key: String, m: Long, ms: Long) { fake.msByMinute[key to minute(m)] = ms }
    // m 분 버킷이 끝나고 settle(30초)이 지난 시각으로 시계를 옮긴 뒤 한 주기 돈다
    fun tickAfter(m: Long): GcTimeRunner.Summary {
        clock.now = minute(m + 1).plusSeconds(35)
        return runner.runOnce()
    }
    // 상태와 상관없이 만들어진 사건 수. A 처럼 잘못 발화한 뒤 곧 정상 2분으로 해제되면 FIRING 만 세서는 안 보인다
    fun events(key: String) = jdbc.queryForObject(
        "SELECT count(*) FROM alert_events WHERE alert_rule_id = ? AND agent_id = ?", Int::class.java, ruleId, agentId(key),
    )!!
    fun firedBucketEnd(key: String) = jdbc.queryForObject(
        "SELECT fired_at FROM alert_events WHERE alert_rule_id = ? AND agent_id = ?", java.sql.Timestamp::class.java, ruleId, agentId(key),
    )!!.toInstant()
    fun streak(key: String) = jdbc.queryForObject(
        "SELECT consecutive_bad FROM alert_evaluation_states WHERE alert_rule_id = ? AND agent_id = ?", Int::class.java, ruleId, agentId(key),
    )!!

    // 분 :   0     1  |  2     3   |  4     5     6
    // A  :  초과  초과 |  정상  정상 |  초과  정상  정상   → 실제로 3분 연속 초과한 적이 없다
    // B  :  초과  초과 |  (값 없음)  |  초과  초과  초과   → 복귀 뒤 세 분이 연속 초과
    //                 Detector 멈춤 (2 · 3분 버킷은 평가되지 않는다)
    Given("A · B 둘 다 0 · 1분에 초과하고, Detector 가 2 · 3분 동안 멈췄다 6분 뒤에 돌아온다") {
        listOf("gap-a", "gap-b").forEach { gc(it, 0, 7000); gc(it, 1, 7000) }
        gc("gap-a", 2, 100); gc("gap-a", 3, 100)
        gc("gap-a", 4, 7000); gc("gap-a", 5, 100); gc("gap-a", 6, 100)
        (4L..6L).forEach { gc("gap-b", it, 7000) }

        tickAfter(0)
        tickAfter(1)

        Then("멈추기 전 두 파드 모두 연속 2회") {
            streak("gap-a") shouldBe 2
            streak("gap-b") shouldBe 2
        }

        When("6분 버킷이 끝난 뒤 한 주기를 돌면 (maxStaleness 안의 4 · 5 · 6분 버킷만 따라잡는다)") {
            val summary = tickAfter(6)

            Then("A 는 발화하지 않는다 — 복귀 뒤 첫 초과(4분)를 멈추기 전 연속에 이어 붙이지 않는다") {
                events("gap-a") shouldBe 0
            }

            Then("B 는 복귀 뒤 세 번째 초과(6분 버킷, 7분에 끝남)에서 발화한다") {
                summary.fired shouldBe 1
                events("gap-b") shouldBe 1
                firedBucketEnd("gap-b") shouldBe minute(7)
            }
        }
    }
})
