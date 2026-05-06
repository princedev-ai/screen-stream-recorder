package com.example.screenstreamer.streaming

import com.example.screenstreamer.model.PcEndpoint
import java.io.Closeable
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoPacketStreamer : Closeable {
    private val running = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<Packet>(MAX_QUEUE_SIZE)
    private val lock = Any()
    private var endpoint: PcEndpoint? = null
    private var worker: Thread? = null
    private var lastConnectAttemptMs = 0L
    @Volatile private var lastConfig: ByteArray? = null

    fun setEndpoint(endpoint: PcEndpoint?) {
        synchronized(lock) {
            this.endpoint = endpoint
        }
        val config = lastConfig
        if (running.get() && config != null && config.isNotEmpty()) {
            enqueue(Packet(TYPE_CONFIG, 0.toByte(), 0L, config))
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread(::runWriter, "video-packet-streamer").apply {
            isDaemon = true
            start()
        }
    }

    fun sendConfig(bytes: ByteArray) {
        lastConfig = bytes
        enqueue(Packet(TYPE_CONFIG, 0.toByte(), 0L, bytes))
    }

    fun sendFrame(bytes: ByteArray, keyFrame: Boolean, ptsUs: Long) {
        enqueue(Packet(TYPE_FRAME, if (keyFrame) FLAG_KEY_FRAME else 0.toByte(), ptsUs, bytes))
    }

    private fun enqueue(packet: Packet) {
        if (!running.get() || packet.payload.isEmpty()) return
        if (!queue.offer(packet)) {
            queue.poll()
            queue.offer(packet)
        }
    }

    private fun runWriter() {
        var socket: Socket? = null
        var out: DataOutputStream? = null

        while (running.get()) {
            val packet = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            try {
                var stream = out
                val currentSocket = socket
                if (currentSocket == null || currentSocket.isClosed || stream == null) {
                    val target = synchronized(lock) { endpoint }
                    if (target == null || !canAttemptConnect()) {
                        continue
                    }
                    val newSocket = Socket()
                    newSocket.tcpNoDelay = true
                    newSocket.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
                    socket = newSocket
                    val newStream = DataOutputStream(newSocket.getOutputStream())
                    stream = newStream
                    out = newStream
                    lastConfig?.let { writePacket(newStream, Packet(TYPE_CONFIG, 0.toByte(), 0L, it)) }
                }
                val activeStream = stream ?: continue
                writePacket(activeStream, packet)
            } catch (_: Exception) {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
                socket = null
                out = null
            }
        }

        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun canAttemptConnect(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastConnectAttemptMs < RECONNECT_INTERVAL_MS) return false
        lastConnectAttemptMs = now
        return true
    }

    private fun writePacket(out: DataOutputStream, packet: Packet) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(packet.type)
            .put(packet.flags)
            .putShort(0.toShort())
            .putLong(packet.ptsUs)
            .putInt(packet.payload.size)
            .array()
        out.write(header)
        out.write(packet.payload)
        out.flush()
    }

    override fun close() {
        running.set(false)
        worker?.interrupt()
        worker = null
        queue.clear()
    }

    private data class Packet(
        val type: Byte,
        val flags: Byte,
        val ptsUs: Long,
        val payload: ByteArray
    )

    companion object {
        private val MAGIC = byteArrayOf('S'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte(), '1'.code.toByte())
        private const val HEADER_SIZE = 20
        private const val MAX_QUEUE_SIZE = 90
        private const val CONNECT_TIMEOUT_MS = 1500
        private const val RECONNECT_INTERVAL_MS = 2000L
        private const val TYPE_CONFIG: Byte = 1
        private const val TYPE_FRAME: Byte = 2
        private const val FLAG_KEY_FRAME: Byte = 1
    }
}
