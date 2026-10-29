package com.monimo.api.selfcheck.dto

// Watchdog 이 읽는 자기 점검 결과. 어느 항목이 ok=false 라도 HTTP 는 200 — 판단은 Watchdog 이 한다
data class SelfCheckResponse(
    val postgres: PostgresCheck,
    val detector: AgeCheck,
    val collector: AgeCheck,
    val notifier: AgeCheck,
    val outbox: OutboxCheck,
)

// SELECT 1 이 돌아오기까지 걸린 ms. 기한 안에 안 돌아왔으면 ok=false 에 error=timeout
data class PostgresCheck(
    val ok: Boolean,
    val ms: Long,
    val error: String? = null,
)

// 낡음 게이지(ADR #54). age_sec 가 null 이면 게이지를 못 읽은 것 (모듈이 죽었거나 주소가 틀림)
data class AgeCheck(
    val ok: Boolean,
    val ageSec: Double?,
    val maxSec: Long,
    val error: String? = null,
)

// 발송 큐 적체 (#267). oldest_pending_sec 는 PENDING · IN_FLIGHT 중 가장 오래된 줄의 나이, 큐가 비면 null.
// failed_recent 는 최근 창(기본 150초 = 카나리 두 주기 반) 안에 FAILED 가 된 줄 수. ok 는 적체가 max_sec 를 안 넘고 최근 FAILED 가 0 인 것
data class OutboxCheck(
    val ok: Boolean,
    val oldestPendingSec: Long?,
    val failedRecent: Long,
    val maxSec: Long,
    val error: String? = null,
)
