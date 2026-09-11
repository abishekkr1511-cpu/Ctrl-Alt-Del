package com.offline.ble.mesh.manager

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MetricsState(
    val discoveryDurationMs: Long = 0L,
    val connectionDurationMs: Long = 0L,
    val transmissionDurationMs: Long = 0L,
    val ackDurationMs: Long = 0L,
    val totalDeliveryDurationMs: Long = 0L,
    val duplicatesPreventedCount: Int = 0,
    val totalRetryCount: Int = 0,
    val successfulTransmissions: Int = 0,
    val failedTransmissions: Int = 0,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val activeConnectionsCount: Int = 0,
    val lastSentMessageId: String? = null,
    val lastReceivedMessageId: String? = null,
    val lastAckReceivedId: String? = null,
    val lastBleError: String? = null
)

object MetricsManager {
    private val _metrics = MutableStateFlow(MetricsState())
    val metrics: StateFlow<MetricsState> = _metrics.asStateFlow()

    fun recordDiscovery(durationMs: Long) {
        _metrics.update { it.copy(discoveryDurationMs = durationMs) }
    }

    fun recordConnection(durationMs: Long) {
        _metrics.update { it.copy(connectionDurationMs = durationMs) }
    }

    fun recordTransmission(durationMs: Long, bytes: Long) {
        _metrics.update {
            it.copy(
                transmissionDurationMs = durationMs,
                bytesSent = it.bytesSent + bytes
            )
        }
    }

    fun recordAckReceived(msgId: String, durationMs: Long) {
        _metrics.update {
            it.copy(
                ackDurationMs = durationMs,
                lastAckReceivedId = msgId,
                successfulTransmissions = it.successfulTransmissions + 1,
                totalDeliveryDurationMs = it.transmissionDurationMs + durationMs
            )
        }
    }

    fun recordMessageSent(msgId: String) {
        _metrics.update { it.copy(lastSentMessageId = msgId) }
    }

    fun recordMessageReceived(msgId: String, bytes: Long) {
        _metrics.update {
            it.copy(
                lastReceivedMessageId = msgId,
                bytesReceived = it.bytesReceived + bytes
            )
        }
    }

    fun recordDuplicatePrevented() {
        _metrics.update { it.copy(duplicatesPreventedCount = it.duplicatesPreventedCount + 1) }
    }

    fun recordRetry() {
        _metrics.update { it.copy(totalRetryCount = it.totalRetryCount + 1) }
    }

    fun recordFailure(error: String) {
        _metrics.update {
            it.copy(
                failedTransmissions = it.failedTransmissions + 1,
                lastBleError = error
            )
        }
    }

    fun setActiveConnections(count: Int) {
        _metrics.update { it.copy(activeConnectionsCount = count) }
    }

    fun reset() {
        _metrics.value = MetricsState()
    }
}