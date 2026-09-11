package com.vibemusic.speechtotext.model

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.utils.ModelStorageHelper
import org.vosk.android.StorageService
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Robust manager for offline Vosk speech recognition models.
 *
 * Handles discovery, validation, asset synchronization, and ZIP archive importing
 * for all supported languages (English, Tamil, and future languages).
 */
class VoskModelManager(private val context: Context) {

    companion object {
        private const val TAG = "VoskModelManager"
        private const val MODELS_DIR_NAME = "models"
    }

    private val modelsBaseDir: File
        get() = File(context.filesDir, MODELS_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Resolves the verified model directory for the given language.
     * Returns null if the model is not installed or incomplete.
     */
    fun getModelDirectory(language: SpeechLanguage): File? {
        // 1. Check internal models directory: files/models/<modelDirName>
        val internalDir = File(modelsBaseDir, language.modelDirName)
        if (isCompleteModelDir(internalDir)) {
            ensureModelCompatibility(internalDir)
            return internalDir
        }

        // 2. Check legacy internal directory: files/<modelDirName>
        val legacyDir = File(context.filesDir, language.modelDirName)
        if (isCompleteModelDir(legacyDir)) {
            ensureModelCompatibility(legacyDir)
            return legacyDir
        }

        // 3. If bundled in assets, attempt extraction/sync
        val assetFolder = language.assetFolderName
        if (assetFolder != null && isAssetModelPresent(assetFolder)) {
            val extracted = syncOrExtractAssetModel(assetFolder)
            if (extracted != null && isCompleteModelDir(extracted)) {
                ensureModelCompatibility(extracted)
                return extracted
            }
        }

        // 4. Check if an asset exists matching modelDirName directly (e.g. assets/model-ta)
        if (isAssetModelPresent(language.modelDirName)) {
            val extracted = syncOrExtractAssetModel(language.modelDirName)
            if (extracted != null && isCompleteModelDir(extracted)) {
                ensureModelCompatibility(extracted)
                return extracted
            }
        }

        return null
    }

    /**
     * Determines current lifecycle status of the model for the given language.
     */
    fun getModelStatus(language: SpeechLanguage): ModelStatus {
        val dir = getModelDirectory(language)
        return if (dir != null && isCompleteModelDir(dir)) {
            ModelStatus.READY
        } else {
            ModelStatus.NOT_INSTALLED
        }
    }

    /**
     * Checks if the model for the given language is completely installed and ready for Kaldi.
     */
    fun isModelReady(language: SpeechLanguage): Boolean {
        return getModelDirectory(language) != null
    }

    /**
     * Diagnostic helper returning names of missing required Kaldi files if directory exists.
     */
    fun getMissingFiles(language: SpeechLanguage): List<String> {
        val dir = File(modelsBaseDir, language.modelDirName).takeIf { it.exists() }
            ?: File(context.filesDir, language.modelDirName).takeIf { it.exists() }
            ?: return listOf("Directory does not exist")

        val missing = mutableListOf<String>()
        val hasAcoustic = File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
        val hasGraph = File(dir, "graph/HCLr.fst").exists() || File(dir, "graph/Gr.fst").exists()
        val hasConf = File(dir, "conf/model.conf").exists()

        if (!hasAcoustic) missing.add("am/final.mdl or final.mdl")
        if (!hasGraph) missing.add("graph/HCLr.fst or graph/Gr.fst")
        if (!hasConf) missing.add("conf/model.conf")
        return missing
    }

    /**
     * Validates that all files required by Kaldi's offline decoder exist on disk.
     */
    fun isCompleteModelDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        val hasAcoustic = File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()
        val hasGraph = File(dir, "graph/HCLr.fst").exists() || File(dir, "graph/Gr.fst").exists()
        val hasConf = File(dir, "conf/model.conf").exists()
        return hasAcoustic && hasGraph && hasConf
    }

    /**
     * Ensures both am/final.mdl and final.mdl exist for maximum Kaldi decoder compatibility.
     */
    fun ensureModelCompatibility(modelDir: File) {
        val amDir = File(modelDir, "am")
        val amFinalMdl = File(amDir, "final.mdl")
        val rootFinalMdl = File(modelDir, "final.mdl")

        if (!amFinalMdl.exists() && rootFinalMdl.exists()) {
            amDir.mkdirs()
            rootFinalMdl.copyTo(amFinalMdl, overwrite = true)
            Log.d(TAG, "Mirrored final.mdl into am/final.mdl for Kaldi compatibility")
        }
        if (!rootFinalMdl.exists() && amFinalMdl.exists()) {
            amFinalMdl.copyTo(rootFinalMdl, overwrite = true)
            Log.d(TAG, "Mirrored am/final.mdl into root final.mdl for Kaldi compatibility")
        }
    }

    /**
     * Unpacks and installs a Vosk model from a ZIP file selected by the user.
     * Automatically discovers the model root even if wrapped in nested folders.
     */
    fun importModelFromZip(language: SpeechLanguage, zipUri: Uri): Result<File> {
        val inputStream: InputStream = try {
            context.contentResolver.openInputStream(zipUri)
                ?: return Result.failure(IllegalArgumentException("Cannot open stream for selected file"))
        } catch (e: Exception) {
            return Result.failure(e)
        }
        return installFromStream(language, inputStream)
    }

    /**
     * Installs a model from a local File (e.g. from Downloads folder).
     */
    fun importModelFromFile(language: SpeechLanguage, zipFile: File): Result<File> {
        if (!zipFile.exists() || !zipFile.canRead()) {
            return Result.failure(IllegalArgumentException("File does not exist or is not readable: ${zipFile.absolutePath}"))
        }
        return installFromStream(language, zipFile.inputStream())
    }

    /**
     * Downloads and installs a Vosk model archive from a remote URL.
     * Includes timeout, progress updates, and storage space validation.
     */
    fun downloadAndInstallModel(
        language: SpeechLanguage,
        urlString: String,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        val tempZipFile = File(context.cacheDir, "${language.modelDirName}_download_${System.currentTimeMillis()}.zip")
        return try {
            Log.i(TAG, "Downloading model for ${language.displayName} from: $urlString")
            val url = java.net.URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true
            connection.connect()

            if (connection.responseCode !in 200..299) {
                return Result.failure(IllegalStateException("Server returned HTTP ${connection.responseCode}: ${connection.responseMessage}"))
            }

            val totalBytes = connection.contentLengthLong
            // Check available storage space (require at least 2.5x download size or 150MB)
            val requiredSpace = if (totalBytes > 0) totalBytes * 3 else 150L * 1024 * 1024
            if (context.filesDir.usableSpace < requiredSpace) {
                return Result.failure(IllegalStateException("Insufficient device storage to install speech model."))
            }

            var downloadedBytes = 0L
            connection.inputStream.use { input ->
                FileOutputStream(tempZipFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes.toFloat())
                        }
                    }
                }
            }

            Log.i(TAG, "Download complete (${tempZipFile.length()} bytes). Extracting and installing...")
            installFromStream(language, tempZipFile.inputStream())
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to download model for ${language.displayName}", e)
            Result.failure(e)
        } finally {
            tempZipFile.delete()
        }
    }

    /**
     * Checks device Downloads directory for any matching pre-downloaded model zip files.
     */
    fun scanDownloadsForModel(language: SpeechLanguage): File? {
        return try {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            )
            if (downloadsDir != null && downloadsDir.exists()) {
                val files = downloadsDir.listFiles() ?: return null
                files.firstOrNull { file ->
                    file.isFile && file.name.endsWith(".zip", ignoreCase = true) &&
                    (file.name.contains(language.modelDirName, ignoreCase = true) ||
                     file.name.contains("vosk", ignoreCase = true) && file.name.contains(language.displayName, ignoreCase = true) ||
                     file.name.contains(language.id, ignoreCase = true))
                }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun installFromStream(language: SpeechLanguage, inputStream: InputStream): Result<File> {
        val tempExtractDir = File(context.cacheDir, "model_import_${System.currentTimeMillis()}")
        val targetDir = File(modelsBaseDir, language.modelDirName)

        return try {
            // Check storage space
            if (context.filesDir.usableSpace < 80L * 1024 * 1024) {
                return Result.failure(IllegalStateException("Insufficient device storage. At least 80MB free space is required."))
            }

            tempExtractDir.mkdirs()
            unzip(inputStream, tempExtractDir)
            inputStream.close()

            // Locate the model root inside the extracted files
            val modelRoot = findModelRoot(tempExtractDir)
                ?: return Result.failure(IllegalStateException(
                    "Selected ZIP does not contain a valid Vosk model.\n" +
                    "A valid model must contain: conf/model.conf, final.mdl (or am/final.mdl), and graph/HCLr.fst (or Gr.fst)"
                ))

            Log.i(TAG, "Found valid model root at: ${modelRoot.absolutePath}")

            // Clean existing target dir and copy files
            targetDir.deleteRecursively()
            targetDir.mkdirs()
            modelRoot.copyRecursively(targetDir, overwrite = true)

            ensureModelCompatibility(targetDir)

            if (!isCompleteModelDir(targetDir)) {
                val missing = getMissingFiles(language).joinToString(", ")
                targetDir.deleteRecursively()
                return Result.failure(IllegalStateException("Model is missing required files: $missing"))
            }

            Log.i(TAG, "Successfully installed ${language.displayName} model at: ${targetDir.absolutePath}")
            Result.success(targetDir)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to import model for ${language.displayName}", e)
            targetDir.deleteRecursively()
            Result.failure(e)
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    /**
     * Searches recursively for the directory containing `conf/model.conf` and `final.mdl`.
     */
    private fun findModelRoot(dir: File): File? {
        if (isCompleteModelDir(dir)) return dir
        val children = dir.listFiles() ?: return null
        for (child in children) {
            if (child.isDirectory) {
                val found = findModelRoot(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Unzips an input stream into a target destination directory with path traversal protection.
     */
    private fun unzip(inputStream: InputStream, destinationDir: File) {
        ZipInputStream(inputStream).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            val destCanonicalPath = destinationDir.canonicalPath

            while (entry != null) {
                val newFile = File(destinationDir, entry.name)
                // Path traversal protection
                if (!newFile.canonicalPath.startsWith(destCanonicalPath + File.separator) &&
                    newFile.canonicalPath != destCanonicalPath) {
                    throw SecurityException("Zip entry is outside of target dir: ${entry.name}")
                }

                if (entry.isDirectory) {
                    newFile.mkdirs()
                } else {
                    newFile.parentFile?.mkdirs()
                    FileOutputStream(newFile).use { fos ->
                        zis.copyTo(fos)
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun isAssetModelPresent(assetFolder: String): Boolean {
        return try {
            val list = context.assets.list(assetFolder)
            !list.isNullOrEmpty()
        } catch (_: Exception) {
            false
        }
    }

    private fun syncOrExtractAssetModel(assetFolder: String): File? {
        // 1. Try StorageService.sync
        try {
            val syncedPath = StorageService.sync(context, assetFolder, "model")
            val syncedDir = File(syncedPath)
            if (isCompleteModelDir(syncedDir)) {
                return syncedDir
            } else {
                syncedDir.deleteRecursively()
                val resynced = StorageService.sync(context, assetFolder, "model")
                val resyncedDir = File(resynced)
                if (isCompleteModelDir(resyncedDir)) {
                    return resyncedDir
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "StorageService.sync failed for $assetFolder: ${e.message}")
        }

        // 2. Direct extraction fallback
        val targetDir = File(context.filesDir, assetFolder)
        if (!isCompleteModelDir(targetDir)) {
            targetDir.deleteRecursively()
            ModelStorageHelper.copyAssetFolder(context, assetFolder, targetDir)
        }
        return if (isCompleteModelDir(targetDir)) targetDir else null
    }
}
