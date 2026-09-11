package com.vibemusic.speechtotext.asr

import com.vibemusic.speechtotext.language.SpeechLanguage

/**
 * Clean abstraction for offline Speech-to-Text / Automatic Speech Recognition (ASR) engines.
 */
interface SpeechRecognizerEngine {
    val name: String
    val isAvailable: Boolean
    val statusDescription: String
    val currentLanguage: SpeechLanguage

    /**
     * Initializes the model and internal recognizer instance for the default language.
     * @return true if initialized and ready to recognize speech.
     */
    fun initialize(): Boolean

    /**
     * Dynamically switches the active speech recognition language and model.
     * @return true if the new language model was successfully loaded and ready.
     */
    fun switchLanguage(newLanguage: SpeechLanguage): Boolean

    /**
     * Accepts a buffer of raw PCM 16-bit audio (little-endian).
     * @return true if a complete utterance or silence boundary was recognized.
     */
    fun acceptAudio(audio: ByteArray): Boolean

    /**
     * Retrieves partial/in-progress recognition text.
     */
    fun getPartialResult(): String

    /**
     * Retrieves the completed recognition text when an endpoint/sentence boundary is detected.
     */
    fun getResult(): String

    /**
     * Retrieves the completed final recognition text for the utterance.
     */
    fun getFinalResult(): String

    /**
     * Resets internal recognizer state for the next utterance.
     */
    fun reset()

    /**
     * Releases recognizer and model native resources.
     */
    fun release()
}

