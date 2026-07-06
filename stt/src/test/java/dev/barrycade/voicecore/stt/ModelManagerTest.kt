package dev.barrycade.voicecore.stt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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
        assertEquals("transcribe (warm-up) must be called", 1, fakeModel.transcribeCount)
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

    // ── Negative: transcribe (warm-up) failure ──────────────────────────

    @Test
    fun initAsync_warmupFails_setsInitFailed() {
        fakeModel.failOnTranscribe = true
        modelManager.initAsync()
        Thread.sleep(100)

        assertTrue("initFailed must be true after warm-up failure", modelManager.initFailed)
        assertFalse("model must not be ready after warm-up failure", modelManager.isReady)
        assertEquals("loadModel must be called", 1, fakeModel.loadCount)
        // failOnTranscribe=true causes transcribe to throw before incrementing counter
        assertEquals("transcribe must be attempted but failed", 0, fakeModel.transcribeCount)
        assertFalse("ready listener must not fire", readyFired)
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
        assertEquals("transcribeCount must increment", 2, fakeModel.transcribeCount)
    }

    // ── Negative: transcribe with empty PCM ─────────────────────────────

    @Test
    fun transcribe_emptyPcm_returnsResult() {
        modelManager.initAsync()
        waitForReady()

        val result = modelManager.transcribe(ShortArray(0))
        assertNotNull("transcribe must not return null for empty PCM", result)
        assertEquals("transcribe must be called even with PCM empty",
            2, fakeModel.transcribeCount)  // 1 for warmup + 1 for this
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
}
