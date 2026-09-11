package com.disasteralert.ui

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.disasteralert.emergency.EmergencyAlert
import com.disasteralert.emergency.EmergencyAlertManager
import com.disasteralert.emergency.EmergencyMessageFormatter
import com.offline.ble.mesh.ble.BleConnectionState
import com.offline.ble.mesh.ble.DiscoveredDevice
import com.offline.ble.mesh.data.model.MessageEntity
import com.offline.ble.mesh.data.model.MessageStatus
import com.offline.ble.mesh.data.model.MessageType
import com.offline.ble.mesh.manager.DebugLogManager
import com.offline.ble.mesh.manager.LogEntry
import com.offline.ble.mesh.manager.MessageManager
import com.offline.ble.mesh.manager.MetricsManager
import com.offline.ble.mesh.manager.MetricsState
import com.offline.ble.mesh.service.BleForegroundService
import com.vibemusic.speechtotext.language.SpeechLanguage
import com.vibemusic.speechtotext.pipeline.PipelineSettings
import com.vibemusic.speechtotext.pipeline.PipelineUiState
import com.vibemusic.speechtotext.pipeline.SpeechPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class EmergencyViewModel(application: Application) : AndroidViewModel(application) {

    private val tag = "EmergencyViewModel"

    // 1. Existing Nearby BLE Communication Subsystem
    val messageManager = MessageManager(application.applicationContext, viewModelScope)

    // 2. Existing Offline Speech-to-Text Subsystem
    val speechPipeline = SpeechPipeline(application.applicationContext, viewModelScope)

    // 3. Emergency Integration Layer
    val emergencyManager = EmergencyAlertManager(application.applicationContext, messageManager, viewModelScope)

    // State flows
    val speechUiState: StateFlow<PipelineUiState> = speechPipeline.uiState
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = messageManager.bleManager.discoveredDevices
    val connectionState: StateFlow<BleConnectionState> = messageManager.bleManager.connectionState
    val isScanning: StateFlow<Boolean> = messageManager.bleManager.isScanning
    val isAdvertising: StateFlow<Boolean> = messageManager.bleManager.isAdvertising

    val incomingAlert: StateFlow<EmergencyAlert?> = emergencyManager.incomingAlert
    val lastOutgoingAlert: StateFlow<EmergencyAlert?> = emergencyManager.lastOutgoingAlert

    val messageHistory: StateFlow<List<MessageEntity>> = messageManager.allMessagesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val metrics: StateFlow<MetricsState> = MetricsManager.metrics
    val logs: StateFlow<List<LogEntry>> = DebugLogManager.logsFlow

    private val _selectedTargetDevice = MutableStateFlow("BROADCAST")
    val selectedTargetDevice: StateFlow<String> = _selectedTargetDevice.asStateFlow()

    private val _transmissionStatus = MutableStateFlow("READY")
    val transmissionStatus: StateFlow<String> = _transmissionStatus.asStateFlow()

    private val _isMeshRunning = MutableStateFlow(false)
    val isMeshRunning: StateFlow<Boolean> = _isMeshRunning.asStateFlow()

    val myDeviceId: String = messageManager.localDeviceId
    val myShortDeviceId: String = messageManager.identityManager.shortDeviceId

    val isBluetoothEnabled: Boolean
        get() = messageManager.bleManager.isBluetoothEnabled

    init {
        // Initialize Speech Pipeline on background dispatcher
        viewModelScope.launch(Dispatchers.IO) {
            speechPipeline.initialize()
        }
    }

    /**
     * Starts BLE advertising, scanning, GATT server, and Foreground Service.
     */
    fun startSubsystems() {
        if (!_isMeshRunning.value) {
            messageManager.start()
            try {
                BleForegroundService.start(getApplication())
            } catch (e: Exception) {
                Log.w(tag, "Foreground service start exception: ${e.message}")
            }
            _isMeshRunning.value = true
            DebugLogManager.i(tag, "Emergency BLE mesh subsystems activated")
        }
    }

    fun stopSubsystems() {
        messageManager.stop()
        try {
            BleForegroundService.stop(getApplication())
        } catch (e: Exception) {
            Log.w(tag, "Foreground service stop exception: ${e.message}")
        }
        _isMeshRunning.value = false
    }

    // SPEECH RECOGNITION ACTIONS
    fun startListening() {
        _transmissionStatus.value = "LISTENING"
        speechPipeline.startListening()
    }

    fun stopListening() {
        speechPipeline.stopListening()
        _transmissionStatus.value = "READY"
    }

    fun clearTranscript() {
        speechPipeline.clearTranscript()
        _transmissionStatus.value = "READY"
    }

    fun selectLanguage(language: SpeechLanguage) {
        viewModelScope.launch(Dispatchers.IO) {
            speechPipeline.switchLanguage(language)
        }
    }

    fun importModel(language: SpeechLanguage, zipUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            speechPipeline.importModelZip(language, zipUri)
        }
    }

    fun scanAndInstallModel(language: SpeechLanguage) {
        viewModelScope.launch(Dispatchers.IO) {
            speechPipeline.scanAndInstallModel(language)
        }
    }

    fun downloadModel(language: SpeechLanguage, url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            speechPipeline.downloadModel(language, url)
        }
    }

    fun setBypassVad(bypass: Boolean) {
        speechPipeline.setBypassVad(bypass)
    }

    fun updateSettings(settings: PipelineSettings) {
        speechPipeline.updateSettings(settings)
    }

    // EMERGENCY SEND ACTION
    fun sendEmergencyAlert(overrideText: String? = null) {
        val currentUi = speechUiState.value
        val textToSend = overrideText?.trim()
            ?: currentUi.finalText.ifBlank { currentUi.partialText }.trim()

        if (textToSend.isBlank()) {
            _transmissionStatus.value = "CANNOT_SEND_EMPTY"
            return
        }

        // Stop listening if still recording
        if (currentUi.isListening) {
            stopListening()
        }

        _transmissionStatus.value = "TRANSMITTING"

        val target = _selectedTargetDevice.value
        emergencyManager.sendEmergencyAlert(
            speechText = textToSend,
            targetReceiverId = target
        ) { messageId ->
            _transmissionStatus.value = "DISPATCHED"
            DebugLogManager.i(tag, "Emergency alert $messageId dispatched to $target")
        }
    }

    fun selectTargetDevice(target: String) {
        _selectedTargetDevice.value = target
    }

    fun dismissIncomingAlert() {
        emergencyManager.dismissIncomingAlert()
    }

    fun sendStandardMessage(receiverId: String, content: String, type: MessageType = MessageType.TEXT) {
        if (receiverId.isBlank() || content.isBlank()) return
        val formatted = EmergencyMessageFormatter.formatNormal(
            text = content.trim(),
            messageId = java.util.UUID.randomUUID().toString()
        )
        messageManager.sendMessage(receiverId, formatted, type)
    }

    // OFFLINE TTS CONTROL & DIAGNOSTICS
    fun testEmergencyVoiceAlert(text: String = "This is an emergency alert. Please assist immediately.") {
        emergencyManager.ttsEngine.speak(text, isAlert = true)
    }

    fun testNormalVoicePlayback(text: String = "Hello. This is normal speech synthesis test.") {
        emergencyManager.ttsEngine.speak(text, isAlert = false)
    }

    fun stopVoicePlayback() {
        emergencyManager.ttsEngine.stop()
    }

    val ttsIsSpeaking: Boolean
        get() = emergencyManager.ttsEngine.isSpeaking

    fun retryFailedMessages() {
        messageManager.processPendingQueue()
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            messageManager.repository.clearAll()
        }
    }

    fun clearLogs() {
        DebugLogManager.clear()
    }

    override fun onCleared() {
        super.onCleared()
        speechPipeline.release()
        emergencyManager.release()
        messageManager.stop()
    }
}
