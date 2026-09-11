package com.vibemusic.speechtotext.pipeline

import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.model.ModelStatus

/**
 * Top-level status displayed on the main UI header banner.
 */
enum class PipelineStatus {
    READY,
    LISTENING,
    SPEECH_DETECTED,
    PROCESSING,
    ERROR
}

/**
 * Live status breakdown of all pipeline stages.
 */
data class ComponentStatus(
    val language: String = "English",
    val modelStatus: String = "READY",
    val microphone: String = "OFF",
    val noiseSuppression: String = "Unavailable",
    val vad: String = "Silero VAD",
    val asr: String = "Vosk",
    val mode: String = "100% Offline"
)

/**
 * Configurable parameters for VAD, buffering, and direct streaming.
 */
data class PipelineSettings(
    val speechStartThreshold: Float = 0.5f,
    val speechEndThreshold: Float = 0.35f,
    val silenceDurationMs: Long = 800L,
    val preBufferMs: Long = 300L,
    val maxUtteranceDurationMs: Long = 15000L,
    val isNoiseSuppressionEnabled: Boolean = false,
    val bypassVad: Boolean = true // Direct streaming to Vosk ensures 100% speech delivery
)

/**
 * Complete reactive UI state consumed by Jetpack Compose.
 *
 * Distinctly separates temporary in-progress speech (partialText)
 * from permanently confirmed utterances (finalText).
 */
data class PipelineUiState(
    val selectedLanguage: SpeechLanguage = SpeechLanguage.ENGLISH,
    val modelStatus: ModelStatus = ModelStatus.READY,
    val isImportingModel: Boolean = false,
    val status: PipelineStatus = PipelineStatus.READY,
    val isListening: Boolean = false,
    val partialText: String = "",          // Temporary live recognition while speaking
    val finalText: String = "",            // Confirmed final sentences displayed permanently
    val recognizedText: String = "",       // Backwards-compatible alias for finalText
    val lastPartialResult: String = "",    // Debug inspection
    val lastFinalResult: String = "",      // Debug inspection
    val audioLevelDb: Float = -80f,
    val normalizedAudioLevel: Float = 0f,
    val speechProbability: Float = 0f,
    val componentStatus: ComponentStatus = ComponentStatus(),
    val settings: PipelineSettings = PipelineSettings(),
    val debugCaptureStatus: String = "OK",
    val debugVadStatus: String = "OK",
    val debugSpeechBufferStatus: String = "OK",
    val debugVoskModelStatus: String = "LOADED",
    val debugVoskRecognizerStatus: String = "READY",
    val totalChunksProcessed: Long = 0L,
    val errorMessage: String? = null
)

