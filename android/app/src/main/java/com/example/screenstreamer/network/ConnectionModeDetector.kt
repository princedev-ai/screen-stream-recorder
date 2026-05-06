package com.example.screenstreamer.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.example.screenstreamer.model.ConnectionMode

class ConnectionModeDetector(private val context: Context) {
    fun detectBestMode(preferredMode: ConnectionMode): ConnectionMode {
        if (preferredMode != ConnectionMode.AUTO) return preferredMode

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return ConnectionMode.NONE
        val capabilities = manager.getNetworkCapabilities(network) ?: return ConnectionMode.NONE

        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return ConnectionMode.WIFI
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
        ) {
            return ConnectionMode.WIFI
        }
        return ConnectionMode.HOTSPOT
    }
}
