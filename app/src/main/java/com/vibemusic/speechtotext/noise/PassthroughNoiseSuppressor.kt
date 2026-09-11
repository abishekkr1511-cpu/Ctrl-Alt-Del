package com.vibemusic.speechtotext.noise

/**
 * Fallback noise suppressor implementing DC-offset removal and rumble filtering.
 * Ensures the pipeline functions smoothly when native DeepFilterNet is not bundled.
 */
class PassthroughNoiseSuppressor : NoiseSuppressor {
    override val name: String = "Passthrough Filter"
    override var isAvailable: Boolean = true
        private set
    override var isEnabled: Boolean = false
    override var statusDescription: String = "Noise suppression is unavailable. Continuing without noise suppression."
        private set

    // Simple single-pole high-pass filter state to remove DC bias and microphone rumble below 80Hz
    private var lastInput = 0f
    private var lastOutput = 0f
    private val alpha = 0.95f // cutoff frequency ~120Hz at 16kHz

    override fun initialize(): Boolean {
        isAvailable = true
        statusDescription = "Passthrough ready (Native DeepFilterNet unavailable)"
        return true
    }

    override fun process(pcmFrame: ShortArray): ShortArray {
        if (!isEnabled) {
            return pcmFrame
        }

        // Apply DC-offset and low-frequency rumble filter
        val cleaned = ShortArray(pcmFrame.size)
        for (i in pcmFrame.indices) {
            val sample = pcmFrame[i].toFloat()
            val filtered = alpha * (lastOutput + sample - lastInput)
            lastInput = sample
            lastOutput = filtered
            cleaned[i] = filtered.coerceIn(-32768f, 32767f).toInt().toShort()
        }
        return cleaned
    }

    override fun release() {
        lastInput = 0f
        lastOutput = 0f
    }
}
