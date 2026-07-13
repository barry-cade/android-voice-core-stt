package dev.barrycade.voicecore.stt

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Ownership stress tests for [CaptureManager].
 *
 * These tests avoid microphone start paths and stress only shared-state
 * ownership around session buffer mutation and lifecycle-style calls.
 */
@RunWith(AndroidJUnit4::class)
class CaptureManagerOwnershipStressTest {

    @Test
    fun concurrentPollFinalizeReset_doesNotThrow() {
        val manager = CaptureManager()
        val failure = AtomicReference<Throwable?>(null)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(3)

        val injector = Thread({
            try {
                startLatch.await()
                repeat(2000) {
                    injectFrame(manager, floatArrayOf(0.2f, 0.1f, -0.1f, -0.2f))
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "CaptureInjectThread")

        val poller = Thread({
            try {
                startLatch.await()
                repeat(2000) {
                    manager.pollFrame()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "CapturePollThread")

        val finalizer = Thread({
            try {
                startLatch.await()
                repeat(400) {
                    manager.finalize()
                    manager.reset()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "CaptureFinalizeThread")

        injector.start()
        poller.start()
        finalizer.start()

        startLatch.countDown()
        val finished = doneLatch.await(8, TimeUnit.SECONDS)

        manager.shutdown()

        assertTrue("ownership stress threads should finish", finished)
        assertNull("concurrent ownership operations should not throw", failure.get())
    }

    @Test
    fun concurrentPollAndShutdown_doesNotThrow() {
        val manager = CaptureManager()
        val failure = AtomicReference<Throwable?>(null)
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(2)

        val worker = Thread({
            try {
                startLatch.await()
                repeat(1500) {
                    injectFrame(manager, floatArrayOf(0.3f, 0.0f, -0.3f))
                    manager.pollFrame()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "CaptureWorkerThread")

        val shutdowner = Thread({
            try {
                startLatch.await()
                repeat(250) {
                    manager.shutdown()
                    manager.reset()
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                doneLatch.countDown()
            }
        }, "CaptureShutdownThread")

        worker.start()
        shutdowner.start()

        startLatch.countDown()
        val finished = doneLatch.await(8, TimeUnit.SECONDS)

        manager.shutdown()

        assertTrue("ownership shutdown stress threads should finish", finished)
        assertNull("concurrent shutdown/poll operations should not throw", failure.get())
    }

    private fun injectFrame(manager: CaptureManager, frame: FloatArray) {
        val audioCaptureField = CaptureManager::class.java.getDeclaredField("audioCapture")
        audioCaptureField.isAccessible = true
        val audioCapture = audioCaptureField.get(manager) as AudioCapture
        audioCapture.frameQueue.offer(frame)
    }
}
