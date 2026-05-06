package com.example.screenstreamer.model

data class PcEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val mode: ConnectionMode
) {
    fun toPreferenceValue(): String = listOf(host, port.toString(), name, mode.wireValue).joinToString("|")

    companion object {
        fun fromPreferenceValue(value: String?): PcEndpoint? {
            if (value.isNullOrBlank()) return null
            val parts = value.split("|")
            if (parts.size < 4) return null
            val port = parts[1].toIntOrNull() ?: return null
            return PcEndpoint(
                host = parts[0],
                port = port,
                name = parts[2],
                mode = ConnectionMode.fromWireValue(parts[3])
            )
        }
    }
}

