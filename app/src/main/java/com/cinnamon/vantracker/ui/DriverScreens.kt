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

// ------------------------------------------------------------------ permissions

object DriverPermissions {
    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun location(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun background(ctx: Context) = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun notifications(ctx: Context) = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    fun bluetooth(ctx: Context) = Build.VERSION.SDK_INT < 31 || granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)
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
    Scaffold(topBar = { AppBar("Van phone setup", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            Text(
                "This phone stays in the van on its charger. Grant everything below so trips and any unapproved movement are recorded, even with the screen off.",
                modifier = Modifier.padding(16.dp),
            )
            key(stamp) {
                PermissionRow("1. Precise location", DriverPermissions.location(ctx)) {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
                PermissionRow("2. Location “Allow all the time”", DriverPermissions.background(ctx), enabled = DriverPermissions.location(ctx)) {
                    if (Build.VERSION.SDK_INT >= 29) launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
                PermissionRow("3. Notifications", DriverPermissions.notifications(ctx)) {
                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
                PermissionRow("4. Nearby devices (van Bluetooth trigger)", DriverPermissions.bluetooth(ctx)) {
                    if (Build.VERSION.SDK_INT >= 31) launcher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                }
                PermissionRow("5. Ignore battery optimisation", DriverPermissions.battery(ctx)) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = DriverPermissions.allGranted(ctx),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    onClick = { TrackingService.start(ctx); s.nav.back() },
                ) { Text("Start van guard") }
                Text(
                    "Tip: in the phone's settings also disable “Pause app activity if unused” and keep Location ON permanently.",
                    color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, enabled: Boolean = true, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️", fontSize = 20.sp)
        Spacer(Modifier.width(8.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!ok) OutlinedButton(enabled = enabled, onClick = onGrant) { Text("Grant") }
    }
}

// ------------------------------------------------------------------ driver home

@Composable
fun DriverHome(s: Session) {
    val ctx = LocalContext.current
    val live by TrackingBus.live.collectAsState()
    val openTrips by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val today = remember { TripCalc.dayStart(System.currentTimeMillis()) }
    val todayTrips by remember { Repo.tripsSinceFlow(today) }.collectAsState(initial = emptyList())
    val needReason by remember { Repo.needsReasonFlow() }.collectAsState(initial = emptyList())
    val online = rememberOnline()
    val tick = rememberResumeTick()
    val permsOk = remember(tick) { DriverPermissions.allGranted(ctx) }
    var confirmTrip by remember { mutableStateOf<Trip?>(null) }
    var confirmSchedule by remember { mutableStateOf<Schedule?>(null) }
    var menu by remember { mutableStateOf(false) }

    val dayKey = Repo.dateKey()
    val knownIds = (openTrips + todayTrips).map { it.id }.toSet()
    val todaysSchedules = schedules.filter {
        it.active && TripCalc.isoDay(System.currentTimeMillis()) in it.days && Repo.scheduleTripId(it.id, dayKey) !in knownIds
    }
    val ready = openTrips.filter { it.status == TripStatus.READY }
    val recording = live.state.mode != TrackMode.GUARD

    Scaffold(topBar = {
        AppBar("Driver · ${s.settings.vehicleName}") {
            IconButton(onClick = { s.nav.go(Screen.DriverSetup) }) { Icon(Icons.Filled.Settings, "Setup") }
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.ExitToApp, "Sign out") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (!online) item { Banner("Offline – trips are still recorded and will sync automatically.", WarnOrange) }
            if (!permsOk) item { Banner("Setup needed: tap to grant location permissions.", DangerRed) { s.nav.go(Screen.DriverSetup) } }
            if (live.state.mode == TrackMode.UNAUTHORIZED) item {
                Banner("⚠ Van is moving without an approved trip. Recording… Tap to open.", DangerRed) { s.nav.go(Screen.ActiveTrip) }
            }
            if (needReason.isNotEmpty()) item {
                Banner("${needReason.size} movement(s) need a reason – tap to enter before ${s.settings.reasonDeadlineHour}:00", WarnOrange) {
                    s.nav.go(Screen.Reasons)
                }
            }
            item {
                SectionCard("Van status") {
                    InfoRow(
                        "Mode", when (live.state.mode) {
                            TrackMode.GUARD -> if (live.running) "Guarding (parked)" else "Guard not running"
                            TrackMode.TRIP -> "On trip"
                            TrackMode.UNAUTHORIZED -> "Unapproved movement"
                        },
                        if (live.running) OkGreen else DangerRed,
                    )
                    InfoRow("Charger (ignition)", if (live.charging) "Connected" else "Not connected")
                    InfoRow("Van Bluetooth", when {
                        s.settings.btDeviceName.isBlank() -> "Not configured"
                        live.btConnected -> "Connected"
                        else -> "Not connected"
                    })
                    InfoRow("GPS", if (live.lastFix == 0L) "Waiting for fix…" else "±${live.lastAccuracy.toInt()} m · ${TripCalc.time(live.lastFix)}")
                    if (!live.running && permsOk) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { TrackingService.start(ctx) }) { Text("Start van guard") }
                    }
                }
            }
            if (recording) item {
                SectionCard(if (live.state.mode == TrackMode.TRIP) "Trip in progress" else "Recording movement", onClick = { s.nav.go(Screen.ActiveTrip) },
                    accent = if (live.state.mode == TrackMode.TRIP) OkGreen else DangerRed) {
                    Text(live.state.tripTitle, fontWeight = FontWeight.Bold)
                    InfoRow("Distance", TripCalc.km(live.state.distanceM / 1000))
                    InfoRow("Started", TripCalc.time(live.state.startTime))
                    Button(onClick = { s.nav.go(Screen.ActiveTrip) }) { Text("Open trip") }
                }
            }
            item { SectionHeader("Approved trips") }
            if (ready.isEmpty()) item { Empty("No approved trips waiting.") }
            items(ready, key = { it.id }) { t ->
                TripCard(t, s) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = live.state.mode != TrackMode.TRIP && permsOk, onClick = { confirmTrip = t }) { Text("Start trip") }
                        OutlinedButton(onClick = { s.nav.go(Screen.TripDetail(t.id)) }) { Text("Details") }
                    }
                }
            }
            item { SectionHeader("Today's scheduled trips (pre-approved)") }
            if (todaysSchedules.isEmpty()) item { Empty("No more scheduled trips today.") }
            items(todaysSchedules, key = { it.id }) { sc ->
                SectionCard(sc.title, accent = InfoBlue) {
                    InfoRow("Time", sc.time)
                    InfoRow("Category", sc.category)
                    if (sc.stops.isNotEmpty()) Text("Stops: " + sc.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Button(enabled = live.state.mode != TrackMode.TRIP && permsOk, onClick = { confirmSchedule = sc }) { Text("Start scheduled trip") }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { s.nav.go(Screen.FuelEntry) }, modifier = Modifier.weight(1f)) { Text("⛽ Fuel fill-up") }
                    OutlinedButton(onClick = { s.nav.go(Screen.Trips) }, modifier = Modifier.weight(1f)) { Text("Trip history") }
                }
            }
        }
    }

    confirmTrip?.let { t ->
        AlertDialog(
            onDismissRequest = { confirmTrip = null },
            title = { Text("Start trip?") },
            text = { Text("Confirm the passenger(s) are on board for:\n${t.title}") },
            confirmButton = {
                Button(onClick = {
                    val now = System.currentTimeMillis()
                    Repo.startTrip(t, s.me, now)
                    TrackingService.startTrip(ctx, t.id, t.title, t.requestIds)
                    confirmTrip = null
                    s.nav.go(Screen.ActiveTrip)
                }) { Text("Passengers on board – Start") }
            },
            dismissButton = { TextButton(onClick = { confirmTrip = null }) { Text("Cancel") } },
        )
    }
    confirmSchedule?.let { sc ->
        AlertDialog(
            onDismissRequest = { confirmSchedule = null },
            title = { Text("Start scheduled trip?") },
            text = { Text("${sc.title} (${sc.time}) is pre-approved. Start recording now?") },
            confirmButton = {
                Button(onClick = {
                    val id = Repo.startScheduled(sc, s.me, System.currentTimeMillis())
                    TrackingService.startTrip(ctx, id, sc.title, emptyList())
                    confirmSchedule = null
                    s.nav.go(Screen.ActiveTrip)
                }) { Text("Start") }
            },
            dismissButton = { TextButton(onClick = { confirmSchedule = null }) { Text("Cancel") } },
        )
    }
    if (menu) {
        AlertDialog(
            onDismissRequest = { menu = false },
            title = { Text("Sign out?") },
            text = { Text("Signing out stops the van guard on this phone. Movement will no longer be monitored.") },
            confirmButton = { Button(onClick = { menu = false; signOut(ctx) }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { menu = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(text, fontWeight = FontWeight.Bold, color = Cinnamon, fontSize = 15.sp,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
fun TripCard(t: Trip, s: Session, onClick: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    val color = when {
        t.type == TripType.UNAUTHORIZED -> DangerRed
        t.status == TripStatus.IN_PROGRESS -> OkGreen
        t.status == TripStatus.READY -> InfoBlue
        t.status == TripStatus.CANCELLED -> Color.Gray
        else -> Cinnamon
    }
    SectionCard(onClick = onClick, accent = color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (t.type == TripType.UNAUTHORIZED && t.status != TripStatus.IN_PROGRESS) "Unapproved" else t.status.label, color)
        }
        if (t.purpose.isNotBlank()) Text(t.purpose, fontSize = 13.sp, color = Color.DarkGray)
        val time = t.startTime ?: t.plannedTime
        if (time > 0) InfoRow(if (t.startTime != null) "Started" else "Planned", TripCalc.dateTime(time))
        if (t.departments.isNotEmpty()) InfoRow("Departments", t.departments.joinToString(", "))
        if (t.stops.isNotEmpty()) Text("Stops: " + t.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
        if (t.passengers > 0) InfoRow("Passengers", t.passengers.toString())
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

    Scaffold(topBar = { AppBar(if (unauthorized) "Unapproved movement" else "Trip in progress", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (unauthorized) Banner("No approved trip. Your route is recorded and management is notified. Enter the reason below.", DangerRed)
            OsmMap(
                modifier = Modifier.fillMaxWidth().height(260.dp),
                path = live.points,
                fitKey = st.tripId,
                follow = true,
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                val (km, liters, cost) = TripCalc.fuel(st.distanceM / 1000, s.settings)
                SectionCard(st.tripTitle) {
                    InfoRow("Elapsed", TripCalc.durationMs(now - st.startTime))
                    InfoRow("Distance", TripCalc.km(km))
                    InfoRow("Moving / stopped", "${TripCalc.durationMs(st.movingMs)} / ${TripCalc.durationMs(st.stoppedMs)}")
                    InfoRow("Speed", "${live.speedKmh.toInt()} km/h")
                    InfoRow("Est. fuel / cost", "${TripCalc.liters(liters)} · ${TripCalc.money(cost, s.settings.currency)}")
                }
                val t = trip
                if (t != null && t.stops.isNotEmpty()) {
                    SectionCard("Stops") {
                        t.stops.forEachIndexed { i, stop ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text("${i + 1}. ${stop.name}", fontWeight = FontWeight.Medium)
                                    if (stop.department.isNotBlank()) Text(stop.department, fontSize = 12.sp, color = Color.Gray)
                                }
                                if (stop.arrivedAt != null) Text("✅ ${TripCalc.time(stop.arrivedAt)}", color = OkGreen)
                                else OutlinedButton(onClick = { Repo.markStopArrived(t, i) }) { Text("Arrived") }
                            }
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
                ) { Text(if (unauthorized) "Van parked – stop recording" else "Back at hotel – End trip", fontSize = 16.sp) }
            }
        }
    }

    if (confirmEnd) {
        val awayFromHotel = s.settings.hotelSet && live.lastLat != null &&
            TripCalc.distanceM(s.settings.hotelLat, s.settings.hotelLng, live.lastLat!!, live.lastLng!!) > s.settings.geofenceRadiusM
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(if (unauthorized) "Stop recording?" else "End trip?") },
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
            Text("Recording fill-ups lets the admin compare real km/L against the configured ${s.settings.kmPerLiter} km/L.", color = Color.Gray)
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
