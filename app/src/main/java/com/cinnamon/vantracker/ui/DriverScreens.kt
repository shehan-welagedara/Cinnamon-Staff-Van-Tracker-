package com.cinnamon.vantracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cinnamon.vantracker.data.FuelLog
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Schedule
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.data.TripType
import com.cinnamon.vantracker.tracking.TrackMode
import com.cinnamon.vantracker.tracking.TrackingBus
import com.cinnamon.vantracker.tracking.TrackingService
import com.cinnamon.vantracker.tracking.TripCalc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cinnamon.vantracker.tracking.DevicePrefs

// ------------------------------------------------------------------ permissions

object DriverPermissions {
    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun location(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun background(ctx: Context) = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun battery(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: true

    fun allGranted(ctx: Context) = location(ctx) && background(ctx)
}

/** Re-evaluates permission state every time the screen resumes (user returns from Settings). */
@Composable
private fun rememberResumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return tick
}

@SuppressLint("BatteryLife")
@Composable
fun DriverSetupScreen(s: Session) {
    val ctx = LocalContext.current
    val tick = rememberResumeTick()
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val stamp = tick * 1000 + refresh
    var dedicated by remember { mutableStateOf(DevicePrefs.isDedicated(ctx)) }
    Scaffold(topBar = { AppBar("Driver phone setup", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text(
                "Grant everything below so your rides are recorded with the screen off. " +
                    "Your location is only recorded during a ride – never off duty or at home.",
                modifier = Modifier.padding(16.dp),
            )
            key(stamp) {
                PermissionRow("1. Precise location", DriverPermissions.location(ctx)) {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                PermissionRow("2. Location “Allow all the time”", DriverPermissions.background(ctx), enabled = DriverPermissions.location(ctx)) {
                    if (Build.VERSION.SDK_INT >= 29) launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                PermissionRow("3. Notifications (trip alerts)", DriverPermissions.notifications(ctx)) {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                PermissionRow("4. Ignore battery optimisation", DriverPermissions.battery(ctx)) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                SectionHeader("Phone type")
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Dedicated vehicle phone", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                            Text(
                                "Only for a spare phone that stays in one vehicle on its charger. It then watches for movement " +
                                    "all the time and reports any drive without an approved ride.",
                                color = TextSecondary, fontSize = 13.sp,
                            )
                        }
                        Switch(dedicated, { dedicated = it; DevicePrefs.setDedicated(ctx, it) })
                    }
                    if (dedicated) {
                        val vehicles = rememberVehicles(s)
                        var vid by remember { mutableStateOf(DevicePrefs.dedicatedVehicleId(ctx).ifBlank { vehicles.firstOrNull()?.id ?: "" }) }
                        Spacer(Modifier.height(8.dp))
                        Picker("Installed in", vehicles.firstOrNull { it.id == vid }?.label ?: "Choose vehicle", vehicles.map { it.label }) { l ->
                            vid = vehicles.first { it.label == l }.id; DevicePrefs.setDedicatedVehicleId(ctx, vid)
                        }
                        if (DevicePrefs.dedicatedVehicleId(ctx).isBlank() && vid.isNotBlank()) DevicePrefs.setDedicatedVehicleId(ctx, vid)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = {
                        if (DriverPermissions.allGranted(ctx) && (dedicated || s.me.onDuty)) TrackingService.start(ctx)
                        s.nav.back()
                    },
                ) { Text("Done") }
                SectionHeader("Appearance")
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { AppearancePicker() }
                Text(
                    "Tip: in the phone's settings also disable “Pause app activity if unused” for Van Tracker.",
                    color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, enabled: Boolean = true, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️", fontSize = 18.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(enabled = enabled, onClick = onGrant) { Text("Grant") }
    }
}

// ------------------------------------------------------------------ driver home

/**
 * Driver mode, Uber-style: go on duty → new requests alert every on-duty driver → the first
 * to accept gets the trip → "Start ride" when passengers are on board → "Finish ride".
 */
@Composable
fun DriverHome(s: Session) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val me = s.me
    val live by TrackingBus.live.collectAsState()
    val openTrips by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val today = remember { TripCalc.dayStart(System.currentTimeMillis()) }
    val todayTrips by remember { Repo.tripsSinceFlow(today) }.collectAsState(initial = emptyList())
    val needReasonAll by remember { Repo.needsReasonFlow() }.collectAsState(initial = emptyList())
    val needReason = needReasonAll.filter { it.driverUid == me.uid || it.driverUid.isBlank() }
    val online = rememberOnline()
    val tick = rememberResumeTick()
    val permsOk = remember(tick) { DriverPermissions.allGranted(ctx) }
    val dedicated = remember(tick) { DevicePrefs.isDedicated(ctx) }
    var confirmRelease by remember { mutableStateOf<Trip?>(null) }
    var menu by remember { mutableStateOf(false) }
    var accepting by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) } // text, isError

    val recording = live.state.mode != TrackMode.GUARD
    val vehicles = rememberVehicles(s)
    val busy = busyVehicles(openTrips.filter { it.driverUid != me.uid })
    val allBusy = vehicles.all { it.id in busy }
    var pickFor by remember { mutableStateOf<Any?>(null) } // Trip or Schedule waiting for a vehicle choice
    val myTrips = openTrips.filter { it.status == TripStatus.ASSIGNED && it.driverUid == me.uid }
    val newRequests = openTrips.filter { it.status == TripStatus.READY && it.driverUid.isBlank() }
    val others = openTrips.filter { it.driverUid.isNotBlank() && it.driverUid != me.uid && it.status != TripStatus.READY }
    val dayKey = Repo.dateKey()
    val knownIds = (openTrips + todayTrips).map { it.id }.toSet()
    val todaysSchedules = schedules.filter {
        it.active && TripCalc.isoDay(System.currentTimeMillis()) in it.days && Repo.scheduleTripId(it.id, dayKey) !in knownIds
    }
    val canStart = me.onDuty && permsOk && !recording && !allBusy

    fun setDuty(on: Boolean) {
        if (on && !permsOk) {
            message = "Grant the location permissions first." to true
            s.nav.go(Screen.DriverSetup); return
        }
        if (!on && recording) {
            message = "Finish the current trip before going off duty." to true; return
        }
        Repo.setDuty(me, on)
        DevicePrefs.setOnDuty(ctx, on)
        if (on) TrackingService.start(ctx) else if (!dedicated) TrackingService.stop(ctx)
        message = null
    }

    GlassScaffold(topBar = {
        AppBar("Hi, ${me.name.substringBefore(' ')}") {
            IconButton(onClick = { s.nav.go(Screen.DriverSetup) }) { Icon(Icons.Filled.Settings, "Setup") }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            item { UpdateBanner() }
            message?.let { (text, err) -> item { Banner(text, if (err) DangerRed else OkGreen) { message = null } } }
            if (!online) item { Banner("Offline – trips are still recorded and sync automatically. Accepting new trips needs internet.", WarnOrange) }
            if (!permsOk) item { Banner("Setup needed: tap to grant location permissions.", DangerRed) { s.nav.go(Screen.DriverSetup) } }
            if (live.state.mode == TrackMode.UNAUTHORIZED) item {
                Banner("⚠ The van is moving without an approved trip. Recording… Tap to open.", DangerRed) { s.nav.go(Screen.ActiveTrip) }
            }
            if (needReason.isNotEmpty()) item {
                Banner("${needReason.size} movement(s) need a reason – tap to enter before ${s.settings.reasonDeadlineHour}:00", WarnOrange) {
                    s.nav.go(Screen.Reasons)
                }
            }

            // ---- duty switch
            item {
                SectionCard(accent = if (me.onDuty) OkGreen else TextSecondary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (me.onDuty) "You're on duty" else "You're off duty",
                                style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                            Text(
                                if (me.onDuty) "You'll be alerted about new trips. Location is used only in the van or on a trip."
                                else "Go on duty to receive and accept trips. No location is recorded while off duty.",
                                color = TextSecondary, fontSize = 13.sp,
                            )
                        }
                        Switch(me.onDuty, { setDuty(it) })
                    }
                }
            }

            // ---- vehicles
            item {
                SectionCard(if (vehicles.size > 1) "Vehicles" else "Vehicle") {
                    vehicles.forEach { v ->
                        val mine = recording && live.state.vehicleId == v.id
                        InfoRow(
                            "${vehicleIcon(v.type)} ${v.label}",
                            when { mine -> "With you"; v.id in busy -> "Out with ${busy[v.id]}"; else -> "Available" },
                            when { mine -> OkGreen; v.id in busy -> WarnOrange; else -> OkGreen },
                        )
                    }
                    if (live.running && live.lastFix > 0 && recording) InfoRow("GPS", "±${live.lastAccuracy.toInt()} m · ${TripCalc.time(live.lastFix)}")
                    if (dedicated) Text("Dedicated vehicle phone – always watching for movement.", color = TextSecondary, fontSize = 13.sp)
                }
            }

            if (recording) item {
                SectionCard(if (live.state.mode == TrackMode.TRIP) "Ride in progress" else "Recording movement", onClick = { s.nav.go(Screen.ActiveTrip) },
                    accent = if (live.state.mode == TrackMode.TRIP) OkGreen else DangerRed) {
                    Text(live.state.tripTitle, fontWeight = FontWeight.Bold)
                    InfoRow("Distance", TripCalc.km(live.state.distanceM / 1000))
                    InfoRow("Started", TripCalc.time(live.state.startTime))
                    Button(onClick = { s.nav.go(Screen.ActiveTrip) }) { Text("Open ride") }
                }
            }

            // ---- my accepted trips
            item { SectionHeader("My trips") }
            if (myTrips.isEmpty()) item { Empty("No trips accepted yet.") }
            items(myTrips, key = { "m" + it.id }) { t ->
                TripCard(t, s) {
                    if (allBusy) Text("All vehicles are out right now. Start when one is back.", color = WarnOrange, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = canStart, onClick = { pickFor = t }) { Text("Start ride") }
                        OutlinedButton(onClick = { confirmRelease = t }) { Text("Release") }
                    }
                    TextButton(onClick = { s.nav.go(Screen.TripDetail(t.id)) }) { Text("Details") }
                }
            }

            // ---- new requests
            item { SectionHeader("New requests – first to accept takes it") }
            if (newRequests.isEmpty()) item { Empty("No open requests.") }
            items(newRequests, key = { "n" + it.id }) { t ->
                TripCard(t, s) {
                    if (!me.onDuty) Text("Go on duty to accept.", color = TextSecondary, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = me.onDuty && online && accepting.isEmpty(),
                            onClick = {
                                accepting = t.id
                                scope.launch {
                                    val err = Repo.acceptTrip(t.id, me)
                                    accepting = ""
                                    message = if (err == null) "Accepted – “${t.title}” is now in My trips." to false else err to true
                                }
                            },
                        ) { Text(if (accepting == t.id) "Accepting…" else "Accept") }
                        OutlinedButton(onClick = { s.nav.go(Screen.TripDetail(t.id)) }) { Text("Details") }
                    }
                }
            }

            // ---- scheduled runs
            item { SectionHeader("Today's scheduled runs (pre-approved)") }
            if (todaysSchedules.isEmpty()) item { Empty("No more scheduled runs today.") }
            items(todaysSchedules, key = { "s" + it.id }) { sc ->
                SectionCard(sc.title, accent = InfoBlue) {
                    InfoRow("Time", sc.time)
                    InfoRow("Category", sc.category)
                    if (sc.stops.isNotEmpty()) Text("Stops: " + sc.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(enabled = canStart, onClick = { pickFor = sc }) { Text("Start run") }
                }
            }

            // ---- other drivers
            if (others.isNotEmpty()) {
                item { SectionHeader("Taken by other drivers") }
                items(others, key = { "o" + it.id }) { t -> TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) }
            }

            item {
                SectionHeader("Driver")
                GroupCard {
                    ListRow("⛽", "Fuel fill-up") { s.nav.go(Screen.FuelEntry) }
                    ListRow("🗂", "Trip history") { s.nav.go(Screen.Trips) }
                    ListRow("📱", "Driver phone setup") { s.nav.go(Screen.DriverSetup) }
                    ListRow("🚪", "Sign out", last = true, tint = DangerRed) { menu = true }
                }
                SectionHeader("Appearance")
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { AppearancePicker() }
                AboutCard()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // Start = choose the vehicle (like accepting a ride in Uber), passengers on board, go.
    pickFor?.let { target ->
        val t = target as? Trip
        val sc = target as? Schedule
        VehiclePickerDialog(
            title = if (t != null) "Start ride" else "Start ${sc?.title}",
            vehicles = vehicles,
            busy = busy,
            preselect = t?.vehicleId?.ifBlank { null } ?: DevicePrefs.lastVehicleId(ctx),
            confirmText = if (t != null) "Passengers on board – Start" else "Start",
            onDismiss = { pickFor = null },
        ) { v ->
            DevicePrefs.setLastVehicleId(ctx, v.id)
            val now = System.currentTimeMillis()
            if (t != null) {
                Repo.startTrip(t, me, now, v)
                TrackingService.startTrip(ctx, t.id, t.title, t.requestIds, v)
            } else if (sc != null) {
                val id = Repo.startScheduled(sc, me, now, v)
                TrackingService.startTrip(ctx, id, sc.title, emptyList(), v)
            }
            pickFor = null
            s.nav.go(Screen.ActiveTrip)
        }
    }
    confirmRelease?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmRelease = null },
            title = { Text("Release this trip?") },
            text = { Text("“${t.title}” goes back to all drivers so someone else can take it.") },
            confirmButton = { Button(onClick = { Repo.releaseTrip(t); confirmRelease = null }) { Text("Release") } },
            dismissButton = { TextButton(onClick = { confirmRelease = null }) { Text("Keep it") } },
        )
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("Sign out?") },
            text = { Text("You will be set off duty and stop receiving trip alerts on this phone.") },
            confirmButton = {
                Button(onClick = {
                    menu = false
                    if (me.onDuty) Repo.setDuty(me, false)
                    DevicePrefs.setOnDuty(ctx, false)
                    signOut(ctx)
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun TripCard(t: Trip, s: Session, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val color = when {
        t.type == TripType.UNAUTHORIZED -> DangerRed
        t.status == TripStatus.IN_PROGRESS -> OkGreen
        t.status == TripStatus.READY -> WarnOrange
        t.status == TripStatus.ASSIGNED -> InfoBlue
        t.status == TripStatus.CANCELLED -> TextSecondary
        else -> Cinnamon
    }
    SectionCard(onClick = onClick, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (t.type == TripType.UNAUTHORIZED && t.status != TripStatus.IN_PROGRESS) "Unapproved" else t.status.label, color)
        }
        if (t.purpose.isNotBlank()) Text(t.purpose, fontSize = 13.sp, color = TextSecondary)
        val time = t.startTime ?: t.plannedTime
        if (time > 0) InfoRow(if (t.startTime != null) "Started" else "Planned", TripCalc.dateTime(time))
        if (t.departments.isNotEmpty()) InfoRow("Departments", t.departments.joinToString(", "))
        if (t.stops.isNotEmpty()) Text("Stops: " + t.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
        t.destination?.let { InfoRow("Destination", it.name) }
        if (t.passengers > 0) InfoRow("Passengers", t.passengers.toString())
        if (t.driverName.isNotBlank()) InfoRow("Driver", t.driverName)
        if (t.vehicleName.isNotBlank()) InfoRow("Vehicle", t.vehicleName)
        if (t.status == TripStatus.COMPLETED) {
            InfoRow("Distance / time", "${TripCalc.km(t.distanceKm)} · ${TripCalc.duration(t.durationMin)}")
            InfoRow("Fuel / cost", "${TripCalc.liters(t.fuelLiters)} · ${TripCalc.money(t.fuelCost, s.settings.currency)}")
        }
        if (t.requestIds.size > 1) Text("Combined trip: ${t.requestIds.size} requests", color = InfoBlue, fontSize = 13.sp)
        Spacer(Modifier.height(4.dp))
        actions()
    }
}

// ------------------------------------------------------------------ active trip

@Composable
fun ActiveTripScreen(s: Session) {
    val ctx = LocalContext.current
    val live by TrackingBus.live.collectAsState()
    val st = live.state
    val trip by remember(st.tripId) { if (st.tripId.isBlank()) kotlinx.coroutines.flow.flowOf(null) else Repo.tripFlow(st.tripId) }
        .collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    var confirmEnd by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    val unauthorized = st.mode == TrackMode.UNAUTHORIZED
    val endedTripId = remember { mutableStateOf("") }

    if (st.mode == TrackMode.GUARD) {
        LaunchedEffect(Unit) {
            val id = endedTripId.value
            s.nav.home()
            if (id.isNotBlank()) s.nav.go(Screen.TripDetail(id))
        }
        return
    }

    Scaffold(topBar = { AppBar(if (unauthorized) "Unapproved movement" else "Ride in progress", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (unauthorized) Banner("No approved trip. Your route is recorded and management is notified. Enter the reason below.", DangerRed)
            val vehicle = rememberVehicles(s, includeInactive = true).firstOrNull { it.id == st.vehicleId }
            val tripNow = trip
            // Drop-off points of every passenger plus the run's destination, so the driver sees the whole route.
            val stopPins = tripNow?.let { tr ->
                (tr.stops + listOfNotNull(tr.destination)).filter { it.hasLocation && it.arrivedAt == null }
                    .map { MapPin(it.lat!!, it.lng!!, it.name, snippet = it.department) }
            }.orEmpty()
            VanMap(
                modifier = Modifier.fillMaxWidth().height(280.dp),
                path = live.points,
                pins = stopPins,
                fitKey = "${st.tripId}-${stopPins.size}",
                follow = true,
                vehicleTitle = st.vehicleName.ifBlank { "Vehicle" },
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val (km, liters, cost) = TripCalc.fuel(st.distanceM / 1000, s.settings, vehicle)
                SectionCard(st.tripTitle) {
                    if (st.vehicleName.isNotBlank()) InfoRow("Vehicle", st.vehicleName)
                    InfoRow("Elapsed", TripCalc.durationMs(now - st.startTime))
                    InfoRow("Distance", TripCalc.km(km))
                    InfoRow("Moving / stopped", "${TripCalc.durationMs(st.movingMs)} / ${TripCalc.durationMs(st.stoppedMs)}")
                    InfoRow("Speed", "${live.speedKmh.toInt()} km/h")
                    InfoRow("Est. fuel / cost", "${TripCalc.liters(liters)} · ${TripCalc.money(cost, s.settings.currency)}")
                }
                val t = trip
                val passengers by remember(st.tripId) {
                    if (st.tripId.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList<com.cinnamon.vantracker.data.TripRequest>()) else Repo.requestsForTripFlow(st.tripId)
                }.collectAsState(initial = emptyList())
                if (t != null && t.stops.isNotEmpty()) {
                    SectionCard("Stops & drop-offs") {
                        t.stops.forEachIndexed { i, stop ->
                            val who = passengers.firstOrNull { it.id == stop.requestId }?.travellers
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${stop.name}", fontWeight = FontWeight.Medium)
                                    Text(listOfNotNull(who, stop.department.ifBlank { null }).joinToString(" · "), fontSize = 12.sp, color = TextSecondary)
                                }
                                if (stop.hasLocation && stop.arrivedAt == null) TextButton(onClick = { navigateTo(ctx, stop.lat!!, stop.lng!!) }) { Text("Navigate") }
                                if (stop.arrivedAt != null) Text("✅ ${TripCalc.time(stop.arrivedAt)}", color = OkGreen)
                                else OutlinedButton(onClick = { Repo.markStopArrived(t, i) }) { Text("Arrived") }
                            }
                        }
                    }
                }
                t?.destination?.let { d ->
                    SectionCard("Final destination", accent = Cinnamon) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            if (d.hasLocation) Button(onClick = { navigateTo(ctx, d.lat!!, d.lng!!) }) { Text("Navigate") }
                        }
                    }
                }
                if (unauthorized) {
                    SectionCard("Reason for this movement") {
                        OutlinedTextField(reason, { reason = it }, label = { Text("e.g. fuel refill, emergency, mechanic") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = reason.isNotBlank(), onClick = { Repo.submitReason(st.tripId, reason.trim()); reason = "" }) { Text("Submit reason") }
                        if (t != null && t.reason.isNotBlank()) Text("Submitted: ${t.reason}", color = OkGreen)
                    }
                }
                Button(
                    onClick = { confirmEnd = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
                ) { Text(if (unauthorized) "Van parked – stop recording" else "Finish ride", fontSize = 16.sp) }
            }
        }
    }

    if (confirmEnd) {
        val awayFromHotel = s.settings.hotelSet && live.lastLat != null &&
            TripCalc.distanceM(s.settings.hotelLat, s.settings.hotelLng, live.lastLat!!, live.lastLng!!) > s.settings.geofenceRadiusM
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(if (unauthorized) "Stop recording?" else "Finish ride?") },
            text = {
                Text(
                    if (awayFromHotel && !unauthorized) "The van does not appear to be at ${s.settings.hotelName} yet. End the trip anyway?"
                    else "The route, time and fuel cost will be saved."
                )
            },
            confirmButton = {
                Button(onClick = {
                    endedTripId.value = st.tripId
                    TrackingService.endTrip(ctx)
                    confirmEnd = false
                }) { Text("End") }
            },
            dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ reasons

@Composable
fun ReasonsScreen(s: Session) {
    val list by remember { Repo.needsReasonFlow() }.collectAsState(initial = emptyList())
    Scaffold(topBar = { AppBar("Reasons required", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text(
                    "The van moved without an approved trip. Enter a reason for each before ${s.settings.reasonDeadlineHour}:00, otherwise management is alerted automatically.",
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (list.isEmpty()) item { Empty("Nothing pending 👍") }
            items(list, key = { it.id }) { t ->
                var text by remember(t.id) { mutableStateOf("") }
                SectionCard(TripCalc.dateTime(t.startTime ?: t.createdAt), accent = DangerRed, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) {
                    InfoRow("Status", if (t.status == TripStatus.IN_PROGRESS) "Still recording" else "Ended ${t.endTime?.let { TripCalc.time(it) } ?: ""}")
                    InfoRow("Distance", TripCalc.km(t.distanceKm))
                    InfoRow("Driver", t.driverName)
                    if (s.me.isDriver || s.me.isAdmin) {
                        OutlinedTextField(text, { text = it }, label = { Text("Reason") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = text.isNotBlank(), onClick = { Repo.submitReason(t.id, text.trim()) }) { Text("Submit") }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ fuel entry

@Composable
fun FuelEntryScreen(s: Session) {
    var liters by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    Scaffold(topBar = { AppBar("Fuel fill-up", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recording fill-ups lets the admin compare real km/L against the configured ${s.settings.kmPerLiter} km/L.", color = TextSecondary)
            val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(liters, { liters = it }, label = { Text("Litres") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cost, { cost = it }, label = { Text("Total cost (${s.settings.currency})") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(odo, { odo = it }, label = { Text("Odometer reading (km)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note / shed name") }, modifier = Modifier.fillMaxWidth())
            if (saved) Text("Saved ✔ (syncs automatically when online)", color = OkGreen)
            Button(
                enabled = liters.toDoubleOrNull() != null,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    Repo.addFuelLog(
                        FuelLog(
                            time = System.currentTimeMillis(), liters = liters.toDoubleOrNull() ?: 0.0,
                            cost = cost.toDoubleOrNull() ?: 0.0, odometerKm = odo.toDoubleOrNull() ?: 0.0,
                            note = note, byName = s.me.name,
                        )
                    )
                    liters = ""; cost = ""; odo = ""; note = ""; saved = true
                },
            ) { Text("Save") }
        }
    }
}


/** Opens turn-by-turn navigation (Google Maps if installed, otherwise any maps app). */
fun navigateTo(ctx: Context, lat: Double, lng: Double) {
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lng&mode=d")).setPackage("com.google.android.apps.maps")
    runCatching { ctx.startActivity(nav) }.onFailure {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lng?q=$lat,$lng"))) }
    }
}
