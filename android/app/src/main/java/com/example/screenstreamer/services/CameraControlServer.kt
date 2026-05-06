package com.example.screenstreamer.services

import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class CameraControlServer(
    port: Int,
    private val handler: CommandHandler
) : WebSocketServer(InetSocketAddress(port)) {

    interface CommandHandler {
        fun switchFront()
        fun switchBack()
        fun zoomIn()
        fun zoomOut()
        fun setZoom(value: Float)
        fun toggleFlash()
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        conn.send("CAMERA_CONTROL_READY")
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        // No per-client state is required for camera controls.
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val clean = message.trim()
            when {
                clean == "SWITCH_FRONT" -> handler.switchFront()
                clean == "SWITCH_BACK" -> handler.switchBack()
                clean == "ZOOM_IN" -> handler.zoomIn()
                clean == "ZOOM_OUT" -> handler.zoomOut()
                clean == "FLASH_TOGGLE" -> handler.toggleFlash()
                clean.startsWith("ZOOM:") -> handler.setZoom(clean.substringAfter(":").toFloatOrNull() ?: 1f)
            }
            conn.send("OK:$clean")
        } catch (ex: Exception) {
            conn.send("ERR:${ex.message}")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        // The stream service keeps running even if a control client disconnects.
    }

    override fun onStart() {
        connectionLostTimeout = 3
    }
}

