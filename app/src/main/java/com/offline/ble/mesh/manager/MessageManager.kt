package com.offline.ble.mesh.manager

import android.bluetooth.BluetoothDevice
import android.content.Context
import com.offline.ble.mesh.ble.BleCommunicationManager
import com.offline.ble.mesh.ble.BleConstants
import com.offline.ble.mesh.ble.protocol.AckPayload
import com.offline.ble.mesh.ble.protocol.MessagePayload
import com.offline.ble.mesh.data.local.AppDatabase
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType
import com.offline.ble.mesh.data.repository.MessageRepository
import com.offline.ble.mesh.identity.DeviceIdentityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class MessageManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    private val tag = "MessageManager"

    val identityManager = DeviceIdentityManager(context)
    val localDeviceId: String = identityManager.deviceId

    private val database = AppDatabase.getInstance(context)
    val repository = MessageRepository(database.messageDao())

    val allMessagesFlow: Flow<List<MessageEntity>> = repository.allMessages.flowOn(Dispatchers.IO)

    private val incomingListeners = CopyOnWriteArrayList<(MessageEntity) -> Unit>()
    private var periodicRetryJob: Job? = null

    val bleManager: BleCommunicationManager = BleCommunicationManager(
        context = context,
        coroutineScope = coroutineScope,
        localDeviceId = localDeviceId,
        onMessageReceived = { payload, device ->
            handleIncomingPayload(payload, device)
        },
        onAckReceived = { ack ->
            handleIncomingAck(ack)
        }
    )

    init {
        startPeriodicRetryQueue()
    }

    fun start() {
        bleManager.start()
    }

    fun stop() {
        bleManager.stop()
        periodicRetryJob?.cancel()
    }

    fun registerMessageReceiver(listener: (MessageEntity) -> Unit) {
        incomingListeners.add(listener)
    }

    fun unregisterMessageReceiver(listener: (MessageEntity) -> Unit) {
        incomingListeners.remove(listener)
    }

    /**
     * Exposes the standardized TransportManager interface for external modules (e.g. STT/PTT/Translation).
     */
    fun asTransportManager(): com.offline.ble.mesh.communication.TransportManager {
        return object : com.offline.ble.mesh.communication.TransportManager {
            override fun startAdvertisingAndDiscovery() {
                this@MessageManager.start()
            }

            override fun sendPacket(packet: com.offline.ble.mesh.communication.MeshPacket) {
                sendMessage(
                    receiverId = packet.senderId.ifBlank { "BROADCAST" },
                    content = packet.textContent,
                    type = MessageType.TEXT
                )
            }

            override fun onPacketReceived(callback: (com.offline.ble.mesh.communication.MeshPacket) -> Unit) {
                registerMessageReceiver { entity ->
                    callback(
                        com.offline.ble.mesh.communication.MeshPacket(
                            messageId = entity.messageId,
                            timestamp = entity.timestamp,
                            senderId = entity.senderId,
                            senderLang = "en",
                            priority = "normal",
                            textContent = entity.content
                        )
                    )
                }
            }

            override fun stop() {
                this@MessageManager.stop()
            }
        }
    }

    /**
     * Primary message sending function.
     * Stores in Room immediately as PENDING, then attempts BLE transmission.
     */
    fun sendMessage(
        receiverId: String,
        content: String,
        type: MessageType = MessageType.TEXT,
        ttl: Int = 5
    ): String {
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val entity = MessageEntity(
            messageId = messageId,
            senderId = localDeviceId,
            receiverId = receiverId.trim(),
            timestamp = now,
            content = content.trim(),
            messageType = type,
            status = MessageStatus.PENDING,
            hopCount = 0,
            ttl = ttl,
            createdAt = now,
            lastAttemptAt = null,
            retryCount = 0,
            receivedAt = null,
            forwarded = false
        )

        coroutineScope.launch(Dispatchers.IO) {
            repository.insertMessage(entity)
            MetricsManager.recordMessageSent(messageId)
            DebugLogManager.i(tag, "Message created and stored: $messageId -> Target: $receiverId")
            attemptTransmission(entity)
        }

        return messageId
    }

    /**
     * Attempt direct or queued BLE transmission for a message entity
     */
    private suspend fun attemptTransmission(entity: MessageEntity) {
        repository.updateStatus(entity.messageId, MessageStatus.TRANSMITTING, System.currentTimeMillis())

        val payload = MessagePayload.fromEntity(entity)
        bleManager.sendPayload(entity.receiverId, payload) { success, error ->
            coroutineScope.launch(Dispatchers.IO) {
                if (success) {
                    DebugLogManager.i(tag, "Transmission succeeded for ${entity.messageId}. Awaiting ACK...")
                    // Status remains TRANSMITTING until ACK arrives or times out
                } else {
                    DebugLogManager.w(tag, "Transmission failed for ${entity.messageId}: $error")
                    repository.incrementRetry(entity.messageId)
                    val updated = repository.getMessageById(entity.messageId)
                    if (updated != null && updated.retryCount >= BleConstants.MAX_RETRY_COUNT) {
                        repository.updateStatus(entity.messageId, MessageStatus.FAILED)
                        DebugLogManager.e(tag, "Message ${entity.messageId} reached max retries (${BleConstants.MAX_RETRY_COUNT}), marked FAILED")
                    } else {
                        repository.updateStatus(entity.messageId, MessageStatus.PENDING)
                        MetricsManager.recordRetry()
                    }
                }
            }
        }
    }

    /**
     * Handle incoming payload from peer with duplicate detection & multi-hop routing
     */
    private fun handleIncomingPayload(payload: MessagePayload, device: BluetoothDevice) {
        coroutineScope.launch(Dispatchers.IO) {
            val exists = repository.hasMessage(payload.messageId)

            if (exists) {
                // DUPLICATE PROTECTION
                MetricsManager.recordDuplicatePrevented()
                DebugLogManager.w(tag, "DUPLICATE PREVENTED: Message ${payload.messageId} already exists in local DB. Echoing ACK.")
                // Send ACK so the sender doesn't keep retrying
                bleManager.sendAck(device, AckPayload(payload.messageId, localDeviceId))
                return@launch
            }

            // Check if intended for this device or for multi-hop forwarding
            val isForMe = payload.receiverId.equals(localDeviceId, ignoreCase = true) ||
                    payload.receiverId.isBlank() ||
                    (payload.receiverId.length >= 8 && localDeviceId.startsWith(payload.receiverId, ignoreCase = true))

            if (isForMe) {
                // Received final destination message
                val entity = payload.toEntity(MessageStatus.RECEIVED)
                repository.insertMessage(entity)
                MetricsManager.recordMessageReceived(payload.messageId, payload.toBytes().size.toLong())
                DebugLogManager.i(tag, "New message stored: ${payload.messageId} from ${payload.senderId}")

                // Send ACK to confirm delivery
                bleManager.sendAck(device, AckPayload(payload.messageId, localDeviceId))

                // Notify UI and upper layers (e.g. Speech / Audio)
                incomingListeners.forEach { listener ->
                    runCatching { listener(entity) }
                }
            } else {
                // Multi-hop routing candidate
                if (payload.ttl > 1) {
                    val forwardedEntity = payload.toEntity(MessageStatus.PENDING).copy(
                        hopCount = payload.hopCount + 1,
                        ttl = payload.ttl - 1,
                        forwarded = true
                    )
                    repository.insertMessage(forwardedEntity)
                    DebugLogManager.i(tag, "Multi-hop candidate queued for forwarding: ${payload.messageId} (TTL: ${forwardedEntity.ttl}, Hop: ${forwardedEntity.hopCount})")
                    // Send hop ACK
                    bleManager.sendAck(device, AckPayload(payload.messageId, localDeviceId))
                } else {
                    DebugLogManager.w(tag, "Multi-hop TTL expired for message ${payload.messageId}. Dropped.")
                }
            }
        }
    }

    /**
     * Handle delivery acknowledgement from receiver
     */
    private fun handleIncomingAck(ack: AckPayload) {
        coroutineScope.launch(Dispatchers.IO) {
            val msg = repository.getMessageById(ack.messageId)
            if (msg != null) {
                repository.markDelivered(ack.messageId)
                DebugLogManager.i(tag, "DELIVERY CONFIRMED: Message ${ack.messageId} status updated to DELIVERED!")
            } else {
                DebugLogManager.d(tag, "Received ACK for unknown or already handled msg: ${ack.messageId}")
            }
        }
    }

    /**
     * Trigger manual processing of all pending messages (e.g. from UI)
     */
    fun processPendingQueue() {
        coroutineScope.launch(Dispatchers.IO) {
            val pending = repository.getPendingMessages()
            DebugLogManager.i(tag, "Processing pending message queue: ${pending.size} messages found")
            for (msg in pending) {
                if (msg.retryCount < BleConstants.MAX_RETRY_COUNT) {
                    attemptTransmission(msg)
                    delay(1000L)
                }
            }
        }
    }

    private fun startPeriodicRetryQueue() {
        periodicRetryJob?.cancel()
        periodicRetryJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(BleConstants.SCAN_REST_DURATION_MS)
                val pending = repository.getPendingMessages()
                if (pending.isNotEmpty()) {
                    DebugLogManager.d(tag, "Automatic retry queue check: ${pending.size} pending message(s)")
                    for (msg in pending) {
                        // Check if receiver is in range
                        val peer = bleManager.scannerManager.getDeviceByAddressOrId(msg.receiverId)
                        if (peer != null && msg.retryCount < BleConstants.MAX_RETRY_COUNT) {
                            DebugLogManager.i(tag, "Peer ${msg.receiverId} is now in range! Retrying pending message ${msg.messageId}")
                            attemptTransmission(msg)
                            delay(1500L)
                        }
                    }
                }
            }
        }
    }
}