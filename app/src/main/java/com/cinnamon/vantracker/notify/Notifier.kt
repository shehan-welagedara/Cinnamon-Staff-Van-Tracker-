package com.cinnamon.vantracker.notify

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
import com.cinnamon.vantracker.MainActivity
import com.cinnamon.vantracker.R

object Notifier {
    const val CH_TRACKING = "tracking"
    const val CH_ALERTS = "alerts"
    // New id: Android never lets an app raise the importance of an existing channel.
    const val CH_REQUESTS = "trip_requests"

    const val EXTRA_OPEN = "open"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        runCatching { nm.deleteNotificationChannel("requests") } // old, lower-priority channel
        nm.createNotificationChannel(NotificationChannel(CH_TRACKING, "Van tracking", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, "Alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_REQUESTS, "Trip requests", NotificationManager.IMPORTANCE_HIGH))
    }

    fun openAppIntent(ctx: Context, open: String = "", requestCode: Int = 0): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN, open)
        return PendingIntent.getActivity(ctx, requestCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * Shows a notification once per [key] – the push message and the background check can
     * both report the same event, the second one is ignored.
     */
    fun showOnce(ctx: Context, key: String, channel: String, title: String, text: String, open: String = "") {
        val prefs = ctx.getSharedPreferences("shown", Context.MODE_PRIVATE)
        val seen = prefs.getString("keys", "").orEmpty().split('\n').filter { it.isNotBlank() }
        if (key in seen) return
        prefs.edit().putString("keys", (seen.takeLast(300) + key).joinToString("\n")).apply()
        show(ctx, key.hashCode(), channel, title, text, open)
    }

    fun canNotify(ctx: Context) = NotificationManagerCompat.from(ctx).areNotificationsEnabled() && (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun show(ctx: Context, id: Int, channel: String, title: String, text: String, open: String = "") {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(if (channel == CH_ALERTS) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(openAppIntent(ctx, open, id))
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
    }
}
