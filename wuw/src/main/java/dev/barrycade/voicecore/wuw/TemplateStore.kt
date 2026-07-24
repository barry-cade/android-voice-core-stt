package dev.barrycade.voicecore.wuw

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persists and loads MFCC templates for wake-word matching.
 *
 * Stores templates as binary blobs in the app's internal storage.
 * Each template is a sequence of MFCC frames, where each frame
 * is a [FloatArray] of coefficients.
 *
 * The default file name is "wuw_template.bin". Multiple templates
 * are stored in separate files.
 */
class TemplateStore(private val context: Context) {

    companion object {
        private const val DEFAULT_TEMPLATE_NAME = "wuw_template.bin"
    }

    /**
     * Save an MFCC template to persistent storage.
     *
     * Format: [numFrames (int)] [numCoefficients (int)] [coefficient values (float × numFrames × numCoefficients)]
     *
     * @param mfccFrames The MFCC frames to save.
     * @param name Optional file name. Defaults to [DEFAULT_TEMPLATE_NAME].
     */
    fun saveTemplate(mfccFrames: List<FloatArray>, name: String = DEFAULT_TEMPLATE_NAME) {
        if (mfccFrames.isEmpty()) {
            return
        }

        val numCoefficients = mfccFrames[0].size
        val file = File(context.filesDir, name)

        DataOutputStream(FileOutputStream(file)).use { dos ->
            dos.writeInt(mfccFrames.size)
            dos.writeInt(numCoefficients)
            for (frame in mfccFrames) {
                for (coeff in frame) {
                    dos.writeFloat(coeff)
                }
            }
            dos.flush()
        }
    }

    /**
     * Load an MFCC template from persistent storage.
     *
     * @param name Optional file name. Defaults to [DEFAULT_TEMPLATE_NAME].
     * @return The loaded MFCC frames, or empty list if the file doesn't exist or is corrupt.
     */
    fun loadTemplate(name: String = DEFAULT_TEMPLATE_NAME): List<FloatArray> {
        val file = File(context.filesDir, name)

        if (!file.exists()) {
            return emptyList()
        }

        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val numFrames = dis.readInt()
                val numCoefficients = dis.readInt()
                val frames = mutableListOf<FloatArray>()

                for (i in 0 until numFrames) {
                    val frame = FloatArray(numCoefficients) { dis.readFloat() }
                    frames.add(frame)
                }

                frames
            }
        } catch (e: Exception) {
            // Corrupt file — return empty.
            emptyList()
        }
    }

    /**
     * Delete a saved template file.
     *
     * @param name Optional file name. Defaults to [DEFAULT_TEMPLATE_NAME].
     * @return True if the file was deleted, false otherwise.
     */
    fun deleteTemplate(name: String = DEFAULT_TEMPLATE_NAME): Boolean {
        val file = File(context.filesDir, name)
        return file.delete()
    }

    /**
     * Check if a template exists.
     *
     * @param name Optional file name. Defaults to [DEFAULT_TEMPLATE_NAME].
     * @return True if the template file exists.
     */
    fun hasTemplate(name: String = DEFAULT_TEMPLATE_NAME): Boolean {
        val file = File(context.filesDir, name)
        return file.exists()
    }
}
