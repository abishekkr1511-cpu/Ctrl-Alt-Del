package com.vibemusic.speechtotext.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Robust utility for extracting and verifying offline AI models on Android.
 *
 * Designed to bypass Android AssetManager quirks (where list() on a leaf file can return non-empty)
 * by attempting stream open first, and always ensuring parent directories exist.
 */
object ModelStorageHelper {
    private const val TAG = "ModelStorageHelper"

    /**
     * Recursively extracts an asset directory to a target directory in application storage.
     * Uses try-open semantics: if open() succeeds, it's a file; otherwise it's a folder.
     */
    fun copyAssetFolder(context: Context, assetPath: String, targetDir: File): Boolean {
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        return try {
            val assetManager = context.assets
            val children = assetManager.list(assetPath) ?: return false

            for (child in children) {
                val subAssetPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
                val targetFile = File(targetDir, child)

                // Check if this child can be opened directly as a stream (meaning it is a file)
                var inputStream: InputStream? = null
                val isFile = try {
                    inputStream = assetManager.open(subAssetPath)
                    true
                } catch (_: Exception) {
                    false
                }

                if (isFile && inputStream != null) {
                    // It's a file - copy stream to target
                    try {
                        targetFile.parentFile?.mkdirs()
                        if (targetFile.exists() && targetFile.length() > 0) {
                            inputStream.close()
                            continue // Already extracted
                        }
                        FileOutputStream(targetFile).use { output ->
                            inputStream.copyTo(output)
                        }
                    } finally {
                        try { inputStream.close() } catch (_: Exception) {}
                    }
                } else {
                    // It's a directory - recurse
                    targetFile.mkdirs()
                    copyAssetFolder(context, subAssetPath, targetFile)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting asset folder: $assetPath", e)
            false
        }
    }

    /**
     * Verifies whether a target directory contains valid Vosk offline model files.
     */
    fun isValidVoskModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val finalMdl = File(dir, "final.mdl")
        val amFinalMdl = File(File(dir, "am"), "final.mdl")
        val confDir = File(dir, "conf")
        return (finalMdl.exists() || amFinalMdl.exists()) && confDir.exists()
    }
}
