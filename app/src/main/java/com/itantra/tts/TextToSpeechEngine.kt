package com.itantra.tts

import android.content.Context

/**
 * Standard interface for offline Text-to-Speech synthesis in iTantra.
 *
 * Implements offline synthesis via Piper/sherpa-onnx VITS models and AudioTrack playback.
 */
interface TextToSpeechEngine {

    /**
     * Initializes the TTS engine with the specified voice model asset or path.
     * Must be called once during application setup.
     */
    fun initialize(context: Context, voiceModelPath: String = "models/english_voice"): Boolean

    /**
     * Synthesizes and speaks text using offline neural speech synthesis.
     *
     * @param text The text to synthesize into spoken audio.
     * @param isAlert When true, plays at full available alarm volume using USAGE_ALARM,
     *                requests transient audio focus, saves/restores volume, and interrupts any normal speech.
     *                When false, plays through normal media stream without modifying alarm volume.
     * @param onComplete Invoked ONLY after AudioTrack has actually completed physical playback.
     */
    fun speak(text: String, isAlert: Boolean, onComplete: () -> Unit = {})

    /**
     * Stops any currently active speech playback immediately and releases AudioTrack.
     */
    fun stop()

    /**
     * Releases all native resources, neural models, and audio hardware.
     */
    fun release()

    /**
     * Returns true if speech synthesis or audio playback is currently active.
     */
    val isSpeaking: Boolean
}
