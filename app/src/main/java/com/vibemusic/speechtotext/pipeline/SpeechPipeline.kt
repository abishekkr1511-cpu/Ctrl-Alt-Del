package com.vibemusic.speechtotext.pipeline

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vibemusic.speechtotext.asr.SpeechRecognizerEngine
import com.vibemusic.speechtotext.asr.VoskSpeechRecognizer
import com.vibemusic.speechtotext.audio.AudioCapture
import com.vibemusic.speechtotext.audio.AudioConfig
import com.vibemusic.speechtotext.buffer.SpeechBuffer
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.model.ModelStatus
import com.vibemusic.speechtotext.model.VoskModelManager
import com.vibemusic.speechtotext.noise.DeepFilterNetProcessor
import com.vibemusic.speechtotext.noise.NoiseSuppressor
import com.vibemusic.speechtotext.text.BasicTextProcessor
import com.vibemusic.speechtotext.text.TextProcessor
import com.vibemusic.speechtotext.vad.SileroVad
import com.vibemusic.speechtotext.vad.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central orchestrator connecting the entire offline speech-to-text pipeline:
 *
 * AudioCapture (16kHz Mono 16-bit PCM)
 *       ↓
 * NoiseSuppressor (DeepFilterNet / Passthrough)
 *       ↓
 * VoiceActivityDetector (Silero VAD ONNX)
 *       ↓
 * SpeechBuffer (Circular Pre-roll & Hysteresis)
 *       ↓
 * Language Selector (English / Tamil)
 *       ↓
 * SpeechRecognizerEngine (Vosk Offline ASR)
 *       ↓
 * Language-Aware TextProcessor (Tamil Unicode Safe / English Rules)
 *       ↓
 * UI (Dual State: partialText & finalText)
 */
class SpeechPipeline(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    val audioCapture: AudioCapture = AudioCapture(context),
    val noiseSuppressor: NoiseSuppressor = DeepFilterNetProcessor(context),
    val vad: VoiceActivityDetector = SileroVad(context),
    val modelManager: VoskModelManager = VoskModelManager(context),
    val asrEngine: SpeechRecognizerEngine = VoskSpeechRecognizer(context, modelManager),
    val textProcessor: TextProcessor = BasicTextProcessor()
) {
    companion object {
        private const val TAG = "SpeechPipeline"
    }

    private val _uiState = MutableStateFlow(PipelineUiState())
    val uiState: StateFlow<PipelineUiState> = _uiState.asStateFlow()

    private val speechBuffer: SpeechBuffer = SpeechBuffer(
        preBufferMs = 300L,
        silenceDurationMs = 800L,
        maxUtteranceDurationMs = 15000L,
        onUtteranceReady = ::handleCompletedBufferUtterance
    )

    private var isInitialized = false
    private var chunkCounter: Long = 0L

    init {
        // Collect real-time dB levels from microphone
        scope.launch {
            audioCapture.audioLevelDb.collect { db ->
                _uiState.update { current ->
                    current.copy(
                        audioLevelDb = db,
                        normalizedAudioLevel = AudioConfig.normalizeDbForUi(db)
                    )
                }
            }
        }

        // Attach audio frame listener
        audioCapture.frameListener = ::processAudioFrame
    }

    /**
     * Initializes all underlying ML models and native subsystems on Dispatchers.IO.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true

        Log.i(TAG, "SpeechPipeline: Initializing all ML models & native libraries...")

        _uiState.update {
            it.copy(
                componentStatus = it.componentStatus.copy(
                    asr = "Loading Model...",
                    vad = "Loading VAD..."
                ),
                debugVoskModelStatus = "LOADING...",
                debugVoskRecognizerStatus = "INITIALIZING..."
            )
        }

        // 1. Initialize Noise Suppressor
        noiseSuppressor.initialize()

        // 2. Initialize VAD
        val vadReady = vad.initialize()

        // 3. Initialize Vosk Offline ASR for default language (English)
        val asrReady = asrEngine.initialize()

        isInitialized = true
        updateComponentStatus()

        val allReady = vadReady && asrReady
        if (allReady) {
            Log.i(TAG, "SpeechPipeline: All models loaded successfully! Vosk and Silero VAD are READY.")
            _uiState.update {
                it.copy(
                    modelStatus = ModelStatus.READY,
                    debugVoskModelStatus = "READY",
                    debugVoskRecognizerStatus = "READY",
                    debugVadStatus = "READY",
                    errorMessage = null
                )
            }
        } else {
            val errorMsg = buildString {
                if (!asrReady) append("${asrEngine.statusDescription}\n")
                if (!vadReady) append("${vad.statusDescription}\n")
            }.trim()
            Log.e(TAG, "SpeechPipeline: Initialization issue: $errorMsg")
            _uiState.update {
                it.copy(
                    errorMessage = errorMsg,
                    modelStatus = if (asrReady) ModelStatus.READY else ModelStatus.ERROR,
                    debugVoskModelStatus = if (asrReady) "READY" else "ERROR",
                    debugVoskRecognizerStatus = if (asrReady) "READY" else "FAILED",
                    debugVadStatus = if (vadReady) "READY" else "FALLBACK"
                )
            }
        }

        return@withContext allReady
    }

    /**
     * Dynamically switches the active speech recognition language and model.
     * Safely stops ongoing recording, releases previous recognizer, loads the new model,
     * updates the text processor, and updates all UI diagnostics.
     */
    suspend fun switchLanguage(language: SpeechLanguage): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "SpeechPipeline: Selected language = ${language.displayName}")

        // 1. Safely stop current recording session if active
        if (_uiState.value.isListening) {
            stopListening()
        }

        // 2. Reset buffers
        speechBuffer.reset()
        vad.reset()

        // 3. Update TextProcessor for the selected language
        textProcessor.currentLanguage = language

        // 4. Update UI to indicate model loading
        _uiState.update {
            it.copy(
                selectedLanguage = language,
                debugVoskModelStatus = "LOADING...",
                debugVoskRecognizerStatus = "INITIALIZING...",
                partialText = ""
            )
        }

        // 5. Load model and initialize recognizer for the selected language
        val isModelReady = asrEngine.switchLanguage(language)
        val status = if (isModelReady) ModelStatus.INSTALLED else ModelStatus.NOT_INSTALLED

        _uiState.update { current ->
            current.copy(
                selectedLanguage = language,
                modelStatus = status,
                debugVoskModelStatus = if (isModelReady) "INSTALLED" else "NOT INSTALLED",
                debugVoskRecognizerStatus = if (isModelReady) "READY" else "STANDBY",
                errorMessage = if (!isModelReady) "${language.displayName} speech model is not installed." else null
            )
        }

        updateComponentStatus()
        return@withContext isModelReady
    }

    /**
     * Imports a user-selected Vosk model archive (.zip), extracts it into app internal storage,
     * validates all Kaldi files, and updates the model status.
     */
    suspend fun importModelZip(language: SpeechLanguage, zipUri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "SpeechPipeline: Importing ${language.displayName} model archive...")
        _uiState.update { it.copy(isImportingModel = true, errorMessage = null) }

        val importResult = modelManager.importModelFromZip(language, zipUri)
        if (importResult.isSuccess) {
            Log.i(TAG, "SpeechPipeline: Model import succeeded for ${language.displayName}!")
            _uiState.update { it.copy(isImportingModel = false, modelStatus = ModelStatus.INSTALLED) }

            // If the currently selected language matches the imported model, load it immediately
            if (_uiState.value.selectedLanguage == language) {
                switchLanguage(language)
            } else {
                updateComponentStatus()
            }
            Result.success(Unit)
        } else {
            val ex = importResult.exceptionOrNull()
            val errorMsg = ex?.localizedMessage ?: "Failed to import model."
            Log.e(TAG, "SpeechPipeline: Model import failed: $errorMsg", ex)
            _uiState.update {
                it.copy(
                    isImportingModel = false,
                    modelStatus = ModelStatus.ERROR,
                    errorMessage = errorMsg
                )
            }
            Result.failure(ex ?: Exception(errorMsg))
        }
    }

    /**
     * Downloads a Vosk model archive from a remote URL, extracts it, and marks it INSTALLED.
     */
    suspend fun downloadModel(language: SpeechLanguage, url: String): Result<Unit> = withContext(Dispatchers.IO) {
        Log.i(TAG, "SpeechPipeline: Downloading ${language.displayName} model from $url...")
        _uiState.update { it.copy(isImportingModel = true, errorMessage = null) }

        val downloadResult = modelManager.downloadAndInstallModel(language, url)
        if (downloadResult.isSuccess) {
            Log.i(TAG, "SpeechPipeline: Model download succeeded for ${language.displayName}!")
            _uiState.update { it.copy(isImportingModel = false, modelStatus = ModelStatus.INSTALLED) }
            if (_uiState.value.selectedLanguage == language) {
                switchLanguage(language)
            } else {
                updateComponentStatus()
            }
            Result.success(Unit)
        } else {
            val ex = downloadResult.exceptionOrNull()
            val errorMsg = ex?.localizedMessage ?: "Failed to download model."
            Log.e(TAG, "SpeechPipeline: Model download failed: $errorMsg", ex)
            _uiState.update {
                it.copy(
                    isImportingModel = false,
                    modelStatus = ModelStatus.ERROR,
                    errorMessage = errorMsg
                )
            }
            Result.failure(ex ?: Exception(errorMsg))
        }
    }

    /**
     * Scans the device's Downloads directory for any matching model zip and installs it.
     */
    suspend fun scanAndInstallModel(language: SpeechLanguage): Result<Unit> = withContext(Dispatchers.IO) {
        val foundFile = modelManager.scanDownloadsForModel(language)
            ?: return@withContext Result.failure(IllegalStateException("No matching model ZIP found in Downloads."))

        Log.i(TAG, "SpeechPipeline: Auto-detected model in Downloads: ${foundFile.absolutePath}")
        _uiState.update { it.copy(isImportingModel = true, errorMessage = null) }

        val importResult = modelManager.importModelFromFile(language, foundFile)
        if (importResult.isSuccess) {
            Log.i(TAG, "SpeechPipeline: Model installed from Downloads for ${language.displayName}!")
            _uiState.update { it.copy(isImportingModel = false, modelStatus = ModelStatus.INSTALLED) }
            if (_uiState.value.selectedLanguage == language) {
                switchLanguage(language)
            } else {
                updateComponentStatus()
            }
            Result.success(Unit)
        } else {
            val ex = importResult.exceptionOrNull()
            val errorMsg = ex?.localizedMessage ?: "Failed to import model from Downloads."
            _uiState.update {
                it.copy(
                    isImportingModel = false,
                    modelStatus = ModelStatus.ERROR,
                    errorMessage = errorMsg
                )
            }
            Result.failure(ex ?: Exception(errorMsg))
        }
    }

    /**
     * Core audio frame processing loop dispatched from AudioCapture thread.
     * Processes 512-sample (32ms) 16kHz mono 16-bit PCM frames in real-time.
     */
    private fun processAudioFrame(rawPcmFrame: ShortArray) {
        chunkCounter++
        val shouldLogChunk = chunkCounter % 50 == 0L

        // 1. Noise Suppression Stage
        val cleanFrame = if (noiseSuppressor.isEnabled && noiseSuppressor.isAvailable) {
            noiseSuppressor.process(rawPcmFrame)
        } else {
            rawPcmFrame
        }

        // 2. Voice Activity Detection Stage
        val vadResult = vad.processFrame(cleanFrame)
        val isSpeech = vadResult.isSpeech

        if (shouldLogChunk) {
            Log.d(TAG, "SpeechPipeline: Audio chunk #$chunkCounter received | VAD prob: %.2f | isSpeech: $isSpeech".format(vadResult.probability))
        }

        // 3. Feed SpeechBuffer
        speechBuffer.addFrame(cleanFrame, vadResult.state)

        // 4. Stream Audio Directly to Vosk ASR Engine
        // Direct continuous streaming ensures zero clipped words
        val settings = _uiState.value.settings
        val shouldProcessVosk = asrEngine.isAvailable && (settings.bypassVad || speechBuffer.isBufferingSpeech || isSpeech)

        if (shouldProcessVosk) {
            val audioBytes = AudioConfig.shortArrayToByteArray(cleanFrame)
            val isSentenceBoundary = asrEngine.acceptAudio(audioBytes)

            if (isSentenceBoundary) {
                // Kaldi found an endpoint/completed sentence
                val rawSentence = asrEngine.getResult()
                if (rawSentence.isNotBlank()) {
                    val cleanSentence = textProcessor.process(rawSentence)
                    Log.i(TAG, "SpeechPipeline: Vosk final = \"$cleanSentence\"")

                    _uiState.update { current ->
                        val updatedFinal = when {
                            current.finalText.isBlank() -> cleanSentence
                            current.finalText.endsWith(cleanSentence) -> current.finalText
                            else -> "${current.finalText}\n$cleanSentence"
                        }
                        current.copy(
                            finalText = updatedFinal,
                            recognizedText = updatedFinal,
                            partialText = "", // Clear partial text when final is confirmed
                            lastFinalResult = cleanSentence,
                            status = PipelineStatus.LISTENING
                        )
                    }
                    Log.d(TAG, "SpeechPipeline: UI text update: partialText=\"\", finalText updated")
                }
            } else {
                // In-progress speech: retrieve and display partial recognition
                val partial = asrEngine.getPartialResult()
                if (partial.isNotBlank()) {
                    val cleanPartial = textProcessor.process(partial)
                    if (shouldLogChunk) {
                        Log.d(TAG, "SpeechPipeline: Vosk partial = \"$cleanPartial\"")
                    }
                    _uiState.update { current ->
                        current.copy(
                            partialText = cleanPartial,
                            lastPartialResult = cleanPartial
                        )
                    }
                }
            }
        }

        // 5. Update UI status indicators
        val status = if (isSpeech) PipelineStatus.SPEECH_DETECTED else PipelineStatus.LISTENING

        _uiState.update { current ->
            if (current.isListening) {
                current.copy(
                    status = status,
                    speechProbability = vadResult.probability,
                    totalChunksProcessed = chunkCounter
                )
            } else {
                current
            }
        }
    }

    /**
     * Fallback handler for SpeechBuffer utterance flushes.
     */
    private fun handleCompletedBufferUtterance(utterancePcmBytes: ByteArray) {
        val settings = _uiState.value.settings
        if (settings.bypassVad) {
            // In direct streaming mode, sentences are handled immediately via acceptAudio
            return
        }

        scope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(status = PipelineStatus.PROCESSING) }
            Log.d(TAG, "SpeechPipeline: SpeechBuffer complete utterance received (${utterancePcmBytes.size} bytes)")

            asrEngine.acceptAudio(utterancePcmBytes)
            val rawText = asrEngine.getFinalResult().ifEmpty { asrEngine.getResult() }
            val cleanText = textProcessor.process(rawText)

            if (cleanText.isNotBlank()) {
                Log.i(TAG, "SpeechPipeline: SpeechBuffer Vosk final = \"$cleanText\"")
                _uiState.update { current ->
                    val updatedFinal = if (current.finalText.isBlank()) cleanText else "${current.finalText}\n$cleanText"
                    current.copy(
                        finalText = updatedFinal,
                        recognizedText = updatedFinal,
                        partialText = "",
                        lastFinalResult = cleanText
                    )
                }
            } else {
                _uiState.update { it.copy(partialText = "") }
            }

            asrEngine.reset()

            _uiState.update { current ->
                val nextStatus = if (current.isListening) PipelineStatus.LISTENING else PipelineStatus.READY
                current.copy(status = nextStatus)
            }
        }
    }

    /**
     * Starts audio capture and speech recognition.
     */
    fun startListening(): Result<Unit> {
        if (!audioCapture.hasPermission()) {
            val err = "Microphone permission is required for speech recognition."
            Log.w(TAG, "SpeechPipeline: startListening failed - permission not granted.")
            _uiState.update { it.copy(status = PipelineStatus.ERROR, errorMessage = err) }
            return Result.failure(SecurityException(err))
        }

        if (!asrEngine.isAvailable) {
            val err = "${_uiState.value.selectedLanguage.displayName} speech model is not installed."
            Log.w(TAG, "SpeechPipeline: startListening aborted - $err")
            _uiState.update { it.copy(status = PipelineStatus.ERROR, errorMessage = err) }
            return Result.failure(IllegalStateException(err))
        }

        if (_uiState.value.isListening) {
            Log.d(TAG, "SpeechPipeline: Already listening, ignoring duplicate start call.")
            return Result.success(Unit)
        }

        Log.i(TAG, "SpeechPipeline: AudioRecord starting...")
        val startResult = audioCapture.startRecording()
        if (startResult.isSuccess) {
            chunkCounter = 0L
            speechBuffer.reset()
            vad.reset()
            asrEngine.reset()

            Log.i(TAG, "SpeechPipeline: AudioRecord started successfully.")

            _uiState.update {
                it.copy(
                    isListening = true,
                    status = PipelineStatus.LISTENING,
                    partialText = "",
                    errorMessage = null,
                    debugCaptureStatus = "RECORDING"
                )
            }
            updateComponentStatus()
            return Result.success(Unit)
        } else {
            val err = startResult.exceptionOrNull()?.localizedMessage ?: "Failed to start microphone."
            Log.e(TAG, "SpeechPipeline: AudioRecord failed to start: $err")
            _uiState.update { it.copy(status = PipelineStatus.ERROR, errorMessage = err, debugCaptureStatus = "ERROR") }
            return startResult
        }
    }

    /**
     * Stops audio capture, finalizes any in-flight utterance, and updates UI state.
     */
    fun stopListening() {
        if (!_uiState.value.isListening) return

        Log.i(TAG, "SpeechPipeline: AudioRecord stopping...")
        audioCapture.stopRecording()

        // Flush any remaining text from the recognizer
        if (asrEngine.isAvailable) {
            val trailingText = asrEngine.getFinalResult()
            if (trailingText.isNotBlank()) {
                val clean = textProcessor.process(trailingText)
                Log.i(TAG, "SpeechPipeline: Vosk final on stop = \"$clean\"")
                _uiState.update { current ->
                    val updated = if (current.finalText.isBlank()) clean else "${current.finalText}\n$clean"
                    current.copy(
                        finalText = updated,
                        recognizedText = updated,
                        partialText = "",
                        lastFinalResult = clean
                    )
                }
            } else {
                _uiState.update { it.copy(partialText = "") }
            }
            asrEngine.reset()
        }

        Log.i(TAG, "SpeechPipeline: AudioRecord stopped. Final text length: ${_uiState.value.finalText.length}")

        _uiState.update {
            it.copy(
                isListening = false,
                status = PipelineStatus.READY,
                partialText = "",
                speechProbability = 0f,
                debugCaptureStatus = "STANDBY"
            )
        }
        updateComponentStatus()
    }

    /**
     * Clears both partial and final transcript text.
     */
    fun clearTranscript() {
        Log.i(TAG, "SpeechPipeline: Clearing all transcript text.")
        _uiState.update {
            it.copy(
                partialText = "",
                finalText = "",
                recognizedText = "",
                lastPartialResult = "",
                lastFinalResult = ""
            )
        }
    }

    /**
     * Toggles whether audio is streamed directly to Vosk (bypassing VAD gating).
     */
    fun setBypassVad(bypass: Boolean) {
        Log.i(TAG, "SpeechPipeline: Setting VAD bypass = $bypass")
        _uiState.update {
            it.copy(settings = it.settings.copy(bypassVad = bypass))
        }
    }

    /**
     * Updates pipeline configuration parameters in real time.
     */
    fun updateSettings(newSettings: PipelineSettings) {
        speechBuffer.preBufferMs = newSettings.preBufferMs
        speechBuffer.silenceDurationMs = newSettings.silenceDurationMs
        speechBuffer.maxUtteranceDurationMs = newSettings.maxUtteranceDurationMs

        vad.setThresholds(newSettings.speechStartThreshold, newSettings.speechEndThreshold)
        noiseSuppressor.isEnabled = newSettings.isNoiseSuppressionEnabled

        _uiState.update { it.copy(settings = newSettings) }
        updateComponentStatus()
    }

    private fun updateComponentStatus() {
        val isListening = audioCapture.isRecording.value
        val lang = _uiState.value.selectedLanguage

        val micStatus = if (isListening) "ON" else "Standby"
        val nsStatus = when {
            !noiseSuppressor.isAvailable -> "Passthrough"
            noiseSuppressor.isEnabled -> "ON"
            else -> "OFF"
        }
        val vadStatus = if (vad.isAvailable) vad.name else "Energy Fallback"
        val asrStatus = if (asrEngine.isAvailable) "Vosk ${lang.displayName} Ready" else "${lang.displayName} Model Not Installed"
        val modelStat = if (asrEngine.isAvailable) "INSTALLED" else "NOT INSTALLED"
        val modeDesc = "100% Offline • ${lang.displayName} Speech to ${lang.displayName} Text"

        _uiState.update { current ->
            current.copy(
                componentStatus = ComponentStatus(
                    language = lang.label,
                    modelStatus = modelStat,
                    microphone = micStatus,
                    noiseSuppression = nsStatus,
                    vad = vadStatus,
                    asr = asrStatus,
                    mode = modeDesc
                )
            )
        }
    }

    /**
     * Releases all pipeline resources.
     */
    fun release() {
        stopListening()
        audioCapture.release()
        vad.release()
        asrEngine.release()
        noiseSuppressor.release()
    }
}

