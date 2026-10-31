package com.monimo.detector.alert.evaluate

import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

// 발화 순간의 자동 스레드 덤프를 평가 스레드 밖에서 부른다 (#248).
// 덤프는 최악 16초(한도)까지 걸려서 평가 스레드에서 부르면 그만큼 다음 판정이 밀리고, 같은 주기에 발화한 파드가 여럿이면 차례로 더해진다.
// 평가는 맡기기만 하고 바로 다음 판정으로 간다. 결과(성공 · skipped · 실패)는 덤프 스레드가 로그로 남긴다
class AutoDumpLauncher(
    private val client: ThreadDumpClient,
    private val executor: ExecutorService,
) : AutoCloseable {

    // 맡겼으면 true. 대기열이 차서 못 맡기면 false — 덤프만 빠지고 경보는 그대로 나간다.
    // kind 는 로그 머리(GC_TIME 등). 실험 수집(gc_collect.sh)이 이 글자로 Detector 로그를 거른다
    fun launch(kind: String, agentUuid: UUID, agentKey: String): Boolean = try {
        executor.execute { call(kind, agentUuid, agentKey) }
        true
    } catch (e: RejectedExecutionException) {
        log.warn("{} 발화 → 스레드 덤프 대기열이 차서 건너뜀 agent={}", kind, agentKey)
        false
    }

    private fun call(kind: String, agentUuid: UUID, agentKey: String) {
        try {
            val result = client.requestAuto(agentUuid)
            log.info("{} 발화 → 스레드 덤프 agent={} skipped={} dump={}", kind, agentKey, result.skipped, result.dumpUuid)
        } catch (e: Exception) {
            log.warn("{} 발화 → 스레드 덤프 실패 agent={} : {}", kind, agentKey, e.message)
        }
    }

    // 종료 때 대기 중인 덤프는 버린다. 경보는 이미 커밋됐고, 늦게 뜬 덤프는 그 순간의 모습이 아니다
    override fun close() {
        executor.shutdownNow()
    }

    companion object {
        private val log = LoggerFactory.getLogger(AutoDumpLauncher::class.java)

        // 덤프 스레드 threads 개 + 대기열 queueCapacity 칸. 둘 다 차면 맡기지 않는다
        fun executor(threads: Int, queueCapacity: Int): ExecutorService = ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(queueCapacity),
            { r -> Thread(r, "auto-thread-dump").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }
}
