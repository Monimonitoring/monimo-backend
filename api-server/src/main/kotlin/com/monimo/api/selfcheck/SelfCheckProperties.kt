package com.monimo.api.selfcheck

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 자기 점검 설정 (application.yml monimo.self-check). 게이지는 관리 포트(업무 포트 + 10000, ADR #61)에 있다
@ConfigurationProperties("monimo.self-check")
data class SelfCheckProperties(
    val detectorManagementUrl: String = "http://localhost:18083",
    val collectorManagementUrl: String = "http://localhost:18081",
    val notifierManagementUrl: String = "http://localhost:18084",
    // PG 두드림이 이보다 오래 걸리면 실패로 친다. Watchdog 의 호출 기한(10초) 안에 답이 가야 해서 그 절반
    val postgresTimeout: Duration = Duration.ofSeconds(5),
    // 낡음 게이지가 이보다 크면 멈춘 것으로 친다. Detector 는 1분 주기 3번, Collector 는 30초 주기 6번
    val detectorMaxAge: Duration = Duration.ofSeconds(180),
    val collectorMaxAge: Duration = Duration.ofSeconds(180),
    // Notifier 는 큐를 몇 초마다 읽으므로 180초면 멈춘 것. 이 게이지는 "큐 읽기 성공" 이지 발송 성공이 아니다 (#267)
    val notifierMaxAge: Duration = Duration.ofSeconds(180),
    // 발송 큐(notification_outbox)에 PENDING · IN_FLIGHT 로 이보다 오래 남은 줄이 있으면 Slack 으로 안 나가는 것 (#267).
    // 정상이면 몇 초 안에 SENT 가 되고, 웹훅이 틀리거나 서킷이 열리면 재시도하며 여기 남는다
    val outboxMaxPending: Duration = Duration.ofSeconds(180),
    // 최근 이 시간 안에 FAILED 가 된 줄 수를 같이 돌려준다 (재시도를 다 쓴 것)
    val outboxFailedWindow: Duration = Duration.ofMinutes(10),
    val gaugeTimeout: Duration = Duration.ofSeconds(2),
)
