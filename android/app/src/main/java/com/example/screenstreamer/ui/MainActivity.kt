package com.example.screenstreamer.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.example.screenstreamer.model.ConnectionMode
import com.example.screenstreamer.services.AudioCaptureService
import com.example.screenstreamer.services.CameraStreamService
import com.example.screenstreamer.services.ScreenRecordService
import com.example.screenstreamer.utils.AppPreferences

class MainActivity : Activity() {
    private lateinit var prefs: AppPreferences
    private lateinit var statusText: TextView
    private lateinit var lastFileText: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var audioSwitch: Switch
    private lateinit var batterySaverSwitch: Switch
    private lateinit var pcIpInput: EditText
    private lateinit var cameraSwitch: Switch
    private val handler = Handler(Looper.getMainLooper())

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPreferences(this)
        requestRuntimePermissions()
        setContentView(createContentView())
        refreshStatus()
        if (!prefs.audioEnabled) {
            handler.postDelayed({ stopAudioService() }, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
    }

    @Deprecated("MediaProjection still uses an activity result intent on platform Activity.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SCREEN_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            prefs.statusText = "Screen capture permission denied"
            refreshStatus()
            return
        }

        val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun createContentView(): View {
        val root = ScrollView(this).apply {
            setBackgroundColor(COLOR_APP_BG)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 36, 32, 36)
        }
        root.addView(content)

        content.addView(TextView(this).apply {
            text = "Screen Stream Recorder"
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
        })

        statusText = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF0F766E.toInt())
            setPadding(22, 18, 22, 18)
            background = roundedBg(0xFFE0F2FE.toInt(), 22f, 0xFF7DD3FC.toInt())
        }
        content.addView(statusText)

        lastFileText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 12, 0, 18)
        }
        content.addView(lastFileText)

        val connectionPanel = panel()
        connectionPanel.addView(sectionLabel("Connection"))
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            gravity = Gravity.START
            addView(modeButton(ID_MODE_AUTO, "Auto"))
            addView(modeButton(ID_MODE_WIFI, "WiFi"))
            addView(modeButton(ID_MODE_HOTSPOT, "Hotspot"))
            check(modeIdFor(prefs.connectionMode))
            setOnCheckedChangeListener { _, checkedId ->
                prefs.connectionMode = modeForId(checkedId)
            }
        }
        connectionPanel.addView(modeGroup)

        connectionPanel.addView(sectionLabel("Manual PC IP"))
        pcIpInput = EditText(this).apply {
            setSingleLine(true)
            hint = "10.46.16.13"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.manualPcHost)
        }
        connectionPanel.addView(pcIpInput)
        connectionPanel.addView(secondaryButton("Use PC IP") {
            useManualPcIp()
        })
        content.addView(connectionPanel)

        val audioPanel = panel()
        audioPanel.addView(sectionLabel("Audio"))
        audioSwitch = Switch(this).apply {
            text = "Audio capture"
            isChecked = prefs.audioEnabled
            isEnabled = true
            setPadding(0, 4, 0, 0)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                prefs.audioEnabled = checked
                if (checked) {
                    startAudioService()
                } else {
                    stopAudioService()
                }
            }
        }
        audioPanel.addView(audioSwitch)
        audioPanel.addView(secondaryButton("Restart Audio") {
            prefs.audioEnabled = true
            if (!audioSwitch.isChecked) {
                audioSwitch.isChecked = true
            }
            startAudioService()
        })
        content.addView(audioPanel)

        val settingsPanel = panel()
        settingsPanel.addView(sectionLabel("Quality"))
        batterySaverSwitch = Switch(this).apply {
            text = "Battery saver mode"
            isChecked = prefs.batterySaverEnabled
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                prefs.batterySaverEnabled = checked
                prefs.statusText = if (checked) {
                    "Battery saver on. Restart streams to apply."
                } else {
                    "High quality mode. Restart streams to apply."
                }
                refreshStatus()
            }
        }
        settingsPanel.addView(batterySaverSwitch)
        content.addView(settingsPanel)

        val cameraPanel = panel()
        cameraPanel.addView(sectionLabel("Camera"))
        cameraSwitch = Switch(this).apply {
            text = "Start Camera"
            isChecked = false
            setPadding(0, 8, 0, 8)
            setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
                if (checked) {
                    startCameraService()
                } else {
                    stopCameraService()
                }
            }
        }
        cameraPanel.addView(cameraSwitch)
        cameraPanel.addView(secondaryButton("Back camera") {
            sendCameraAction(CameraStreamService.ACTION_SWITCH_BACK)
        })
        cameraPanel.addView(secondaryButton("Front camera") {
            sendCameraAction(CameraStreamService.ACTION_SWITCH_FRONT)
        })
        content.addView(cameraPanel)

        val screenPanel = panel()
        screenPanel.addView(sectionLabel("Screen recording"))
        screenPanel.addView(primaryButton("Start recording") {
            startCapturePermissionFlow()
        })
        screenPanel.addView(dangerButton("Stop screen") {
            stopScreenAndStayHere()
        })
        screenPanel.addView(secondaryButton("Find PC receiver") {
            sendServiceAction(ScreenRecordService.ACTION_DISCOVER_PC)
        })
        content.addView(screenPanel)

        content.addView(TextView(this).apply {
            text = "Screen, camera, and audio are independent. Use each control only when you need it."
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, 24, 0, 0)
        })

        return root
    }

    private fun sectionLabel(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_INK)
            setPadding(0, 2, 0, 8)
        }
    }

    private fun modeButton(idValue: Int, label: String): RadioButton {
        return RadioButton(this).apply {
            id = idValue
            text = label
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedBg(0xFF2563EB.toInt(), 18f)
            layoutParams = buttonLayoutParams()
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(COLOR_INK)
            background = roundedBg(0xFFEFF6FF.toInt(), 18f, 0xFFBFDBFE.toInt())
            layoutParams = buttonLayoutParams()
            setOnClickListener { onClick() }
        }
    }

    private fun dangerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            setAllCaps(false)
            setTextColor(0xFFFFFFFF.toInt())
            background = roundedBg(0xFFEF4444.toInt(), 18f)
            layoutParams = buttonLayoutParams()
            setOnClickListener { onClick() }
        }
    }

    private fun panel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            background = roundedBg(0xFFFFFFFF.toInt(), 24f, 0xFFE5E7EB.toInt())
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 12, 0, 0)
            }
        }
    }

    private fun roundedBg(color: Int, radius: Float, strokeColor: Int? = null): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeColor != null) {
                setStroke(2, strokeColor)
            }
        }
    }

    private fun buttonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 8, 0, 0)
        }
    }

    private fun startCapturePermissionFlow() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    private fun sendServiceAction(actionValue: String) {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = actionValue
        }
        startService(intent)
    }

    private fun stopScreenAndStayHere() {
        prefs.statusText = "Stopping screen recording..."
        refreshStatus()
        sendServiceAction(ScreenRecordService.ACTION_STOP)
        handler.postDelayed({ keepAppInFront() }, 250)
        handler.postDelayed({ keepAppInFront() }, 900)
    }

    private fun keepAppInFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
            refreshStatus()
        } catch (_: Exception) {
        }
    }

    private fun startAudioService() {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
            }
            startForegroundAware(intent)
            prefs.statusText = "Audio starting"
            refreshStatus()
        } catch (exception: Exception) {
            prefs.statusText = "Audio could not start: ${exception.message ?: "permission issue"}"
            refreshStatus()
        }
    }

    private fun stopAudioService() {
        try {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_STOP
            }
            startService(intent)
        } catch (_: Exception) {
        }
    }

    private fun startCameraService() {
        requestRuntimePermissions()
        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_START
        }
        startForegroundAware(intent)
    }

    private fun stopCameraService() {
        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_STOP
        }
        startService(intent)
    }

    private fun sendCameraAction(actionValue: String) {
        val intent = Intent(this, CameraStreamService::class.java).apply {
            action = actionValue
        }
        startService(intent)
    }

    private fun startForegroundAware(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun useManualPcIp() {
        val host = pcIpInput.text.toString().trim()
        if (host.isBlank()) {
            prefs.statusText = "Enter PC IP address"
            refreshStatus()
            return
        }
        prefs.manualPcHost = host
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_USE_MANUAL_PC
            putExtra(ScreenRecordService.EXTRA_PC_HOST, host)
        }
        startService(intent)
        prefs.statusText = "Checking PC IP: $host"
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = "Status: ${prefs.statusText}"
        val path = prefs.lastRecordingPath
        lastFileText.text = if (path.isBlank()) "Last recording: none" else "Last recording: $path"
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissions.add(Manifest.permission.CAMERA)
        requestPermissions(permissions.toTypedArray(), REQUEST_PERMISSIONS)
    }

    private fun modeForId(idValue: Int): ConnectionMode {
        return when (idValue) {
            ID_MODE_WIFI -> ConnectionMode.WIFI
            ID_MODE_HOTSPOT -> ConnectionMode.HOTSPOT
            else -> ConnectionMode.AUTO
        }
    }

    private fun modeIdFor(mode: ConnectionMode): Int {
        return when (mode) {
            ConnectionMode.WIFI -> ID_MODE_WIFI
            ConnectionMode.HOTSPOT -> ID_MODE_HOTSPOT
            else -> ID_MODE_AUTO
        }
    }

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 501
        private const val REQUEST_PERMISSIONS = 502
        private const val ID_MODE_AUTO = 1001
        private const val ID_MODE_WIFI = 1002
        private const val ID_MODE_HOTSPOT = 1003
        private const val COLOR_APP_BG = 0xFFF8FAFC.toInt()
        private const val COLOR_INK = 0xFF111827.toInt()
    }
}
