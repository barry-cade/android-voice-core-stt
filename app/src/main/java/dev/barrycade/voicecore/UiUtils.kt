package dev.barrycade.voicecore

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import java.io.File
import java.io.FileOutputStream

/**
 * Common UI and Asset helper functions.
 */

/**
 * Recursively copy an asset folder tree to internal storage.
 */
fun copyAssetFolder(context: Context, assetFolder: String): File {
    val outDir = File(context.filesDir, assetFolder)
    copyAssetTree(context, assetFolder, outDir)
    return outDir
}

private fun copyAssetTree(context: Context, assetPath: String, outDir: File) {
    if (!outDir.exists()) outDir.mkdirs()

    val assetManager = context.assets
    val entries = assetManager.list(assetPath) ?: return

    for (entry in entries) {
        val childAssetPath = "$assetPath/$entry"
        val childOutFile = File(outDir, entry)
        // Try opening as a file — if it throws, it's a directory.
        try {
            assetManager.open(childAssetPath).use { inStream ->
                FileOutputStream(childOutFile).use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
        } catch (_: Exception) {
            copyAssetTree(context, childAssetPath, childOutFile)
        }
    }
}

/**
 * Escapes special characters for injection into a JSON string.
 */
fun escapeJsonString(value: String): String {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

/**
 * Displays a simple alert dialog with an OK button.
 */
fun showErrorDialog(context: Context, title: String, message: String) {
    AlertDialog.Builder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK", null)
        .show()
}

/**
 * Recursively enables or disables a view and all its children if it's a ViewGroup.
 */
fun setViewEnabled(view: View, enabled: Boolean) {
    view.isEnabled = enabled
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            setViewEnabled(view.getChildAt(i), enabled)
        }
    }
}
