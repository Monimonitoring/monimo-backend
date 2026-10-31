package com.monimo.api.query.metric

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.monimo.api.support.TestInfraConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.context.annotation.Import
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant

// metrics_raw 에 CPU · 메모리 줄을 넣고, Detector 용 내부 문이 파드 · 1분마다 CPU % · heap 합을 내는지 본다
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["monimo.internal-token=test-internal-token"],
)
@Import(TestInfraConfig::class)
class CpuHeapApiTest(
    rest: TestRestTemplate,
    objectMapper: ObjectMapper,
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    // 1분 경계에 맞춘 30분 전 시각
    val t0 = Instant.ofEpochSecond((Instant.now().epochSecond - 1800) / 60 * 60)

    fun point(offsetSec: Long, name: String, value: Double, hash: Int, type: String = "", agent: String = "pod-a", service: String = "ch-order") {
        val attributes = if (type.isEmpty()) "map()" else "map('jvm.memory.type', '$type', 'jvm.memory.pool.name', 'pool-$hash')"
        clickHouse.jdbcTemplate.update(
            """
            INSERT INTO metrics_raw (service_name, agent_id, metric_name, series_hash, attributes, ts, value)
            VALUES ('$service', '$agent', '$name', $hash, $attributes, toDateTime(${t0.plusSeconds(offsetSec).epochSecond}, 'UTC'), $value)
            """.trimIndent(),
        )
    }

    val cpu = "jvm.cpu.recent_utilization"
    val used = "jvm.memory.used"
    val limit = "jvm.memory.limit"

    // pod-a 0분: CPU 0.2 · 0.4, heap Eden 100 → 150 · Old 300, limit(Old) 1000, non_heap 999
    point(10, cpu, 0.2, 1)
    point(40, cpu, 0.4, 1)
    point(10, used, 100.0, 2, "heap")
    point(40, used, 150.0, 2, "heap")
    point(40, used, 300.0, 3, "heap")
    point(40, limit, 1000.0, 4, "heap")
    point(40, used, 999.0, 5, "non_heap")
    // 1분은 점 없음(앞 점과 90초 → 채움). 2분: CPU 0.5, Eden 200 · Old 300
    point(130, cpu, 0.5, 1)
    point(130, used, 200.0, 2, "heap")
    point(130, used, 300.0, 3, "heap")
    point(130, limit, 1000.0, 4, "heap")
    // 3 · 4분은 점 없음(앞 점과 200초 → 안 채움). 5분: CPU 만
    point(330, cpu, 0.1, 1)
    // pod-b : limit 을 안 보낸다
    point(20, cpu, 0.9, 1, agent = "pod-b")
    point(20, used, 500.0, 2, "heap", agent = "pod-b")
    // 다른 서비스
    point(20, cpu, 0.7, 1, agent = "pod-p", service = "ch-payment")

    fun get(query: String, token: String? = "test-internal-token"): ResponseEntity<String> {
        val headers = HttpHeaders().apply { token?.let { set("X-Internal-Token", it) } }
        return rest.exchange("/api/v1/internal/metrics/cpu-heap?$query", HttpMethod.GET, HttpEntity<Void>(headers), String::class.java)
    }

    fun json(response: ResponseEntity<String>): JsonNode = objectMapper.readTree(response.body)

    fun minute(n: Long) = t0.plusSeconds(n * 60).toString()

    Given("Detector 가 CPU · HEAP 재료를 읽을 때") {
        When("서비스를 주면") {
            val response = get("service_name=ch-order&from=$t0&to=${t0.plusSeconds(360)}")
            val rows = json(response)["data"]
            fun row(min: Long, agent: String = "pod-a") = rows.first { it["ts_min"].asText() == minute(min) && it["agent_key"].asText() == agent }

            Then("파드 · 1분마다 한 줄, 시각 순") {
                response.statusCode.value() shouldBe 200
                rows.map { it["ts_min"].asText() to it["agent_key"].asText() } shouldBe listOf(
                    minute(0) to "pod-a", minute(0) to "pod-b", minute(1) to "pod-a", minute(2) to "pod-a", minute(5) to "pod-a",
                )
                row(0)["service_name"].asText() shouldBe "ch-order"
            }

            Then("CPU 는 1분 평균을 % 로, heap 은 풀마다 마지막 값을 더한다 (non_heap 은 빠진다)") {
                row(0)["cpu_pct"].asDouble() shouldBe 30.0
                row(0)["heap_used_bytes"].asLong() shouldBe 450
                row(0)["heap_limit_bytes"].asLong() shouldBe 1000
                row(0)["heap_pct"].asDouble() shouldBe 45.0
                row(2)["cpu_pct"].asDouble() shouldBe 50.0
                row(2)["heap_pct"].asDouble() shouldBe 50.0
            }

            Then("두 점이 150초 안이면 사이의 빈 1분을 앞 값으로 채운다") {
                row(1)["cpu_pct"].asDouble() shouldBe 30.0
                row(1)["heap_used_bytes"].asLong() shouldBe 450
            }

            Then("150초보다 멀면 채우지 않는다") {
                rows.none { it["ts_min"].asText() == minute(3) || it["ts_min"].asText() == minute(4) } shouldBe true
            }

            Then("안 온 값은 null : heap 없는 1분, limit 없는 파드") {
                row(5)["cpu_pct"].asDouble() shouldBe 10.0
                row(5)["heap_used_bytes"].isNull shouldBe true
                row(5)["heap_pct"].isNull shouldBe true
                row(0, "pod-b")["heap_used_bytes"].asLong() shouldBe 500
                row(0, "pod-b")["heap_limit_bytes"].isNull shouldBe true
                row(0, "pod-b")["heap_pct"].isNull shouldBe true
            }
        }

        When("범위 첫 1분이 비어 있으면") {
            Then("범위 앞의 점으로 채운다") {
                val rows = json(get("service_name=ch-order&from=${t0.plusSeconds(60)}&to=${t0.plusSeconds(120)}"))["data"]
                rows.map { it["ts_min"].asText() } shouldBe listOf(minute(1))
                rows[0]["heap_pct"].asDouble() shouldBe 45.0
            }
        }

        When("서비스를 안 주면") {
            Then("모든 서비스의 파드가 온다") {
                val rows = json(get("from=$t0&to=${t0.plusSeconds(60)}"))["data"].filter { it["service_name"].asText().startsWith("ch-") }
                rows.map { it["service_name"].asText() to it["agent_key"].asText() }.toSet() shouldBe
                    setOf("ch-order" to "pod-a", "ch-order" to "pod-b", "ch-payment" to "pod-p")
            }
        }

        When("from 이 to 보다 늦으면") {
            Then("422 UNPROCESSABLE") {
                get("from=${t0.plusSeconds(60)}&to=$t0").statusCode.value() shouldBe 422
            }
        }

        When("내부 토큰이 없으면") {
            Then("401") {
                get("from=$t0&to=${t0.plusSeconds(60)}", token = null).statusCode.value() shouldBe 401
            }
        }
    }
})
