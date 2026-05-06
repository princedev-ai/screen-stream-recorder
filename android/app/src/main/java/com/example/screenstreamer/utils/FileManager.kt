package com.example.screenstreamer.utils

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileManager(private val context: Context) {
    private val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun nextRecordingFile(): File {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "ScreenStream")
        if (!root.exists()) {
            root.mkdirs()
        }
        return File(root, "screen_${formatter.format(Date())}.mp4")
    }
}

