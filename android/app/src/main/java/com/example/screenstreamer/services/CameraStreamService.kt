package com.example.screenstreamer.services

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import com.example.screenstreamer.recording.AvcUtils
import com.example.screenstreamer.utils.AppPreferences
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class CameraStreamService : Service(), CameraControlServer.CommandHandler {
    private val running = AtomicBoolean(false)
    private lateinit var cameraManager: CameraManager
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderThread: Thread? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var videoServer: CameraWebSocketServer? = null
    private var controlServer: CameraControlServer? = null
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    private var zoomValue = 1f
    private var flashEnabled = false
    private var activeCameraId: String? = null
    private var activeCharacteristics: CameraCharacteristics? = null
    private var csd0: ByteArray? = null
    private var csd1: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private val restartingPipeline = AtomicBoolean(false)
    private lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startCamera()
                ACTION_STOP -> stopCamera()
                ACTION_SWITCH_FRONT -> switchFront()
                ACTION_SWITCH_BACK -> switchBack()
                else -> {
                    if (!running.get()) {
                        stopSelf()
                    }
                }
            }
        } catch (_: Exception) {
            running.set(false)
            stopCameraResources(stopSelfAfterStop = true, stopForeground = true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        try {
            stopCamera()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCamera() {
        if (!running.compareAndSet(false, true)) {
            if (cameraHandler == null || videoServer == null || encoder == null) {
                resetPartialCameraStart()
            } else {
                schedulePcAnnouncements()
            }
            return
        }
        try {
            startForegroundCompat()
            cameraThread = HandlerThread("camera-stream").also { it.start() }
            cameraHandler = Handler(cameraThread!!.looper)
            videoServer = CameraWebSocketServer(CAMERA_PORT).also { it.start() }
            controlServer = CameraControlServer(CONTROL_PORT, this).also { it.start() }
            startEncoder()
            openCamera(lensFacing)
            schedulePcAnnouncements()
        } catch (_: Exception) {
            running.set(false)
            stopCameraResources(stopSelfAfterStop = true, stopForeground = true)
        }
    }

    private fun resetPartialCameraStart() {
        try {
            stopCameraResources(stopSelfAfterStop = false, stopForeground = false)
        } catch (_: Exception) {
        } finally {
            running.set(false)
        }
        startCamera()
    }

    private fun stopCamera() {
        if (!running.compareAndSet(true, false)) return
        stopCameraResources(stopSelfAfterStop = true, stopForeground = true)
    }

    private fun stopCameraResources(stopSelfAfterStop: Boolean, stopForeground: Boolean) {
        restartingPipeline.set(false)
        closeCamera()
        stopEncoder()
        try {
            videoServer?.stop(500)
        } catch (_: Exception) {
        }
        videoServer = null
        try {
            controlServer?.stop(500)
        } catch (_: Exception) {
        }
        controlServer = null
        cameraThread?.quitSafely()
        cameraThread = null
        if (stopForeground) {
            stopForegroundCompat()
        }
        if (stopSelfAfterStop) {
            stopSelf()
        }
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, videoWidth(), videoHeight()).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, videoBitrate())
            setInteger(MediaFormat.KEY_FRAME_RATE, videoFps())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        encoderSurface = encoder!!.createInputSurface()
        encoder!!.start()
        encoderThread = Thread(::drainEncoder, "camera-h264-encoder").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopEncoder() {
        try {
            encoder?.signalEndOfInputStream()
        } catch (_: Exception) {
        }
        try {
            encoderThread?.join(1000)
        } catch (_: Exception) {
        }
        encoderThread = null
        try {
            encoder?.stop()
        } catch (_: Exception) {
        }
        try {
            encoder?.release()
        } catch (_: Exception) {
        }
        encoder = null
        try {
            encoderSurface?.release()
        } catch (_: Exception) {
        }
        encoderSurface = null
    }

    private fun openCamera(facing: Int) {
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            val cameraId = findCameraId(facing) ?: return
            activeCameraId = cameraId
            activeCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    try {
                        cameraDevice = camera
                        createSession(camera)
                    } catch (_: Exception) {
                        try {
                            camera.close()
                        } catch (_: Exception) {
                        }
                        cameraDevice = null
                        restartPipelineDelayed(RESTART_DELAY_MS)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                    restartPipelineDelayed(RESTART_DELAY_MS)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    cameraDevice = null
                    restartPipelineDelayed(RESTART_DELAY_MS)
                }
            }, cameraHandler)
        } catch (_: Exception) {
            restartPipelineDelayed(RESTART_DELAY_MS)
        }
    }

    private fun createSession(camera: CameraDevice) {
        try {
            val surface = encoderSurface ?: return
            previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                applyZoomAndFlash(this)
            }
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    try {
                        captureSession = session
                        updateRepeatingRequest()
                    } catch (_: Exception) {
                        restartPipelineDelayed(RESTART_DELAY_MS)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    session.close()
                    restartPipelineDelayed(RESTART_DELAY_MS)
                }
            }, cameraHandler)
        } catch (_: Exception) {
            restartPipelineDelayed(RESTART_DELAY_MS)
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null
        previewRequestBuilder = null
    }

    private fun drainEncoder() {
        try {
            while (running.get()) {
                val mediaCodec = encoder ?: break
                val outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = mediaCodec.outputFormat
                        csd0 = format.getOptionalByteArray("csd-0")
                        csd1 = format.getOptionalByteArray("csd-1")
                        videoServer?.broadcastBinary(AvcUtils.csdToAnnexB(csd0, csd1), dropIfBuffered = false)
                    }
                    outputIndex >= 0 -> {
                        val output = mediaCodec.getOutputBuffer(outputIndex)
                        if (output != null && bufferInfo.size > 0 && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            output.position(bufferInfo.offset)
                            output.limit(bufferInfo.offset + bufferInfo.size)
                            val sample = AvcUtils.byteBufferToArray(output)
                            val keyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                            videoServer?.broadcastBinary(
                                AvcUtils.sampleToAnnexB(sample, csd0, csd1, keyFrame),
                                dropIfBuffered = !keyFrame
                            )
                        }
                        mediaCodec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } catch (_: Exception) {
            restartPipelineDelayed(RESTART_DELAY_MS)
        }
    }

    private fun findCameraId(facing: Int): String? {
        for (id in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) return id
        }
        return cameraManager.cameraIdList.firstOrNull()
    }

    override fun switchFront() {
        lensFacing = CameraCharacteristics.LENS_FACING_FRONT
        restartCameraOnly()
    }

    override fun switchBack() {
        lensFacing = CameraCharacteristics.LENS_FACING_BACK
        restartCameraOnly()
    }

    override fun zoomIn() {
        setZoom(zoomValue + 0.2f)
    }

    override fun zoomOut() {
        setZoom(zoomValue - 0.2f)
    }

    override fun setZoom(value: Float) {
        zoomValue = value.coerceIn(1f, maxZoom())
        updateRepeatingRequest()
    }

    override fun toggleFlash() {
        flashEnabled = !flashEnabled
        updateRepeatingRequest()
    }

    private fun restartCameraOnly() {
        cameraHandler?.post {
            closeCamera()
            openCamera(lensFacing)
        }
    }

    private fun updateRepeatingRequest() {
        val builder = previewRequestBuilder ?: return
        val session = captureSession ?: return
        applyZoomAndFlash(builder)
        try {
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (_: Exception) {
            restartPipelineDelayed(RESTART_DELAY_MS)
        }
    }

    private fun restartPipelineDelayed(delayMs: Long) {
        if (!running.get()) return
        if (!restartingPipeline.compareAndSet(false, true)) return
        cameraHandler?.postDelayed({
            try {
                if (!running.get()) return@postDelayed
                closeCamera()
                stopEncoder()
                csd0 = null
                csd1 = null
                startEncoder()
                openCamera(lensFacing)
            } catch (_: Exception) {
                if (running.get()) {
                    cameraHandler?.postDelayed({ restartPipelineDelayed(RESTART_DELAY_MS) }, RESTART_DELAY_MS)
                }
            } finally {
                restartingPipeline.set(false)
            }
        }, delayMs)
    }

    private fun applyZoomAndFlash(builder: CaptureRequest.Builder) {
        val characteristics = activeCharacteristics
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensor != null && zoomValue > 1f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, cropForZoom(sensor, zoomValue))
        }
        val flashAvailable = characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        builder.set(CaptureRequest.FLASH_MODE, if (flashEnabled && flashAvailable) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    private fun cropForZoom(sensor: Rect, zoom: Float): Rect {
        val clamped = zoom.coerceIn(1f, maxZoom())
        val cropWidth = (sensor.width() / clamped).toInt()
        val cropHeight = (sensor.height() / clamped).toInt()
        val left = sensor.left + (sensor.width() - cropWidth) / 2
        val top = sensor.top + (sensor.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun maxZoom(): Float {
        return activeCharacteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 4f
    }

    private fun videoWidth(): Int {
        return if (prefs.batterySaverEnabled) WIDTH_SAVER else WIDTH_STANDARD
    }

    private fun videoHeight(): Int {
        return if (prefs.batterySaverEnabled) HEIGHT_SAVER else HEIGHT_STANDARD
    }

    private fun videoFps(): Int {
        return if (prefs.batterySaverEnabled) FPS_SAVER else FPS_STANDARD
    }

    private fun videoBitrate(): Int {
        return if (prefs.batterySaverEnabled) BITRATE_SAVER else BITRATE_STANDARD
    }

    private fun schedulePcAnnouncements() {
        val handler = cameraHandler ?: return
        for (delay in ANNOUNCE_DELAYS_MS) {
            handler.postDelayed({ announceToPcReceiver() }, delay)
        }
    }

    private fun announceToPcReceiver() {
        val hosts = linkedSetOf<String>()
        val manualHost = prefs.manualPcHost.trim()
        if (manualHost.isNotBlank()) {
            hosts.add(manualHost)
        }
        prefs.lastPcEndpoint?.host?.trim()?.takeIf { it.isNotBlank() }?.let { hosts.add(it) }
        if (hosts.isEmpty()) return

        Thread {
            for (host in hosts) {
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        socket.connect(InetSocketAddress(host, PC_STREAM_PORT), ANNOUNCE_TIMEOUT_MS)
                        Thread.sleep(250)
                    }
                    return@Thread
                } catch (_: Exception) {
                }
            }
        }.start()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification("Camera streaming active", "Streaming camera on port $CAMERA_PORT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            try {
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            }
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
        val channel = NotificationChannel(CHANNEL_ID, "Camera streaming", NotificationManager.IMPORTANCE_LOW)
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
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun MediaFormat.getOptionalByteArray(key: String): ByteArray? {
        return try {
            val buffer = getByteBuffer(key)
            if (buffer == null) null else AvcUtils.byteBufferToArray(buffer)
        } catch (_: Exception) {
            null
        }
    }

    class CameraWebSocketServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        private val clients = Collections.synchronizedSet(mutableSetOf<WebSocket>())

        override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
            clients.add(conn)
        }

        override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
            clients.remove(conn)
        }

        override fun onMessage(conn: WebSocket, message: String) {
            // Camera video stream is one-way; controls use port 8083.
        }

        override fun onError(conn: WebSocket?, ex: Exception) {
            if (conn != null) clients.remove(conn)
        }

        override fun onStart() {
            connectionLostTimeout = 3
        }

        fun broadcastBinary(bytes: ByteArray, dropIfBuffered: Boolean = false) {
            if (bytes.isEmpty()) return
            synchronized(clients) {
                clients.removeAll { !it.isOpen }
                for (client in clients) {
                    try {
                        if (dropIfBuffered && client.hasBufferedData()) {
                            continue
                        }
                        client.send(bytes)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_START = "com.example.screenstreamer.camera.START"
        const val ACTION_STOP = "com.example.screenstreamer.camera.STOP"
        const val ACTION_SWITCH_FRONT = "com.example.screenstreamer.camera.SWITCH_FRONT"
        const val ACTION_SWITCH_BACK = "com.example.screenstreamer.camera.SWITCH_BACK"
        const val CAMERA_PORT = 8082
        const val CONTROL_PORT = 8083
        private const val PC_STREAM_PORT = 45892
        private const val ANNOUNCE_TIMEOUT_MS = 900
        private val ANNOUNCE_DELAYS_MS = longArrayOf(250L, 2000L, 5000L)
        private const val CHANNEL_ID = "screen_stream_camera"
        private const val NOTIFICATION_ID = 82
        private const val MIME_TYPE = "video/avc"
        private const val WIDTH_STANDARD = 1280
        private const val HEIGHT_STANDARD = 720
        private const val FPS_STANDARD = 20
        private const val BITRATE_STANDARD = 1_500_000
        private const val WIDTH_SAVER = 1280
        private const val HEIGHT_SAVER = 720
        private const val FPS_SAVER = 15
        private const val BITRATE_SAVER = 800_000
        private const val RESTART_DELAY_MS = 1200L
    }
}
