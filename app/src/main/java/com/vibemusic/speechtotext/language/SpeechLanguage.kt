package com.vibemusic.speechtotext.language

/**
 * Extensible enum representing languages supported by the offline speech pipeline.
 *
 * Designed to accommodate additional Indian languages (e.g. Hindi, Telugu, Kannada,
 * Malayalam, Bengali, Marathi, Gujarati) in future releases without architectural refactoring.
 */
enum class SpeechLanguage(
    val id: String,
    val displayName: String,
    val nativeName: String,
    val modelDirName: String,
    val assetFolderName: String? = null
) {
    ENGLISH(
        id = "en",
        displayName = "English",
        nativeName = "English",
        modelDirName = "model-en-us",
        assetFolderName = "model-en-us"
    ),
    HINDI(
        id = "hi",
        displayName = "Hindi",
        nativeName = "हिन्दी",
        modelDirName = "model-hi",
        assetFolderName = null
    ),
    MALAYALAM(
        id = "ml",
        displayName = "Malayalam",
        nativeName = "മലയാളം",
        modelDirName = "model-ml",
        assetFolderName = null
    ),
    KANNADA(
        id = "kn",
        displayName = "Kannada",
        nativeName = "ಕನ್ನಡ",
        modelDirName = "model-kn",
        assetFolderName = null
    ),
    TAMIL(
        id = "ta",
        displayName = "Tamil",
        nativeName = "தமிழ்",
        modelDirName = "model-ta",
        assetFolderName = null // Downloadable/importable into internal app storage or assets
    );

    /**
     * User-facing label with native script support (e.g., "Tamil (தமிழ்)").
     */
    val label: String
        get() = if (displayName.equals(nativeName, ignoreCase = true)) {
            displayName
        } else {
            "$displayName ($nativeName)"
        }

    companion object {
        fun fromId(id: String): SpeechLanguage =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ENGLISH
    }
}
