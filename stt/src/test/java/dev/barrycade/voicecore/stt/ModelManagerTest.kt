package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for [ModelManager] using [FakeWhisperModel].
 *
 * Designed to produce failures: load/unload ordering, warmup behaviour,
 * error propagation, executor shutdown, idempotency.
 */
class ModelManagerTest {

    private lateinit var fakeModel: FakeWhisperModel
    private lateinit var modelManager: ModelManager
    private var capturedErrors: MutableList<SttError> = mutableListOf()
    private var readyFired: Boolean = false

    @Before
    fun setUp() {
        fakeModel = FakeWhisperModel()
        capturedErrors.clear()
        readyFired = false

        modelManager = ModelManager(
            modelPath = "/test/model.bin",
            sttErrorListener = object : SttErrorListener {
                override fun onSttError(error: SttError) {
                    capturedErrors.add(error)
                }
            },
            readyListener = object : SttReadyListener {
                override fun onSttReady() {
                    readyFired = true
                }
            },
            whisperModel = fakeModel
        )
    }

    private fun waitForReady(timeoutMs: Long = 2000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (modelManager.isReady) return
            Thread.sleep(10)
        }
    }

    // ── Happy path: load and warm-up ───────────────────────────────────

    @Test
    fun initAsync_loadsModelAndSetsReady() {
        modelManager.initAsync()
        waitForReady()

        assertTrue("model must be ready after init", modelManager.isReady)
        assertEquals("loadModel must be called exactly once", 1, fakeModel.loadCount)
        // Warm-up is handled by SpeechToText, not ModelManager.
        assertEquals("transcribe must not be called during init", 0, fakeModel.transcribeCount)
        assertEquals("model path must match", "/test/model.bin", fakeModel.lastModelPath)
        assertTrue("ready listener must fire", readyFired)
        assertFalse("initFailed must be false", modelManager.initFailed)
    }

    // ── Negative: model load failure ────────────────────────────────────

    @Test
    fun initAsync_loadFails_setsInitFailed() {
        fakeModel.failOnLoad = true
        assertFalse("initFailed must be false before initAsync", modelManager.initFailed)
        assertEquals("loadCount must be 0 before init", 0, fakeModel.loadCount)
        modelManager.initAsync()
        Thread.sleep(100)

        assertTrue("initFailed must be true after load failure, actual=" + modelManager.initFailed,
            modelManager.initFailed)
        assertFalse("model must not be ready after load failure", modelManager.isReady)
        assertEquals("loadModel must be attempted but failed before counter increment",
            0, fakeModel.loadCount)
        assertEquals("transcribe must not be called after load failure", 0, fakeModel.transcribeCount)
        assertFalse("ready listener must not fire", readyFired)
    }

    // ── Negative: transcribe failure (no longer relevant — warm-up removed) ──

    @Test
    fun initAsync_transcribeFailure_doesNotAffectInit() {
        fakeModel.failOnTranscribe = true
        modelManager.initAsync()
        waitForReady()

        // Warm-up is handled by SpeechToText, not ModelManager.
        // ModelManager only loads the model — transcribe failures are irrelevant here.
        assertFalse("initFailed must remain false", modelManager.initFailed)
        assertTrue("model must be ready", modelManager.isReady)
        assertEquals("loadModel must be called", 1, fakeModel.loadCount)
        assertTrue("ready listener must fire", readyFired)
    }

    // ── Negative: forceWhisperLoadFailure hook ──────────────────────────

    @Test
    fun initAsync_forceWhisperLoadFailure_setsInitFailed() {
        modelManager.forceWhisperLoadFailure = true
        modelManager.initAsync()
        Thread.sleep(100)

        assertTrue("initFailed must be true with forceWhisperLoadFailure",
            modelManager.initFailed)
        assertFalse("model must not be ready", modelManager.isReady)
        assertEquals("loadModel must not be called", 0, fakeModel.loadCount)
    }

    // ── Negative: double initAsync is idempotent ────────────────────────

    @Test
    fun initAsync_twice_loadsModelAgain() {
        modelManager.initAsync()
        waitForReady()

        assertTrue("model must be ready after first init", modelManager.isReady)
        assertEquals("loadModel must be called once after first init",
            1, fakeModel.loadCount)

        modelManager.initAsync()
        Thread.sleep(200)

        // initAsync submits a new task that calls loadModel again.
        // This is expected — the caller is responsible for idempotency.
        assertEquals("loadModel must be called again after second initAsync",
            2, fakeModel.loadCount)
    }

    // ── Negative: unload then reload ────────────────────────────────────

    @Test
    fun unloadThenReload_resetsState() {
        modelManager.initAsync()
        waitForReady()

        modelManager.unload()
        assertFalse("model must not be loaded after unload", fakeModel.isLoaded)
        assertEquals("unloadModel must be called", 1, fakeModel.unloadCount)

        // Reload
        modelManager.initAsync()
        waitForReady()

        assertEquals("loadModel must be called again", 2, fakeModel.loadCount)
        assertTrue("model must be ready after reload", modelManager.isReady)
    }

    // ── Negative: shutdown before initAsync ─────────────────────────────

    @Test
    fun shutdown_beforeInitAsync_doesNotCrash() {
        modelManager.shutdown()
        // No crash
    }

    // ── Negative: shutdown cancels pending init ─────────────────────────

    @Test
    fun shutdown_cancelsPendingInit() {
        modelManager.shutdown()
        // After shutdown, initAsync submits a task but executor is shut down.
        // The task may not run — initFailed stays false, isReady stays false.
        modelManager.initAsync()
        Thread.sleep(100)
        assertFalse("initFailed must not be set after initAsync on shut down executor",
            modelManager.initFailed)
        assertFalse("model must not be ready after init on shut down executor",
            modelManager.isReady)
    }

    // ── Negative: transcribe after unload ───────────────────────────────

    @Test
    fun transcribe_afterUnload_doesNotCrash() {
        modelManager.initAsync()
        waitForReady()

        modelManager.unload()
        // transcribe on unloaded model — should still go through to WhisperModel
        val result = modelManager.transcribe(ShortArray(100))
        assertNotNull(result)
        // No warm-up in ModelManager; transcribe method called directly.
        assertEquals("transcribeCount must increment to 1", 1, fakeModel.transcribeCount)
    }

    // ── Negative: transcribe with empty PCM ─────────────────────────────

    @Test
    fun transcribe_emptyPcm_returnsResult() {
        modelManager.initAsync()
        waitForReady()

        val result = modelManager.transcribe(ShortArray(0))
        assertNotNull("transcribe must not return null for empty PCM", result)
        // No warm-up in ModelManager; transcribe is called directly once.
        assertEquals("transcribe must be called even with PCM empty",
            1, fakeModel.transcribeCount)
    }

    // ── Negative: setReadyListener after ready ──────────────────────────

    @Test
    fun setReadyListener_afterReady_firesImmediately() {
        modelManager.initAsync()
        waitForReady()

        var secondReadyFired = false
        modelManager.setReadyListener(object : SttReadyListener {
            override fun onSttReady() {
                secondReadyFired = true
            }
        })

        assertTrue("ready listener must fire immediately if already ready",
            secondReadyFired)
    }

    // ── Negative: multiple setReadyListener calls ───────────────────────

    @Test
    fun setReadyListener_replacesPrevious() {
        var firstFired = false
        var lastFired = false

        modelManager.setReadyListener(object : SttReadyListener {
            override fun onSttReady() {
                firstFired = true
            }
        })

        modelManager.setReadyListener(object : SttReadyListener {
            override fun onSttReady() {
                lastFired = true
            }
        })

        modelManager.initAsync()
        waitForReady()

        assertFalse("first (replaced) ready listener must not fire", firstFired)
        assertTrue("last ready listener must fire", lastFired)
    }

    // ── Negative: shutdown twice is idempotent ──────────────────────────

    @Test
    fun shutdown_twice_isIdempotent() {
        modelManager.shutdown()
        modelManager.shutdown()
    }

    @Test
    fun concurrentSetReadyListenerAndInitAsync_doesNotThrow() {
        val failure = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(2)

        val listenerThread = Thread({
            try {
                repeat(200) {
                    modelManager.setReadyListener(object : SttReadyListener {
                        override fun onSttReady() {
                            // no-op
                        }
                    })
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }, "ModelManagerListenerThread")

        val initThread = Thread({
            try {
                repeat(20) {
                    modelManager.initAsync()
                    Thread.sleep(5)
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            } finally {
                done.countDown()
            }
        }, "ModelManagerInitThread")

        listenerThread.start()
        initThread.start()

        val finished = done.await(3, TimeUnit.SECONDS)
        assertTrue("concurrency test threads must finish", finished)
        assertNull("concurrent listener/init operations must not throw", failure.get())
    }
}
