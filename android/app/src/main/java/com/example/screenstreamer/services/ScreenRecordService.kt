package com.example.screenstreamer.services

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.example.screenstreamer.model.PcEndpoint
import com.example.screenstreamer.model.RecordingConfig
import com.example.screenstreamer.network.PcDiscoveryManager
import com.example.screenstreamer.recording.MediaCodecScreenRecorder
import com.example.screenstreamer.streaming.VideoPacketStreamer
import com.example.screenstreamer.utils.AppPreferences
import com.example.screenstreamer.utils.FileManager
import com.example.screenstreamer.utils.NotificationHelper
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.roundToInt

class ScreenRecordService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var prefs: AppPreferences
    private lateinit var fileManager: FileManager
    private lateinit var discoveryManager: PcDiscoveryManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var recorder: MediaCodecScreenRecorder? = null
    private var streamer: VideoPacketStreamer? = null
    private var activeConfig: RecordingConfig? = null
    private var stoppingProjection = false
    private val screenStopInProgress = AtomicBoolean(false)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                Intent.ACTION_SCREEN_ON -> handleScreenOn()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        fileManager = FileManager(this)
        discoveryManager = PcDiscoveryManager(this)
        NotificationHelper.ensureChannel(this)
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startFromPermission(intent)
            ACTION_STOP -> {
                stopScreenRecordingFromButton()
            }
            ACTION_DISCOVER_PC -> {
                NotificationHelper.startWaitingForeground(this, "Searching PC receiver...")
                discoverPc()
            }
            ACTION_USE_MANUAL_PC -> {
                NotificationHelper.startWaitingForeground(this, "Checking manual PC IP...")
                useManualPc(intent.getStringExtra(EXTRA_PC_HOST) ?: prefs.manualPcHost)
            }
            ACTION_ARM -> {
                prefs.statusText = "Armed. Open app once to grant screen capture permission."
                NotificationHelper.startWaitingForeground(this, prefs.statusText)
            }
            else -> {
                NotificationHelper.startWaitingForeground(this, "Waiting for screen capture permission")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        stopCapture(stopProjection = true)
        discoveryManager.close()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startFromPermission(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || resultData == null) {
            prefs.statusText = "Screen capture permission missing"
            NotificationHelper.startWaitingForeground(this, prefs.statusText)
            return
        }

        val config = createRecordingConfig()
        activeConfig = config
        val notification = NotificationHelper.build(
            this,
            "Screen recording active",
            "Saving MP4 and streaming when PC is available",
            recording = true
        )
        NotificationHelper.startForeground(this, notification, audioEnabled = config.audioEnabled)

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = projectionManager.getMediaProjection(resultCode, resultData)
        AudioCaptureService.setMediaProjection(projection)
        registerProjectionCallback()
        startCaptureUsingExistingProjection()
        if (prefs.audioEnabled) {
            mainHandler.postDelayed({ startAudioServiceSafely() }, 1200)
        }
    }

    private fun startCaptureUsingExistingProjection() {
        val currentProjection = projection
        val config = activeConfig ?: createRecordingConfig().also { activeConfig = it }
        if (currentProjection == null) {
            prefs.statusText = "Open app to grant screen capture permission"
            NotificationHelper.startWaitingForeground(this, prefs.statusText)
            return
        }

        stopRecorderOnly()
        val output = fileManager.nextRecordingFile()
        prefs.lastRecordingPath = output.absolutePath

        val packetStreamer = VideoPacketStreamer()
        streamer = packetStreamer
        prefs.lastPcEndpoint?.let { packetStreamer.setEndpoint(it) }

        recorder = MediaCodecScreenRecorder(
            context = this,
            projection = currentProjection,
            config = config,
            outputFile = output,
            streamer = packetStreamer
        ).also { it.start() }

        prefs.statusText = "Recording: ${output.name}"
        discoverPc()
    }

    private fun discoverPc() {
        val mode = prefs.connectionMode
        prefs.statusText = "Searching PC receiver..."
        discoveryManager.discover(mode) { endpoint ->
            mainHandler.post {
                handleEndpoint(endpoint)
            }
        }
    }

    private fun useManualPc(host: String) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) {
            prefs.statusText = "Enter PC IP address"
            return
        }
        prefs.manualPcHost = cleanHost
        Thread {
            val endpoint = PcEndpoint(
                host = cleanHost,
                port = PcDiscoveryManager.STREAM_PORT,
                name = "Manual PC $cleanHost",
                mode = prefs.connectionMode
            )
            val reachable = canReachEndpoint(endpoint)
            mainHandler.post {
                if (reachable) {
                    handleEndpoint(endpoint)
                } else {
                    prefs.lastPcEndpoint = endpoint
                    streamer?.setEndpoint(endpoint)
                    prefs.statusText = "Manual PC saved, but not reachable yet: $cleanHost"
                }
            }
        }
    }

    private fun canReachEndpoint(endpoint: PcEndpoint): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), MANUAL_CONNECT_TIMEOUT_MS)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun handleEndpoint(endpoint: PcEndpoint?) {
        if (endpoint == null) {
            prefs.statusText = if (recorder == null) {
                "PC not found. Recording can still run locally."
            } else {
                "Recording locally. PC stream waiting."
            }
            return
        }
        prefs.lastPcEndpoint = endpoint
        streamer?.setEndpoint(endpoint)
        recorder?.requestSyncFrame()
        prefs.statusText = "PC connected: ${endpoint.name} (${endpoint.mode.wireValue})"
    }

    private fun handleScreenOff() {
        val message = "Screen off detected. Recording kept running."
        prefs.statusText = message
        NotificationHelper.startWaitingForeground(this, message)
    }

    private fun handleScreenOn() {
        if (recorder != null) {
            prefs.statusText = "Recording continues."
            return
        }
        prefs.statusText = "Screen on. Preparing..."
        mainHandler.postDelayed({
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE && projection != null) {
                startCaptureUsingExistingProjection()
            } else {
                prefs.statusText = "Open app to grant screen capture permission"
                NotificationHelper.startWaitingForeground(this, prefs.statusText)
            }
        }, SCREEN_ON_START_DELAY_MS)
    }

    private fun stopCapture(stopProjection: Boolean) {
        stopRecorderOnly()
        if (stopProjection) {
            val current = projection
            projectionCallback?.let { callback ->
                try {
                    current?.unregisterCallback(callback)
                } catch (_: Exception) {
                }
            }
            projection = null
            projectionCallback = null
            AudioCaptureService.setMediaProjection(null)
            if (current != null && !stoppingProjection) {
                stoppingProjection = true
                try {
                    current.stop()
                } catch (_: Exception) {
                } finally {
                    stoppingProjection = false
                }
            }
        }
    }

    private fun stopScreenRecordingFromButton() {
        if (!screenStopInProgress.compareAndSet(false, true)) return
        prefs.statusText = "Stopping screen recording..."
        showStopStatusSafely(prefs.statusText)

        Thread {
            try {
                stopRecorderOnly()
                mainHandler.post {
                    prefs.statusText = "Screen recording stopped"
                    showStopStatusSafely("Screen recording stopped. Camera and audio stay independent.")
                }
            } catch (exception: Exception) {
                mainHandler.post {
                    prefs.statusText = "Screen stop failed: ${exception.message ?: "try again"}"
                    showStopStatusSafely(prefs.statusText)
                }
            } finally {
                screenStopInProgress.set(false)
            }
        }.start()
    }

    private fun showStopStatusSafely(message: String) {
        try {
            NotificationHelper.startWaitingForeground(this, message)
        } catch (_: Exception) {
        }
    }

    private fun stopRecorderOnly() {
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        recorder = null

        try {
            streamer?.close()
        } catch (_: Exception) {
        }
        streamer = null
    }

    private fun registerProjectionCallback() {
        val currentProjection = projection ?: return
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                if (!stoppingProjection) {
                    mainHandler.post {
                        stopCapture(stopProjection = false)
                        projection = null
                        AudioCaptureService.setMediaProjection(null)
                        prefs.statusText = "Screen capture permission ended"
                    }
                }
            }
        }
        projectionCallback = callback
        currentProjection.registerCallback(callback, mainHandler)
    }

    private fun createRecordingConfig(): RecordingConfig {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }

        val targetShortSide = if (prefs.batterySaverEnabled) TARGET_SHORT_SIDE_SAVER else TARGET_SHORT_SIDE_STANDARD
        val shortSide = min(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
        val scale = min(1f, targetShortSide.toFloat() / shortSide.toFloat())
        val width = makeEven((metrics.widthPixels * scale).roundToInt())
        val height = makeEven((metrics.heightPixels * scale).roundToInt())
        val fps = if (prefs.batterySaverEnabled) 15 else 24
        val bitrate = if (prefs.batterySaverEnabled) 1_000_000 else 2_000_000

        return RecordingConfig(
            width = width,
            height = height,
            dpi = metrics.densityDpi,
            fps = fps,
            bitrate = bitrate,
            audioEnabled = prefs.audioEnabled,
            connectionMode = prefs.connectionMode
        )
    }

    private fun makeEven(value: Int): Int {
        val even = value - (value % 2)
        return even.coerceAtLeast(2)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ScreenStreamRecorder:Monitor").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {
        }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun startAudioServiceSafely() {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (ex: Exception) {
            prefs.statusText = "Screen recording active. Audio failed: ${ex.message}"
        }
    }

    private fun stopAudioServiceSafely() {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(intent)
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "com.example.screenstreamer.action.START"
        const val ACTION_STOP = "com.example.screenstreamer.action.STOP"
        const val ACTION_DISCOVER_PC = "com.example.screenstreamer.action.DISCOVER_PC"
        const val ACTION_USE_MANUAL_PC = "com.example.screenstreamer.action.USE_MANUAL_PC"
        const val ACTION_ARM = "com.example.screenstreamer.action.ARM"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_PC_HOST = "extra_pc_host"
        private const val SCREEN_ON_START_DELAY_MS = 3000L
        private const val TARGET_SHORT_SIDE_STANDARD = 720
        private const val TARGET_SHORT_SIDE_SAVER = 540
        private const val MANUAL_CONNECT_TIMEOUT_MS = 1200
    }
}
