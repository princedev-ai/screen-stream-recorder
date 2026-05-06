package com.example.screenstreamer.utils

import android.content.Context
import com.example.screenstreamer.model.ConnectionMode
import com.example.screenstreamer.model.PcEndpoint

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screen_stream_preferences", Context.MODE_PRIVATE)

    var connectionMode: ConnectionMode
        get() = ConnectionMode.fromWireValue(prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.AUTO.wireValue))
        set(value) {
            prefs.edit().putString(KEY_CONNECTION_MODE, value.wireValue).apply()
        }

    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply()
        }

    var batterySaverEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SAVER, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BATTERY_SAVER, value).apply()
        }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean(KEY_AUTO_BOOT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_BOOT, value).apply()
        }

    var statusText: String
        get() = prefs.getString(KEY_STATUS, "Ready") ?: "Ready"
        set(value) {
            prefs.edit().putString(KEY_STATUS, value).apply()
        }

    var lastRecordingPath: String
        get() = prefs.getString(KEY_LAST_RECORDING, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_LAST_RECORDING, value).apply()
        }

    var lastPcEndpoint: PcEndpoint?
        get() = PcEndpoint.fromPreferenceValue(prefs.getString(KEY_LAST_PC, null))
        set(value) {
            prefs.edit().putString(KEY_LAST_PC, value?.toPreferenceValue()).apply()
        }

    var manualPcHost: String
        get() = prefs.getString(KEY_MANUAL_PC_HOST, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_MANUAL_PC_HOST, value.trim()).apply()
        }

    companion object {
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"
        private const val KEY_BATTERY_SAVER = "battery_saver"
        private const val KEY_AUTO_BOOT = "auto_boot"
        private const val KEY_STATUS = "status"
        private const val KEY_LAST_RECORDING = "last_recording"
        private const val KEY_LAST_PC = "last_pc"
        private const val KEY_MANUAL_PC_HOST = "manual_pc_host"
    }
}
