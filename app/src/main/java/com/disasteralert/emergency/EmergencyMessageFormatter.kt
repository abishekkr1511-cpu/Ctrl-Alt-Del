package com.disasteralert.emergency

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Encodes and decodes the structured emergency and normal message formats for iTantra:
 *
 * Emergency format:
 * type=EMERGENCY
 * message_id=<unique-id>
 * timestamp=<timestamp>
 * text=<recognized speech>
 *
 * Normal format:
 * type=NORMAL
 * message_id=<unique-id>
 * timestamp=<timestamp>
 * text=<normal message>
 *
 * Also maintains backwards compatibility with "EMERGENCY\nMessage: ..." format.
 */
object EmergencyMessageFormatter {

    const val TYPE_EMERGENCY = "EMERGENCY"
    const val TYPE_NORMAL = "NORMAL"

    private const val KEY_TYPE = "type="
    private const val KEY_MSG_ID = "message_id="
    private const val KEY_TIMESTAMP = "timestamp="
    private const val KEY_TEXT = "text="

    // Legacy headers
    private const val LEGACY_HEADER = "EMERGENCY"
    private const val LEGACY_PREFIX_MSG = "Message: "
    private const val LEGACY_PREFIX_TIME = "Time: "
    private const val LEGACY_PREFIX_DEVICE = "Device ID: "
    private const val LEGACY_PREFIX_MSG_ID = "Message ID: "

    /**
     * Formats an emergency message using the standard key-value format.
     */
    fun format(
        text: String,
        senderId: String,
        messageId: String,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        return formatWithType(TYPE_EMERGENCY, text, messageId, timestamp)
    }

    /**
     * Formats a normal message using the standard key-value format.
     */
    fun formatNormal(
        text: String,
        messageId: String,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        return formatWithType(TYPE_NORMAL, text, messageId, timestamp)
    }

    private fun formatWithType(
        type: String,
        text: String,
        messageId: String,
        timestamp: Long
    ): String {
        return buildString {
            append(KEY_TYPE).appendLine(type)
            append(KEY_MSG_ID).appendLine(messageId)
            append(KEY_TIMESTAMP).appendLine(timestamp)
            append(KEY_TEXT).append(text.trim())
        }
    }

    /**
     * Determines whether the given content is an EMERGENCY message.
     */
    fun isEmergencyText(content: String): Boolean {
        val trimmed = content.trim()
        if (trimmed.startsWith("${KEY_TYPE}$TYPE_EMERGENCY", ignoreCase = true)) return true
        if (trimmed.contains("${KEY_TYPE}$TYPE_EMERGENCY", ignoreCase = true)) return true
        if (trimmed.startsWith(LEGACY_HEADER) || trimmed.contains("🚨") || trimmed.startsWith("[EMERGENCY]")) return true
        return false
    }

    /**
     * Extracts the message text from the payload.
     */
    fun extractSpeechText(content: String): String {
        val lines = content.lines()
        var textFound = false
        val textLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (textFound) {
                textLines.add(line)
            } else if (trimmed.startsWith(KEY_TEXT, ignoreCase = true)) {
                textLines.add(trimmed.substring(KEY_TEXT.length))
                textFound = true
            } else if (trimmed.startsWith(LEGACY_PREFIX_MSG, ignoreCase = true)) {
                textLines.add(trimmed.substring(LEGACY_PREFIX_MSG.length))
                textFound = true
            }
        }

        if (textLines.isNotEmpty()) {
            return textLines.joinToString("\n").trim()
        }

        // Fallback: strip headers
        val cleaned = lines.filterNot {
            val t = it.trim()
            t.startsWith(KEY_TYPE, ignoreCase = true) ||
            t.startsWith(KEY_MSG_ID, ignoreCase = true) ||
            t.startsWith(KEY_TIMESTAMP, ignoreCase = true) ||
            t == LEGACY_HEADER ||
            t.startsWith(LEGACY_PREFIX_TIME) ||
            t.startsWith(LEGACY_PREFIX_DEVICE) ||
            t.startsWith(LEGACY_PREFIX_MSG_ID)
        }.joinToString("\n").trim()

        return cleaned.ifBlank { content.trim() }
    }

    /**
     * Parses a message payload into an EmergencyAlert model.
     */
    fun parse(
        content: String,
        fallbackMessageId: String,
        fallbackSenderId: String,
        fallbackTimestamp: Long,
        isOutgoing: Boolean = false
    ): EmergencyAlert {
        var messageId = fallbackMessageId
        var timestamp = fallbackTimestamp
        var senderId = fallbackSenderId

        for (line in content.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith(KEY_MSG_ID, ignoreCase = true) ->
                    messageId = trimmed.substring(KEY_MSG_ID.length).trim()
                trimmed.startsWith(KEY_TIMESTAMP, ignoreCase = true) ->
                    timestamp = trimmed.substring(KEY_TIMESTAMP.length).trim().toLongOrNull() ?: fallbackTimestamp
                trimmed.startsWith(LEGACY_PREFIX_DEVICE, ignoreCase = true) ->
                    senderId = trimmed.substring(LEGACY_PREFIX_DEVICE.length).trim()
                trimmed.startsWith(LEGACY_PREFIX_MSG_ID, ignoreCase = true) ->
                    messageId = trimmed.substring(LEGACY_PREFIX_MSG_ID.length).trim()
            }
        }

        val speechText = extractSpeechText(content)

        return EmergencyAlert(
            messageId = messageId.ifBlank { fallbackMessageId },
            senderId = senderId.ifBlank { fallbackSenderId },
            timestamp = timestamp,
            text = speechText,
            isOutgoing = isOutgoing,
            status = if (isOutgoing) "PENDING" else "RECEIVED"
        )
    }
}
