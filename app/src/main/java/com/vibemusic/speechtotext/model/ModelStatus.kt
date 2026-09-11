package com.vibemusic.speechtotext.model

/**
 * Lifecycle status of an offline speech recognition model.
 */
enum class ModelStatus {
    NOT_INSTALLED,
    INSTALLING,
    INSTALLED,
    ERROR;

    val isInstalled: Boolean get() = this == INSTALLED

    companion object {
        // Alias for backwards compatibility
        val READY: ModelStatus get() = INSTALLED
    }
}
