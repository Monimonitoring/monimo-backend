package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.IOException
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

// API server 내부 문 GET /api/v1/internal/metrics/cpu-heap 의 한 줄 (API 명세 53번, 조회 #252 구현).
// 파드 하나의 1분 CPU 사용률 평균(%)과 heap 사용률(%). 현재값이라 빼기가 없고, 150초 안의 빈 1분은 조회가 앞 값으로 채운다.
// 값이 안 온 쪽은 null, 행이 없는 1분 = 값이 안 왔고 앞뒤 점도 150초 넘게 떨어진 1분 — 둘 다 0 으로 채우지 않는다
data class CpuHeapBucket(
    val serviceName: String,
    val agentKey: String,
    val start: Instant,
    val cpuPct: BigDecimal?,
    val heapPct: BigDecimal?,
)

// 조회 계약이 바뀌면 이 인터페이스 구현만 고친다. 판정 쪽은 그대로
fun interface CpuHeapClient {
    // [from, to) 의 모든 서비스 · 모든 파드 (service_name 없이 한 번). 실패하면 예외 (빈 목록으로 삼키지 않는다)
    fun fetch(from: Instant, to: Instant): List<CpuHeapBucket>
}

// X-Internal-Token 으로 인증한다. 스스로 재시도하지 않는다 — 다음 주기가 곧 재시도다
class HttpCpuHeapClient(
    private val props: EvaluationProperties.Query,
    private val internalToken: String,
    private val objectMapper: ObjectMapper,
) : CpuHeapClient {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override fun fetch(from: Instant, to: Instant): List<CpuHeapBucket> {
        val request = HttpRequest.newBuilder(URI.create("${props.baseUrl.trimEnd('/')}$PATH?from=$from&to=$to"))
            .timeout(props.requestTimeout)
            .header("X-Internal-Token", internalToken)
            .GET()
            .build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw CpuHeapQueryException("cpu-heap 호출 실패: ${e.javaClass.simpleName}", e)
        }
        if (response.statusCode() != 200) {
            throw CpuHeapQueryException("cpu-heap 응답 ${response.statusCode()}")
        }
        return objectMapper.readTree(response.body())["data"].map {
            CpuHeapBucket(
                serviceName = it["service_name"].asText(),
                agentKey = it["agent_key"].asText(),
                start = Instant.parse(it["ts_min"].asText()),
                cpuPct = it.decimalOrNull("cpu_pct"),
                heapPct = it.decimalOrNull("heap_pct"),
            )
        }
    }

    private fun JsonNode.decimalOrNull(field: String): BigDecimal? = get(field)?.takeUnless { it.isNull }?.decimalValue()

    private companion object {
        const val PATH = "/api/v1/internal/metrics/cpu-heap"
    }
}

class CpuHeapQueryException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
