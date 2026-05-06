package com.example.screenstreamer.model

data class RecordingConfig(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val fps: Int = 30,
    val bitrate: Int = 3_000_000,
    val audioEnabled: Boolean = false,
    val connectionMode: ConnectionMode = ConnectionMode.AUTO
)

