package com.offline.ble.mesh.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offline.ble.mesh.ble.BleConnectionState
import com.offline.ble.mesh.ble.DiscoveredDevice
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageType
import com.offline.ble.mesh.manager.DebugLogManager
import com.offline.ble.mesh.manager.LogEntry
import com.offline.ble.mesh.manager.MessageManager
import com.offline.ble.mesh.manager.MetricsManager
import com.offline.ble.mesh.manager.MetricsState
import com.offline.ble.mesh.service.BleForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val messageManager = MessageManager(application.applicationContext, viewModelScope)

    val myDeviceId: String = messageManager.localDeviceId
    val myShortDeviceId: String = messageManager.identityManager.shortDeviceId

    val isBluetoothEnabled: Boolean
        get() = messageManager.bleManager.isBluetoothEnabled

    val isBleSupported: Boolean
        get() = messageManager.bleManager.isBleSupported

    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = messageManager.bleManager.discoveredDevices
    val isScanning: StateFlow<Boolean> = messageManager.bleManager.isScanning
    val isAdvertising: StateFlow<Boolean> = messageManager.bleManager.isAdvertising
    val connectionState: StateFlow<BleConnectionState> = messageManager.bleManager.connectionState
    val connectedDeviceId: StateFlow<String?> = messageManager.bleManager.connectedDeviceId

    val messageHistory: StateFlow<List<MessageEntity>> = messageManager.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val metrics: StateFlow<MetricsState> = MetricsManager.metrics
    val logs: StateFlow<List<LogEntry>> = DebugLogManager.logsFlow

    private val _receiverInput = MutableStateFlow("")
    val receiverInput: StateFlow<String> = _receiverInput.asStateFlow()

    private val _messageInput = MutableStateFlow("")
    val messageInput: StateFlow<String> = _messageInput.asStateFlow()

    private val _selectedMessageType = MutableStateFlow(MessageType.TEXT)
    val selectedMessageType: StateFlow<MessageType> = _selectedMessageType.asStateFlow()

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    fun onReceiverInputChanged(newValue: String) {
        _receiverInput.value = newValue
    }

    fun onMessageInputChanged(newValue: String) {
        _messageInput.value = newValue
    }

    fun onMessageTypeSelected(type: MessageType) {
        _selectedMessageType.value = type
    }

    fun selectPeer(peer: DiscoveredDevice) {
        _receiverInput.value = if (peer.deviceId.isNotBlank()) peer.deviceId else peer.device.address
    }

    fun startSubsystems() {
        messageManager.start()
        startForegroundService()
    }

    fun startForegroundService() {
        BleForegroundService.start(getApplication())
        _isServiceRunning.value = true
    }

    fun stopForegroundService() {
        BleForegroundService.stop(getApplication())
        _isServiceRunning.value = false
    }

    fun sendMessage() {
        val receiver = _receiverInput.value.trim()
        val text = _messageInput.value.trim()
        if (receiver.isBlank() || text.isBlank()) return

        messageManager.sendMessage(
            receiverId = receiver,
            content = text,
            type = _selectedMessageType.value
        )
        _messageInput.value = ""
    }

    fun sendLargeTestMessage() {
        val receiver = _receiverInput.value.trim().ifBlank {
            discoveredDevices.value.firstOrNull()?.let {
                if (it.deviceId.isNotBlank()) it.deviceId else it.device.address
            } ?: "BROADCAST"
        }
        val builder = StringBuilder()
        builder.append("[LARGE CHUNK TEST MESSAGE: ")
        for (i in 1..40) {
            builder.append("ChunkTestPayload#$i-Offline-BLE-Mesh-Direct-D2D-")
        }
        builder.append("END]")

        messageManager.sendMessage(
            receiverId = receiver,
            content = builder.toString(),
            type = MessageType.DATA
        )
    }

    fun testDuplicateMessage() {
        val lastMsg = messageHistory.value.firstOrNull { it.senderId == myDeviceId }
        if (lastMsg != null) {
            DebugLogManager.i("MainViewModel", "Injecting intentional duplicate for Message ID: ${lastMsg.messageId}")
            viewModelScope.launch(Dispatchers.IO) {
                // Re-send with exact same messageId to test duplicate protection
                val payload = com.offline.ble.mesh.ble.protocol.MessagePayload.fromEntity(lastMsg)
                messageManager.bleManager.sendPayload(lastMsg.receiverId, payload) { success, _ ->
                    DebugLogManager.d("MainViewModel", "Duplicate test payload transmitted: $success")
                }
            }
        } else {
            sendMessage()
        }
    }

    fun toggleScanning() {
        if (isScanning.value) {
            messageManager.bleManager.scannerManager.stopScanning()
        } else {
            messageManager.bleManager.scannerManager.startScanning()
        }
    }

    fun toggleAdvertising() {
        if (isAdvertising.value) {
            messageManager.bleManager.advertiserManager.stopAdvertising()
        } else {
            messageManager.bleManager.advertiserManager.startAdvertising()
        }
    }

    fun triggerRetryQueue() {
        messageManager.processPendingQueue()
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            messageManager.repository.clearAll()
            DebugLogManager.i("MainViewModel", "Message history cleared")
        }
    }

    fun clearLogs() {
        DebugLogManager.clear()
    }

    fun copyDeviceIdToClipboard(context: Context) {
        messageManager.identityManager.copyToClipboard(context)
        DebugLogManager.i("MainViewModel", "Device ID copied to clipboard")
    }

    override fun onCleared() {
        super.onCleared()
        messageManager.stop()
    }
}