package com.monimo.api.query.metric

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.GcTimeResponse
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

// GcTimeService 의 계산 계약을 DB 없이 고정 입력으로 확인한다 (블로그 「GC 가 10% 를 넘으면」 근거 a ~ g).
// 가짜 저장소는 GcTimeRepository SQL 과 같은 일(1분마다 마지막 값 · 그 시각)만 한다.
// "현재 동작" 이라고 적은 Then 은 의도한 계약과 다른 지금 동작을 기록한 것이다 — 고치면 기대값도 같이 바꾼다
class GcTimeServiceContractTest : BehaviorSpec({

    val t0 = Instant.parse("2026-10-16T09:00:00Z")
    val sum = GcTimeRepository.TIME_METRIC
    val count = GcTimeRepository.COUNT_METRIC

    fun at(offsetSec: Long) = t0.plusSeconds(offsetSec)
    fun young(offsetSec: Long, total: Double, metric: String = sum) = RawPoint(metric, "young", at(offsetSec), total)

    // 범위 [t0, t0 + 180) 의 1분 세 개
    fun gcTime(vararg points: RawPoint): Map<Instant, GcTimeResponse> =
        GcTimeService(FakeGcTimeRepository(points.toList()))
            .list("svc", TimeRange.of(t0, at(180)))
            .associateBy { it.tsMin }

    Given("a. 60초마다 점이 오면") {
        val rows = gcTime(young(-10, 1.0), young(50, 1.1), young(110, 1.2), young(170, 1.3))
        Then("1분마다 늘어난 양 그대로 (100ms)") {
            rows.keys shouldBe setOf(t0, at(60), at(120))
            rows.values.forEach { it.gcTimeMs shouldBe (100.0 plusOrMinus 0.001) }
        }
    }

    Given("b. 한 1분이 비고 120초 동안 총합이 1초에서 9초로 늘면") {
        val rows = gcTime(young(-10, 1.0), young(110, 9.0))
        Then("8초를 120초로 나눈 60초치 4,000ms 를 빈 1분과 뒤 점의 1분 둘 다에 넣는다 (옛 계산은 t0 없음 · t0+60 에 8,000)") {
            rows.keys shouldBe setOf(t0, at(60))
            rows[t0]!!.gcTimeMs shouldBe (4000.0 plusOrMinus 0.001)
            rows[at(60)]!!.gcTimeMs shouldBe (4000.0 plusOrMinus 0.001)
        }
        Then("현재 동작: 채운 1분인지 · 원래 증가량(8초)이 얼마였는지는 응답에 없다") {
            GcTimeResponse::class.java.declaredFields.map { it.name }.toSet() shouldBe
                setOf("serviceName", "agentKey", "tsMin", "gcTimeMs", "gcCount")
        }
    }

    Given("c. 1분 칸은 이어지지만 실제 점 간격이 50초 · 70초면") {
        val rows = gcTime(young(-10, 1.0), young(40, 1.1), young(110, 1.2))
        Then("같은 0.1초라도 50초 간격은 120ms, 70초 간격은 약 85.7ms 로 60초치에 맞춘다") {
            rows[t0]!!.gcTimeMs shouldBe (120.0 plusOrMinus 0.001)
            rows[at(60)]!!.gcTimeMs shouldBe (85.714 plusOrMinus 0.001)
        }
    }

    Given("d. 두 점 간격이 150초 경계에 걸리면") {
        When("정확히 150초면") {
            val rows = gcTime(young(-10, 1.0), young(140, 1.5))
            Then("믿는다 : 사이 1분 둘과 뒤 점의 1분에 0.5초 × 60 / 150 = 200ms") {
                rows.keys shouldBe setOf(t0, at(60), at(120))
                rows.values.forEach { it.gcTimeMs shouldBe (200.0 plusOrMinus 0.001) }
            }
        }
        When("151초면") {
            val rows = gcTime(young(-10, 1.0), young(141, 1.5))
            Then("전송이 빠진 것으로 보고 세 1분 모두 비운다 (Detector 에서는 판정 불가)") {
                rows shouldBe emptyMap()
            }
        }
    }

    Given("e. 총합이 줄면 (재시작으로 0 부터 다시)") {
        val rows = gcTime(young(-10, 5.0), young(50, 0.5), young(110, 0.6))
        Then("줄어든 1분은 비우고 다음 1분부터 다시 뺀다") {
            rows.keys shouldBe setOf(at(60))
            rows[at(60)]!!.gcTimeMs shouldBe (100.0 plusOrMinus 0.001)
        }
    }

    Given("f. 같은 이름표로 재시작했는데 새 JVM 의 첫 값이 옛 값보다 크면") {
        // 옛 JVM 마지막 5.0초 → 재시작 → 새 JVM 이 첫 30초에 6.0초를 썼다
        val rows = gcTime(young(-10, 5.0), young(50, 6.0))
        Then("현재 동작: 재시작을 모르고 6.0 − 5.0 = 1,000ms 를 낸다 (OTLP 시작 시각을 저장하지 않아 구분할 근거가 없다)") {
            rows[t0]!!.gcTimeMs shouldBe (1000.0 plusOrMinus 0.001)
        }
    }

    Given("g. sum 과 count 중 한쪽 · GC 종류 중 일부만 오면") {
        When("t0+60 에 count 만 있고 sum 이 없으면") {
            val rows = gcTime(
                young(-10, 1.0), young(50, 1.1),
                young(-10, 10.0, count), young(50, 12.0, count), young(110, 14.0, count),
            )
            Then("그 1분을 비운다 → Detector 는 0ms 정상이 아니라 판정 불가로 본다") {
                rows.keys shouldBe setOf(t0)
            }
        }
        When("Young 은 오고 Old 줄만 그 1분에 없으면") {
            val rows = gcTime(
                young(-10, 1.0), young(50, 1.1),
                RawPoint(sum, "old", at(-10), 2.0), RawPoint(sum, "old", at(50), 2.5), // t0 까지만 오고 끊김
            )
            Then("t0 은 둘을 더한 600ms, 현재 동작: t0+60 은 아무 줄도 없어 비고(Young 도 끝남), 한 줄만 빠진 1분은 일부 합을 그대로 낸다") {
                rows[t0]!!.gcTimeMs shouldBe (600.0 plusOrMinus 0.001)
                val partial = gcTime(
                    young(-10, 1.0), young(50, 1.1), young(110, 1.2),
                    RawPoint(sum, "old", at(-10), 2.0), RawPoint(sum, "old", at(50), 2.5),
                )
                partial[at(60)]!!.gcTimeMs shouldBe (100.0 plusOrMinus 0.001)
            }
        }
    }

    Given("첫 점 · GC 가 한 번도 없음") {
        Then("앞 점이 없는 첫 점의 1분은 비운다") {
            gcTime(young(10, 1.0)) shouldBe emptyMap()
        }
        Then("시계열이 아예 없으면(GC 가 한 번도 안 남) 줄이 없다 — 0ms 가 아니라 판정 불가") {
            gcTime() shouldBe emptyMap()
        }
    }
})

data class RawPoint(val metric: String, val series: String, val ts: Instant, val total: Double)

// GcTimeRepository SQL 과 같은 일 : [since, to) 의 점을 시계열 · 1분마다 묶어 마지막 값과 그 시각을 돌려준다
private class FakeGcTimeRepository(private val points: List<RawPoint>) :
    GcTimeRepository(NamedParameterJdbcTemplate(JdbcTemplate())) {

    override fun findTotals(serviceName: String?, since: Instant, to: Instant): List<GcTotal> =
        points.filter { !it.ts.isBefore(since) && it.ts.isBefore(to) }
            .groupBy { Triple(it.metric, it.series, Instant.ofEpochSecond(Math.floorDiv(it.ts.epochSecond, 60L) * 60)) }
            .map { (key, rows) ->
                val last = rows.maxBy { it.ts }
                GcTotal("svc", "pod", key.first, key.second, key.third, last.ts, last.total)
            }
            .sortedWith(compareBy({ it.metricName }, { it.seriesKey }, { it.tsMin }))
}
