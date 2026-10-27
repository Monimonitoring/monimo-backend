package com.monimo.api.query.metric

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

// metrics_raw 에서 CPU 사용률 · heap 메모리 줄을 파드 × 줄 × 1분마다 읽는다(평균 · 마지막 값 · 마지막 시각).
// 1분 롤업은 마지막 값의 시각이 없어 빈 1분을 채울지 판단할 수 없어 원본을 읽는다
@Repository
class CpuHeapRepository(
    @Qualifier("clickHouseJdbcTemplate") private val jdbc: NamedParameterJdbcTemplate,
) {

    fun findPoints(serviceName: String?, since: Instant, to: Instant): List<GaugePoint> {
        val serviceFilter = if (serviceName != null) "AND service_name = :serviceName" else ""
        val sql = """
            SELECT
                service_name,
                agent_id,
                metric_name,
                toUnixTimestamp(toStartOfMinute(ts)) AS minute_ts,
                toUnixTimestamp(max(ts))             AS last_ts,
                avg(value)                           AS avg_val,
                argMax(value, ts)                    AS last_val
            FROM metrics_raw
            WHERE (metric_name = :cpuMetric
                   OR (metric_name IN (:memoryMetrics) AND attributes['jvm.memory.type'] = 'heap'))
              AND ts >= toDateTime(:sinceSec) AND ts < toDateTime(:toSec) $serviceFilter
            GROUP BY service_name, agent_id, metric_name, series_hash, minute_ts
            ORDER BY service_name, agent_id, minute_ts
        """.trimIndent()
        val params = mapOf(
            "cpuMetric" to CPU_METRIC,
            "memoryMetrics" to listOf(HEAP_USED_METRIC, HEAP_LIMIT_METRIC),
            "sinceSec" to since.epochSecond,
            "toSec" to to.epochSecond,
            "serviceName" to serviceName,
        )
        return jdbc.query(sql, params) { rs, _ ->
            GaugePoint(
                serviceName = rs.getString("service_name"),
                agentKey = rs.getString("agent_id"),
                metricName = rs.getString("metric_name"),
                tsMin = Instant.ofEpochSecond(rs.getLong("minute_ts")),
                lastTs = Instant.ofEpochSecond(rs.getLong("last_ts")),
                avg = rs.getDouble("avg_val"),
                last = rs.getDouble("last_val"),
            )
        }
    }

    companion object {
        // CPU 사용률은 0 ~ 1 Gauge, 메모리는 풀마다 한 줄(Attribute jvm.memory.pool.name). 단위는 바이트
        const val CPU_METRIC = "jvm.cpu.recent_utilization"
        const val HEAP_USED_METRIC = "jvm.memory.used"
        const val HEAP_LIMIT_METRIC = "jvm.memory.limit"
    }
}

// 한 파드 · 한 줄 · 1분. lastTs 는 그 1분 안의 마지막 점 시각
data class GaugePoint(
    val serviceName: String,
    val agentKey: String,
    val metricName: String,
    val tsMin: Instant,
    val lastTs: Instant,
    val avg: Double,
    val last: Double,
)
