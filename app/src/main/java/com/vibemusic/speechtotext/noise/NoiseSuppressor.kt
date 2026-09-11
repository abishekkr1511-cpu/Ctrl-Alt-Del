package com.vibemusic.speechtotext.noise

/**
 * Interface for streaming audio noise suppression processors.
 *
 * Operates on individual 512-sample PCM 16-bit frames in real-time.
 */
interface NoiseSuppressor {
    val name: String
    val isAvailable: Boolean
    var isEnabled: Boolean
    val statusDescription: String

    /**
     * Initializes native dependencies or DSP filters.
     * @return true if operational, false if native libraries/models are missing.
     */
    fun initialize(): Boolean

    /**
     * Cleans an incoming PCM 16-bit audio frame in real time.
     * @param pcmFrame 16-bit mono PCM samples
     * @return enhanced PCM samples, or original if disabled/unavailable
     */
    fun process(pcmFrame: ShortArray): ShortArray

    /**
     * Releases any native memory allocations.
     */
    fun release()
}
