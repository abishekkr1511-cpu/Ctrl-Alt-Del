package com.vibemusic.speechtotext

import com.vibemusic.speechtotext.audio.AudioConfig
import com.vibemusic.speechtotext.buffer.SpeechBuffer
import com.vibemusic.speechtotext.text.BasicTextProcessor
import com.vibemusic.speechtotext.vad.VadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineComponentsTest {

    @Test
    fun testBasicTextProcessor_normalizesWhitespace() {
        val processor = BasicTextProcessor()
        val raw = "hello    world \t how   are you"
        val result = processor.process(raw)
        assertEquals("Hello world how are you", result)
    }

    @Test
    fun testBasicTextProcessor_removesDuplicatesAndCapitalizes() {
        val processor = BasicTextProcessor()
        val raw = "the the speech recognition is is working"
        val result = processor.process(raw)
        assertEquals("The speech recognition is working", result)
    }

    @Test
    fun testBasicTextProcessor_removesAcousticNoiseTags() {
        val processor = BasicTextProcessor()
        val raw = "[laughter] thank you very much <unk>"
        val result = processor.process(raw)
        assertEquals("Thank you very much", result)
    }

    @Test
    fun testAudioConfig_conversionRoundtrip() {
        val original = shortArrayOf(0, 1000, -1000, 32767, -32768)
        val bytes = AudioConfig.shortArrayToByteArray(original)
        val recovered = AudioConfig.byteArrayToShortArray(bytes)
        assertEquals(original.size, recovered.size)
        for (i in original.indices) {
            assertEquals(original[i], recovered[i])
        }
    }

    @Test
    fun testSpeechBuffer_accumulatesAndFlushes() {
        var emittedBytes: ByteArray? = null
        val buffer = SpeechBuffer(
            preBufferMs = 64L,
            silenceDurationMs = 64L,
            maxUtteranceDurationMs = 1000L
        ) { bytes ->
            emittedBytes = bytes
        }

        val dummyFrame = ShortArray(AudioConfig.FRAME_SIZE) { 100 }

        buffer.addFrame(dummyFrame, VadState.SILENCE)
        buffer.addFrame(dummyFrame, VadState.SILENCE)
        buffer.addFrame(dummyFrame, VadState.SPEECH_STARTED)
        buffer.addFrame(dummyFrame, VadState.SPEECH_CONTINUING)
        buffer.addFrame(dummyFrame, VadState.SPEECH_ENDED)
        buffer.addFrame(dummyFrame, VadState.SILENCE)

        assertTrue(emittedBytes != null)
        assertTrue(emittedBytes!!.isNotEmpty())
    }

    @Test
    fun testTamilTextProcessor_preservesTamilUnicode() {
        val processor = BasicTextProcessor(currentLanguage = com.vibemusic.speechtotext.language.SpeechLanguage.TAMIL)
        val raw = "என் பெயர் அபிஷேக்"
        val result = processor.process(raw)
        assertEquals("என் பெயர் அபிஷேக்", result)
    }

    @Test
    fun testTamilTextProcessor_removesNoiseTagsAndPreservesUnicode() {
        val processor = BasicTextProcessor(currentLanguage = com.vibemusic.speechtotext.language.SpeechLanguage.TAMIL)
        val raw = "[noise] வணக்கம் என் பெயர் அபிஷேக் <unk>"
        val result = processor.process(raw)
        assertEquals("வணக்கம் என் பெயர் அபிஷேக்", result)
    }

    @Test
    fun testTamilTextProcessor_deduplicatesConsecutiveWords() {
        val processor = BasicTextProcessor(currentLanguage = com.vibemusic.speechtotext.language.SpeechLanguage.TAMIL)
        val raw = "வணக்கம் வணக்கம் என் என் பெயர் அபிஷேக்"
        val result = processor.process(raw)
        assertEquals("வணக்கம் என் பெயர் அபிஷேக்", result)
    }

    @Test
    fun testSpeechLanguage_labelAndResolution() {
        val english = com.vibemusic.speechtotext.language.SpeechLanguage.ENGLISH
        val tamil = com.vibemusic.speechtotext.language.SpeechLanguage.TAMIL

        assertEquals("English", english.label)
        assertEquals("Tamil (தமிழ்)", tamil.label)
        assertEquals(tamil, com.vibemusic.speechtotext.language.SpeechLanguage.fromId("ta"))
        assertEquals(english, com.vibemusic.speechtotext.language.SpeechLanguage.fromId("en"))
    }
}

