package com.disasteralert.emergency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyMessageFormatterTest {

    @Test
    fun testStructuredEmergencyFormatAndParse() {
        val messageText = "Help me. I am trapped inside the building."
        val senderId = "A1B2C3D4"
        val messageId = "msg-12345"
        val timestamp = 1715000000000L

        val formatted = EmergencyMessageFormatter.format(
            text = messageText,
            senderId = senderId,
            messageId = messageId,
            timestamp = timestamp
        )

        assertTrue(EmergencyMessageFormatter.isEmergencyText(formatted))
        assertTrue(formatted.contains("type=EMERGENCY"))
        assertTrue(formatted.contains("message_id=$messageId"))
        assertTrue(formatted.contains("timestamp=$timestamp"))
        assertTrue(formatted.contains("text=$messageText"))

        val parsed = EmergencyMessageFormatter.parse(
            content = formatted,
            fallbackMessageId = "fallback-id",
            fallbackSenderId = "fallback-sender",
            fallbackTimestamp = 0L
        )

        assertEquals(messageText, parsed.text)
        assertEquals(messageId, parsed.messageId)
    }

    @Test
    fun testStructuredNormalFormatAndParse() {
        val normalText = "Hello from nearby responder"
        val messageId = "norm-100"
        val timestamp = 1715000010000L

        val formatted = EmergencyMessageFormatter.formatNormal(
            text = normalText,
            messageId = messageId,
            timestamp = timestamp
        )

        assertFalse("Normal message must NOT be detected as emergency text", EmergencyMessageFormatter.isEmergencyText(formatted))
        assertTrue(formatted.contains("type=NORMAL"))
        assertTrue(formatted.contains("message_id=$messageId"))
        assertTrue(formatted.contains("text=$normalText"))

        val parsed = EmergencyMessageFormatter.parse(
            content = formatted,
            fallbackMessageId = "fallback-id",
            fallbackSenderId = "fallback-sender",
            fallbackTimestamp = 0L
        )

        assertEquals(normalText, parsed.text)
        assertEquals(messageId, parsed.messageId)
    }

    @Test
    fun testTamilEmergencyFormatAndPreservation() {
        val tamilText = "எனக்கு உதவி தேவை."
        val senderId = "TAMIL-NODE"
        val messageId = "msg-ta-999"

        val formatted = EmergencyMessageFormatter.format(
            text = tamilText,
            senderId = senderId,
            messageId = messageId
        )

        assertTrue(EmergencyMessageFormatter.isEmergencyText(formatted))
        assertTrue(formatted.contains("text=$tamilText"))

        val extracted = EmergencyMessageFormatter.extractSpeechText(formatted)
        assertEquals("Exact Tamil Unicode script must be preserved intact without translation", tamilText, extracted)

        val parsed = EmergencyMessageFormatter.parse(
            content = formatted,
            fallbackMessageId = "fallback",
            fallbackSenderId = "fallback",
            fallbackTimestamp = 0L
        )
        assertEquals(tamilText, parsed.text)
    }

    @Test
    fun testNonEmergencyFallback() {
        val normalText = "Hello from nearby friend"
        assertEquals(normalText, EmergencyMessageFormatter.extractSpeechText(normalText))

        val parsed = EmergencyMessageFormatter.parse(
            content = normalText,
            fallbackMessageId = "msg-normal",
            fallbackSenderId = "node-1",
            fallbackTimestamp = 1000L
        )
        assertEquals(normalText, parsed.text)
        assertEquals("msg-normal", parsed.messageId)
        assertEquals("node-1", parsed.senderId)
    }
}
