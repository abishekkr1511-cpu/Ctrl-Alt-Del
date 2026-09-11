package com.vibemusic.speechtotext.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.vibemusic.speechtotext.audio.AudioConfig
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Voice Activity Detector powered by Silero VAD running locally via ONNX Runtime.
 *
 * Supports both Silero V4 and V5 architectures dynamically by inspecting session input schemas.
 * Includes hysteresis state machine (SPEECH_STARTED, SPEECH_CONTINUING, SPEECH_ENDED, SILENCE).
 */
class SileroVad(
    private val context: Context,
    private val modelAssetPath: String = "silero_vad.onnx",
    private var speechStartThreshold: Float = 0.5f,
    private var speechEndThreshold: Float = 0.35f
) : VoiceActivityDetector {

    companion object {
        private const val TAG = "SileroVad"
    }

    override val name: String = "Silero VAD (ONNX)"
    override var isAvailable: Boolean = false
        private set
    override var statusDescription: String = "Not initialized"
        private set

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    // Model version detection
    private var isV5Model: Boolean = false
    private var inputNames: Set<String> = emptySet()

    // Recurrent state for V5: [2, 1, 128]
    private var v5State = Array(2) { Array(1) { FloatArray(128) } }

    // Recurrent state for V4: [2, 1, 64]
    private var v4H = Array(2) { Array(1) { FloatArray(64) } }
    private var v4C = Array(2) { Array(1) { FloatArray(64) } }

    // Hysteresis tracking
    private var isInSpeech = false

    // Fallback if ONNX fails
    private val fallback = EnergyVadFallback()

    override fun initialize(): Boolean {
        try {
            env = OrtEnvironment.getEnvironment()

            val modelFile = getOrCopyModelFile()
            if (modelFile == null || !modelFile.exists()) {
                isAvailable = false
                statusDescription = "VAD model file ($modelAssetPath) not found"
                fallback.initialize()
                return false
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
            }

            session = env?.createSession(modelFile.absolutePath, sessionOptions)
            inputNames = session?.inputNames ?: emptySet()

            isV5Model = inputNames.contains("state")
            reset()

            isAvailable = true
            statusDescription = if (isV5Model) "Silero VAD v5 active" else "Silero VAD v4 active"
            Log.d(TAG, "Initialized Silero VAD successfully. Version: $statusDescription, Inputs: $inputNames")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Silero VAD, falling back to Energy VAD", e)
            isAvailable = false
            statusDescription = "Silero VAD unavailable (${e.localizedMessage ?: "Unknown error"}). Energy fallback active."
            fallback.initialize()
            return false
        }
    }

    private fun getOrCopyModelFile(): File? {
        val targetFile = File(context.filesDir, "silero_vad.onnx")
        if (targetFile.exists() && targetFile.length() > 0) {
            return targetFile
        }

        return try {
            context.assets.open(modelAssetPath).use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            targetFile
        } catch (e: Exception) {
            Log.w(TAG, "Could not copy $modelAssetPath from assets: ${e.message}")
            null
        }
    }

    @Synchronized
    override fun processFrame(pcmFrame: ShortArray): VadResult {
        if (!isAvailable || session == null || env == null) {
            return fallback.processFrame(pcmFrame)
        }

        try {
            val environment = env!!
            val currentSession = session!!

            // Normalize 16-bit PCM shorts to [-1.0f, 1.0f]
            val floatSamples = FloatArray(AudioConfig.FRAME_SIZE)
            for (i in 0 until AudioConfig.FRAME_SIZE) {
                floatSamples[i] = if (i < pcmFrame.size) {
                    pcmFrame[i] / 32768.0f
                } else {
                    0.0f
                }
            }

            val input2D = arrayOf(floatSamples)
            val inputTensor = OnnxTensor.createTensor(environment, input2D)
            val srTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(16000L)), longArrayOf(1))

            val inputMap = mutableMapOf<String, OnnxTensor>()
            inputMap["input"] = inputTensor
            if (inputNames.contains("sr")) {
                inputMap["sr"] = srTensor
            }

            if (isV5Model) {
                val stateTensor = OnnxTensor.createTensor(environment, v5State)
                inputMap["state"] = stateTensor

                val results = currentSession.run(inputMap)
                val outputTensor = results[0] as OnnxTensor
                val probArray = outputTensor.value as Array<FloatArray>
                val speechProbability = probArray[0][0]

                // Extract new state
                if (results.size() > 1) {
                    val nextStateTensor = results[1] as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    v5State = nextStateTensor.value as Array<Array<FloatArray>>
                }

                results.close()
                inputTensor.close()
                srTensor.close()
                stateTensor.close()

                return evaluateHysteresis(speechProbability)
            } else {
                // Silero V4: requires h and c
                val hTensor = OnnxTensor.createTensor(environment, v4H)
                val cTensor = OnnxTensor.createTensor(environment, v4C)
                inputMap["h"] = hTensor
                inputMap["c"] = cTensor

                val results = currentSession.run(inputMap)
                val outputTensor = results[0] as OnnxTensor
                val probArray = outputTensor.value as Array<FloatArray>
                val speechProbability = probArray[0][0]

                if (results.size() > 2) {
                    val hnTensor = results[1] as OnnxTensor
                    val cnTensor = results[2] as OnnxTensor
                    @Suppress("UNCHECKED_CAST")
                    v4H = hnTensor.value as Array<Array<FloatArray>>
                    @Suppress("UNCHECKED_CAST")
                    v4C = cnTensor.value as Array<Array<FloatArray>>
                }

                results.close()
                inputTensor.close()
                srTensor.close()
                hTensor.close()
                cTensor.close()

                return evaluateHysteresis(speechProbability)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Inference error in Silero VAD", e)
            return fallback.processFrame(pcmFrame)
        }
    }

    private fun evaluateHysteresis(probability: Float): VadResult {
        val state = if (!isInSpeech) {
            if (probability >= speechStartThreshold) {
                isInSpeech = true
                VadState.SPEECH_STARTED
            } else {
                VadState.SILENCE
            }
        } else {
            if (probability >= speechEndThreshold) {
                VadState.SPEECH_CONTINUING
            } else {
                isInSpeech = false
                VadState.SPEECH_ENDED
            }
        }

        return VadResult(
            state = state,
            probability = probability,
            isSpeech = isInSpeech
        )
    }

    override fun setThresholds(speechStart: Float, speechEnd: Float) {
        this.speechStartThreshold = speechStart
        this.speechEndThreshold = speechEnd
        fallback.setThresholds(speechStart, speechEnd)
    }

    @Synchronized
    override fun reset() {
        isInSpeech = false
        v5State = Array(2) { Array(1) { FloatArray(128) } }
        v4H = Array(2) { Array(1) { FloatArray(64) } }
        v4C = Array(2) { Array(1) { FloatArray(64) } }
        fallback.reset()
    }

    @Synchronized
    override fun release() {
        try {
            session?.close()
            session = null
            env?.close()
            env = null
        } catch (_: Exception) {
        } finally {
            isAvailable = false
            statusDescription = "Released"
        }
    }
}
