package dev.barrycade.voicecore.stt

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Concurrency-focused tests for [SttCallbackDispatcher].
 */
class SttCallbackDispatcherTest {

    @Test
    fun clearListeners_preventsFurtherDispatch() {
        val dispatcher = SttCallbackDispatcher()
        var resultCalls = 0
        var timingCalls = 0
        var errorCalls = 0

        dispatcher.setOnResultListener { resultCalls++ }
        dispatcher.onTimingListener = { _, _, _, _ -> timingCalls++ }
        dispatcher.setOnErrorListener { errorCalls++ }

        dispatcher.dispatchResult("before", SttReturnCode.SUCCESS, null)
        dispatcher.dispatchTiming(1, 2, 3, 4)
        dispatcher.dispatchError(RuntimeException("before"))

        dispatcher.clearListeners()

        dispatcher.dispatchResult("after", SttReturnCode.SUCCESS, null)
        dispatcher.dispatchTiming(1, 2, 3, 4)
        dispatcher.dispatchError(RuntimeException("after"))

        assertTrue("result listener should be called once before clear", resultCalls == 1)
        assertTrue("timing listener should be called once before clear", timingCalls == 1)
        assertTrue("error listener should be called once before clear", errorCalls == 1)
    }

    @Test
    fun concurrentRegisterClearAndDispatch_doesNotThrow() {
        val dispatcher = SttCallbackDispatcher()
        val failure = AtomicReference<Throwable?>(null)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(4)

        val registerThread = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    dispatcher.setOnResultListener { _ -> }
                    dispatcher.setOnResultWithTimingListener { _, _, _ -> }
                    dispatcher.setOnErrorListener { _ -> }
                    dispatcher.setSttErrorListener { _ -> }
                    dispatcher.onTimingListener = { _, _, _, _ -> }
                    if (it % 7 == 0) {
                        dispatcher.clearListeners()
                    }
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "DispatcherRegisterThread")

        val resultThread = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    dispatcher.dispatchResult("text", SttReturnCode.SUCCESS, null)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "DispatcherResultThread")

        val errorThread = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    dispatcher.dispatchError(RuntimeException("boom"))
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "DispatcherErrorThread")

        val timingThread = Thread({
            try {
                startLatch.await()
                repeat(1000) {
                    dispatcher.dispatchTiming(1, 2, 3, 4)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "DispatcherTimingThread")

        registerThread.start()
        resultThread.start()
        errorThread.start()
        timingThread.start()

        startLatch.countDown()
        val finished = doneLatch.await(5, TimeUnit.SECONDS)

        assertTrue("dispatcher stress threads should finish", finished)
        assertNull("dispatcher concurrent operations should not throw", failure.get())
    }
}
