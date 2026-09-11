package com.vibemusic.speechtotext.text

import com.vibemusic.speechtotext.language.SpeechLanguage
import java.util.Locale

/**
 * Language-aware text processor that cleans, deduplicates, and formats raw ASR transcripts.
 *
 * Distinct processing pipelines:
 * - English: acoustic tags, control chars, duplicate words, capitalization.
 * - Tamil: acoustic tags, control chars (preserving all Unicode \u0B80-\u0BFF),
 *   whitespace normalization, Unicode token duplicate removal, standard punctuation preservation,
 *   strictly ZERO Latin capitalization, ZERO transliteration, and ZERO translation.
 */
class BasicTextProcessor(
    override var currentLanguage: SpeechLanguage = SpeechLanguage.ENGLISH
) : TextProcessor {

    override val name: String
        get() = "Language-Aware Text Processor (${currentLanguage.displayName})"

    companion object {
        // Regex to match bracketed acoustic tags like [laughter], [noise], <unk>
        private val ACOUSTIC_TAGS_REGEX = Regex("""\[[^\]]*\]|<[^>]*>""")

        // Regex to match consecutive duplicate English words (case-insensitive)
        private val DUPLICATE_WORDS_REGEX_EN = Regex("""(?i)\b(\w+)\s+\1\b""")

        // Multiple spaces/tabs/newlines
        private val MULTI_SPACE_REGEX = Regex("""\s+""")

        // English Capitalization after sentence boundaries (. ? !)
        private val SENTENCE_START_REGEX = Regex("""(^|[.!?]\s+)([a-z])""")

        // Control characters excluding normal whitespaces
        private val CONTROL_CHARS_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F-\u009F]""")
    }

    override fun process(text: String): String {
        if (text.isBlank()) return ""

        return when (currentLanguage) {
            SpeechLanguage.TAMIL -> processTamil(text)
            SpeechLanguage.ENGLISH -> processEnglish(text)
            SpeechLanguage.HINDI,
            SpeechLanguage.MALAYALAM,
            SpeechLanguage.KANNADA -> processTamil(text)
        }
    }

    /**
     * Tamil-safe text processing:
     * - Preserves all Tamil Unicode characters (range \u0B80 - \u0BFF: vowels, consonants, virama, combining signs).
     * - Strips acoustic tags and invisible control characters.
     * - Preserves punctuation.
     * - Normalizes whitespace.
     * - Deduplicates immediate repeating words using exact Unicode token comparison.
     * - Does NOT apply uppercase/lowercase or Latin title-casing.
     */
    private fun processTamil(text: String): String {
        // 1. Remove acoustic noise tags ([noise], <unk>, etc.)
        var cleaned = text.replace(ACOUSTIC_TAGS_REGEX, "")

        // 2. Remove invisible control characters without touching Tamil Unicode codepoints
        cleaned = cleaned.replace(CONTROL_CHARS_REGEX, "")

        // 3. Normalize multiple whitespace into single spaces
        cleaned = cleaned.replace(MULTI_SPACE_REGEX, " ").trim()
        if (cleaned.isEmpty()) return ""

        // 4. Deduplicate consecutive identical Tamil words (Unicode token safe)
        val tokens = cleaned.split(" ")
        if (tokens.size > 1) {
            val deduplicated = mutableListOf<String>()
            for (token in tokens) {
                if (deduplicated.isEmpty() || deduplicated.last() != token) {
                    deduplicated.add(token)
                }
            }
            cleaned = deduplicated.joinToString(" ")
        }

        return cleaned
    }

    /**
     * Standard English text processing:
     * - Strips acoustic tags and unwanted punctuation/control chars.
     * - Normalizes whitespace.
     * - Deduplicates repeated words.
     * - Capitalizes first letter of sentences.
     */
    private fun processEnglish(text: String): String {
        // 1. Remove acoustic noise tags
        var cleaned = text.replace(ACOUSTIC_TAGS_REGEX, "")

        // 2. Remove unwanted control characters while preserving alphanumeric and basic punctuation
        cleaned = cleaned.replace(Regex("""[^\w\s.,!?'"'\-]"""), " ")

        // 3. Normalize whitespace
        cleaned = cleaned.replace(MULTI_SPACE_REGEX, " ").trim()
        if (cleaned.isEmpty()) return ""

        // 4. Remove immediate consecutive duplicate words (e.g., "in in" -> "in")
        var prevCleaned = ""
        while (prevCleaned != cleaned) {
            prevCleaned = cleaned
            cleaned = cleaned.replace(DUPLICATE_WORDS_REGEX_EN, "$1")
        }

        // 5. Capitalize first letter of each sentence
        cleaned = cleaned.replace(SENTENCE_START_REGEX) { matchResult ->
            val prefix = matchResult.groupValues[1]
            val char = matchResult.groupValues[2].uppercase(Locale.getDefault())
            "$prefix$char"
        }

        // 6. Ensure first letter is capitalized
        if (cleaned.isNotEmpty() && cleaned[0].isLowerCase()) {
            cleaned = cleaned.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

        return cleaned
    }
}

