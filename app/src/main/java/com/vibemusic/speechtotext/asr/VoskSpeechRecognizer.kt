package com.vibemusic.speechtotext.asr

import android.content.Context
import android.util.Log
import com.vibemusic.speechtotext.audio.AudioConfig
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.model.VoskModelManager
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline Speech Recognition Engine powered by Vosk, supporting dynamic language selection.
 *
 * Employs automatic cache validation, ensuring all required acoustic and graph
 * model components (am/final.mdl, graph/HCLr.fst, conf/model.conf) are intact before
 * instantiating the native Kaldi recognizer.
 */
class VoskSpeechRecognizer(
    private val context: Context,
    val modelManager: VoskModelManager = VoskModelManager(context),
    initialLanguage: SpeechLanguage = SpeechLanguage.ENGLISH
) : SpeechRecognizerEngine {

    companion object {
        private const val TAG = "SpeechPipeline"
        private const val INTERNAL_TAG = "VoskRecognizer"
    }

    override var currentLanguage: SpeechLanguage = initialLanguage
        private set

    override val name: String
        get() = "Vosk Offline ${currentLanguage.displayName} ASR"

    override var isAvailable: Boolean = false
        private set

    override var statusDescription: String = "Initializing..."
        private set

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @Synchronized
    override fun initialize(): Boolean {
        return loadModelForLanguage(currentLanguage)
    }

    @Synchronized
    override fun switchLanguage(newLanguage: SpeechLanguage): Boolean {
        Log.i(TAG, "SpeechPipeline: Selected language = ${newLanguage.displayName}")
        currentLanguage = newLanguage

        // 1. Release active recognizer and model native resources
        releaseInternal()

        // 2. Load the newly selected language model
        return loadModelForLanguage(newLanguage)
    }

    @Synchronized
    private fun loadModelForLanguage(language: SpeechLanguage): Boolean {
        try {
            statusDescription = "Loading ${language.displayName} model..."
            Log.i(TAG, "SpeechPipeline: Loading ${language.displayName} model")

            // 1. Locate and validate model directory
            val modelDir = modelManager.getModelDirectory(language)
            val modelExists = modelDir != null && modelManager.isCompleteModelDir(modelDir)

            Log.i(TAG, "SpeechPipeline: ${language.displayName} model path = ${modelDir?.absolutePath ?: "null"}")
            Log.i(TAG, "SpeechPipeline: ${language.displayName} model exists = $modelExists")

            if (modelDir == null || !modelExists) {
                isAvailable = false
                statusDescription = "${language.displayName} speech recognition model is not installed."
                Log.w(TAG, "SpeechPipeline: ${language.displayName} model is missing or incomplete: ${modelManager.getMissingFiles(language)}")
                return false
            }

            // 2. Ensure both am/final.mdl and final.mdl exist in the directory
            modelManager.ensureModelCompatibility(modelDir)

            // 3. Instantiate native Kaldi model
            Log.d(INTERNAL_TAG, "Instantiating Vosk Model from ${modelDir.absolutePath}...")
            model = Model(modelDir.absolutePath)
            Log.i(TAG, "SpeechPipeline: ${language.displayName} model loaded")

            // 4. Instantiate native recognizer with 16kHz sample rate
            recognizer = Recognizer(model, AudioConfig.SAMPLE_RATE.toFloat())
            isAvailable = true
            statusDescription = "Vosk ${language.displayName} Ready"
            Log.i(TAG, "SpeechPipeline: Vosk recognizer initialized")
            Log.i(TAG, "SpeechPipeline: Vosk ${language.displayName} ASR ready for 16kHz mono audio")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "SpeechPipeline: Failed to initialize ${language.displayName} model: ${e.message}", e)
            isAvailable = false
            statusDescription = "Unable to load ${language.displayName} speech recognition model."
            releaseInternal()
            return false
        }
    }

    @Synchronized
    override fun acceptAudio(audio: ByteArray): Boolean {
        val rec = recognizer ?: return false
        return try {
            rec.acceptWaveForm(audio, audio.size)
        } catch (e: Exception) {
            Log.e(INTERNAL_TAG, "Error passing audio waveform to Vosk", e)
            false
        }
    }

    @Synchronized
    override fun getPartialResult(): String {
        val rec = recognizer ?: return ""
        return try {
            val json = rec.partialResult
            extractTextFromJson(json, "partial")
        } catch (e: Exception) {
            Log.e(INTERNAL_TAG, "Error reading partial result", e)
            ""
        }
    }

    @Synchronized
    override fun getFinalResult(): String {
        val rec = recognizer ?: return ""
        return try {
            val finalJson = rec.finalResult
            val text = extractTextFromJson(finalJson, "text")
            if (text.isNotEmpty()) text else extractTextFromJson(rec.result, "text")
        } catch (e: Exception) {
            Log.e(INTERNAL_TAG, "Error reading final result", e)
            ""
        }
    }

    @Synchronized
    override fun getResult(): String {
        val rec = recognizer ?: return ""
        return try {
            val json = rec.result
            extractTextFromJson(json, "text")
        } catch (e: Exception) {
            Log.e(INTERNAL_TAG, "Error reading result", e)
            ""
        }
    }

    private fun extractTextFromJson(jsonString: String, key: String): String {
        return try {
            val obj = JSONObject(jsonString)
            obj.optString(key, "").trim()
        } catch (_: Exception) {
            ""
        }
    }

    @Synchronized
    override fun reset() {
        try {
            recognizer?.reset()
        } catch (e: Exception) {
            Log.e(INTERNAL_TAG, "Error resetting Vosk recognizer", e)
        }
    }

    @Synchronized
    private fun releaseInternal() {
        try {
            recognizer?.close()
        } catch (e: Exception) {
            Log.w(INTERNAL_TAG, "Error closing Vosk recognizer", e)
        } finally {
            recognizer = null
        }

        try {
            model?.close()
        } catch (e: Exception) {
            Log.w(INTERNAL_TAG, "Error closing Vosk model", e)
        } finally {
            model = null
            isAvailable = false
        }
    }

    @Synchronized
    override fun release() {
        releaseInternal()
        statusDescription = "Released"
    }
}

