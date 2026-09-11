package com.disasteralert.emergency

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data class representing a structured emergency alert across the application.
 */
data class EmergencyAlert(
    val messageId: String,
    val senderId: String,
    val receiverId: String = "BROADCAST",
    val timestamp: Long,
    val text: String,
    val isOutgoing: Boolean,
    val status: String = "RECEIVED", // PENDING, TRANSMITTING, DELIVERED, FAILED, RECEIVED
    val hopCount: Int = 0
) {
    val formattedTime: String
        get() = try {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        } catch (_: Exception) {
            ""
        }
}
