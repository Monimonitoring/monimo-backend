package com.monimo.notifier.delivery

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("monimo.notifier.delivery")
data class DeliveryProperties(
    val enabled: Boolean = true,            // false 면 스케줄러를 켜지 않는다 (테스트는 pollOnce 를 직접 부른다)
    val pollInterval: Duration = Duration.ofSeconds(1),
    val batchSize: Int = 10,
    val lease: Duration = Duration.ofSeconds(90),
    val retryBase: Duration = Duration.ofSeconds(5),
    val retryCap: Duration = Duration.ofMinutes(5),
    val maxAttempts: Int = 5,
    val maxAge: Duration = Duration.ofMinutes(30),
    val circuitFailureThreshold: Int = 5,    // 채널별 연속 실패가 이만큼이면 호출을 멈춘다 (E8)
    val circuitOpenDuration: Duration = Duration.ofSeconds(30),  // 멈춘 뒤 시험 호출까지 기다리는 시간
    // 그룹핑 (docs/alert/50-grouping.md). 같은 채널 · 서비스 알림을 이만큼 기다렸다 한 메시지로. NFR 60초 안에 들도록 10초 (G1)
    val groupWait: Duration = Duration.ofSeconds(10),
    val maxGroupSize: Int = 20,               // 한 메시지에 넣을 최대 건수. 넘치면 다음 메시지로
    // 동시에 보내는 중인 채널이 이만큼이면 새로 선점하지 않는다 (#250 채널별 레인). 느린 채널이 많아도 메모리 · 소켓이 끝없이 늘지 않게.
    // 스레드 레인일 때는 바쁜 채널 하나 = 스레드 하나라 8 로 묶었는데, 느린 채널이 100개면 정상 채널이 51초 밀렸다.
    // 코루틴 레인은 바쁜 채널이 늘어도 스레드가 늘지 않아(1000개에서 97개) 상한을 크게 둔다 (docs/alert/120-coroutine-lanes.md)
    val maxBusyChannels: Int = 256,
    // 레인을 무엇으로 돌릴지 (120-coroutine-lanes.md). COROUTINE = 코루틴(기본), THREAD = 바쁜 채널마다 스레드 하나(#250, 되돌릴 때)
    val laneMode: LaneMode = LaneMode.COROUTINE,
    // COROUTINE 일 때 레인을 돌리는 스레드 수. DB 일(채널 조회 · 결과 기록)이 이 스레드를 붙잡으므로 Hikari 연결 수(기본 10)를 넘기지 않는다
    val laneThreads: Int = 8,
)

enum class LaneMode { THREAD, COROUTINE }
