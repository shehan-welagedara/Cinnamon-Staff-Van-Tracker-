package com.cinnamon.vantracker.tracking

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.cinnamon.vantracker.R
import com.cinnamon.vantracker.data.AppSettings
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.TrackPoint
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.notify.Notifier
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Always-on service for the dedicated van phone.
 *
 *  GUARD        – no trip running. Watches for movement; the van charger (ignition) or the
 *                 van's Bluetooth connecting raises GPS precision. Movement without a trip
 *                 automatically opens an UNAUTHORIZED recording and asks the driver why.
 *  TRIP         – an approved/scheduled trip is being recorded.
 *  UNAUTHORIZED – recording a movement nobody approved; ends by itself once the van is
 *                 parked again.
 */
class TrackingService : LifecycleService() {

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIF_ID = 1001
        private const val NOTIF_UNAUTH = 1002

        const val ACTION_GUARD = "guard"
        const val ACTION_START_TRIP = "start_trip"
        const val ACTION_END_TRIP = "end_trip"
        const val ACTION_STOP = "stop"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_REQUEST_IDS = "request_ids"

        fun start(ctx: Context, action: String = ACTION_GUARD, configure: Intent.() -> Unit = {}) {
            val i = Intent(ctx, TrackingService::class.java).setAction(action).apply(configure)
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .onFailure { Log.w(TAG, "cannot start service: ${it.message}") }
        }

        fun startTrip(ctx: Context, tripId: String, title: String, requestIds: List<String>) =
            start(ctx, ACTION_START_TRIP) {
                putExtra(EXTRA_TRIP_ID, tripId); putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_REQUEST_IDS, requestIds.toTypedArray())
            }

        fun endTrip(ctx: Context) = start(ctx, ACTION_END_TRIP)
        fun stop(ctx: Context) = start(ctx, ACTION_STOP)
    }

    private lateinit var fused: FusedLocationProviderClient
    private var state = TrackingState()
    private var settings = AppSettings()
    private var driverName = ""
    private val points = mutableListOf<TrackPoint>()
    private var charging = false
    private var btConnected = false
    private var movementHits = 0
    private var currentPriority = -1
    private var currentInterval = -1L
    private var lastLiveWrite = 0L
    private var lastFlush = 0L
    private var lastDeadlineCheck = 0L
    private var lastLoc: Location? = null
    private val seenReadyTrips = mutableSetOf<String>()
    private var readyTripsPrimed = false

    private val triggerActive get() = (settings.usePowerTrigger && charging) || btConnected

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        startInForeground()
        if (!Backend.init(this) || Backend.auth.currentUser == null) {
            stopSelf(); return
        }
        fused = LocationServices.getFusedLocationProviderClient(this)
        state = TrackingState.load(this)
        if (state.mode != TrackMode.GUARD && state.tripId.isNotBlank()) {
            // Resuming after a restart: reload what was already uploaded so the map is complete.
            lifecycleScope.launch {
                val saved = Repo.trackPoints(state.tripId)
                points.clear(); points.addAll(saved + state.pending)
                publish()
            }
        }
        readInitialTriggers()
        registerTriggers()
        observeBackend()
        startTicker()
        applyLocationRequest()
        publish()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!Backend.initialized) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_START_TRIP -> beginTrip(
                intent.getStringExtra(EXTRA_TRIP_ID) ?: return START_STICKY,
                intent.getStringExtra(EXTRA_TITLE) ?: "",
                intent.getStringArrayExtra(EXTRA_REQUEST_IDS)?.toList().orEmpty(),
            )
            ACTION_END_TRIP -> finishRecording(manual = true)
            ACTION_STOP -> {
                if (state.mode != TrackMode.GUARD) finishRecording(manual = true)
                TrackingBus.live.value = LiveTrack()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(locationCallback) }
        runCatching { unregisterReceiver(triggerReceiver) }
        if (state.pending.isNotEmpty()) flushPoints(force = true)
        TrackingBus.live.value = TrackingBus.live.value.copy(running = false)
        super.onDestroy()
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        runCatching { ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type) }
            .onFailure { Log.w(TAG, "startForeground failed: ${it.message}"); stopSelf() }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, Notifier.CH_TRACKING)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(Notifier.openAppIntent(this))
        .setContentTitle(
            when (state.mode) {
                TrackMode.GUARD -> "Van guard active"
                TrackMode.TRIP -> "Trip: ${state.tripTitle}"
                TrackMode.UNAUTHORIZED -> "Unapproved movement – recording"
            }
        )
        .setContentText(
            when (state.mode) {
                TrackMode.GUARD -> if (triggerActive) "Ignition/Bluetooth on – start an approved trip before driving" else "Watching for movement"
                else -> "${TripCalc.km(state.distanceM / 1000)} · ${TripCalc.durationMs(System.currentTimeMillis() - state.startTime)}"
            }
        )
        .build()

    private fun refreshNotification() {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        }
    }

    // ------------------------------------------------------------------ backend

    private fun observeBackend() {
        val uid = Backend.auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            Repo.settingsFlow().collect {
                settings = it
                if (state.anchorLat == null && it.hotelSet) {
                    state = state.copy(anchorLat = it.hotelLat, anchorLng = it.hotelLng); state.save(this@TrackingService)
                }
                applyLocationRequest()
            }
        }
        lifecycleScope.launch {
            Repo.profileFlow(uid).collect { p ->
                driverName = p?.name ?: ""
                if (p != null && (!p.isDriver || !p.active)) {
                    // Account is no longer a driver: stop guarding this phone.
                    stopSelf()
                }
            }
        }
        lifecycleScope.launch {
            // Instant notification to the driver when a trip is approved for the van.
            Repo.openTripsFlow().collect { list ->
                val ready = list.filter { it.status == TripStatus.READY }
                if (readyTripsPrimed) {
                    ready.filter { it.id !in seenReadyTrips }.forEach {
                        Notifier.show(
                            this@TrackingService, it.id.hashCode(), Notifier.CH_REQUESTS,
                            "New approved trip", "${it.title} at ${TripCalc.dateTime(it.plannedTime)}",
                        )
                    }
                }
                seenReadyTrips.clear(); seenReadyTrips.addAll(ready.map { it.id }); readyTripsPrimed = true
            }
        }
    }

    private fun startTicker() {
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                val now = System.currentTimeMillis()
                if (state.mode != TrackMode.GUARD) flushPoints(force = true)
                if (state.mode == TrackMode.GUARD && now - lastLiveWrite > 5 * 60_000) writeLive(force = true)
                if (now - lastDeadlineCheck > 30 * 60_000) {
                    lastDeadlineCheck = now
                    Repo.runReasonDeadlineCheck(settings)
                }
                refreshNotification()
            }
        }
    }

    // ------------------------------------------------------------------ triggers

    private val triggerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_POWER_CONNECTED -> charging = true
                Intent.ACTION_POWER_DISCONNECTED -> charging = false
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isVanDevice(i)) btConnected = i.action == BluetoothDevice.ACTION_ACL_CONNECTED
                }
            }
            if (triggerActive && state.mode == TrackMode.GUARD) {
                Notifier.show(
                    this@TrackingService, 1003, Notifier.CH_REQUESTS, "Van started",
                    "Start an approved or scheduled trip in the app before driving. Movement without a trip is reported to management.",
                )
            }
            applyLocationRequest(); publish(); refreshNotification()
        }
    }

    @SuppressLint("MissingPermission")
    private fun isVanDevice(i: Intent): Boolean {
        val wanted = settings.btDeviceName.trim()
        if (wanted.isEmpty()) return false
        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
            i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        val name = runCatching { device?.name }.getOrNull() ?: return false
        return name.contains(wanted, ignoreCase = true)
    }

    private fun readInitialTriggers() {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun registerTriggers() {
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(this, triggerReceiver, f, ContextCompat.RECEIVER_EXPORTED)
    }

    // ------------------------------------------------------------------ location

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { onLocation(it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun applyLocationRequest() {
        if (!::fused.isInitialized) return
        val (priority, interval) = when {
            state.mode != TrackMode.GUARD -> Priority.PRIORITY_HIGH_ACCURACY to 5_000L
            triggerActive -> Priority.PRIORITY_HIGH_ACCURACY to 10_000L
            else -> Priority.PRIORITY_HIGH_ACCURACY to 30_000L
        }
        if (priority == currentPriority && interval == currentInterval) return
        currentPriority = priority; currentInterval = interval
        val req = LocationRequest.Builder(priority, interval)
            .setMinUpdateIntervalMillis(interval / 2)
            .setWaitForAccurateLocation(false)
            .build()
        runCatching {
            fused.removeLocationUpdates(locationCallback)
            fused.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        }.onFailure { Log.w(TAG, "location updates: ${it.message}") }
    }

    private fun onLocation(loc: Location) {
        lastLoc = loc
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        when (state.mode) {
            TrackMode.GUARD -> guard(loc, speedKmh)
            else -> record(loc, speedKmh)
        }
        writeLive(force = false)
        publish()
    }

    private fun guard(loc: Location, speedKmh: Double) {
        if (loc.accuracy > max(settings.minGpsAccuracyM, 50.0)) return
        if (state.anchorLat == null) {
            state = state.copy(anchorLat = loc.latitude, anchorLng = loc.longitude); state.save(this); return
        }
        val fromAnchor = TripCalc.distanceM(state.anchorLat!!, state.anchorLng!!, loc.latitude, loc.longitude)
        val moving = speedKmh >= settings.movementSpeedKmh || fromAnchor > settings.geofenceRadiusM + loc.accuracy
        movementHits = if (moving) movementHits + 1 else 0
        if (movementHits >= 2) {
            movementHits = 0
            startUnauthorized(loc)
        }
    }

    private fun startUnauthorized(loc: Location) {
        val uid = Backend.auth.currentUser?.uid ?: return
        val now = System.currentTimeMillis()
        val id = Repo.createUnauthorizedTrip(uid, driverName, now)
        val anchor = if (state.anchorLat != null) TrackPoint(state.anchorLat!!, state.anchorLng!!, now, 0.0) else null
        state = TrackingState(
            mode = TrackMode.UNAUTHORIZED, tripId = id, tripTitle = "Unapproved movement", startTime = now,
            anchorLat = state.anchorLat, anchorLng = state.anchorLng, lastFixTime = now,
        )
        points.clear()
        // Seed the track with the parked position so the distance from the hotel is counted.
        if (anchor != null) {
            state = state.copy(lastLat = anchor.lat, lastLng = anchor.lng, pending = listOf(anchor)); points.add(anchor)
        }
        state.save(this)
        record(loc, if (loc.hasSpeed()) loc.speed * 3.6 else 0.0)
        Notifier.show(
            this, NOTIF_UNAUTH, Notifier.CH_ALERTS, "Van moving without an approved trip",
            "Tap to enter the reason. All reasons must be entered before ${settings.reasonDeadlineHour}:00 or management will be alerted.",
            open = "reasons",
        )
        applyLocationRequest(); refreshNotification()
    }

    private fun beginTrip(tripId: String, title: String, requestIds: List<String>) {
        if (state.mode == TrackMode.TRIP && state.tripId == tripId) return
        if (state.mode != TrackMode.GUARD) finishRecording(manual = true)
        val now = System.currentTimeMillis()
        state = TrackingState(
            mode = TrackMode.TRIP, tripId = tripId, tripTitle = title, requestIds = requestIds,
            startTime = now, lastFixTime = now, anchorLat = state.anchorLat, anchorLng = state.anchorLng,
        )
        points.clear()
        state.save(this)
        lastLoc?.let { if (System.currentTimeMillis() - it.time < 60_000) record(it, 0.0) }
        applyLocationRequest(); publish(); refreshNotification(); writeLive(force = true)
    }

    private fun record(loc: Location, speedKmh: Double) {
        val now = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        val dt = (now - state.lastFixTime).coerceIn(0, 5 * 60_000)
        var s = state.copy(lastFixTime = now)
        // Time on the road: moving vs. stopped (traffic, bank queue, loading laundry...).
        s = if (speedKmh >= 5) s.copy(movingMs = s.movingMs + dt) else s.copy(stoppedMs = s.stoppedMs + dt)

        if (loc.accuracy <= settings.minGpsAccuracyM) {
            val point = TrackPoint(loc.latitude, loc.longitude, now, TripCalc.round(speedKmh, 1))
            if (s.lastLat == null) {
                s = s.copy(lastLat = point.lat, lastLng = point.lng, pending = s.pending + point)
                points.add(point)
            } else {
                val d = TripCalc.distanceM(s.lastLat!!, s.lastLng!!, point.lat, point.lng)
                // Ignore GPS jitter while standing still.
                if (d >= max(8.0, loc.accuracy * 0.6)) {
                    s = s.copy(
                        distanceM = s.distanceM + d, lastLat = point.lat, lastLng = point.lng,
                        pending = s.pending + point, maxSpeedKmh = max(s.maxSpeedKmh, speedKmh),
                    )
                    points.add(point)
                }
            }
        }

        s = if (speedKmh < 3) s.copy(stationarySince = if (s.stationarySince == 0L) now else s.stationarySince)
        else s.copy(stationarySince = 0)
        state = s
        state.save(this)
        if (state.pending.size >= 30) flushPoints(force = true)

        if (state.mode == TrackMode.UNAUTHORIZED) checkUnauthorizedEnd(loc)
    }

    /** An unapproved movement ends once the van has been parked for a while. */
    private fun checkUnauthorizedEnd(loc: Location) {
        if (state.stationarySince == 0L) return
        val parkedMs = System.currentTimeMillis() - state.stationarySince
        val limitMs = settings.autoEndUnauthorizedMin * 60_000L
        val atHotel = settings.hotelSet &&
            TripCalc.distanceM(settings.hotelLat, settings.hotelLng, loc.latitude, loc.longitude) <= settings.geofenceRadiusM
        val ready = when {
            atHotel -> parkedMs >= limitMs
            !triggerActive -> parkedMs >= limitMs * 2
            else -> parkedMs >= limitMs * 4
        }
        if (ready) finishRecording(manual = false)
    }

    private fun flushPoints(force: Boolean) {
        if (state.pending.isEmpty() || state.tripId.isBlank()) return
        if (!force && System.currentTimeMillis() - lastFlush < 60_000) return
        Repo.appendTrack(state.tripId, state.chunkSeq, state.pending)
        state = state.copy(chunkSeq = state.chunkSeq + 1, pending = emptyList())
        state.save(this)
        lastFlush = System.currentTimeMillis()
    }

    /** Ends the current trip/unapproved movement and writes the summary with fuel and cost. */
    private fun finishRecording(manual: Boolean) {
        if (state.mode == TrackMode.GUARD) return
        flushPoints(force = true)
        val end = System.currentTimeMillis()
        val gpsKm = state.distanceM / 1000.0
        val (km, liters, cost) = TripCalc.fuel(gpsKm, settings)
        val summary = mapOf(
            "endTime" to end,
            "gpsDistanceKm" to TripCalc.round(gpsKm, 3),
            "distanceKm" to km,
            "durationMin" to TripCalc.round((end - state.startTime) / 60000.0, 1),
            "movingMin" to TripCalc.round(state.movingMs / 60000.0, 1),
            "stoppedMin" to TripCalc.round(state.stoppedMs / 60000.0, 1),
            "maxSpeedKmh" to TripCalc.round(state.maxSpeedKmh, 1),
            "fuelLiters" to liters,
            "fuelCost" to cost,
            "fuelPrice" to settings.fuelPricePerLiter,
            "kmPerLiter" to settings.kmPerLiter,
            "endedBy" to if (manual) "Driver" else "Auto (van parked)",
        )
        Repo.completeTrip(state.tripId, summary, state.requestIds)
        // The van is now parked here; guard relative to this point (or the hotel if inside it).
        val atHotel = settings.hotelSet && state.lastLat != null &&
            TripCalc.distanceM(settings.hotelLat, settings.hotelLng, state.lastLat!!, state.lastLng!!) <= settings.geofenceRadiusM
        val anchorLat = if (atHotel || state.lastLat == null) (if (settings.hotelSet) settings.hotelLat else state.anchorLat) else state.lastLat
        val anchorLng = if (atHotel || state.lastLng == null) (if (settings.hotelSet) settings.hotelLng else state.anchorLng) else state.lastLng
        state = TrackingState(mode = TrackMode.GUARD, anchorLat = anchorLat, anchorLng = anchorLng)
        state.save(this)
        points.clear()
        movementHits = 0
        applyLocationRequest(); publish(); refreshNotification(); writeLive(force = true)
    }

    // ------------------------------------------------------------------ live status

    private fun writeLive(force: Boolean) {
        val now = System.currentTimeMillis()
        val interval = if (state.mode == TrackMode.GUARD) 5 * 60_000 else 20_000
        if (!force && now - lastLiveWrite < interval) return
        val loc = lastLoc ?: return
        lastLiveWrite = now
        val bm = getSystemService(BatteryManager::class.java)
        Repo.updateLive(
            mapOf(
                "lat" to loc.latitude, "lng" to loc.longitude,
                "speedKmh" to TripCalc.round(if (loc.hasSpeed()) loc.speed * 3.6 else 0.0, 1),
                "updatedAt" to now, "mode" to state.mode.name, "tripId" to state.tripId,
                "tripTitle" to state.tripTitle, "driverName" to driverName,
                "battery" to (bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1),
                "charging" to charging,
            )
        )
    }

    private fun publish() {
        val loc = lastLoc
        TrackingBus.live.value = LiveTrack(
            running = true, state = state, points = points.toList(),
            lastLat = loc?.latitude, lastLng = loc?.longitude, lastAccuracy = loc?.accuracy ?: 0f,
            lastFix = loc?.time ?: 0, speedKmh = if (loc?.hasSpeed() == true) loc.speed * 3.6 else 0.0,
            charging = charging, btConnected = btConnected, triggerActive = triggerActive,
        )
    }
}
