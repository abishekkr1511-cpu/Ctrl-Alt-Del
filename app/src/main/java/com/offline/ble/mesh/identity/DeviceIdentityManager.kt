package com.offline.ble.mesh.identity

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class DeviceIdentityManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String by lazy {
        getOrGenerateDeviceId()
    }

    val shortDeviceId: String
        get() = if (deviceId.length >= 8) deviceId.substring(0, 8) else deviceId

    private fun getOrGenerateDeviceId(): String {
        var id = prefs.getString(KEY_DEVICE_ID, null)
        if (id.isNullOrBlank()) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        }
        return id
    }

    fun copyToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Device ID", deviceId)
        clipboard?.setPrimaryClip(clip)
    }

    companion object {
        private const val PREFS_NAME = "offline_ble_mesh_identity"
        private const val KEY_DEVICE_ID = "local_device_id"
    }
}