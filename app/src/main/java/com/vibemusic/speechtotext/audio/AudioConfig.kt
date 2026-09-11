package com.vibemusic.speechtotext.audio

import android.media.AudioFormat
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Global audio configuration for the speech pipeline.
 *
 * Configured for 16kHz mono 16-bit PCM frames (512 samples / 32ms)
 * to match Silero VAD, DeepFilterNet, and Vosk ASR requirements.
 */
object AudioConfig {
    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val BYTES_PER_SAMPLE = 2 // 16-bit PCM = 2 bytes per sample

    // 512 samples @ 16kHz = 32ms. Optimal window size required by Silero VAD
    const val FRAME_SIZE = 512

    // 512 samples * 2 bytes = 1024 bytes per frame
    const val FRAME_SIZE_BYTES = FRAME_SIZE * BYTES_PER_SAMPLE

    /**
     * Converts a ShortArray (16-bit PCM) to a ByteArray (little-endian).
     */
    fun shortArrayToByteArray(shorts: ShortArray, size: Int = shorts.size): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in 0 until size) {
            val sample = shorts[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /**
     * Converts a ByteArray (little-endian) to a ShortArray (16-bit PCM).
     */
    fun byteArrayToShortArray(bytes: ByteArray, byteCount: Int = bytes.size): ShortArray {
        val shortCount = byteCount / 2
        val shorts = ShortArray(shortCount)
        for (i in 0 until shortCount) {
            val low = bytes[i * 2].toInt() and 0xFF
            val high = bytes[i * 2 + 1].toInt()
            shorts[i] = ((high shl 8) or low).toShort()
        }
        return shorts
    }

    /**
     * Computes the Root Mean Square (RMS) decibel level from a PCM short frame.
     * Returns a value between -80 dB (silence) and 0 dB (full scale).
     */
    fun calculateDbLevel(samples: ShortArray, length: Int = samples.size): Float {
        if (length == 0) return -80f
        var sumSquares = 0.0
        for (i in 0 until length) {
            val sample = samples[i].toDouble()
            sumSquares += sample * sample
        }
        val rms = sqrt(sumSquares / length)
        if (rms <= 1.0) return -80f
        val db = 20.0 * log10(rms / 32767.0)
        return max(-80.0, db).toFloat()
    }

    /**
     * Normalizes decibel level [-80dB..0dB] to [0.0f..1.0f] for UI visualizers.
     */
    fun normalizeDbForUi(db: Float): Float {
        val clamped = max(-60f, kotlin.math.min(0f, db))
        return ((clamped + 60f) / 60f).coerceIn(0f, 1f)
    }
}
