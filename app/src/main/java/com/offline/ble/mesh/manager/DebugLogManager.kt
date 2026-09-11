package com.offline.ble.mesh.manager

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class LogEntry(
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
}

object DebugLogManager {
    private const val MAX_LOGS = 300
    private val buffer = ConcurrentLinkedDeque<LogEntry>()
    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    fun d(tag: String, msg: String) = log("DEBUG", tag, msg)
    fun i(tag: String, msg: String) = log("INFO", tag, msg)
    fun w(tag: String, msg: String) = log("WARN", tag, msg)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg - ${tr.message}" else msg
        log("ERROR", tag, fullMsg)
        if (tr != null) Log.e(tag, msg, tr)
    }

    private fun log(level: String, tag: String, msg: String) {
        Log.println(
            when (level) {
                "ERROR" -> Log.ERROR
                "WARN" -> Log.WARN
                "INFO" -> Log.INFO
                else -> Log.DEBUG
            },
            tag,
            msg
        )
        val entry = LogEntry(System.currentTimeMillis(), level, tag, msg)
        buffer.addLast(entry)
        while (buffer.size > MAX_LOGS) {
            buffer.pollFirst()
        }
        _logsFlow.value = buffer.toList()
    }

    fun clear() {
        buffer.clear()
        _logsFlow.value = emptyList()
    }
}