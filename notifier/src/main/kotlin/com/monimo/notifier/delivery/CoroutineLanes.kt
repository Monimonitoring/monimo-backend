package com.monimo.notifier.delivery

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// 채널별 발송 레인을 스레드 대신 코루틴으로 (docs/alert/120-coroutine-lanes.md).
// ChannelLanes 와 약속은 같다 — 채널 안은 차례로 하나씩, 바쁜 채널은 다음 선점에서 빠진다, 바쁜 채널이 maxBusy 면 쉰다.
// 다른 것은 "Slack 응답을 기다리는 동안 무엇이 남는가" 하나다.
//  - 스레드 레인: 레인마다 스레드 하나가 응답을 기다리며 잠든다(스택 · 커널 스레드가 남는다)
//  - 코루틴 레인: 기다리는 동안 코루틴 상태(힙 객체 몇 개)만 남고 스레드는 다른 레인의 일을 한다
// DB 일(채널 조회 · 결과 기록)은 JDBC 라 스레드를 붙잡는다. 그래서 레인 전체를 dbParallelism 개 스레드에서 돌린다 —
// 스레드 수가 DB 연결 수를 넘지 않게. @Transactional 은 claimer.finish 한 번의 안에서 끝나고 그 안에 suspend 지점이 없으므로
// 트랜잭션이 스레드를 옮겨 가는 일은 없다
class CoroutineLanes(
    private val maxBusy: Int,
    private val shutdownWait: Duration,
    dbParallelism: Int,
) : DeliveryLanes {

    private val log = LoggerFactory.getLogger(javaClass)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(dbParallelism) + CoroutineName("delivery-lane"))

    private val busy = ConcurrentHashMap<Long, Int>()
    private val closing = AtomicBoolean(false)

    override val suspendsOnSend = true

    override fun busyChannels(): Set<Long> = busy.keys.toSet()

    override fun hasRoom(): Boolean = busy.size < maxBusy

    override fun submit(channelId: Long, work: suspend () -> Unit) {
        if (closing.get()) {
            log.warn("종료 중이라 채널 {} 발송을 맡기지 못했다 — 임대가 끝나면 회수된다", channelId)
            return
        }
        busy.merge(channelId, 1, Int::plus)
        scope.launch {
            try {
                work()
            } catch (e: Exception) {
                // 끝내지 못한 줄은 IN_FLIGHT 로 남고 임대가 끝나면 회수된다. 다른 레인은 SupervisorJob 이라 같이 취소되지 않는다
                log.error("채널 {} 발송 실패", channelId, e)
            } finally {
                busy.computeIfPresent(channelId) { _, n -> if (n <= 1) null else n - 1 }
            }
        }
    }

    // 진행 중인 호출은 마저 끝내고 결과를 기록하게 기다린다 (ChannelLanes.close 와 같은 이유)
    override fun close() {
        closing.set(true)
        val done = runBlocking { withTimeoutOrNull(shutdownWait.toMillis()) { scope.coroutineContext.job.children.toList().joinAll() } }
        if (done == null) log.warn("발송 레인이 {} 안에 끝나지 않았다 — 남은 줄은 임대가 끝나면 회수된다", shutdownWait)
        scope.cancel()
    }
}
