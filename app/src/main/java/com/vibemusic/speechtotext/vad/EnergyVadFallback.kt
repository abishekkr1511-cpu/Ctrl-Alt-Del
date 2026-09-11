package com.vibemusic.speechtotext.vad

import com.vibemusic.speechtotext.audio.AudioConfig

/**
 * Lightweight, zero-dependency RMS energy-based Voice Activity Detector.
 * Serves as an immediate reliable fallback if ONNX Runtime or the Silero model fails to load.
 */
class EnergyVadFallback(
    private var speechStartThresholdDb: Float = -40f,
    private var speechEndThresholdDb: Float = -48f
) : VoiceActivityDetector {

    override val name: String = "RMS Energy VAD (Fallback)"
    override var isAvailable: Boolean = true
        private set
    override var statusDescription: String = "Energy VAD active (Fallback)"
        private set

    private var isInSpeech: Boolean = false

    override fun initialize(): Boolean {
        isAvailable = true
        statusDescription = "Energy VAD ready"
        return true
    }

    override fun processFrame(pcmFrame: ShortArray): VadResult {
        val db = AudioConfig.calculateDbLevel(pcmFrame)
        val normalizedProb = AudioConfig.normalizeDbForUi(db)

        val state = if (!isInSpeech) {
            if (db >= speechStartThresholdDb) {
                isInSpeech = true
                VadState.SPEECH_STARTED
            } else {
                VadState.SILENCE
            }
        } else {
            if (db >= speechEndThresholdDb) {
                VadState.SPEECH_CONTINUING
            } else {
                isInSpeech = false
                VadState.SPEECH_ENDED
            }
        }

        return VadResult(
            state = state,
            probability = normalizedProb,
            isSpeech = isInSpeech
        )
    }

    override fun setThresholds(speechStart: Float, speechEnd: Float) {
        // Map [0.0..1.0] to [-60dB..-20dB]
        this.speechStartThresholdDb = -60f + (speechStart * 40f)
        this.speechEndThresholdDb = -60f + (speechEnd * 40f)
    }

    override fun reset() {
        isInSpeech = false
    }

    override fun release() {
        reset()
    }
}
