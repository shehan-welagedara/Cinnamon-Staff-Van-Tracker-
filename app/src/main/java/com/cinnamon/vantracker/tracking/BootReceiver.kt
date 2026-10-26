package com.cinnamon.vantracker.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cinnamon.vantracker.data.Backend

/**
 * Restarts tracking after a reboot or an app update – on a dedicated van phone always,
 * on a driver's own phone only while that driver is on duty (or mid-trip).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!DevicePrefs.isDriverPhone(context)) return
        val recording = TrackingState.load(context).mode != TrackMode.GUARD
        if (!DevicePrefs.isDedicated(context) && !DevicePrefs.isOnDuty(context) && !recording) return
        if (!Backend.init(context) || Backend.auth.currentUser == null) return
        TrackingService.start(context)
    }
}

/** Per-phone settings for driver phones. */
object DevicePrefs {
    private const val PREFS = "device"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Signed in as a driver on this phone. */
    fun isDriverPhone(ctx: Context) = p(ctx).getBoolean("driverPhone", false)
    fun setDriverPhone(ctx: Context, value: Boolean) { p(ctx).edit().putBoolean("driverPhone", value).apply() }

    /**
     * Dedicated vehicle phone (stays in one vehicle, always guarding) vs. the driver's own
     * phone (location recorded only during a ride).
     */
    fun isDedicated(ctx: Context) = p(ctx).getBoolean("dedicated", false)
    fun setDedicated(ctx: Context, value: Boolean) { p(ctx).edit().putBoolean("dedicated", value).apply() }

    /** Local copy of the driver's duty status, so tracking can resume after a reboot offline. */
    fun isOnDuty(ctx: Context) = p(ctx).getBoolean("onDuty", false)
    fun setOnDuty(ctx: Context, value: Boolean) { p(ctx).edit().putBoolean("onDuty", value).apply() }

    /** Vehicle a dedicated phone is installed in. */
    fun dedicatedVehicleId(ctx: Context) = p(ctx).getString("dedicatedVehicle", "") ?: ""
    fun setDedicatedVehicleId(ctx: Context, id: String) { p(ctx).edit().putString("dedicatedVehicle", id).apply() }

    /** Vehicle this driver used last – preselected when starting a ride. */
    fun lastVehicleId(ctx: Context) = p(ctx).getString("lastVehicle", "") ?: ""
    fun setLastVehicleId(ctx: Context, id: String) { p(ctx).edit().putString("lastVehicle", id).apply() }

    fun getLong(ctx: Context, key: String) = p(ctx).getLong(key, 0L)
    fun putLong(ctx: Context, key: String, v: Long) { p(ctx).edit().putLong(key, v).apply() }
}
