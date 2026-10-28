package com.monimo.detector.alert.evaluate

import com.monimo.detector.alert.record.EvaluationRecorder
import com.monimo.detector.alert.record.EvaluationTarget
import com.monimo.detector.alert.record.RuleSnapshot
import com.monimo.detector.alert.state.AlertOperator
import com.monimo.detector.alert.state.AlertTarget
import com.monimo.detector.alert.state.Condition
import com.monimo.detector.alert.state.Evaluation
import com.monimo.detector.alert.state.Outcome
import com.monimo.detector.alert.state.Transition
import com.monimo.detector.alert.state.UnknownReason
import com.monimo.detector.alert.state.Verdict
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

// CPU · HEAP 한 주기 (docs/alert/110-cpu-heap.md). GcTimeRunner 와 같은 꼴 : 규칙은 서비스에 걸고, 판정 · 사건은 그 서비스의 파드마다 따로 낸다.
//
// 값 = 그 1분의 cpu_pct(CPU) · heap_pct(HEAP), 둘 다 %. 조회(#252)가 한 응답에 둘 다 주므로 한 주기에 한 번만 부른다.
// window_sec 은 쓰지 않는다: 1분 버킷마다 판정하고 "N분 연속" 은 상태머신이 센다 (전역 N)
// 경보가 열리는 순간 그 파드의 스레드 덤프를 한 번 부른다. 같은 파드에 CPU · HEAP · GC 가 같이 열려도 API server 가 하나로 묶는다(#164)
@Component
class CpuHeapRunner(
    private val jdbc: JdbcTemplate,
    private val client: CpuHeapClient,
    private val dumps: ThreadDumpClient,
    private val recorder: EvaluationRecorder,
    private val props: EvaluationProperties,
    private val clock: Clock,
) {
    data class Summary(
        val rules: Int,
        val applied: Int,
        val fired: Int,
        val resolved: Int,
        val queryFailures: Int,
        val dumps: Int,
        val dumpFailures: Int,
    )

    private data class Pod(val id: Long, val uuid: UUID, val applicationId: Long, val agentKey: String, val status: String)

    fun runOnce(): Summary {
        val now = clock.instant()
        val rules = loadRules()
        val ends = BucketValues.settledBucketEnds(now, props.settleDelay, props.maxStaleness)
        // 규칙이 없으면 부르지 않는다 (cpu-heap 이 없는 환경에서 15초마다 실패 로그가 쌓이지 않게)
        if (rules.isEmpty() || ends.isEmpty()) return Summary(rules.size, 0, 0, 0, 0, 0, 0)

        val buckets = try {
            client.fetch(ends.first().minus(BucketValues.BUCKET), ends.last()).associateBy { it.agentKey to it.start }
        } catch (e: Exception) {
            log.warn("cpu-heap 조회 실패 : {}", e.message)
            null
        }
        val sending = buckets?.keys?.mapTo(HashSet()) { it.first }.orEmpty()
        val podsByApp = loadPods().groupBy { it.applicationId }

        var applied = 0
        var fired = 0
        var resolved = 0
        var dumped = 0
        var dumpFailures = 0
        rules.forEach { (rule, applicationId) ->
            val condition = Condition(rule.operator, rule.threshold, props.maxStaleness)
            // DOWN 파드는 판정하지 않는다 (GcTimeRunner 와 같은 이유). 단 지금 값을 보내고 있으면 판정한다
            val pods = podsByApp[applicationId].orEmpty().filter { it.status != "DOWN" || it.agentKey in sending }
            pods.forEach { pod ->
                val target = EvaluationTarget(rule, AlertTarget.Agent(pod.uuid), pod.id, pod.agentKey)
                // 조회 실패는 가장 최근 버킷 하나에만 판정 불가로 남긴다. 정상으로 치환하지 않는다
                val verdicts = if (buckets == null) {
                    listOf(Verdict.Unknown(ends.last(), UnknownReason.QUERY_FAILED))
                } else {
                    ends.map { end -> condition.judge(end, valueOf(rule.metricKind, buckets[pod.agentKey to end.minus(BucketValues.BUCKET)]), now) }
                }
                verdicts.forEach { verdict ->
                    val outcome = try {
                        recorder.record(target, Evaluation(rule.version.toLong(), verdict))
                    } catch (e: Exception) {
                        // 파드 하나의 실패가 다른 파드 · 규칙 평가를 막지 않게 한다
                        log.error("{} 기록 실패 rule={} agent={} bucketEnd={}", rule.metricKind, rule.uuid, pod.agentKey, verdict.bucketEnd, e)
                        null
                    }
                    if (outcome is Outcome.Applied) {
                        applied++
                        when (outcome.transition) {
                            is Transition.Fired -> {
                                fired++
                                if (dump(rule.metricKind, pod)) dumped++ else dumpFailures++
                            }
                            is Transition.Resolved -> resolved++
                            null -> Unit
                        }
                    }
                }
            }
        }
        return Summary(rules.size, applied, fired, resolved, if (buckets == null) 1 else 0, dumped, dumpFailures)
    }

    // 행이 없는 1분과 그 지표만 null 인 1분은 둘 다 데이터 없음 → 판정 불가 (0% 로 치지 않는다)
    private fun valueOf(metricKind: String, bucket: CpuHeapBucket?): BigDecimal? = when (metricKind) {
        CPU -> bucket?.cpuPct
        HEAP -> bucket?.heapPct
        else -> null
    }

    // 사건 · 발송 의도는 이미 커밋됐다. 여기서 무엇이 터져도 경보는 그대로 나간다
    private fun dump(metricKind: String, pod: Pod): Boolean {
        if (!props.threadDump.enabled) return true
        return try {
            val result = dumps.requestAuto(pod.uuid)
            log.info("{} 발화 → 스레드 덤프 agent={} skipped={} dump={}", metricKind, pod.agentKey, result.skipped, result.dumpUuid)
            true
        } catch (e: Exception) {
            log.warn("{} 발화 → 스레드 덤프 실패 agent={} : {}", metricKind, pod.agentKey, e.message)
            false
        }
    }

    // 규칙을 건 서비스의 파드. agents 에 아직 없는 파드는 다음 주기에 등록된 뒤 판정한다 (agent_id 가 agents FK)
    private fun loadPods(): List<Pod> = jdbc.query(
        """
        SELECT g.id, g.agent_uuid, g.application_id, g.agent_key, g.status
        FROM agents g
        WHERE g.application_id IN (
            SELECT r.application_id FROM alert_rules r WHERE r.enabled AND r.metric_kind IN ('$CPU', '$HEAP')
        )
        ORDER BY g.id
        """,
    ) { rs, _ ->
        Pod(
            rs.getLong("id"), rs.getObject("agent_uuid", UUID::class.java), rs.getLong("application_id"),
            rs.getString("agent_key"), rs.getString("status"),
        )
    }

    // 켜진 CPU · HEAP 규칙. threshold 단위는 % (0 ~ 100)
    private fun loadRules(): List<Pair<RuleSnapshot, Long>> = jdbc.query(
        """
        SELECT r.id, r.alert_rule_uuid, r.version, r.name, a.id AS application_id, a.name AS service_name,
               r.metric_kind, r.operator, r.threshold, r.window_sec, r.severity
        FROM alert_rules r JOIN applications a ON a.id = r.application_id
        WHERE r.enabled AND a.deleted_at IS NULL AND r.metric_kind IN ('$CPU', '$HEAP')
        ORDER BY r.id
        """,
    ) { rs, _ ->
        RuleSnapshot(
            id = rs.getLong("id"),
            uuid = rs.getObject("alert_rule_uuid", UUID::class.java),
            version = rs.getInt("version"),
            name = rs.getString("name"),
            serviceName = rs.getString("service_name"),
            metricKind = rs.getString("metric_kind"),
            operator = AlertOperator.valueOf(rs.getString("operator")),
            threshold = rs.getBigDecimal("threshold") ?: BigDecimal.ZERO,
            windowSec = rs.getInt("window_sec"),
            severity = rs.getString("severity"),
        ) to rs.getLong("application_id")
    }

    private companion object {
        const val CPU = "CPU"
        const val HEAP = "HEAP"
        val log = LoggerFactory.getLogger(CpuHeapRunner::class.java)
    }
}
