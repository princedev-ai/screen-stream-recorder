package com.example.screenstreamer.model

enum class ConnectionMode(val wireValue: String) {
    AUTO("auto"),
    WIFI("wifi"),
    HOTSPOT("hotspot"),
    NONE("none");

    companion object {
        fun fromWireValue(value: String?): ConnectionMode {
            return entries.firstOrNull { it.wireValue == value } ?: AUTO
        }
    }
}

