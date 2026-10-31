package com.monimo.detector.alert.evaluate

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

// 스케줄 평가 설정. 값은 임시 — 수집 → 조회 지연을 실측한 뒤 조정한다
@ConfigurationProperties("monimo.alert.schedule")
data class EvaluationProperties(
    // 스케줄러를 켤지. 테스트는 끄고 EvaluationRunner.runOnce 를 직접 부른다
    val enabled: Boolean = true,
    // 평가 주기 (ADR #39: 규칙 폴링 10~30초)
    val interval: Duration = Duration.ofSeconds(15),
    // 1분 버킷이 끝나고 이만큼 지나야 "완료"로 보고 판정한다. 적재가 덜 끝난 분을 정상으로 오판하지 않으려는 것
    val settleDelay: Duration = Duration.ofSeconds(30),
    // 이보다 오래된 버킷은 판정하지 않는다. 멈췄다 다시 돌 때 따라잡는 범위이기도 하다
    val maxStaleness: Duration = Duration.ofMinutes(3),
    val query: Query = Query(),
    val agentDown: AgentDown = AgentDown(),
    val threadDump: ThreadDump = ThreadDump(),
) {
    // 파드 단위 경보(GC_TIME · CPU · HEAP)가 열리는 순간 부르는 자동 스레드 덤프 (API server #164, docs/alert/80-gc-time.md)
    data class ThreadDump(
        // 끄면 경보는 그대로 돌고 덤프만 안 부른다 (실험 · 장애 때)
        val enabled: Boolean = true,
        val connectTimeout: Duration = Duration.ofSeconds(1),
        // API server 가 덤프를 끝내는 데 걸릴 수 있는 최악. Collector 호출 한 번이 덤프 5초(ThreadDumpService.DEFAULT_TIMEOUT_MS)
        // + 여유 2초(monimo.collector.response-margin) = 7초이고, 답이 없으면 한 번 더 부른다 → 14초.
        // API server 상수를 옮겨 적은 값이라 그쪽이 바뀌면 같이 고친다 (AGENTS §6)
        val upstreamWorst: Duration = Duration.ofSeconds(14),
        // upstreamWorst 보다 길어야 한다(기동 때 검사). 짧으면 API server 는 저장했는데 여기서는 실패로 남는다.
        // 덤프 스레드에서 부르므로 길어도 평가 주기는 밀리지 않는다 (AutoDumpLauncher)
        val requestTimeout: Duration = Duration.ofSeconds(16),
        // 덤프 스레드 수. 1 이면 같은 때 발화한 파드들의 덤프가 차례로 떠서 뒤 파드는 그만큼 늦게 뜬다
        val threads: Int = 1,
        // 덤프 스레드가 바쁠 때 기다리는 칸. 차면 그 덤프만 건너뛴다
        val queueCapacity: Int = 16,
    )

    // AGENT_DOWN (FN-29 · docs/alert/40-agent-down.md)
    data class AgentDown(
        // 이만큼 데이터가 없으면 그 파드 키를 DOWN 으로 본다 (FN-29: 90초)
        val silence: Duration = Duration.ofSeconds(90),
        // agents/active 를 이 범위로 부른다. silence 보다 길어야 "방금 끊긴 키"도 응답에 남는다
        val lookback: Duration = Duration.ofMinutes(3),
        // DOWN 이 된 지 이만큼 지난 키는 감시 대상에서 뺀다. 재시작마다 키가 새로 생겨 옛 키가 쌓이지 않게 (표에서 지우지는 않는다)
        val forgetAfter: Duration = Duration.ofHours(24),
        // agents.status 를 UP/DOWN 으로 맞출지 (ADR #39: Detector 가 생존 상태 갱신)
        val updateStatus: Boolean = true,
    )

    data class Query(
        // API server 주소 (내부 문 service-health)
        val baseUrl: String = "http://localhost:8080",
        val connectTimeout: Duration = Duration.ofSeconds(1),
        val requestTimeout: Duration = Duration.ofSeconds(3),
    )
}
