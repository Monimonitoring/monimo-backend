package com.monimo.api.common.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

// Detector 내부 문 조회에만 붙이는 짧은 서버 상한 (#287). Detector 가 3초만 기다리므로 그 안에 ClickHouse 가 먼저 끊고 503 을 준다
@Component
class InternalQueryLimit(@Value("\${monimo.clickhouse-internal-max-execution-time}") seconds: Int) {
    val settings = "SETTINGS max_execution_time = $seconds"
}
