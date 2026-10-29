package com.cinnamon.vantracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.cinnamon.vantracker.data.AppSettings
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.CATEGORIES
import com.cinnamon.vantracker.data.DEPARTMENTS
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Role
import com.cinnamon.vantracker.data.Schedule
import com.cinnamon.vantracker.data.Stop
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.data.TripType
import com.cinnamon.vantracker.data.UserProfile
import com.cinnamon.vantracker.tracking.TripCalc
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.Calendar

// ------------------------------------------------------------------ settings

private fun fmtCoord(v: Double) = String.format(java.util.Locale.US, "%.6f", v)

@Composable
fun AdminSettingsScreen(s: Session) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cur = s.settings
    var currency by remember { mutableStateOf(cur.currency) }
    var price by remember { mutableStateOf(cur.fuelPricePerLiter.toString()) }
    var petrol by remember { mutableStateOf(cur.petrolPricePerLiter.toString()) }
    var kmpl by remember { mutableStateOf(cur.kmPerLiter.toString()) }
    var correction by remember { mutableStateOf(cur.distanceCorrectionPct.toString()) }
    var hotel by remember { mutableStateOf(cur.hotelName) }
    var lat by remember { mutableStateOf(if (cur.hotelSet) cur.hotelLat.toString() else "") }
    var lng by remember { mutableStateOf(if (cur.hotelSet) cur.hotelLng.toString() else "") }
    var radius by remember { mutableStateOf(cur.geofenceRadiusM.toInt().toString()) }
    var speed by remember { mutableStateOf(cur.movementSpeedKmh.toInt().toString()) }
    var accuracy by remember { mutableStateOf(cur.minGpsAccuracyM.toInt().toString()) }
    var power by remember { mutableStateOf(cur.usePowerTrigger) }
    var autoRuns by remember { mutableStateOf(cur.autoApproveScheduled) }
    var deadline by remember { mutableStateOf(cur.reasonDeadlineHour.toString()) }
    var autoEnd by remember { mutableStateOf(cur.autoEndUnauthorizedMin.toString()) }
    var msg by remember { mutableStateOf("") }
    var mapOpen by remember { mutableStateOf(false) }

    @SuppressLint("MissingPermission")
    fun useCurrentLocation() {
        scope.launch {
            val loc = runCatching {
                LocationServices.getFusedLocationProviderClient(ctx).getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
            }.getOrNull()
            if (loc != null) { lat = fmtCoord(loc.latitude); lng = fmtCoord(loc.longitude); msg = "" }
            else msg = "Could not get location – make sure GPS is on."
        }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) useCurrentLocation() }

    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    Scaffold(topBar = { AppBar("Settings", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionHeader("Vehicles")
            OutlinedButton(onClick = { s.nav.go(Screen.Vehicles) }, modifier = Modifier.fillMaxWidth()) { Text("Manage vehicles (name, plate, fuel, km/L)") }

            SectionHeader("Fuel cost (from GPS distance)")
            OutlinedTextField(price, { price = it }, label = { Text("Diesel price per litre") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(petrol, { petrol = it }, label = { Text("Petrol price per litre") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(kmpl, { kmpl = it }, label = { Text("Default km per litre (vehicles without their own)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(correction, { correction = it }, label = { Text("GPS distance correction % (e.g. 3 or -2)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(currency, { currency = it }, label = { Text("Currency") }, modifier = Modifier.fillMaxWidth())
            val example = TripCalc.fuel(10.0, AppSettings(fuelPricePerLiter = price.toDoubleOrNull() ?: 0.0, kmPerLiter = kmpl.toDoubleOrNull() ?: 0.0,
                distanceCorrectionPct = correction.toDoubleOrNull() ?: 0.0))
            Text("Example: 10 km GPS → ${TripCalc.km(example.first)} → ${TripCalc.liters(example.second)} → ${TripCalc.money(example.third, currency)}",
                fontSize = 13.sp, color = TextSecondary)

            SectionHeader("Hotel location (trip start/end & geofence)")
            OutlinedTextField(hotel, { hotel = it }, label = { Text("Hotel name") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat, { lat = it }, label = { Text("Latitude") }, keyboardOptions = num, modifier = Modifier.weight(1f))
                OutlinedTextField(lng, { lng = it }, label = { Text("Longitude") }, keyboardOptions = num, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    if (DriverPermissions.location(ctx)) useCurrentLocation() else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }) { Text("Use my location") }
                OutlinedButton(onClick = { mapOpen = true }) { Text("Pick on map") }
            }
            OutlinedTextField(radius, { radius = it }, label = { Text("Hotel geofence radius (m)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())

            SectionHeader("Scheduled runs")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm seats on scheduled runs instantly (no GM/OM approval)", modifier = Modifier.weight(1f)); Switch(autoRuns, { autoRuns = it })
            }

            SectionHeader("Dedicated vehicle phones (optional)")
            Text(
                "Drivers' own phones record only rides they start. To also catch driving without an approved ride, " +
                    "keep a spare phone in a vehicle on its charger and mark it as a dedicated vehicle phone in Driver phone setup.",
                fontSize = 13.sp, color = TextSecondary,
            )
            OutlinedTextField(speed, { speed = it }, label = { Text("Movement speed threshold (km/h)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(accuracy, { accuracy = it }, label = { Text("Ignore GPS fixes worse than (m)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Charger = ignition signal", modifier = Modifier.weight(1f)); Switch(power, { power = it })
            }
            OutlinedTextField(deadline, { deadline = it }, label = { Text("Reason deadline hour (0-23)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(autoEnd, { autoEnd = it }, label = { Text("Auto-end recording after parked (min)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())

            SectionHeader("App updates on every phone")
            AppUpdatesAdmin(cur.minVersionCode)

            if (msg.isNotBlank()) Text(msg, color = if (msg.startsWith("Saved")) OkGreen else DangerRed)
            Button(modifier = Modifier.fillMaxWidth().height(50.dp), onClick = {
                val ns = AppSettings(
                    vehicleName = cur.vehicleName, plateNo = cur.plateNo, currency = currency.trim().ifBlank { "LKR" },
                    fuelPricePerLiter = price.toDoubleOrNull() ?: cur.fuelPricePerLiter,
                    petrolPricePerLiter = petrol.toDoubleOrNull() ?: cur.petrolPricePerLiter,
                    kmPerLiter = kmpl.toDoubleOrNull()?.takeIf { it > 0 } ?: cur.kmPerLiter,
                    distanceCorrectionPct = correction.toDoubleOrNull() ?: 0.0,
                    hotelName = hotel.trim(), hotelLat = lat.toDoubleOrNull() ?: 0.0, hotelLng = lng.toDoubleOrNull() ?: 0.0,
                    geofenceRadiusM = radius.toDoubleOrNull() ?: cur.geofenceRadiusM,
                    movementSpeedKmh = speed.toDoubleOrNull() ?: cur.movementSpeedKmh,
                    minGpsAccuracyM = accuracy.toDoubleOrNull() ?: cur.minGpsAccuracyM,
                    usePowerTrigger = power, autoApproveScheduled = autoRuns,
                    reasonDeadlineHour = (deadline.toIntOrNull() ?: cur.reasonDeadlineHour).coerceIn(0, 23),
                    autoEndUnauthorizedMin = (autoEnd.toIntOrNull() ?: cur.autoEndUnauthorizedMin).coerceIn(1, 120),
                    minVersionCode = cur.minVersionCode,
                )
                Repo.saveSettings(ns)
                msg = "Saved ✔ New fuel rates apply to trips ending from now on."
            }) { Text("Save settings") }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (mapOpen) {
        var picked by remember { mutableStateOf(lat.toDoubleOrNull()?.let { la -> lng.toDoubleOrNull()?.let { MapPin(la, it, hotel) } }) }
        AlertDialog(
            onDismissRequest = { mapOpen = false },
            title = { Text("Tap the hotel's location") },
            text = { VanMap(Modifier.fillMaxWidth().height(340.dp), pins = listOfNotNull(picked), fitKey = "hotel", onTap = { a, o -> picked = MapPin(a, o, hotel) }) },
            confirmButton = {
                Button(onClick = {
                    picked?.let { lat = fmtCoord(it.lat); lng = fmtCoord(it.lng) }; mapOpen = false
                }) { Text("Use") }
            },
            dismissButton = { TextButton(onClick = { mapOpen = false }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ users

@Composable
fun UsersScreen(s: Session) {
    val users by remember { Repo.usersFlow() }.collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<UserProfile?>(null) }
    Scaffold(topBar = { AppBar("Users & roles", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            val pending = users.filter { it.role == Role.PENDING }
            if (pending.isNotEmpty()) item { SectionHeader("Waiting for approval (${pending.size})") }
            items(pending, key = { "p" + it.uid }) { u -> UserCard(u) { edit = u } }
            item { SectionHeader("All users") }
            items(users.filter { it.role != Role.PENDING }, key = { it.uid }) { u -> UserCard(u) { edit = u } }
        }
    }
    edit?.let { u ->
        var role by remember(u.uid) { mutableStateOf(if (u.role == Role.PENDING) u.requestedRole else u.role) }
        var dept by remember(u.uid) { mutableStateOf(u.department) }
        var admin by remember(u.uid) { mutableStateOf(u.isAdmin) }
        var active by remember(u.uid) { mutableStateOf(u.active) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(u.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(u.email, color = TextSecondary)
                    Picker("Role", role.label, Role.entries.map { it.label }) { l ->
                        role = Role.entries.first { it.label == l }
                        if (role == Role.ADMIN) admin = true
                    }
                    Picker("Department", dept, DEPARTMENTS) { dept = it }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(admin, { admin = it }); Text("Administrator") }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("Active") }
                }
            },
            confirmButton = {
                Button(onClick = {
                    Repo.updateUser(u.uid, mapOf("role" to role.name, "department" to dept, "isAdmin" to (admin || role == Role.ADMIN), "active" to active))
                    edit = null
                }) { Text(if (u.role == Role.PENDING) "Approve" else "Save") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun UserCard(u: UserProfile, onClick: () -> Unit) {
    SectionCard(onClick = onClick, accent = if (u.role == Role.PENDING) WarnOrange else if (!u.active) TextSecondary else OkGreen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(u.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(if (u.role == Role.PENDING) "Wants: ${u.requestedRole.label}" else u.role.label, if (u.role == Role.PENDING) WarnOrange else Cinnamon)
        }
        Text("${u.department} · ${u.email}" + if (u.isAdmin) " · Admin" else "", fontSize = 13.sp, color = TextSecondary)
        if (u.phone.isNotBlank()) Text(u.phone, fontSize = 13.sp, color = TextSecondary)
    }
}

// ------------------------------------------------------------------ schedules

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun SchedulesScreen(s: Session) {
    val list by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Schedule?>(null) }
    Scaffold(
        topBar = { AppBar("Recurring schedules", onBack = { s.nav.back() }) },
        floatingActionButton = { FloatingActionButton(onClick = { edit = Schedule(days = listOf(1, 2, 3, 4, 5, 6)) }) { Icon(Icons.Filled.Add, "Add") } },
    ) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Text("Pre-approved runs (night transport to the staff accommodation, laundry pickup…). Staff book a seat with their own " +
                    "drop-off point; seats are confirmed instantly and drivers start the run without waiting for GM/OM approval.",
                    modifier = Modifier.padding(16.dp), color = TextSecondary)
            }
            if (list.isEmpty()) item { Empty("No schedules yet. Tap + to add one.") }
            items(list, key = { it.id }) { sc ->
                SectionCard("${sc.time} · ${sc.title}", onClick = { edit = sc }, accent = if (sc.active) InfoBlue else TextSecondary) {
                    Text(sc.days.sorted().joinToString(" ") { DAY_NAMES[(it - 1).coerceIn(0, 6)] }, fontSize = 13.sp)
                    Text("${sc.category} · ${sc.department}", fontSize = 13.sp, color = TextSecondary)
                    sc.destination?.let { Text("Destination: ${it.name}" + if (it.hasLocation) " 📍" else "", fontSize = 13.sp) }
                    if (sc.stops.isNotEmpty()) Text("Via: " + sc.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
                    if (!sc.active) Text("Paused", color = WarnOrange)
                }
            }
        }
    }
    edit?.let { sc ->
        val ctx = LocalContext.current
        var title by remember(sc) { mutableStateOf(sc.title) }
        var category by remember(sc) { mutableStateOf(sc.category.ifBlank { CATEGORIES[2] }) }
        var purpose by remember(sc) { mutableStateOf(sc.purpose) }
        var dept by remember(sc) { mutableStateOf(sc.department.ifBlank { DEPARTMENTS.first() }) }
        val stops = remember(sc) { mutableStateListOf<Stop>().apply { addAll(sc.stops.ifEmpty { listOf(Stop()) }) } }
        var destination by remember(sc) { mutableStateOf(sc.destination ?: Stop(name = "Staff Accommodation")) }
        var pickDest by remember(sc) { mutableStateOf(false) }
        val days = remember(sc) { mutableStateListOf<Int>().apply { addAll(sc.days) } }
        var time by remember(sc) { mutableStateOf(sc.time) }
        var active by remember(sc) { mutableStateOf(sc.active) }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(if (sc.id.isBlank()) "New schedule" else "Edit schedule") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(title, { title = it }, label = { Text("Title, e.g. Morning staff pickup") }, modifier = Modifier.fillMaxWidth())
                    Picker("Category", category, CATEGORIES) { category = it }
                    Picker("Department", dept, DEPARTMENTS) { dept = it }
                    OutlinedTextField(purpose, { purpose = it }, label = { Text("Purpose") }, modifier = Modifier.fillMaxWidth())
                    SectionHeader("Destination")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(destination.name, { destination = destination.copy(name = it) }, label = { Text("e.g. Staff Accommodation") },
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = { pickDest = true }) {
                            Icon(Icons.Filled.Place, "Pick on map", tint = if (destination.hasLocation) OkGreen else Cinnamon)
                        }
                    }
                    if (destination.hasLocation) Text("📍 Location set on map", color = OkGreen, fontSize = 12.sp)
                    SectionHeader("Stops on the way (optional)")
                    StopsEditor(stops, s, label = { "Stop ${it + 1}" })
                    OutlinedButton(onClick = { pickTime(ctx, time) { time = it } }) { Text("Time: $time") }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        DAY_NAMES.forEachIndexed { i, n ->
                            val d = i + 1
                            FilterChip(selected = d in days, onClick = { if (d in days) days.remove(d) else days.add(d) },
                                label = { Text(n.take(2), fontSize = 11.sp) })
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(active, { active = it }); Text("Active") }
                    if (sc.id.isNotBlank()) TextButton(onClick = { Repo.deleteSchedule(sc.id); edit = null }) { Text("Delete schedule", color = DangerRed) }
                }
            },
            confirmButton = {
                Button(enabled = title.isNotBlank() && days.isNotEmpty(), onClick = {
                    Repo.saveSchedule(
                        sc.copy(
                            title = title.trim(), category = category, purpose = purpose.trim(), department = dept,
                            stops = stops.filter { it.name.isNotBlank() }.map { it.copy(department = dept) },
                            days = days.sorted(), time = time, active = active,
                            destination = destination.takeIf { it.name.isNotBlank() }?.copy(department = dept),
                        )
                    )
                    edit = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Cancel") } },
        )
        if (pickDest) LocationPickerDialog(
            title = "Run destination",
            initialName = destination.name,
            initial = if (destination.hasLocation) Place(destination.name, destination.lat!!, destination.lng!!) else null,
            s = s,
            onDismiss = { pickDest = false },
        ) { p -> destination = destination.copy(name = p.name, lat = p.lat, lng = p.lng); pickDest = false }
    }
}

// ------------------------------------------------------------------ fuel log

@Composable
fun FuelLogsScreen(s: Session) {
    val logs by remember { Repo.fuelLogsFlow() }.collectAsState(initial = emptyList())
    Scaffold(
        topBar = { AppBar("Fuel fill-ups", onBack = { s.nav.back() }) },
        floatingActionButton = { FloatingActionButton(onClick = { s.nav.go(Screen.FuelEntry) }) { Icon(Icons.Filled.Add, "Add") } },
    ) { pad ->
        // Real economy between consecutive full-tank fill-ups that have odometer readings.
        val withOdo = logs.filter { it.odometerKm > 0 }.sortedBy { it.time }
        val economies = withOdo.zipWithNext { a, b -> if (b.odometerKm > a.odometerKm && b.liters > 0) (b.odometerKm - a.odometerKm) / b.liters else null }.filterNotNull()
        LazyColumn(Modifier.padding(pad)) {
            item {
                SectionCard("Actual vs configured") {
                    InfoRow("Configured", "${s.settings.kmPerLiter} km/L")
                    InfoRow("Actual (avg of fill-ups)", if (economies.isEmpty()) "need 2+ fill-ups with odometer" else "%.2f km/L".format(economies.average()))
                    InfoRow("Fuel bought (listed)", "${TripCalc.liters(logs.sumOf { it.liters })} · ${TripCalc.money(logs.sumOf { it.cost }, s.settings.currency)}")
                    Text("Tip: fill the tank fully each time for accurate km/L, then update Settings.", fontSize = 12.sp, color = TextSecondary)
                }
            }
            if (logs.isEmpty()) item { Empty("No fill-ups recorded.") }
            items(logs, key = { it.id }) { f ->
                SectionCard(TripCalc.dateTime(f.time)) {
                    InfoRow("Litres", TripCalc.liters(f.liters))
                    InfoRow("Cost", TripCalc.money(f.cost, s.settings.currency))
                    if (f.liters > 0 && f.cost > 0) InfoRow("Price / L", TripCalc.money(f.cost / f.liters, s.settings.currency))
                    if (f.odometerKm > 0) InfoRow("Odometer", TripCalc.km(f.odometerKm))
                    if (f.note.isNotBlank()) InfoRow("Note", f.note)
                    InfoRow("By", f.byName)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ setup sharing

@Composable
fun ShareSetupScreen(s: Session) {
    val ctx = LocalContext.current
    val code = remember { Backend.storedConfig(ctx)?.toSetupCode() ?: "" }
    Scaffold(topBar = { AppBar("Set up other phones", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Install the APK on the van phone and on each HOD / manager phone, then paste this code on the first screen. Only share it inside the hotel.")
            Text(code, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(FillColor, RoundedCornerShape(8.dp)).padding(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Van Tracker setup", code))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "Van Tracker setup code:\n$code"), "Share setup code"))
                }) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(4.dp)); Text("Share") }
            }
            Text("After they create an account, approve them under Users & roles.", color = TextSecondary)
        }
    }
}

// ------------------------------------------------------------------ reports

@Composable
fun ReportsScreen(s: Session) {
    val ctx = LocalContext.current
    var month by remember { mutableLongStateOf(TripCalc.monthStart(System.currentTimeMillis())) }
    val next = remember(month) { Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, 1) }.timeInMillis }
    val all by remember(month) { Repo.tripsSinceFlow(month) }.collectAsState(initial = emptyList())
    val trips = all.filter { (it.startTime ?: it.createdAt) < next && it.status == TripStatus.COMPLETED }
    val cur = s.settings.currency
    Scaffold(topBar = {
        AppBar("Reports", onBack = { s.nav.back() }) {
            IconButton(onClick = { exportCsv(ctx, trips, cur, month) }) { Icon(Icons.Filled.Share, "Export CSV") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { month = Calendar.getInstance().apply { timeInMillis = month; add(Calendar.MONTH, -1) }.timeInMillis }) {
                        Icon(Icons.Filled.KeyboardArrowLeft, "Previous")
                    }
                    Text(java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(month)),
                        fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    IconButton(enabled = next <= System.currentTimeMillis(), onClick = { month = next }) { Icon(Icons.Filled.KeyboardArrowRight, "Next") }
                }
            }
            item {
                SectionCard("Month totals") {
                    InfoRow("Trips", trips.size.toString())
                    InfoRow("Distance", TripCalc.km(trips.sumOf { it.distanceKm }))
                    InfoRow("Time on road", TripCalc.duration(trips.sumOf { it.durationMin }))
                    InfoRow("Fuel (est.)", TripCalc.liters(trips.sumOf { it.fuelLiters }))
                    InfoRow("Fuel cost", TripCalc.money(trips.sumOf { it.fuelCost }, cur))
                    val un = trips.filter { it.type == TripType.UNAUTHORIZED }
                    InfoRow("Unapproved movements", "${un.size} · ${TripCalc.km(un.sumOf { it.distanceKm })}", if (un.isNotEmpty()) DangerRed else Color.Unspecified)
                    InfoRow("…without reason", un.count { it.reason.isBlank() }.toString(), if (un.any { it.reason.isBlank() }) DangerRed else Color.Unspecified)
                }
            }
            item { BreakdownCard("Cost by purpose", trips.groupBy { it.category.ifBlank { "Other" } }.mapValues { e -> e.value.sumOf { it.fuelCost } }, cur) }
            item {
                // Combined trips: cost shared equally among the departments on board.
                val byDept = mutableMapOf<String, Double>()
                trips.forEach { t ->
                    val depts = t.departments.ifEmpty { listOf(if (t.type == TripType.UNAUTHORIZED) "Unapproved" else "Unassigned") }
                    depts.forEach { d -> byDept[d] = (byDept[d] ?: 0.0) + t.fuelCost / depts.size }
                }
                BreakdownCard("Cost by department", byDept, cur)
            }
            item { BreakdownCard("Distance by trip type (km)", trips.groupBy { it.type.label }.mapValues { e -> e.value.sumOf { it.distanceKm } }, "") }
            item {
                val byVehicle = trips.groupBy { it.vehicleName.ifBlank { s.settings.vehicleName } }
                BreakdownCard("Distance by vehicle (km)", byVehicle.mapValues { e -> e.value.sumOf { it.distanceKm } }, "")
                BreakdownCard("Fuel cost by vehicle", byVehicle.mapValues { e -> e.value.sumOf { it.fuelCost } }, cur)
                val byDriver = trips.groupBy { it.driverName.ifBlank { "Unknown" } }
                BreakdownCard("Distance by driver (km)", byDriver.mapValues { e -> e.value.sumOf { it.distanceKm } }, "")
                BreakdownCard("Hours on the road by driver", byDriver.mapValues { e -> e.value.sumOf { it.durationMin } / 60.0 }, "")
                BreakdownCard("Trips by driver", byDriver.mapValues { e -> e.value.size.toDouble() }, "")
            }
            item { PassengerReport(s, month, next) }
            item { SectionHeader("Trips") }
            items(trips, key = { it.id }) { t -> TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) }
        }
    }
}

/** Who travelled: every booking/request in the month, searchable by passenger, department or run. */
@Composable
private fun PassengerReport(s: Session, from: Long, to: Long) {
    val all by remember(from) { Repo.requestsSinceFlow(from) }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    val rows = all.filter {
        it.requestedTime < to && it.status != com.cinnamon.vantracker.data.RequestStatus.REJECTED &&
            it.status != com.cinnamon.vantracker.data.RequestStatus.CANCELLED
    }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) rows else rows.filter {
        listOf(it.travellers, it.requesterName, it.department, it.joinTargetLabel, it.category, it.stops.joinToString { st -> st.name })
            .any { f -> f.lowercase().contains(q) }
    }
    // Trips per passenger (names separated by commas count individually).
    val perPassenger = shown.flatMap { r -> r.travellers.split(",", "&", "/").map { it.trim() }.filter { it.isNotEmpty() } }
        .groupingBy { it }.eachCount().mapValues { it.value.toDouble() }
    SectionHeader("Passengers")
    SectionCard {
        OutlinedTextField(query, { query = it }, label = { Text("Filter by passenger, department or run") }, singleLine = true,
            modifier = Modifier.fillMaxWidth())
        InfoRow("Bookings / requests", shown.size.toString())
        InfoRow("Seats", shown.sumOf { it.passengers }.toString())
    }
    BreakdownCard("Trips per passenger", perPassenger, "")
    shown.take(100).forEach { r ->
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.travellers, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Chip(r.status.label, if (r.status == com.cinnamon.vantracker.data.RequestStatus.COMPLETED) OkGreen else InfoBlue)
            }
            Text("${TripCalc.dateTime(r.requestedTime)} · ${r.joinTargetLabel.ifBlank { r.category }} · ${r.department}",
                color = TextSecondary, fontSize = 13.sp)
            if (r.stops.isNotEmpty()) Text("Drop-off: " + r.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
            if (r.driverName.isNotBlank()) Text("Driver: ${r.driverName}", color = TextSecondary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun BreakdownCard(title: String, data: Map<String, Double>, currency: String) {
    SectionCard(title) {
        if (data.isEmpty()) Text("No data", color = TextSecondary)
        val max = data.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        data.entries.sortedByDescending { it.value }.forEach { (k, v) ->
            Column(Modifier.padding(vertical = 3.dp)) {
                Row {
                    Text(k, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text(if (currency.isBlank()) "%.1f".format(v) else TripCalc.money(v, currency), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier.fillMaxWidth((v / max).toFloat().coerceIn(0.02f, 1f)).height(8.dp).background(Cinnamon, RoundedCornerShape(4.dp)))
            }
        }
    }
}

private fun exportCsv(ctx: Context, trips: List<Trip>, currency: String, month: Long) {
    fun q(v: String) = "\"" + v.replace("\"", "\"\"") + "\""
    val sb = StringBuilder("Date,Start,End,Type,Title,Purpose/Reason,Departments,Stops,Driver,Vehicle,Distance km,Duration min,Moving min,Stopped min,Fuel L,Fuel cost ($currency),Requests\n")
    trips.sortedBy { it.startTime ?: 0 }.forEach { t ->
        sb.append(listOf(
            TripCalc.date(t.startTime ?: t.createdAt), t.startTime?.let { TripCalc.time(it) } ?: "", t.endTime?.let { TripCalc.time(it) } ?: "",
            t.type.name, q(t.title), q(t.purpose.ifBlank { t.reason }), q(t.departments.joinToString("; ")),
            q(t.stops.joinToString(" > ") { it.name }), q(t.driverName), q(t.vehicleName), t.distanceKm.toString(), t.durationMin.toString(),
            t.movingMin.toString(), t.stoppedMin.toString(), t.fuelLiters.toString(), t.fuelCost.toString(), t.requestIds.size.toString(),
        ).joinToString(",")).append('\n')
    }
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val name = "van-trips-" + java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(java.util.Date(month)) + ".csv"
    val file = File(dir, name).apply { writeText(sb.toString()) }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        "Export trips",
    ))
}

/**
 * Phones are told about new versions automatically (banner + notification, one-tap install).
 * The administrator can also make a version compulsory: older phones then show only an
 * "Update required" screen until they install it.
 */
@Composable
private fun AppUpdatesAdmin(minVersion: Int) {
    LaunchedEffect(Unit) { if (!UpdateCache.checked) UpdateCache.check() }
    var latest by remember { mutableStateOf<com.cinnamon.vantracker.data.Updater.Release?>(null) }
    LaunchedEffect(Unit) { latest = com.cinnamon.vantracker.data.Updater.latest() }
    Text(
        "Every phone checks for new versions and offers a one-tap install (data is kept). " +
            "To make sure nobody stays on an old version, require the latest one.",
        fontSize = 13.sp, color = TextSecondary,
    )
    InfoRow("This phone", com.cinnamon.vantracker.data.Updater.currentVersion)
    InfoRow("Latest published", latest?.name ?: "checking…")
    InfoRow("Required on all phones", if (minVersion > 0) "v1.0.$minVersion or newer" else "not required")
    val l = latest
    if (l != null && l.versionCode > minVersion) {
        OutlinedButton(onClick = { Repo.setMinVersion(l.versionCode) }, modifier = Modifier.fillMaxWidth()) {
            Text("Require every phone to update to ${l.name}")
        }
    }
    if (minVersion > 0) TextButton(onClick = { Repo.setMinVersion(0) }) { Text("Remove the requirement") }
}
