package com.monimo.detector.alert.evaluate

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// 덤프는 덤프 스레드에서 뜨고, 평가(부르는 쪽)는 맡기기만 하고 바로 돌아온다 (#248)
class AutoDumpLauncherTest : BehaviorSpec({

    val pod = UUID.fromString("4a1c2d3e-0000-4000-8000-000000000001")

    Given("덤프 스레드 1개 · 대기열 1칸, 덤프 문이 풀어 줄 때까지 응답하지 않는다") {
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        val client = ThreadDumpClient {
            started.incrementAndGet()
            release.await(10, TimeUnit.SECONDS)
            AutoDumpResult(skipped = false, dumpUuid = null)
        }
        val executor = AutoDumpLauncher.executor(threads = 1, queueCapacity = 1)
        val launcher = AutoDumpLauncher(client, executor)

        When("발화 셋을 차례로 맡기면") {
            val begin = System.nanoTime()
            val first = launcher.launch("GC_TIME", pod, "pod-1")
            val elapsedMs = Duration.ofNanos(System.nanoTime() - begin).toMillis()
            val second = launcher.launch("GC_TIME", pod, "pod-2")
            val third = launcher.launch("GC_TIME", pod, "pod-3")

            Then("첫 덤프가 끝나기를 기다리지 않고 바로 돌아온다") {
                first shouldBe true
                elapsedMs shouldBeLessThan 1000L
            }

            Then("둘째는 대기열에 들어가고, 셋째는 자리가 없어 건너뛴다") {
                second shouldBe true
                third shouldBe false
            }

            Then("종료하면 대기 중인 덤프는 버린다 — 늦게 뜬 덤프는 발화 순간의 모습이 아니다") {
                launcher.close()
                release.countDown()
                executor.awaitTermination(5, TimeUnit.SECONDS) shouldBe true
                started.get() shouldBe 1
            }
        }
    }

    Given("덤프 문이 실패한다") {
        val calls = AtomicInteger()
        val done = CountDownLatch(2)
        val client = ThreadDumpClient {
            calls.incrementAndGet()
            done.countDown()
            throw ThreadDumpRequestException("주입한 장애: thread-dumps 503")
        }
        val launcher = AutoDumpLauncher(client, AutoDumpLauncher.executor(threads = 1, queueCapacity = 4))

        When("두 번 맡기면") {
            val results = listOf(launcher.launch("GC_TIME", pod, "pod-1"), launcher.launch("GC_TIME", pod, "pod-2"))
            done.await(5, TimeUnit.SECONDS)
            launcher.close()

            Then("실패가 부르는 쪽으로 올라오지 않고, 덤프 스레드도 죽지 않아 다음 덤프를 뜬다") {
                results shouldBe listOf(true, true)
                calls.get() shouldBe 2
            }
        }
    }

    Given("기동 때 한도 검사") {
        val config = EvaluationConfig()
        fun props(timeout: Duration) = EvaluationProperties(threadDump = EvaluationProperties.ThreadDump(requestTimeout = timeout))

        When("한도가 API server 최악(14초) 이하이면") {
            Then("빈을 만들지 않는다 — API server 는 저장했는데 여기서 실패로 남는 일을 막는다") {
                shouldThrow<IllegalArgumentException> {
                    config.threadDumpClient(props(Duration.ofSeconds(8)), "token", jacksonObjectMapper())
                }.message!! shouldContain "upstream-worst"
                shouldThrow<IllegalArgumentException> {
                    config.threadDumpClient(props(Duration.ofSeconds(14)), "token", jacksonObjectMapper())
                }
            }
        }

        When("기본값(16초)이면") {
            Then("통과한다") {
                config.threadDumpClient(EvaluationProperties(), "token", jacksonObjectMapper())
            }
        }
    }
})
