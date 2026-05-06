package com.example.screenstreamer.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.IBinder
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class AudioCaptureService : Service() {
    private val running = AtomicBoolean(false)
    private var audioServer: AudioWebSocketServer? = null
    private var audioThread: Thread? = null
    private var encoder: AacEncoder? = null
    private var systemCapture: SystemAudioCapture? = null
    private var micCapture: MicCapture? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startAudio()
                ACTION_STOP -> stopAudio()
                else -> {
                    if (!running.get()) {
                        stopSelf()
                    }
                }
            }
        } catch (_: Exception) {
            running.set(false)
            stopAudioResources(stopSelfAfterStop = true, stopForeground = true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            stopAudio()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAudio() {
        if (running.get()) {
            stopAudioResources(stopSelfAfterStop = false, stopForeground = false)
            running.set(false)
        }
        if (!running.compareAndSet(false, true)) return
        try {
            startForegroundCompat()
            audioServer = AudioWebSocketServer(AUDIO_PORT).also { it.start() }
            encoder = AacEncoder(SAMPLE_RATE, CHANNEL_COUNT, AAC_BITRATE).also { it.start() }
            systemCapture = SystemAudioCapture(this, sharedProjection).also { it.start() }
            micCapture = MicCapture(this).also { it.start() }

            audioThread = Thread(::runAudioLoop, "audio-capture-mixer").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            running.set(false)
            stopAudioResources(stopSelfAfterStop = true, stopForeground = true)
        }
    }

    private fun stopAudio() {
        if (!running.compareAndSet(true, false)) return
        stopAudioResources(stopSelfAfterStop = true, stopForeground = true)
    }

    private fun stopAudioResources(stopSelfAfterStop: Boolean, stopForeground: Boolean) {
        try {
            audioThread?.interrupt()
        } catch (_: Exception) {
        }
        audioThread = null

        try {
            systemCapture?.close()
        } catch (_: Exception) {
        }
        systemCapture = null

        try {
            micCapture?.close()
        } catch (_: Exception) {
        }
        micCapture = null

        try {
            encoder?.close()
        } catch (_: Exception) {
        }
        encoder = null

        try {
            audioServer?.stop(500)
        } catch (_: Exception) {
        }
        audioServer = null

        if (stopForeground) {
            stopForegroundCompat()
        }
        if (stopSelfAfterStop) {
            stopSelf()
        }
    }

    private fun runAudioLoop() {
        val mixer = AudioMixer()
        val systemBuffer = ShortArray(CHUNK_SHORTS)
        val micBuffer = ShortArray(CHUNK_SHORTS)

        while (running.get()) {
            val systemRead = systemCapture?.read(systemBuffer) ?: 0
            val micRead = micCapture?.readStereo(micBuffer) ?: 0
            val mixedShorts = mixer.mix(systemBuffer, systemRead, micBuffer, micRead)
            if (mixedShorts.isNotEmpty()) {
                encoder?.encode(mixedShorts) { frame ->
                    audioServer?.broadcastBinary(frame)
                }
            } else {
                try {
                    Thread.sleep(10)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Audio streaming active", "Streaming mixed system and mic audio on port $AUDIO_PORT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            ) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Audio streaming", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    class SystemAudioCapture(
        private val context: Context,
        private val projection: MediaProjection?
    ) {
        private var record: AudioRecord? = null

        fun start() {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || projection == null) return
                val format = stereoFormat()
                val bufferSize = max(CHUNK_BYTES * 4, AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                record?.startRecording()
            } catch (_: Exception) {
                close()
            }
        }

        fun read(out: ShortArray): Int {
            val audioRecord = record ?: return 0
            return try {
                max(0, audioRecord.read(out, 0, out.size, AudioRecord.READ_NON_BLOCKING))
            } catch (_: Exception) {
                0
            }
        }

        fun close() {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
            record = null
        }
    }

    class MicCapture(private val context: Context) {
        private var record: AudioRecord? = null
        private val monoBuffer = ShortArray(CHUNK_FRAMES)

        fun start() {
            try {
                if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
                val bufferSize = max(CHUNK_BYTES * 4, AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
                record?.startRecording()
            } catch (_: Exception) {
                close()
            }
        }

        fun readStereo(out: ShortArray): Int {
            val audioRecord = record ?: return 0
            val frames = try {
                max(0, audioRecord.read(monoBuffer, 0, monoBuffer.size, AudioRecord.READ_BLOCKING))
            } catch (_: Exception) {
                0
            }
            var target = 0
            for (i in 0 until frames) {
                val sample = monoBuffer[i]
                if (target + 1 >= out.size) break
                out[target++] = sample
                out[target++] = sample
            }
            return target
        }

        fun close() {
            try {
                record?.stop()
            } catch (_: Exception) {
            }
            try {
                record?.release()
            } catch (_: Exception) {
            }
            record = null
        }
    }

    class AudioMixer {
        fun mix(system: ShortArray, systemRead: Int, mic: ShortArray, micRead: Int): ShortArray {
            val count = max(systemRead, micRead).coerceAtMost(CHUNK_SHORTS)
            if (count <= 0) return ShortArray(0)
            val mixed = ShortArray(count)
            for (i in 0 until count) {
                val systemSample = if (i < systemRead) system[i].toInt() else 0
                val micSample = if (i < micRead) mic[i].toInt() else 0
                val value = (systemSample * SYSTEM_GAIN + micSample * MIC_GAIN).toInt()
                mixed[i] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            return mixed
        }
    }

    class AacEncoder(
        private val sampleRate: Int,
        private val channels: Int,
        private val bitrate: Int
    ) {
        private val bufferInfo = MediaCodec.BufferInfo()
        private var codec: MediaCodec? = null
        private var ptsUs = 0L

        fun start() {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        }

        fun encode(samples: ShortArray, onFrame: (ByteArray) -> Unit) {
            val mediaCodec = codec ?: return
            val inputIndex = mediaCodec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val input = mediaCodec.getInputBuffer(inputIndex)
                input?.clear()
                val bytes = shortsToBytes(samples)
                input?.put(bytes)
                mediaCodec.queueInputBuffer(inputIndex, 0, bytes.size, ptsUs, 0)
                ptsUs += samples.size / channels * 1_000_000L / sampleRate
            }
            drain(mediaCodec, onFrame)
        }

        private fun drain(mediaCodec: MediaCodec, onFrame: (ByteArray) -> Unit) {
            while (true) {
                val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                if (outputIndex < 0) break
                val output = mediaCodec.getOutputBuffer(outputIndex)
                if (output != null && bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                    output.position(bufferInfo.offset)
                    output.limit(bufferInfo.offset + bufferInfo.size)
                    val packet = ByteArray(bufferInfo.size)
                    output.get(packet)
                    onFrame(addAdtsHeader(packet, sampleRate, channels))
                }
                mediaCodec.releaseOutputBuffer(outputIndex, false)
            }
        }

        fun close() {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            codec = null
        }

        private fun shortsToBytes(samples: ShortArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
            return bytes
        }

        private fun addAdtsHeader(packet: ByteArray, sampleRate: Int, channels: Int): ByteArray {
            val profile = 2
            val freqIdx = 4
            val packetLen = packet.size + 7
            val out = ByteArray(packetLen)
            out[0] = 0xFF.toByte()
            out[1] = 0xF1.toByte()
            out[2] = (((profile - 1) shl 6) + (freqIdx shl 2) + (channels shr 2)).toByte()
            out[3] = (((channels and 3) shl 6) + (packetLen shr 11)).toByte()
            out[4] = ((packetLen and 0x7FF) shr 3).toByte()
            out[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
            out[6] = 0xFC.toByte()
            System.arraycopy(packet, 0, out, 7, packet.size)
            return out
        }
    }

    class AudioWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        private val clients = Collections.synchronizedSet(mutableSetOf<WebSocket>())

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients.add(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            clients.remove(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            // Audio stream is one-way; text messages are ignored.
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn != null) clients.remove(conn)
        }

        override fun onStart() {
            connectionLostTimeout = 3
        }

        fun broadcastBinary(bytes: ByteArray) {
            synchronized(clients) {
                clients.removeAll { !it.isOpen }
                for (client in clients) {
                    try {
                        client.send(bytes)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.screenstreamer.audio.START"
        const val ACTION_STOP = "com.example.screenstreamer.audio.STOP"
        const val AUDIO_PORT = 8081
        const val SAMPLE_RATE = 44_100
        const val CHANNEL_COUNT = 2
        const val AAC_BITRATE = 64_000
        private const val CHANNEL_ID = "screen_stream_audio"
        private const val NOTIFICATION_ID = 81
        private const val CHUNK_FRAMES = 1024
        private const val CHUNK_SHORTS = CHUNK_FRAMES * CHANNEL_COUNT
        private const val CHUNK_BYTES = CHUNK_SHORTS * 2
        private const val SYSTEM_GAIN = 0.70f
        private const val MIC_GAIN = 0.30f
        @Volatile var sharedProjection: MediaProjection? = null

        fun setMediaProjection(projection: MediaProjection?) {
            sharedProjection = projection
        }

        private fun stereoFormat(): AudioFormat {
            return AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
        }
    }
}
