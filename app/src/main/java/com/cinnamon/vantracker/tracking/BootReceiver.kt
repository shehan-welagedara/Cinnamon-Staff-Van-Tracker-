package com.cinnamon.vantracker.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cinnamon.vantracker.data.Backend

/** Restarts the van guard after a reboot, an app update, or when the van's Bluetooth connects. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DevicePrefs.isDriverPhone(context)) return
        if (!Backend.init(context) || Backend.auth.currentUser == null) return
        TrackingService.start(context)
    }
}

/** Remembers on this phone whether it is the van (driver) phone. */
object DevicePrefs {
    private const val PREFS = "device"

    fun isDriverPhone(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("driverPhone", false)

    fun setDriverPhone(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("driverPhone", value).apply()
    }

    fun getLong(ctx: Context, key: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(key, v).apply()
    }
}
