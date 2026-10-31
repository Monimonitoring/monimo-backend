package com.monimo.api.query.metric

import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.CpuHeapResponse
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import kotlin.math.round
import kotlin.math.roundToLong

// 파드 · 1분마다 CPU 평균과 heap 풀 합을 낸다. 현재값이라 빼기는 없고, 전송 간격 때문에 빈 1분만 앞 값으로 채운다
@Service
class CpuHeapService(
    private val cpuHeapRepository: CpuHeapRepository,
) {

    fun list(serviceName: String?, range: TimeRange): List<CpuHeapResponse> {
        // 범위 첫 1분 · 마지막 1분이 비었을 때 앞뒤 점으로 판단할 수 있게 더 읽는다
        val points = cpuHeapRepository.findPoints(
            serviceName,
            range.from.minusSeconds(MAX_GAP_SEC),
            range.to.plusSeconds(MAX_GAP_SEC),
        )
        return points.groupBy { it.serviceName to it.agentKey }
            .flatMap { (pod, rows) -> fillGaps(rows.groupBy { it.tsMin }.toSortedMap().map { (minute, inMinute) -> minuteOf(pod, minute, inMinute) }) }
            .filter { !it.tsMin.isBefore(range.from) && it.tsMin.isBefore(range.to) }
            .map { it.response }
            .sortedWith(compareBy<CpuHeapResponse>({ it.tsMin }, { it.serviceName }, { it.agentKey }))
    }

    // CPU 는 1분 평균(줄이 여럿이면 그 평균), heap 은 풀마다 1분 마지막 값을 더한다
    private fun minuteOf(pod: Pair<String, String>, minute: Instant, rows: List<GaugePoint>): PodMinute {
        val cpu = rows.filter { it.metricName == CpuHeapRepository.CPU_METRIC }.map { it.avg }
        val used = rows.filter { it.metricName == CpuHeapRepository.HEAP_USED_METRIC }.map { it.last }
        val limit = rows.filter { it.metricName == CpuHeapRepository.HEAP_LIMIT_METRIC }.map { it.last }
        val usedBytes = used.takeIf { it.isNotEmpty() }?.sum()?.roundToLong()
        val limitBytes = limit.takeIf { it.isNotEmpty() }?.sum()?.roundToLong()?.takeIf { it > 0 }
        val response = CpuHeapResponse(
            serviceName = pod.first,
            agentKey = pod.second,
            tsMin = minute,
            cpuPct = cpu.takeIf { it.isNotEmpty() }?.let { percent(it.average()) },
            heapUsedBytes = usedBytes,
            heapLimitBytes = limitBytes,
            heapPct = if (usedBytes != null && limitBytes != null) percent(usedBytes.toDouble() / limitBytes) else null,
        )
        return PodMinute(response, rows.maxOf { it.lastTs })
    }

    // 60초 전송은 보내는 시각이 분 경계를 넘나들어 어떤 1분은 점이 없다. 두 점 간격이 MAX_GAP_SEC 이하면 그 사이 빈 1분을 앞 값으로 채운다.
    // 더 길면 전송이 실제로 빠진 것이라 채우지 않는다 (gc-time #239 와 같은 기준)
    private fun fillGaps(minutes: List<PodMinute>): List<PodMinute> =
        minutes.zipWithNext().flatMap { (before, after) ->
            if (Duration.between(before.lastTs, after.lastTs).seconds > MAX_GAP_SEC) return@flatMap emptyList()
            generateSequence(before.tsMin.plusSeconds(MINUTE_SEC)) { it.plusSeconds(MINUTE_SEC) }
                .takeWhile { it.isBefore(after.tsMin) }
                .map { PodMinute(before.response.copy(tsMin = it), before.lastTs) }
                .toList()
        } + minutes

    private data class PodMinute(val response: CpuHeapResponse, val lastTs: Instant) {
        val tsMin: Instant get() = response.tsMin
    }

    // 0 ~ 1 → %, 소수 둘째 자리
    private fun percent(ratio: Double): Double = round(ratio * 10_000) / 100

    companion object {
        // 빈 1분을 채워도 되는 두 점의 최대 간격. GcTimeService.MAX_GAP_SEC 과 같은 값이다
        const val MAX_GAP_SEC = GcTimeService.MAX_GAP_SEC
        private const val MINUTE_SEC = 60L
    }
}
