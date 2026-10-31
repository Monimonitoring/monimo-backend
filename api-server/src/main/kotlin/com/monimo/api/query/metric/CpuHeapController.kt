package com.monimo.api.query.metric

import com.monimo.api.common.web.ApiResponse
import com.monimo.api.common.web.TimeRange
import com.monimo.api.query.metric.dto.CpuHeapResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

// Detector 가 CPU · HEAP 을 판정할 재료를 읽는 내부 문 (API 명세 #53). X-Internal-Token 은 SecurityConfig 가 검사한다
// 응답에 없는 1분 = 그 파드가 값을 안 보냈고 앞뒤 점도 150초 넘게 떨어진 1분
@RestController
@RequestMapping("/api/v1/internal/metrics/cpu-heap")
class CpuHeapController(
    private val cpuHeapService: CpuHeapService,
) {

    @GetMapping
    fun list(
        @RequestParam("service_name", required = false) serviceName: String?,
        @RequestParam from: Instant,
        @RequestParam to: Instant,
    ): ApiResponse<List<CpuHeapResponse>> =
        ApiResponse.of(cpuHeapService.list(serviceName, TimeRange.of(from, to)))
}
