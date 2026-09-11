package com.vibemusic.speechtotext.vad

/**
 * State of voice activity detection across incoming audio frames.
 */
enum class VadState {
    SILENCE,            // No speech detected
    SPEECH_STARTED,     // Speech threshold crossed from silence
    SPEECH_CONTINUING,  // Speech continues above speechEndThreshold
    SPEECH_ENDED        // Speech dropped below speechEndThreshold
}

/**
 * Result data holder for each processed audio frame.
 */
data class VadResult(
    val state: VadState,
    val probability: Float,
    val isSpeech: Boolean
)

/**
 * Standard interface for Voice Activity Detection engines.
 */
interface VoiceActivityDetector {
    val name: String
    val isAvailable: Boolean
    val statusDescription: String

    /**
     * Initializes any models or underlying inference sessions.
     * @return true if successfully initialized and ready for inference.
     */
    fun initialize(): Boolean

    /**
     * Ingests a single 512-sample PCM 16-bit audio frame and computes voice activity.
     */
    fun processFrame(pcmFrame: ShortArray): VadResult

    /**
     * Updates detection sensitivity thresholds.
     * @param speechStart Probability threshold to transition from SILENCE to SPEECH_STARTED (e.g. 0.5)
     * @param speechEnd Probability threshold to transition from SPEECH_CONTINUING to SPEECH_ENDED (e.g. 0.35)
     */
    fun setThresholds(speechStart: Float, speechEnd: Float)

    /**
     * Resets internal hidden state tensors.
     */
    fun reset()

    /**
     * Releases native memory and inference sessions.
     */
    fun release()
}
