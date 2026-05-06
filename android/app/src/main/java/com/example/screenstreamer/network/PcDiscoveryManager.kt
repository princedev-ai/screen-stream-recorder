package com.example.screenstreamer.network

import android.content.Context
import android.os.Build
import com.example.screenstreamer.model.ConnectionMode
import com.example.screenstreamer.model.PcEndpoint
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PcDiscoveryManager(private val context: Context) {
    private val modeDetector = ConnectionModeDetector(context)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    fun discover(
        preferredMode: ConnectionMode,
        timeoutMs: Int = 8000,
        callback: (PcEndpoint?) -> Unit
    ) {
        executor.execute {
            val mode = modeDetector.detectBestMode(preferredMode)
            val endpoint = discoverByUdp(mode, timeoutMs)
            callback(endpoint)
        }
    }

    fun close() {
        executor.shutdownNow()
    }

    private fun discoverByUdp(mode: ConnectionMode, timeoutMs: Int): PcEndpoint? {
        val deadline = System.currentTimeMillis() + timeoutMs
        val message = "SSP_DISCOVER|${Build.MODEL}|${context.packageName}".toByteArray(StandardCharsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.broadcast = true
            socket.soTimeout = 700

            while (System.currentTimeMillis() < deadline) {
                sendDiscoveryPackets(socket, message)
                val response = receiveResponse(socket, mode)
                if (response != null) return response
            }
        }
        return null
    }

    private fun sendDiscoveryPackets(socket: DatagramSocket, message: ByteArray) {
        val targets = mutableSetOf<InetAddress>()
        targets.add(InetAddress.getByName("255.255.255.255"))
        targets.addAll(findBroadcastAddresses())

        for (target in targets) {
            try {
                val packet = DatagramPacket(message, message.size, target, DISCOVERY_PORT)
                socket.send(packet)
            } catch (_: Exception) {
            }
        }
    }

    private fun receiveResponse(socket: DatagramSocket, mode: ConnectionMode): PcEndpoint? {
        val buffer = ByteArray(512)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            parseResponse(String(packet.data, 0, packet.length, StandardCharsets.UTF_8), packet.address, mode)
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseResponse(payload: String, address: InetAddress, mode: ConnectionMode): PcEndpoint? {
        val parts = payload.trim().split("|")
        if (parts.size < 4 || parts[0] != "SSP_PC") return null
        val port = parts[2].toIntOrNull() ?: return null
        val name = parts[1].ifBlank { address.hostAddress ?: "PC Receiver" }
        return PcEndpoint(
            host = address.hostAddress ?: parts[3],
            port = port,
            name = name,
            mode = mode
        )
    }

    private fun findBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        val interfaces = try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
        } catch (_: Exception) {
            emptyList<NetworkInterface>()
        }

        for (networkInterface in interfaces) {
            if (!isUsableInterface(networkInterface)) continue
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val broadcast = interfaceAddress.broadcast
                if (broadcast is Inet4Address) {
                    result.add(broadcast)
                }
            }
        }
        return result
    }

    private fun isUsableInterface(networkInterface: NetworkInterface): Boolean {
        return try {
            networkInterface.isUp && !networkInterface.isLoopback
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val DISCOVERY_PORT = 45891
        const val STREAM_PORT = 45892
        const val SERVICE_TYPE = "_screenstream._tcp."
    }
}
