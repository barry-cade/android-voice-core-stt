package dev.barrycade.voicecore.stt

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency ownership tests for [SttSessionController].
 */
class SttSessionControllerTest {

    @Test
    fun concurrentReadWrite_doesNotThrow() {
        val controller = SttSessionController()
        val failure = AtomicReference<Throwable?>(null)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)

        val writerThread = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    controller.beginSession()
                    controller.beginPcmTiming()
                    controller.beginUtteranceTiming()
                    controller.endPcmTiming()
                    controller.beginInference()
                    controller.endInference()
                    controller.resetUtteranceTiming()
                    controller.resetSession()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "SessionControllerWriter")

        val readerThreadA = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    controller.endSession()
                    controller.captureMs()
                    controller.utteranceElapsedMs()
                    controller.currentPcmElapsedMs()
                    controller.hasPcmTimingStarted()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "SessionControllerReaderA")

        val readerThreadB = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    controller.endSession()
                    controller.captureMs()
                    controller.utteranceElapsedMs()
                    controller.currentPcmElapsedMs()
                    controller.hasPcmTimingStarted()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "SessionControllerReaderB")

        writerThread.start()
        readerThreadA.start()
        readerThreadB.start()

        startLatch.countDown()
        val finished = doneLatch.await(5, TimeUnit.SECONDS)

        assertTrue("session controller stress threads should finish", finished)
        assertTrue("session controller concurrent operations should not throw", failure.get() == null)
    }

    @Test
    fun currentPcmElapsedMs_returnsZeroWhenNotStarted() {
        val controller = SttSessionController()
        assertTrue("elapsed PCM must be zero before beginPcmTiming", controller.currentPcmElapsedMs() == 0L)
    }

    @Test
    fun hasPcmTimingStarted_reflectsBeginAndReset() {
        val controller = SttSessionController()

        assertTrue("pcm timing should start false", !controller.hasPcmTimingStarted())

        controller.beginPcmTiming()
        assertTrue("pcm timing should be true after beginPcmTiming", controller.hasPcmTimingStarted())

        controller.resetUtteranceTiming()
        assertTrue("pcm timing should be false after resetUtteranceTiming", !controller.hasPcmTimingStarted())
    }
}
