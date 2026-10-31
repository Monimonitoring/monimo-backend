package com.monimo.notifier.delivery

import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// 채널별 발송 레인 (bulkhead, #250 · docs/alert/70-slow-channel.md).
// 예전에는 스레드 하나가 선점한 그룹을 차례로 보내서, 느리게 성공하는 채널 하나가 뒤의 다른 채널 알림까지 밀었다(헤드 오브 라인).
// 서킷브레이커는 "실패"가 이어질 때만 호출을 멈추므로 "느린 성공"은 못 막는다.
// 이제 선점한 줄을 채널마다 나눠 그 채널의 레인에서 보낸다. 레인이 바쁜 채널은 다음 선점에서 빠지므로
//  - 한 채널 안에서는 지금처럼 차례로 한 번에 하나씩 보낸다 (순서 · Slack 초당 호출 제한)
//  - 한 레인은 한 번에 선점 하나(최대 batch-size 줄)만 맡는다 → "lease > batch × 호출 한도" 검사가 그대로 맞는다
class ChannelLanes(
    private val executor: ExecutorService,
    // 동시에 바쁜 채널이 이만큼이면 새로 선점하지 않는다. 느린 채널이 많아도 스레드 · DB 연결이 끝없이 늘지 않게
    private val maxBusy: Int,
    // 종료할 때 진행 중인 발송을 기다리는 시간. 넘기면 그 줄은 IN_FLIGHT 로 남고 임대가 끝나면 다른 워커가 회수한다
    private val shutdownWait: Duration,
) : AutoCloseable {

    private val log = LoggerFactory.getLogger(javaClass)

    // 채널 → 맡은 일 수. 보통 0 이거나 1 이다(바쁜 채널은 선점에서 빠진다). 같은 인스턴스에서 폴러가 여럿이면 잠깐 2 가 될 수 있다
    private val busy = ConcurrentHashMap<Long, Int>()

    fun busyChannels(): Set<Long> = busy.keys.toSet()

    fun hasRoom(): Boolean = busy.size < maxBusy

    // 그 채널의 레인에 맡긴다. 맡긴 일은 끝날 때까지 그 채널을 바쁨으로 둔다
    fun submit(channelId: Long, work: () -> Unit) {
        busy.merge(channelId, 1, Int::plus)
        try {
            executor.execute {
                try {
                    work()
                } catch (e: Exception) {
                    // 끝내지 못한 줄은 IN_FLIGHT 로 남고 임대가 끝나면 회수된다. 레인은 계속 돈다
                    log.error("채널 {} 발송 실패", channelId, e)
                } finally {
                    release(channelId)
                }
            }
        } catch (e: RejectedExecutionException) {
            // 종료 중. 선점한 줄은 임대가 끝나면 다시 보낸다
            release(channelId)
            log.warn("종료 중이라 채널 {} 발송을 맡기지 못했다 — 임대가 끝나면 회수된다", channelId)
        }
    }

    private fun release(channelId: Long) {
        busy.computeIfPresent(channelId) { _, n -> if (n <= 1) null else n - 1 }
    }

    // 진행 중인 호출은 마저 끝내고 결과를 기록하게 기다린다. 끊으면 Slack 은 받았는데 기록이 없는 줄이 생겨 임대 뒤 한 번 더 간다
    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("발송 레인이 {} 안에 끝나지 않았다 — 남은 줄은 임대가 끝나면 회수된다", shutdownWait)
            executor.shutdownNow()
        }
    }
}
