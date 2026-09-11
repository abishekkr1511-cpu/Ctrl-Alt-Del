package com.vibemusic.speechtotext.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Robust, low-latency audio capture component using Android's AudioRecord API.
 *
 * Runs capture on a dedicated IO coroutine, calculates real-time decibel levels,
 * and distributes PCM frames via Kotlin Coroutine Flows.
 */
class AudioCapture(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    // Real-time audio amplitude in dB [-80dB..0dB]
    private val _audioLevelDb = MutableStateFlow(-80f)
    val audioLevelDb: StateFlow<Float> = _audioLevelDb.asStateFlow()

    // Stream of 512-sample PCM frames
    private val _audioFrames = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val audioFrames: SharedFlow<ShortArray> = _audioFrames.asSharedFlow()

    // Callback listener for synchronous downstream pipeline processing
    var frameListener: ((ShortArray) -> Unit)? = null

    private val isStopping = AtomicBoolean(false)

    /**
     * Checks if RECORD_AUDIO permission has been granted.
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts continuous audio capture.
     * @return Result.success(Unit) or Result.failure(Exception)
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startRecording(): Result<Unit> {
        if (_isRecording.value) {
            return Result.success(Unit)
        }

        if (!hasPermission()) {
            return Result.failure(SecurityException("Microphone permission (RECORD_AUDIO) not granted."))
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            AudioConfig.SAMPLE_RATE,
            AudioConfig.CHANNEL_CONFIG,
            AudioConfig.AUDIO_FORMAT
        )

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return Result.failure(IllegalStateException("Failed to query minimum AudioRecord buffer size."))
        }

        // Allocate buffer size that is at least twice minBufferSize and a multiple of frame size
        val bufferSize = max(minBufferSize * 2, AudioConfig.FRAME_SIZE_BYTES * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                AudioConfig.SAMPLE_RATE,
                AudioConfig.CHANNEL_CONFIG,
                AudioConfig.AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                // Fallback to standard MIC audio source if VOICE_RECOGNITION is unavailable
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    AudioConfig.SAMPLE_RATE,
                    AudioConfig.CHANNEL_CONFIG,
                    AudioConfig.AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return Result.failure(IllegalStateException("AudioRecord initialization failed (microphone may be in use)."))
            }

            audioRecord?.startRecording()
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord?.release()
                audioRecord = null
                return Result.failure(IllegalStateException("AudioRecord failed to enter RECORDING state."))
            }

            _isRecording.value = true
            isStopping.set(false)

            captureJob = scope.launch(Dispatchers.IO) {
                readAudioLoop()
            }

            return Result.success(Unit)
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            _isRecording.value = false
            return Result.failure(e)
        }
    }

    /**
     * Internal audio capture loop reading into 512-sample frames.
     */
    private suspend fun readAudioLoop() {
        val frameBuffer = ShortArray(AudioConfig.FRAME_SIZE)

        while (scope.isActive && _isRecording.value && !isStopping.get()) {
            val record = audioRecord ?: break
            val readSamples = record.read(frameBuffer, 0, AudioConfig.FRAME_SIZE)

            if (readSamples > 0) {
                val frameCopy = if (readSamples == AudioConfig.FRAME_SIZE) {
                    frameBuffer.clone()
                } else {
                    frameBuffer.copyOf(readSamples)
                }

                // Compute audio level
                val db = AudioConfig.calculateDbLevel(frameCopy, readSamples)
                _audioLevelDb.value = db

                // Dispatch to flow and callback
                _audioFrames.tryEmit(frameCopy)
                frameListener?.invoke(frameCopy)
            } else if (readSamples == AudioRecord.ERROR_INVALID_OPERATION ||
                readSamples == AudioRecord.ERROR_BAD_VALUE ||
                readSamples == AudioRecord.ERROR_DEAD_OBJECT
            ) {
                break
            }
        }
    }

    /**
     * Stops audio capture safely and releases microphone resources.
     */
    @Synchronized
    fun stopRecording() {
        if (!_isRecording.value) return
        isStopping.set(true)
        _isRecording.value = false
        _audioLevelDb.value = -80f

        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.let {
                if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    it.stop()
                }
                it.release()
            }
        } catch (_: Exception) {
        } finally {
            audioRecord = null
            isStopping.set(false)
        }
    }

    /**
     * Completely tears down capture resources.
     */
    fun release() {
        stopRecording()
    }
}
