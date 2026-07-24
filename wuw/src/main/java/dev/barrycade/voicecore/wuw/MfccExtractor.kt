package dev.barrycade.voicecore.wuw

import kotlin.math.pow

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
    val numCoefficients: Int = 13,

    /** Frame length in milliseconds. */
    val frameDurationMs: Int = 25,

    /** Frame stride (hop) in milliseconds. */
    val frameStrideMs: Int = 10,

    /** Number of mel filterbank channels. */
    val numFilters: Int = 26,

    /** Lower frequency bound for the mel filterbank. */
    val lowFreq: Float = 0f,

    /** Upper frequency bound for the mel filterbank. Default to Nyquist. */
    val highFreq: Float = 8000f,

    /** Pre-emphasis alpha coefficient. 0.0 disables pre-emphasis. */
    val preEmphasisAlpha: Float = 0.97f
) {
    /** Frame size in samples. */
    val frameSize: Int = (sampleRate * frameDurationMs / 1000f).toInt()

    /** Frame stride in samples. */
    val frameStride: Int = (sampleRate * frameStrideMs / 1000f).toInt()

    /** FFT size (next power of two >= frameSize). */
    val fftSize: Int = nextPowerOfTwo(frameSize)

    /** Pre-computed Hamming window. */
    private val hammingWindow: FloatArray = computeHammingWindow(frameSize)

    /** Pre-computed mel filterbank: [numFilters] x [fftSize/2 + 1]. */
    private val melFilterbank: Array<FloatArray> = computeMelFilterbank()

    /** Pre-computed DCT matrix: [numCoefficients] x [numFilters]. */
    private val dctMatrix: Array<FloatArray> = computeDctMatrix()

    // ── FFT twiddle factors ─────────────────────────────────────────────
    /** Pre-computed cos(2πk/N) for k = 0 .. N/2 - 1 */
    private val twiddleCos: FloatArray
    /** Pre-computed sin(2πk/N) for k = 0 .. N/2 - 1 */
    private val twiddleSin: FloatArray

    init {
        val half = fftSize / 2
        twiddleCos = FloatArray(half) { k ->
            kotlin.math.cos(2f * kotlin.math.PI.toFloat() * k / fftSize)
        }
        twiddleSin = FloatArray(half) { k ->
            kotlin.math.sin(2f * kotlin.math.PI.toFloat() * k / fftSize)
        }
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
        val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
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
            val stdDev = kotlin.math.sqrt(sumSqDiff / frames.size).coerceAtLeast(1e-6f)

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

        // Centre frequencies of mel filters in Hz.
        val centreFreqs = FloatArray(numFilters + 2)
        for (i in centreFreqs.indices) {
            val mel = melLow + (melHigh - melLow) * i / (numFilters + 1)
            centreFreqs[i] = melToFreq(mel)
        }

        // Map centre frequencies to FFT bin indices.
        val binIndices = IntArray(centreFreqs.size) { i ->
            (centreFreqs[i] / sampleRate * 2 * fftSize).toInt().coerceIn(0, numBins - 1)
        }

        val filterbank = Array(numFilters) { FloatArray(numBins) }

        for (m in 0 until numFilters) {
            val startBin = binIndices[m]
            val centreBin = binIndices[m + 1]
            val endBin = binIndices[m + 2]

            // Rising slope.
            for (k in startBin until centreBin) {
                filterbank[m][k] = (k - startBin).toFloat() / (centreBin - startBin)
            }

            // Falling slope.
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
        return kotlin.math.sqrt(sum)
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


