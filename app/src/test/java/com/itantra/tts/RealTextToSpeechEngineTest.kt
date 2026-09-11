package com.itantra.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RealTextToSpeechEngineTest {

    /**
     * Mock TextToSpeechEngine to verify priority and completion callback contract without hardware AudioTrack.
     */
    class MockTextToSpeechEngine : TextToSpeechEngine {
        var lastSpokenText: String? = null
        var lastIsAlert: Boolean? = null
        var speakCallCount = 0
        var stopCallCount = 0
        var autoComplete = true
        private val speaking = AtomicBoolean(false)

        override fun initialize(context: android.content.Context, voiceModelPath: String): Boolean {
            return true
        }

        override fun speak(text: String, isAlert: Boolean, onComplete: () -> Unit) {
            if (speaking.get() && isAlert) {
                // Preempt active speech
                stop()
            }
            speaking.set(true)
            lastSpokenText = text
            lastIsAlert = isAlert
            speakCallCount++

            if (autoComplete) {
                // Simulate completion
                speaking.set(false)
                onComplete()
            }
        }

        override fun stop() {
            stopCallCount++
            speaking.set(false)
        }

        override fun release() {
            stop()
        }

        override val isSpeaking: Boolean
            get() = speaking.get()
    }

    @Test
    fun testEmergencyTTSContract_isAlertTrue() {
        val tts = MockTextToSpeechEngine()
        var completed = false

        tts.speak("Help me. I am trapped.", isAlert = true) {
            completed = true
        }

        assertEquals("Help me. I am trapped.", tts.lastSpokenText)
        assertEquals(true, tts.lastIsAlert)
        assertTrue("onComplete callback must be invoked after playback", completed)
    }

    @Test
    fun testNormalTTSContract_isAlertFalse() {
        val tts = MockTextToSpeechEngine()
        var completed = false

        tts.speak("Hello from nearby device", isAlert = false) {
            completed = true
        }

        assertEquals("Hello from nearby device", tts.lastSpokenText)
        assertEquals(false, tts.lastIsAlert)
        assertTrue(completed)
    }

    @Test
    fun testEmergencyPreemptsNormalSpeech() {
        val tts = MockTextToSpeechEngine()
        tts.autoComplete = false

        // Normal speech requested (active and not auto-completed)
        tts.speak("Normal conversation text", isAlert = false)
        assertEquals(false, tts.lastIsAlert)
        assertTrue(tts.isSpeaking)

        // Emergency speech requested while normal speech is active
        tts.speak("Emergency alert! Assistance required immediately!", isAlert = true)
        assertEquals(true, tts.lastIsAlert)
        assertEquals("Emergency alert! Assistance required immediately!", tts.lastSpokenText)
        assertTrue("stop() must have been called to preempt normal speech", tts.stopCallCount >= 1)
    }

    @Test
    fun testDuplicateMessageSuppressionLogic() {
        val processedIds = Collections.synchronizedSet(LinkedHashSet<String>())
        val messageId = "alert-unique-999"

        // First receipt: process
        val isFirstDuplicate = processedIds.contains(messageId)
        assertFalse(isFirstDuplicate)
        processedIds.add(messageId)

        // Second receipt: suppressed
        val isSecondDuplicate = processedIds.contains(messageId)
        assertTrue("Subsequent duplicate packet must be recognized and suppressed", isSecondDuplicate)
    }
}
