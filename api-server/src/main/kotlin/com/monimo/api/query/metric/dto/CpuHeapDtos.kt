package com.monimo.api.query.metric.dto

import java.time.Instant

// 파드 하나의 1분 CPU 사용률(%)과 힙 사용량. 값이 안 온 쪽은 null (API 명세 #53)
data class CpuHeapResponse(
    val serviceName: String,
    val agentKey: String,
    val tsMin: Instant,
    val cpuPct: Double?,
    val heapUsedBytes: Long?,
    val heapLimitBytes: Long?,
    val heapPct: Double?,
)
