package com.example.screenstreamer.utils

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import com.example.screenstreamer.R
import com.example.screenstreamer.services.ScreenRecordService
import com.example.screenstreamer.ui.MainActivity

object NotificationHelper {
    const val CHANNEL_ID = "screen_stream_recorder"
    const val NOTIFICATION_ID = 42

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Screen recorder",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent controls for screen recording and streaming"
            setShowBadge(false)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, title: String, message: String, recording: Boolean): Notification {
        val openIntent = Intent(context, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            context,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val stopIntent = Intent(context, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            context,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag()
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentIntent(openPendingIntent)
            .setOngoing(recording)
            .setOnlyAlertOnce(true)
            .setColor(context.getColorCompat(R.color.notification_accent))

        if (recording) {
            builder
                .setUsesChronometer(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
        }

        return builder.build()
    }

    fun startForeground(service: Service, notification: Notification, @Suppress("UNUSED_PARAMETER") audioEnabled: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            service.startForeground(NOTIFICATION_ID, notification, type)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun startWaitingForeground(service: Service, message: String) {
        val notification = build(service, "Screen recorder armed", message, recording = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun Context.getColorCompat(id: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getColor(id)
        } else {
            @Suppress("DEPRECATION")
            resources.getColor(id)
        }
    }

    private fun immutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }
}
