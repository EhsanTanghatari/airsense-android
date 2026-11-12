package com.airsense.monitor.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.airsense.monitor.R
import com.airsense.monitor.data.AppSettings
import com.airsense.monitor.sensor.SensorSnapshot
import com.airsense.monitor.ui.MainActivity
import java.util.Locale

object Notifications {
    const val CH_MONITOR = "monitor"
    const val CH_ALERTS = "alerts"
    const val ID_FOREGROUND = 1001

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MONITOR, "Sensor connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ongoing notification while the sensor is connected or logging"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Air quality alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown when a reading crosses one of your thresholds"
                enableVibration(true)
            }
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun foreground(
        context: Context,
        title: String,
        snap: SensorSnapshot?,
        logging: Boolean,
        settings: AppSettings,
    ): android.app.Notification {
        val stop = PendingIntent.getService(
            context, 1,
            Intent(context, SensorService::class.java).setAction(SensorService.ACTION_SHUTDOWN),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = snap?.let { summary(it) } ?: "Waiting for data…"
        val sub = if (logging) "Logging every ${formatInterval(settings.logIntervalSec)}" else "Live view"
        return NotificationCompat.Builder(context, CH_MONITOR)
            .setSmallIcon(R.drawable.ic_stat_air)
            .setContentTitle(title)
            .setContentText(body)
            .setSubText(sub)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, if (logging) "Stop logging & disconnect" else "Disconnect", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun summary(s: SensorSnapshot): String = buildList {
        s.pm25?.let { add("PM2.5 ${it.fmt0()}") }
        s.pm10?.let { add("PM10 ${it.fmt0()}") }
        s.temperature?.let { add("${it.fmt1()}°C") }
        s.humidity?.let { add("${it.fmt0()}%") }
    }.joinToString(" · ")

    fun alert(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(context, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_air)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    fun formatInterval(sec: Int): String = when {
        sec < 60 -> "$sec s"
        sec % 3600 == 0 -> "${sec / 3600} h"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "${sec / 60} min ${sec % 60} s"
    }

    private fun Float.fmt0() = String.format(Locale.US, "%.0f", this)
    private fun Float.fmt1() = String.format(Locale.US, "%.1f", this)
}
