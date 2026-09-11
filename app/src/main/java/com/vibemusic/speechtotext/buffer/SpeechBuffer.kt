package com.vibemusic.speechtotext.buffer

import com.vibemusic.speechtotext.audio.AudioConfig
import com.vibemusic.speechtotext.vad.VadState
import java.io.ByteArrayOutputStream
import java.util.LinkedList
import kotlin.math.max

/**
 * Collects speech detected by Voice Activity Detection (VAD) into complete, coherent utterances.
 *
 * Key features:
 * - Pre-roll circular buffer prevents clipping beginning consonant sounds.
 * - Hysteresis silence window handles natural mid-sentence pauses without breaking sentences.
 * - Maximum utterance ceiling protects against unbounded memory growth.
 * - Rapid consecutive speech seamlessly re-enters accumulation.
 */
class SpeechBuffer(
    var preBufferMs: Long = 300L,
    var silenceDurationMs: Long = 800L,
    var maxUtteranceDurationMs: Long = 15000L,
    private val onUtteranceReady: (ByteArray) -> Unit
) {
    enum class State {
        IDLE,           // Waiting for speech to start
        ACCUMULATING,   // Currently recording active speech
        SILENCE_WAIT    // Speech ended; waiting for silenceDurationMs before closing
    }

    private var currentState = State.IDLE
    private val preRollQueue = LinkedList<ShortArray>()
    private val activeUtteranceStream = ByteArrayOutputStream()

    private var silenceFramesAccumulated = 0
    private var totalUtteranceFrames = 0

    // Frame duration in milliseconds: 512 samples / 16000 Hz = 32ms
    private val frameDurationMs: Long = (AudioConfig.FRAME_SIZE * 1000L) / AudioConfig.SAMPLE_RATE

    private val maxPreRollFrames: Int
        get() = max(1, (preBufferMs / frameDurationMs).toInt())

    private val maxSilenceFrames: Int
        get() = max(1, (silenceDurationMs / frameDurationMs).toInt())

    private val maxUtteranceFrames: Int
        get() = max(1, (maxUtteranceDurationMs / frameDurationMs).toInt())

    /**
     * Ingests an audio frame along with the VAD classification for that frame.
     */
    @Synchronized
    fun addFrame(pcmFrame: ShortArray, vadState: VadState) {
        val frameBytes = AudioConfig.shortArrayToByteArray(pcmFrame)

        when (currentState) {
            State.IDLE -> {
                // Maintain sliding pre-roll window
                preRollQueue.add(pcmFrame.clone())
                while (preRollQueue.size > maxPreRollFrames) {
                    preRollQueue.removeFirst()
                }

                if (vadState == VadState.SPEECH_STARTED || vadState == VadState.SPEECH_CONTINUING) {
                    currentState = State.ACCUMULATING
                    activeUtteranceStream.reset()
                    totalUtteranceFrames = 0
                    silenceFramesAccumulated = 0

                    // Flush all pre-roll frames into the new utterance
                    while (preRollQueue.isNotEmpty()) {
                        val preFrame = preRollQueue.removeFirst()
                        val bytes = AudioConfig.shortArrayToByteArray(preFrame)
                        activeUtteranceStream.write(bytes)
                        totalUtteranceFrames++
                    }

                    // Append current frame
                    activeUtteranceStream.write(frameBytes)
                    totalUtteranceFrames++
                }
            }

            State.ACCUMULATING -> {
                activeUtteranceStream.write(frameBytes)
                totalUtteranceFrames++

                if (vadState == VadState.SPEECH_ENDED || vadState == VadState.SILENCE) {
                    currentState = State.SILENCE_WAIT
                    silenceFramesAccumulated = 1
                } else if (totalUtteranceFrames >= maxUtteranceFrames) {
                    // Maximum duration exceeded, force close and emit
                    flushUtterance()
                }
            }

            State.SILENCE_WAIT -> {
                activeUtteranceStream.write(frameBytes)
                totalUtteranceFrames++
                silenceFramesAccumulated++

                if (vadState == VadState.SPEECH_STARTED || vadState == VadState.SPEECH_CONTINUING) {
                    // Resumed talking before timeout; cancel silence wait and continue accumulating
                    currentState = State.ACCUMULATING
                    silenceFramesAccumulated = 0
                } else if (silenceFramesAccumulated >= maxSilenceFrames || totalUtteranceFrames >= maxUtteranceFrames) {
                    // Silence timeout expired or max duration reached; complete utterance
                    flushUtterance()
                }
            }
        }
    }

    /**
     * Emits the accumulated utterance and resets state back to IDLE.
     */
    @Synchronized
    fun flushUtterance() {
        if (activeUtteranceStream.size() > 0) {
            val completeAudio = activeUtteranceStream.toByteArray()
            activeUtteranceStream.reset()
            currentState = State.IDLE
            silenceFramesAccumulated = 0
            totalUtteranceFrames = 0
            preRollQueue.clear()

            onUtteranceReady(completeAudio)
        } else {
            currentState = State.IDLE
            silenceFramesAccumulated = 0
            totalUtteranceFrames = 0
            preRollQueue.clear()
        }
    }

    /**
     * Clears all buffers and resets state without emitting.
     */
    @Synchronized
    fun reset() {
        currentState = State.IDLE
        preRollQueue.clear()
        activeUtteranceStream.reset()
        silenceFramesAccumulated = 0
        totalUtteranceFrames = 0
    }

    val isBufferingSpeech: Boolean
        get() = currentState != State.IDLE
}
