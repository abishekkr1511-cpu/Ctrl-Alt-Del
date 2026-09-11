package com.vibemusic.speechtotext.text

import com.vibemusic.speechtotext.language.SpeechLanguage

/**
 * Interface for post-ASR text normalization, formatting, and cleanup.
 *
 * Designed to be modular, permitting language-aware rule-based processors as well as
 * future Transformer-based punctuation restoration models.
 */
interface TextProcessor {
    val name: String
    var currentLanguage: SpeechLanguage

    /**
     * Cleans, sanitizes, and formats raw ASR output text according to active language rules.
     */
    fun process(text: String): String
}

