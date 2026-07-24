package dev.barrycade.voicecore.wuw

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Persists and loads named MFCC templates for wake-word matching.
 *
 * Stores a manifest file (wuw_manifest.json) listing all template names,
 * and individual binary blobs: wuw_template_{name}.bin.
 *
 * Each template binary format:
 *   [numFrames (int)] [numCoefficients (int)] [coefficients (float x ...)]
 */
class TemplateStore(private val context: Context) {

    companion object {
        private const val MANIFEST_NAME = "wuw_manifest.json"
        private const val TEMPLATE_PREFIX = "wuw_template_"
        private const val PCM_PREFIX = "wuw_audio_"
        private const val TEMPLATE_SUFFIX = ".bin"
        private const val PCM_SUFFIX = ".pcm"
    }

    /** Info about a saved template. */
    data class TemplateInfo(
        val name: String,
        val frameCount: Int,
        val coefficientCount: Int
    )

    // ── Manifest management ──────────────────────────────────────────────────

    /**
     * List all saved templates.
     */
    fun listTemplates(): List<TemplateInfo> {
        val manifest = loadManifest()
        val templates = mutableListOf<TemplateInfo>()
        val arr = manifest.optJSONArray("templates")
        if (arr == null) return templates

        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i)
            if (entry == null) continue
            templates.add(
                TemplateInfo(
                    name = entry.optString("name", ""),
                    frameCount = entry.optInt("frameCount", 0),
                    coefficientCount = entry.optInt("coefficientCount", 0)
                )
            )
        }
        return templates
    }

    /**
     * Save an MFCC template with a given name.
     */
    fun saveTemplate(name: String, mfccFrames: List<FloatArray>, rawPcm: ShortArray? = null) {
        if (mfccFrames.isEmpty()) return

        val numCoefficients = mfccFrames[0].size
        val file = templateFile(name)

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

        // Save raw PCM if provided.
        rawPcm?.let { pcm ->
            val pcmFile = pcmFile(name)
            DataOutputStream(FileOutputStream(pcmFile)).use { dos ->
                dos.writeInt(pcm.size)
                for (s in pcm) {
                    dos.writeShort(s.toInt())
                }
                dos.flush()
            }
        }

        // Update manifest.
        val manifest = loadManifest()
        var templates = manifest.optJSONArray("templates")
        if (templates == null) {
            templates = JSONArray()
            manifest.put("templates", templates)
        }

        var found = false
        for (i in 0 until templates.length()) {
            val entry = templates.optJSONObject(i)
            if (entry != null && entry.optString("name", "") == name) {
                entry.put("frameCount", mfccFrames.size)
                entry.put("coefficientCount", numCoefficients)
                found = true
                break
            }
        }
        if (!found) {
            val entry = JSONObject()
            entry.put("name", name)
            entry.put("frameCount", mfccFrames.size)
            entry.put("coefficientCount", numCoefficients)
            templates.put(entry)
        }

        saveManifest(manifest)
    }

    /**
     * Load an MFCC template by name.
     */
    fun loadTemplate(name: String): List<FloatArray> {
        val file = templateFile(name)
        if (!file.exists()) return emptyList()

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
            emptyList()
        }
    }

    /**
     * Load the raw PCM audio for a template.
     */
    fun loadPcm(name: String): ShortArray? {
        val file = pcmFile(name)
        if (!file.exists()) return null

        return try {
            DataInputStream(FileInputStream(file)).use { dis ->
                val size = dis.readInt()
                ShortArray(size) { dis.readShort() }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete a saved template by name.
     */
    fun deleteTemplate(name: String): Boolean {
        val file = templateFile(name)
        val deletedFile = file.delete()

        val pcmFile = pcmFile(name)
        if (pcmFile.exists()) pcmFile.delete()

        val manifest = loadManifest()
        val templates = manifest.optJSONArray("templates")
        var removed = false
        if (templates != null) {
            val toRemove = mutableListOf<Int>()
            for (i in 0 until templates.length()) {
                val entry = templates.optJSONObject(i)
                if (entry != null && entry.optString("name", "") == name) {
                    toRemove.add(i)
                }
            }
            for (i in toRemove.reversed()) {
                templates.remove(i)
                removed = true
            }
        }
        if (removed) saveManifest(manifest)

        return deletedFile || removed
    }

    /**
     * Check if a template with the given name exists.
     */
    fun hasTemplate(name: String): Boolean {
        return templateFile(name).exists()
    }

    /**
     * Generate a unique template name based on a base name.
     */
    fun uniqueName(base: String): String {
        val existing = listTemplates().map { it.name }.toSet()
        var candidate = base
        var suffix = 1
        while (existing.contains(candidate)) {
            suffix += 1
            candidate = "${base}_$suffix"
        }
        return candidate
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun templateFile(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.filesDir, "$TEMPLATE_PREFIX$safeName$TEMPLATE_SUFFIX")
    }

    private fun pcmFile(name: String): File {
        val safeName = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.filesDir, "$PCM_PREFIX$safeName$PCM_SUFFIX")
    }

    private fun loadManifest(): JSONObject {
        val file = File(context.filesDir, MANIFEST_NAME)
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun saveManifest(manifest: JSONObject) {
        val file = File(context.filesDir, MANIFEST_NAME)
        file.writeText(manifest.toString(2))
    }
}
