package com.monimo.api.common.config

import com.monimo.api.support.TestInfraConfig
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.QueryTimeoutException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

// application.yml 의 ClickHouse 시간 상한이 실제로 쿼리를 끊고, 503 으로 가는 QueryTimeoutException 이 되는지 본다 (#287).
// 기다리는 시간을 줄이려고 상한만 짧게 덮어쓴다 (서버 1초, 기다림 3초)
@SpringBootTest(
    properties = [
        "monimo.clickhouse.data-source-properties.clickhouse_setting_max_execution_time=1",
        "monimo.clickhouse-wait-timeout=3s",
    ],
)
@Import(TestInfraConfig::class)
class ClickHouseQueryTimeoutTest(
    @Qualifier("clickHouseJdbcTemplate") clickHouse: NamedParameterJdbcTemplate,
) : BehaviorSpec({

    // 끝나지 않는 쿼리. system.numbers 는 끝없이 숫자를 내놓는다
    val endless = "SELECT count() FROM system.numbers"

    fun elapsedMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    Given("ClickHouse 시간 상한이 걸린 API server") {
        Then("서버 상한이 쿼리 세션에 들어간다") {
            clickHouse.jdbcTemplate.queryForObject("SELECT getSetting('max_execution_time')", Double::class.java) shouldBe 1.0
        }

        When("쿼리가 서버 상한을 넘으면") {
            val ms = elapsedMs { shouldThrow<QueryTimeoutException> { clickHouse.jdbcTemplate.queryForObject(endless, Long::class.java) } }

            Then("서버가 한 번에 끊는다 (드라이버가 다시 보내지 않는다)") {
                ms shouldBeLessThan 2_500
            }
        }

        When("서버가 대답을 안 하면 (서버 상한을 이 쿼리만 끈 경우)") {
            val ms = elapsedMs {
                shouldThrow<QueryTimeoutException> { clickHouse.jdbcTemplate.queryForObject("$endless SETTINGS max_execution_time = 0", Long::class.java) }
            }
            // 서버에 남은 쿼리를 치운다. 같은 컨테이너를 쓰는 다른 테스트가 느려지지 않게
            clickHouse.jdbcTemplate.execute("KILL QUERY WHERE query LIKE '%max_execution_time = 0%' AND query NOT LIKE '%KILL QUERY%' SYNC")

            Then("요청 스레드가 기다리다 끊는다") {
                ms shouldBeLessThan 6_000
            }
        }
    }
})
