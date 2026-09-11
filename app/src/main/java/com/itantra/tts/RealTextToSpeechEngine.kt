package com.itantra.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.offline.ble.mesh.manager.DebugLogManager
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Text-to-Speech Engine for iTantra:
 * Converts received text into real human speech at FULL VOLUME on the receiver side without any alarm siren or beeps.
 *
 * Implements:
 * - Offline on-device human speech synthesis via Android TextToSpeech engine
 * - Automatic elevation of media volume to 100% MAXIMUM available volume during playback
 * - Proper restoration of original user volume immediately after speech completes
 * - Accurate completion callback via UtteranceProgressListener
 * - Immediate preemption/interruption of previous speech when a new message arrives
 * - Robust error handling with zero crash guarantee
 */
class RealTextToSpeechEngine(private val context: Context) : TextToSpeechEngine {

    companion object {
        private const val TAG = "RealTextToSpeechEngine"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var textToSpeech: TextToSpeech? = null
    private val isInitialized = AtomicBoolean(false)
    private val _isSpeaking = AtomicBoolean(false)
    override val isSpeaking: Boolean get() = _isSpeaking.get()

    private var originalVolume: Int = -1
    private var pendingSpeechOnComplete: (() -> Unit)? = null
    private var queuedText: String? = null
    private var queuedOnComplete: (() -> Unit)? = null

    init {
        initialize(context)
    }

    override fun initialize(context: Context, voiceModelPath: String): Boolean {
        if (isInitialized.get() && textToSpeech != null) return true

        Log.i(TAG, "Initializing Android TextToSpeech engine...")
        try {
            textToSpeech = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val tts = textToSpeech
                    if (tts != null) {
                        val result = tts.setLanguage(Locale.US)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            Log.w(TAG, "Locale.US not supported; falling back to default locale: ${Locale.getDefault()}")
                            tts.language = Locale.getDefault()
                        }
                        tts.setPitch(1.0f)
                        tts.setSpeechRate(1.0f)

                        val audioAttributes = AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                        tts.setAudioAttributes(audioAttributes)

                        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                _isSpeaking.set(true)
                                DebugLogManager.i(TAG, "🔊 Speech playback started at full volume")
                            }

                            override fun onDone(utteranceId: String?) {
                                DebugLogManager.i(TAG, "✓ Speech playback finished cleanly")
                                finishPlayback()
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                Log.e(TAG, "Speech playback error for utterance: $utteranceId")
                                finishPlayback()
                            }

                            override fun onError(utteranceId: String?, errorCode: Int) {
                                Log.e(TAG, "Speech playback error $errorCode for utterance: $utteranceId")
                                finishPlayback()
                            }
                        })

                        isInitialized.set(true)
                        DebugLogManager.i(TAG, "TextToSpeech engine initialized successfully")

                        // Process any queued speech that arrived during initialization
                        val text = queuedText
                        val cb = queuedOnComplete
                        queuedText = null
                        queuedOnComplete = null
                        if (!text.isNullOrBlank()) {
                            mainHandler.post {
                                speak(text, isAlert = true, onComplete = cb ?: {})
                            }
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to initialize TextToSpeech engine, status code: $status")
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TextToSpeech", e)
            return false
        }
    }

    override fun speak(text: String, isAlert: Boolean, onComplete: () -> Unit) {
        val cleanText = text.trim()
        if (cleanText.isBlank()) {
            onComplete()
            return
        }

        // If TTS is not yet initialized, queue the message and initialize
        val tts = textToSpeech
        if (tts == null || !isInitialized.get()) {
            Log.i(TAG, "TTS not ready yet; queuing text and initializing: \"$cleanText\"")
            queuedText = cleanText
            queuedOnComplete = onComplete
            initialize(context)
            return
        }

        // If speech is already active, stop previous speech immediately to preempt
        if (_isSpeaking.get()) {
            stopPlaybackInternal()
        }

        try {
            // Save original volume and set to 100% MAXIMUM VOLUME
            if (originalVolume == -1) {
                originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVolume, 0)
                DebugLogManager.i(TAG, "Media volume set to FULL MAXIMUM: $maxVolume (Saved previous: $originalVolume)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not set stream volume: ${e.message}")
            }

            pendingSpeechOnComplete = onComplete
            _isSpeaking.set(true)

            // Detect Tamil text if present
            val hasTamil = cleanText.any { it in '\u0B80'..'\u0BFF' }
            if (hasTamil) {
                val tamilLocale = Locale.Builder().setLanguage("ta").setRegion("IN").build()
                if (tts.isLanguageAvailable(tamilLocale) >= TextToSpeech.LANG_AVAILABLE) {
                    tts.language = tamilLocale
                }
            } else {
                tts.language = Locale.US
            }

            val utteranceId = UUID.randomUUID().toString()
            val params = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            DebugLogManager.i(TAG, "🔊 Converting text to speech at full volume: \"$cleanText\"")
            val result = tts.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)

            if (result != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TextToSpeech.speak() returned non-success code: $result")
                finishPlayback()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error executing speak() for text: \"$cleanText\"", e)
            finishPlayback()
        }
    }

    private fun finishPlayback() {
        _isSpeaking.set(false)

        // Restore original user volume
        if (originalVolume != -1) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                Log.i(TAG, "Restored original media volume to: $originalVolume")
            } catch (e: Exception) {
                Log.w(TAG, "Could not restore volume: ${e.message}")
            }
            originalVolume = -1
        }

        val cb = pendingSpeechOnComplete
        pendingSpeechOnComplete = null
        if (cb != null) {
            mainHandler.post { cb.invoke() }
        }
    }

    override fun stop() {
        stopPlaybackInternal()
    }

    private fun stopPlaybackInternal() {
        try {
            textToSpeech?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TextToSpeech: ${e.message}")
        }
        finishPlayback()
    }

    override fun release() {
        stop()
        try {
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error shutting down TextToSpeech: ${e.message}")
        }
        textToSpeech = null
        isInitialized.set(false)
    }
}
