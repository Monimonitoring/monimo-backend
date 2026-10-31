package com.monimo.api.selfcheck

import com.monimo.api.selfcheck.dto.AgeCheck
import com.monimo.api.selfcheck.dto.OutboxCheck
import com.monimo.api.selfcheck.dto.PostgresCheck
import com.monimo.api.selfcheck.dto.SelfCheckResponse
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource

// PG 두드림 + Detector · Collector · Notifier 낡음 게이지 + 발송 큐 적체. Watchdog 이 밖에서
// "PG 가 죽었다 · Detector 가 멈췄다 · Slack 으로 안 나간다" 를 볼 유일한 문 (#226 · #267, #208 ⑥)
@Service
class SelfCheckService(
    dataSource: DataSource,
    private val gauges: GaugeReader,
    private val props: SelfCheckProperties,
) {
    private val jdbc = JdbcTemplate(dataSource)

    // PG 가 죽으면 연결을 얻는 데 Hikari connectionTimeout(30초)까지 걸린다. Watchdog 이 그만큼 기다리게 하지 않으려고
    // 다른 스레드에서 두드리고 기한만큼만 기다린다. 기한을 넘긴 스레드는 Hikari 가 포기할 때 알아서 끝난다
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "self-check").apply { isDaemon = true } }

    // 다섯을 동시에 : 차례로 하면 PG 기한 + 게이지 기한이 더해져 Watchdog 의 호출 기한(10초)을 넘긴다
    fun check(): SelfCheckResponse {
        val detector = CompletableFuture.supplyAsync({ age(props.detectorManagementUrl, DETECTOR_GAUGE, props.detectorMaxAge) }, executor)
        val collector = CompletableFuture.supplyAsync({ age(props.collectorManagementUrl, COLLECTOR_GAUGE, props.collectorMaxAge) }, executor)
        val notifier = CompletableFuture.supplyAsync({ age(props.notifierManagementUrl, NOTIFIER_GAUGE, props.notifierMaxAge) }, executor)
        val outbox = CompletableFuture.supplyAsync({ outbox() }, executor)
        return SelfCheckResponse(
            postgres = pingPostgres(),
            detector = detector.join(),
            collector = collector.join(),
            notifier = notifier.join(),
            outbox = outbox.join(),
        )
    }

    private fun pingPostgres(): PostgresCheck {
        val started = System.nanoTime()
        val elapsed = { (System.nanoTime() - started) / 1_000_000 }
        val future = CompletableFuture.supplyAsync({ jdbc.queryForObject("SELECT 1", Int::class.java) }, executor)
        return try {
            future.get(props.postgresTimeout.toMillis(), TimeUnit.MILLISECONDS)
            PostgresCheck(ok = true, ms = elapsed())
        } catch (e: TimeoutException) {
            PostgresCheck(ok = false, ms = elapsed(), error = "timeout")
        } catch (e: ExecutionException) {
            PostgresCheck(ok = false, ms = elapsed(), error = (e.cause ?: e).javaClass.simpleName)
        }
    }

    // 발송 큐를 직접 본다 : Notifier 가 돌아도 Slack 이 안 받으면 줄이 PENDING 에 남아 재시도되다 FAILED 가 된다.
    // 게이지(큐 읽기 성공)로는 이것을 못 잡는다. 표 주인은 알림 파트, 여기서는 읽기만 한다 (#267)
    private fun outbox(): OutboxCheck {
        val max = props.outboxMaxPending.seconds
        val future = CompletableFuture.supplyAsync({
            jdbc.queryForMap(
                """
                SELECT (SELECT EXTRACT(EPOCH FROM (now() - MIN(created_at)))::bigint
                          FROM notification_outbox WHERE status IN ('PENDING', 'IN_FLIGHT')) AS oldest_pending_sec,
                       (SELECT COUNT(*) FROM notification_outbox
                         WHERE status = 'FAILED' AND updated_at >= now() - make_interval(secs => ?)) AS failed_recent
                """.trimIndent(),
                props.outboxFailedWindow.seconds.toDouble(),
            )
        }, executor)
        return try {
            val row = future.get(props.postgresTimeout.toMillis(), TimeUnit.MILLISECONDS)
            val oldest = (row["oldest_pending_sec"] as Number?)?.toLong()
            val failed = (row["failed_recent"] as Number).toLong()
            // FAILED 도 ok=false : 웹훅이 틀리면 줄이 75초 안에 재시도 한도에 닿아 FAILED 가 되므로 PENDING 나이만 보면 180초를 못 넘기고 놓친다 (로컬 실측)
            OutboxCheck(ok = (oldest ?: 0) <= max && failed == 0L, oldestPendingSec = oldest, failedRecent = failed, maxSec = max)
        } catch (e: TimeoutException) {
            OutboxCheck(ok = false, oldestPendingSec = null, failedRecent = 0, maxSec = max, error = "timeout")
        } catch (e: ExecutionException) {
            OutboxCheck(ok = false, oldestPendingSec = null, failedRecent = 0, maxSec = max, error = (e.cause ?: e).javaClass.simpleName)
        }
    }

    private fun age(managementUrl: String, gauge: String, max: Duration): AgeCheck {
        val value = gauges.read(managementUrl, gauge)
            ?: return AgeCheck(ok = false, ageSec = null, maxSec = max.seconds, error = "unreachable")
        return AgeCheck(ok = value <= max.seconds, ageSec = value, maxSec = max.seconds)
    }

    private companion object {
        // Detector · Collector · Notifier 가 ADR #54 로 내놓는 "마지막 성공 뒤 흐른 초"
        const val DETECTOR_GAUGE = "monimo.detector.evaluation.age"
        const val COLLECTOR_GAUGE = "monimo.collector.sampling.refresh.age"
        const val NOTIFIER_GAUGE = "monimo.notifier.delivery.poll.age"
    }
}
