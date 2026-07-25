package dev.barrycade.voicecore.wuw

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extracts MFCC feature vectors from raw PCM audio.
 *
 * Pipeline: pre-emphasis â†’ frame blocking â†’ Hamming window â†’ FFT â†’
 * mel filterbank â†’ log energy â†’ DCT â†’ MFCC coefficients.
 *
 * Designed for 16 kHz mono PCM input. Each call to [extract] converts
 * a buffer of PCM samples into a list of MFCC frames, where each frame
 * is a [FloatArray] of [numCoefficients] coefficients.
 *
 * The extractor is stateless: it holds configuration but no accumulated
 * audio data. Callers accumulate frames if needed.
 */
class MfccExtractor(
    /** Sample rate of incoming PCM. Default 16000 Hz. */
    val sampleRate: Int = 16000,

    /** Number of MFCC coefficients per frame. */
    var numCoefficients: Int = 13,

    /** Frame length in milliseconds. */
    var frameDurationMs: Int = 25,

    /** Frame stride (hop) in milliseconds. */
    var frameStrideMs: Int = 10,

    /** Number of mel filterbank channels. */
    val numFilters: Int = 26,

    /** Lower frequency bound for the mel filterbank. */
    val lowFreq: Float = 0f,

    /** Upper frequency bound for the mel filterbank. Default to Nyquist. */
    val highFreq: Float = 8000f,

    /** Pre-emphasis alpha coefficient. 0.0 disables pre-emphasis. */
    var preEmphasisAlpha: Float = 0.97f
) {
    /** Frame size in samples. Updated via [rebuildDerived]. */
    var frameSize: Int = (sampleRate * frameDurationMs / 1000f).toInt()
        private set

    /** Frame stride in samples. Updated via [rebuildDerived]. */
    var frameStride: Int = (sampleRate * frameStrideMs / 1000f).toInt()
        private set

    /** FFT size (next power of two >= frameSize). Updated via [rebuildDerived]. */
    private var fftSize: Int = nextPowerOfTwo(frameSize)

    /** Pre-computed Hamming window. Updated via [rebuildDerived]. */
    private var hammingWindow: FloatArray = computeHammingWindow(frameSize)

    /** Pre-computed mel filterbank. Updated via [rebuildDerived]. */
    private var melFilterbank: Array<FloatArray> = computeMelFilterbank()

    /** Pre-computed DCT matrix. Updated via [rebuildDerived]. */
    private var dctMatrix: Array<FloatArray> = computeDctMatrix()

    /** Pre-computed cos(2πk/N). Updated via [rebuildDerived]. */
    private var twiddleCos: FloatArray = FloatArray(fftSize / 2) { k ->
        kotlin.math.cos(2f * kotlin.math.PI.toFloat() * k / fftSize)
    }

    /** Pre-computed sin(2πk/N). Updated via [rebuildDerived]. */
    private var twiddleSin: FloatArray = FloatArray(fftSize / 2) { k ->
        kotlin.math.sin(2f * kotlin.math.PI.toFloat() * k / fftSize)
    }

    /**
     * Recompute all derived dimensions and pre-computed tables.
     * Must be called after changing frameDurationMs, frameStrideMs, or numCoefficients.
     */
    fun rebuildDerived() {
        frameSize = (sampleRate * frameDurationMs / 1000f).toInt()
        frameStride = (sampleRate * frameStrideMs / 1000f).toInt()
        fftSize = nextPowerOfTwo(frameSize)
        hammingWindow = computeHammingWindow(frameSize)
        twiddleCos = FloatArray(fftSize / 2) { k ->
            kotlin.math.cos(2f * kotlin.math.PI.toFloat() * k / fftSize)
        }
        twiddleSin = FloatArray(fftSize / 2) { k ->
            kotlin.math.sin(2f * kotlin.math.PI.toFloat() * k / fftSize)
        }
        melFilterbank = computeMelFilterbank()
        dctMatrix = computeDctMatrix()
    }

    /**
     * Extract MFCC frames from a PCM buffer.
     *
     * @param pcm PCM samples as [ShortArray] (16 kHz, mono, 16-bit signed).
     * @return List of MFCC frames, each a [FloatArray] of length [numCoefficients].
     *         Returns empty list if the buffer is shorter than one frame.
     */
    fun extract(pcm: ShortArray): List<FloatArray> {
        if (pcm.size < frameSize) {
            return emptyList()
        }

        // Convert to float and normalise to [-1.0, 1.0].
        val floatPcm = FloatArray(pcm.size) { pcm[it] / 32768f }

        // Pre-emphasis.
        val emphasised = applyPreEmphasis(floatPcm)

        // Number of complete frames.
        val numFrames = 1 + (emphasised.size - frameSize) / frameStride
        val frames = mutableListOf<FloatArray>()

        var start = 0
        for (i in 0 until numFrames) {
            // Extract frame.
            val frame = FloatArray(frameSize)
            System.arraycopy(emphasised, start, frame, 0, frameSize)

            // Apply Hamming window.
            for (j in frame.indices) {
                frame[j] = frame[j] * hammingWindow[j]
            }

            // Compute power spectrum via FFT.
            val powerSpectrum = computePowerSpectrum(frame)

            // Apply mel filterbank and take log.
            val logMelEnergies = FloatArray(numFilters) { f ->
                var sum = 0f
                for (k in powerSpectrum.indices) {
                    sum += powerSpectrum[k] * melFilterbank[f][k]
                }
                // Log with floor to avoid -Infinity.
                if (sum < 1e-10f) -10f else (10f * kotlin.math.log10(sum))
            }

            // Apply DCT to get MFCC coefficients.
            val mfcc = FloatArray(numCoefficients) { c ->
                var sum = 0f
                for (f in logMelEnergies.indices) {
                    sum += dctMatrix[c][f] * logMelEnergies[f]
                }
                sum
            }

            frames.add(mfcc)
            start += frameStride
        }

        return frames
    }

    /**
     * Trim leading and trailing silence from PCM audio using RMS energy.
     *
     * Splits the PCM into [frameSize]-sample frames, computes RMS energy for
     * each, discards frames below [silenceThreshold] * peak energy.
     *
     * @param pcm Raw PCM samples.
     * @param silenceThreshold Fraction of peak RMS to use as cutoff (default 0.02 = 2%).
     * @return Trimmed PCM with silence removed from both ends.
     *         Returns the original buffer if no silence is detected.
     */
    fun trimSilence(pcm: ShortArray, silenceThreshold: Float = 0.02f): ShortArray {
        if (pcm.isEmpty()) return pcm

        val numFrames = pcm.size / frameSize
        if (numFrames < 3) return pcm

        val energies = FloatArray(numFrames) { f ->
            var sumSq = 0f
            val start = f * frameSize
            val end = minOf(start + frameSize, pcm.size)
            for (i in start until end) {
                val norm = pcm[i] / 32768f
                sumSq += norm * norm
            }
            sqrt(sumSq / (end - start).toFloat())
        }

        val maxEnergy = energies.maxOrNull() ?: return pcm
        if (maxEnergy < 1e-6f) return pcm

        val cutoff = maxEnergy * silenceThreshold
        var firstActive = -1
        var lastActive = -1

        for (i in energies.indices) {
            if (energies[i] >= cutoff) {
                if (firstActive < 0) firstActive = i
                lastActive = i
            }
        }

        if (firstActive < 0 || lastActive < 0) return ShortArray(0)

        val trimStart = firstActive * frameSize
        val trimEnd = minOf((lastActive + 1) * frameSize, pcm.size)
        return pcm.copyOfRange(trimStart, trimEnd)
    }

    /**
     * Compute DTW distance between a reference MFCC sequence and a test MFCC sequence.
     *
     * Uses Euclidean distance as the local cost metric and a standard
     * symmetric DTW algorithm with no window constraint.
     *
     * @param reference Reference MFCC frames (e.g. saved wake-word template).
     * @param test Test MFCC frames (e.g. from live audio).
     * @return DTW distance. Lower values indicate better match.
     */
    fun dtwDistance(reference: List<FloatArray>, test: List<FloatArray>): Float {
        if (reference.isEmpty() || test.isEmpty()) {
            return Float.MAX_VALUE
        }

        val n = reference.size
        val m = test.size

        // DTW cost matrix.
        val cost = Array(n) { FloatArray(m) { Float.MAX_VALUE } }
        cost[0][0] = euclideanDistance(reference[0], test[0])

        // First column.
        for (i in 1 until n) {
            cost[i][0] = cost[i - 1][0] + euclideanDistance(reference[i], test[0])
        }

        // First row.
        for (j in 1 until m) {
            cost[0][j] = cost[0][j - 1] + euclideanDistance(reference[0], test[j])
        }

        // Fill the rest.
        for (i in 1 until n) {
            for (j in 1 until m) {
                val d = euclideanDistance(reference[i], test[j])
                cost[i][j] = d + minOf(cost[i - 1][j], cost[i][j - 1], cost[i - 1][j - 1])
            }
        }

        return cost[n - 1][m - 1]
    }

    /**
     * Compute cosine similarity between two equal-length MFCC frames.
     *
     * Returns a value in [-1, 1]. Higher values indicate better match.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) {
            return 0f
        }
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        if (denom < 1e-10f) {
            return 0f
        }
        return dot / denom
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun applyPreEmphasis(samples: FloatArray): FloatArray {
        if (preEmphasisAlpha <= 0f) {
            return samples
        }
        val result = FloatArray(samples.size)
        result[0] = samples[0]
        for (i in 1 until samples.size) {
            result[i] = samples[i] - preEmphasisAlpha * samples[i - 1]
        }
        return result
    }

    private fun computePowerSpectrum(frame: FloatArray): FloatArray {
        val real = FloatArray(fftSize)
        val imag = FloatArray(fftSize)
        System.arraycopy(frame, 0, real, 0, frame.size)
        fft(real, imag)
        val power = FloatArray(fftSize / 2 + 1)
        for (k in power.indices) {
            power[k] = (real[k] * real[k] + imag[k] * imag[k]) / fftSize
        }
        return power
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = fftSize
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tmpRe = real[i]
                real[i] = real[j]
                real[j] = tmpRe
                val tmpIm = imag[i]
                imag[i] = imag[j]
                imag[j] = tmpIm
            }
        }
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val twiddleStep = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until halfLen) {
                    val tIdx = k * twiddleStep
                    val wRe = twiddleCos[tIdx]
                    val wIm = -twiddleSin[tIdx]
                    val i1 = i + k
                    val i2 = i1 + halfLen
                    val tRe = real[i2] * wRe - imag[i2] * wIm
                    val tIm = real[i2] * wIm + imag[i2] * wRe
                    real[i2] = real[i1] - tRe
                    imag[i2] = imag[i1] - tIm
                    real[i1] = real[i1] + tRe
                    imag[i1] = imag[i1] + tIm
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Normalize MFCC frames using zero-mean unit-variance normalization.
     * This makes matching more robust to volume differences.
     */
    fun normalizeFrames(frames: List<FloatArray>): List<FloatArray> {
        if (frames.isEmpty()) return frames
        val numCoeffs = frames[0].size
        val normalized = frames.map { it.copyOf() }

        for (c in 0 until numCoeffs) {
            var sum = 0f
            for (f in frames.indices) sum += frames[f][c]
            val mean = sum / frames.size

            var sumSqDiff = 0f
            for (f in frames.indices) {
                val diff = frames[f][c] - mean
                sumSqDiff += diff * diff
            }
            val stdDev = sqrt(sumSqDiff / frames.size).coerceAtLeast(1e-6f)

            for (f in normalized.indices) {
                normalized[f][c] = (normalized[f][c] - mean) / stdDev
            }
        }
        return normalized
    }

    private fun computeMelFilterbank(): Array<FloatArray> {
        val numBins = fftSize / 2 + 1
        val melLow = freqToMel(lowFreq)
        val melHigh = freqToMel(highFreq)

        val centreFreqs = FloatArray(numFilters + 2)
        for (i in centreFreqs.indices) {
            val mel = melLow + (melHigh - melLow) * i / (numFilters + 1)
            centreFreqs[i] = melToFreq(mel)
        }

        val binIndices = IntArray(centreFreqs.size) { i ->
            ((fftSize + 1) * centreFreqs[i] / sampleRate).toInt().coerceIn(0, numBins - 1)
        }

        val filterbank = Array(numFilters) { FloatArray(numBins) }

        for (m in 0 until numFilters) {
            val startBin = binIndices[m]
            val centreBin = binIndices[m + 1]
            val endBin = binIndices[m + 2]

            for (k in startBin until centreBin) {
                filterbank[m][k] = (k - startBin).toFloat() / (centreBin - startBin)
            }

            for (k in centreBin until endBin) {
                filterbank[m][k] = (endBin - k).toFloat() / (endBin - centreBin)
            }
        }

        return filterbank
    }

    private fun computeDctMatrix(): Array<FloatArray> {
        val matrix = Array(numCoefficients) { FloatArray(numFilters) }
        for (c in 0 until numCoefficients) {
            for (f in 0 until numFilters) {
                val angle = kotlin.math.PI.toFloat() * c * (f + 0.5f) / numFilters
                matrix[c][f] = kotlin.math.cos(angle)
            }
        }
        return matrix
    }

    private fun euclideanDistance(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val diff = a[i] - b[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

    companion object {
        private fun freqToMel(freqHz: Float): Float {
            return 2595f * kotlin.math.log10(1f + freqHz / 700f)
        }

        private fun melToFreq(mel: Float): Float {
            val base = 10f
            return 700f * (base.pow(mel / 2595f) - 1f)
        }

        private fun nextPowerOfTwo(n: Int): Int {
            var x = n
            x--
            x = x or (x shr 1)
            x = x or (x shr 2)
            x = x or (x shr 4)
            x = x or (x shr 8)
            x = x or (x shr 16)
            return x + 1
        }

        private fun computeHammingWindow(size: Int): FloatArray {
            return FloatArray(size) { i ->
                0.54f - 0.46f * kotlin.math.cos(2f * kotlin.math.PI.toFloat() * i / (size - 1))
            }
        }
    }
}