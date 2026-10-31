package com.monimo.notifier.support

import com.monimo.notifier.delivery.ChannelLanes
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

// 맡긴 일을 부른 스레드에서 바로 돌린다. pollOnce 가 돌아오면 발송 · 기록까지 끝나 있어 DB 를 바로 볼 수 있다.
// 레인이 정말 따로 도는지는 ChannelLanesTest 가 실제 스레드로 본다
class DirectExecutorService : AbstractExecutorService() {
    @Volatile private var shut = false
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() { shut = true }
    override fun shutdownNow(): List<Runnable> { shut = true; return emptyList() }
    override fun isShutdown() = shut
    override fun isTerminated() = shut
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}

fun inlineLanes() = ChannelLanes(DirectExecutorService(), maxBusy = Int.MAX_VALUE, shutdownWait = Duration.ZERO)
