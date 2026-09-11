package com.disasteralert.emergency

import android.content.Context
import android.util.Log
import com.itantra.tts.RealTextToSpeechEngine
import com.itantra.tts.TextToSpeechEngine
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType
import com.offline.ble.mesh.manager.DebugLogManager
import com.offline.ble.mesh.manager.MessageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.UUID

/**
 * High-level integration coordinator for iTantra:
 * Connects Speech-to-Text, Nearby BLE Communication, and Offline Text-to-Speech Engine.
 *
 * Responsibilities:
 * - Formats recognized speech into structured emergency format (type=EMERGENCY)
 * - Transmits emergency payloads to nearby devices (direct or broadcast to all discovered peers)
 * - Identifies incoming messages:
 *     - NORMAL: Displays in message log without voice alert or volume elevation.
 *     - EMERGENCY: Displays prominent visual alert and triggers offline TTS (isAlert = true)
 *       at maximum available alarm volume.
 * - Enforces duplicate emergency protection using message_id tracking.
 * - Handles preemption and priority (emergency speech interrupts normal speech).
 */
class EmergencyAlertManager(
    val context: Context,
    val messageManager: MessageManager,
    private val coroutineScope: CoroutineScope,
    val ttsEngine: TextToSpeechEngine = RealTextToSpeechEngine(context)
) {
    private val tag = "EmergencyAlertManager"

    val localDeviceId: String = messageManager.localDeviceId
    val shortDeviceId: String = messageManager.identityManager.shortDeviceId

    // Active incoming emergency alert to be displayed prominently on receiver's screen
    private val _incomingAlert = MutableStateFlow<EmergencyAlert?>(null)
    val incomingAlert: StateFlow<EmergencyAlert?> = _incomingAlert.asStateFlow()

    // Last outgoing emergency alert status
    private val _lastOutgoingAlert = MutableStateFlow<EmergencyAlert?>(null)
    val lastOutgoingAlert: StateFlow<EmergencyAlert?> = _lastOutgoingAlert.asStateFlow()

    // Duplicate message tracking: prevents repeated emergency voice alerts
    private val processedEmergencyMessageIds = Collections.synchronizedSet(LinkedHashSet<String>())

    private val incomingMessageListener: (MessageEntity) -> Unit = { messageEntity ->
        handleIncomingMessage(messageEntity)
    }

    init {
        messageManager.registerMessageReceiver(incomingMessageListener)
    }

    /**
     * Sends an emergency message recognized from speech to nearby devices.
     */
    fun sendEmergencyAlert(
        speechText: String,
        targetReceiverId: String = "BROADCAST",
        onSent: (messageId: String) -> Unit = {}
    ): String {
        val cleanText = speechText.trim()
        if (cleanText.isBlank()) return ""

        val messageId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        val structuredContent = EmergencyMessageFormatter.format(
            text = cleanText,
            senderId = shortDeviceId,
            messageId = messageId,
            timestamp = timestamp
        )

        val discoveredPeers = messageManager.bleManager.discoveredDevices.value
        val targets = if (targetReceiverId.isNotBlank() && targetReceiverId != "BROADCAST") {
            listOf(targetReceiverId)
        } else if (discoveredPeers.isNotEmpty()) {
            discoveredPeers.map { if (it.deviceId.isNotBlank()) it.deviceId else it.device.address }
        } else {
            listOf("BROADCAST")
        }

        DebugLogManager.i(tag, "🚨 Triggering EMERGENCY dispatch: \"$cleanText\" to ${targets.size} target(s)")

        // Send via MessageManager
        for (target in targets) {
            messageManager.sendMessage(
                receiverId = target,
                content = structuredContent,
                type = MessageType.EMERGENCY,
                ttl = 5
            )
        }

        val outgoingAlert = EmergencyAlert(
            messageId = messageId,
            senderId = localDeviceId,
            receiverId = if (targets.size == 1) targets.first() else "ALL (${targets.size})",
            timestamp = timestamp,
            text = cleanText,
            isOutgoing = true,
            status = "PENDING"
        )
        _lastOutgoingAlert.value = outgoingAlert
        onSent(messageId)

        return messageId
    }

    /**
     * Handles incoming messages from BLE mesh and classifies them.
     */
    private fun handleIncomingMessage(entity: MessageEntity) {
        val isEmergency = entity.messageType == MessageType.EMERGENCY ||
                EmergencyMessageFormatter.isEmergencyText(entity.content)

        if (isEmergency) {
            val alert = EmergencyMessageFormatter.parse(
                content = entity.content,
                fallbackMessageId = entity.messageId,
                fallbackSenderId = entity.senderId,
                fallbackTimestamp = entity.timestamp,
                isOutgoing = false
            ).copy(
                status = entity.status.name,
                hopCount = entity.hopCount
            )

            // DUPLICATE PROTECTION: Do not repeat voice speech if already processed
            if (processedEmergencyMessageIds.contains(alert.messageId)) {
                DebugLogManager.w(tag, "DUPLICATE SUPPRESSED: Message ${alert.messageId} already handled. Skipping duplicate speech.")
                return
            }
            processedEmergencyMessageIds.add(alert.messageId)
            if (processedEmergencyMessageIds.size > 500) {
                val oldest = processedEmergencyMessageIds.iterator().next()
                processedEmergencyMessageIds.remove(oldest)
            }

            Log.w(tag, "🚨 MESSAGE RECEIVED from ${alert.senderId}: \"${alert.text}\"")
            DebugLogManager.i(tag, "🚨 MESSAGE RECEIVED from ${alert.senderId}: \"${alert.text}\"")

            // Display alert modal on screen
            coroutineScope.launch(Dispatchers.Main) {
                _incomingAlert.value = alert
            }

            // Convert received text into speech at FULL VOLUME (pure speech, no alarm siren/sound)
            val receivedText = alert.text.trim()
            if (receivedText.isNotBlank()) {
                DebugLogManager.i(tag, "🔊 Speaking received emergency text at FULL VOLUME: \"$receivedText\"")
                try {
                    ttsEngine.speak(
                        text = receivedText,
                        isAlert = true
                    ) {
                        DebugLogManager.i(tag, "✓ Voice playback completed for: ${alert.messageId}")
                    }
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to speak received emergency text", e)
                    DebugLogManager.e(tag, "TTS playback failed for ${alert.messageId}: ${e.message}")
                }
            }
        } else {
            // NORMAL MESSAGE: Also convert received text into speech at FULL VOLUME
            val rawContent = EmergencyMessageFormatter.extractSpeechText(entity.content).trim()
            DebugLogManager.d(tag, "Normal message received from ${entity.senderId}: \"$rawContent\"")
            if (rawContent.isNotBlank()) {
                DebugLogManager.i(tag, "🔊 Speaking received text at FULL VOLUME: \"$rawContent\"")
                try {
                    ttsEngine.speak(
                        text = rawContent,
                        isAlert = false
                    ) {
                        DebugLogManager.i(tag, "✓ Normal message speech completed")
                    }
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to speak normal message text", e)
                }
            }
        }
    }

    /**
     * Dismisses the active emergency alert modal/banner on receiver screen and stops any ongoing speech.
     */
    fun dismissIncomingAlert() {
        ttsEngine.stop()
        _incomingAlert.value = null
    }

    fun release() {
        ttsEngine.release()
        messageManager.unregisterMessageReceiver(incomingMessageListener)
    }
}
