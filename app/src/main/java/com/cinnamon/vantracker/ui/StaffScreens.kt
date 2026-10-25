package com.cinnamon.vantracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.draw.clip
import com.cinnamon.vantracker.data.Role
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.AlertItem
import com.cinnamon.vantracker.data.CATEGORIES
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.RequestStatus
import com.cinnamon.vantracker.data.Schedule
import com.cinnamon.vantracker.data.Stop
import com.cinnamon.vantracker.data.TrackPoint
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripRequest
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.data.TripType
import com.cinnamon.vantracker.tracking.TripCalc
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// ------------------------------------------------------------------ home with tabs

private data class Tab(val label: String, val icon: ImageVector, val badge: Int = 0)

@Composable
fun StaffHome(s: Session) {
    val pending by remember { Repo.pendingRequestsFlow() }.collectAsState(initial = emptyList())
    val alerts by remember { Repo.alertsFlow() }.collectAsState(initial = emptyList())
    val openAlerts = alerts.count { !it.acknowledged }
    val tabs = if (s.canApprove) listOf(
        Tab("Home", Icons.Filled.Home), Tab("Approvals", Icons.Filled.CheckCircle, pending.size),
        Tab("Trips", Icons.Filled.List), Tab("Alerts", Icons.Filled.Notifications, openAlerts), Tab("More", Icons.Filled.Menu),
    ) else listOf(
        Tab("Home", Icons.Filled.Home), Tab("My requests", Icons.Filled.CheckCircle),
        Tab("Trips", Icons.Filled.List), Tab("More", Icons.Filled.Menu),
    )
    val online = rememberOnline()
    val tab = s.nav.homeTab.coerceIn(0, tabs.lastIndex)
    val label = tabs[tab].label
    Scaffold(
        topBar = { AppBar(if (label == "Home") s.settings.vehicleName else label) },
        bottomBar = {
            // iOS-style tab bar: hairline on top, tinted icon, no pill indicator.
            Column {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
            NavigationBar(containerColor = CardColor, tonalElevation = 0.dp) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = i == tab, onClick = { s.nav.homeTab = i }, label = { Text(t.label, fontSize = 11.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Cinnamon, selectedTextColor = Cinnamon, indicatorColor = Color.Transparent,
                            unselectedIconColor = TextSecondary, unselectedTextColor = TextSecondary,
                        ),
                        icon = {
                            if (t.badge > 0) BadgedBox(badge = { Badge { Text(t.badge.toString()) } }) { Icon(t.icon, t.label) }
                            else Icon(t.icon, t.label)
                        },
                    )
                }
            }
            }
        },
        floatingActionButton = {
            if (label == "Home" || label == "My requests") ExtendedFloatingActionButton(
                onClick = { s.nav.go(Screen.NewRequest()) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Request van") },
                containerColor = Cinnamon, contentColor = MaterialTheme.colorScheme.onPrimary, shape = leafShape(28.dp, 8.dp),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item { UpdateBanner() }
            if (!online) item { Banner("Offline – showing saved data. Changes sync when back online.", WarnOrange) }
            when (label) {
                "Home" -> homeTab(s, pending.size, openAlerts)
                "Approvals" -> approvalsList(s, pending)
                "My requests" -> myRequestsList(s)
                "Trips" -> tripsList(s)
                "Alerts" -> alertsList(s, alerts)
                "More" -> moreMenu(s)
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun LazyListScope.homeTab(s: Session, pendingCount: Int, openAlerts: Int) {
    item { LiveVanCard(s) }
    if (s.canApprove && (pendingCount > 0 || openAlerts > 0)) item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pendingCount > 0) Button(onClick = { s.nav.homeTab = 1 }, modifier = Modifier.weight(1f)) { Text("$pendingCount to approve") }
            if (openAlerts > 0) Button(
                onClick = { s.nav.homeTab = 3 }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
            ) { Text("$openAlerts alert(s)") }
        }
    }
    item { UpcomingTrips(s) }
    item { MyRecentRequests(s) }
}

@Composable
private fun LiveVanCard(s: Session) {
    val live by remember { Repo.liveFlow() }.collectAsState(initial = null)
    val l = live
    SectionCard("Van now", onClick = { s.nav.go(Screen.LiveMap) }) {
        if (l == null || l.updatedAt == 0L) {
            Text("No position yet – the van phone has not reported.", color = TextSecondary)
        } else {
            val (text, color) = when (l.mode) {
                "TRIP" -> "On trip: ${l.tripTitle}" to OkGreen
                "UNAUTHORIZED" -> "Moving WITHOUT approval" to DangerRed
                else -> "Parked / idle" to InfoBlue
            }
            Chip(text, color)
            Spacer(Modifier.height(6.dp))
            VanMap(Modifier.fillMaxWidth().height(160.dp), pins = listOf(MapPin(l.lat, l.lng, "Van")), fitKey = "live")
            InfoRow("Speed", "${l.speedKmh.toInt()} km/h")
            InfoRow("Last update", TripCalc.dateTime(l.updatedAt))
            if (l.driverName.isNotBlank()) InfoRow("Driver", l.driverName)
        }
    }
}

@Composable
private fun UpcomingTrips(s: Session) {
    val open by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val dayKey = Repo.dateKey()
    val todays = schedules.filter {
        it.active && TripCalc.isoDay(System.currentTimeMillis()) in it.days && open.none { t -> t.id == Repo.scheduleTripId(it.id, dayKey) }
    }
    SectionHeader("Van plan – join a trip going your way")
    if (open.isEmpty() && todays.isEmpty()) Empty("No trips planned.")
    open.forEach { t ->
        TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) {
            if (t.status == TripStatus.READY || t.status == TripStatus.IN_PROGRESS) {
                OutlinedButton(onClick = { s.nav.go(Screen.NewRequest("trip:${t.id}", t.title)) }) { Text("+ Add my stop to this trip") }
            }
        }
    }
    todays.forEach { sc ->
        SectionCard("${sc.title} · ${sc.time}", accent = InfoBlue) {
            Text("Scheduled daily run (pre-approved)", fontSize = 13.sp, color = TextSecondary)
            if (sc.stops.isNotEmpty()) Text("Stops: " + sc.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
            OutlinedButton(onClick = { s.nav.go(Screen.NewRequest(Repo.scheduleTarget(sc), "${sc.title} (${sc.time})")) }) {
                Text("+ Add my stop to this run")
            }
        }
    }
}

@Composable
private fun MyRecentRequests(s: Session) {
    val mine by remember(s.me.uid) { Repo.myRequestsFlow(s.me.uid) }.collectAsState(initial = emptyList())
    SectionHeader("My recent requests")
    if (mine.isEmpty()) Empty("You have not requested the van yet.")
    mine.take(3).forEach { RequestCard(it, s) }
}

// ------------------------------------------------------------------ requests

@Composable
fun RequestCard(r: TripRequest, s: Session, actions: @Composable () -> Unit = {}) {
    val color = when (r.status) {
        RequestStatus.PENDING -> WarnOrange
        RequestStatus.APPROVED -> InfoBlue
        RequestStatus.IN_PROGRESS -> OkGreen
        RequestStatus.COMPLETED -> Cinnamon
        else -> TextSecondary
    }
    SectionCard(accent = color, onClick = if (r.tripId.isNotBlank()) ({ s.nav.go(Screen.TripDetail(r.tripId)) }) else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.category} – ${r.department}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Chip(r.status.label, color)
        }
        Text(r.purpose, fontSize = 14.sp)
        InfoRow("Requested by", r.requesterName)
        InfoRow("When", TripCalc.dateTime(r.requestedTime))
        InfoRow("Passengers", r.passengers.toString())
        if (r.stops.isNotEmpty()) Text("Stops: " + r.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
        if (r.joinTargetLabel.isNotBlank()) Text("Wants to join: ${r.joinTargetLabel}", color = InfoBlue, fontSize = 13.sp)
        if (r.notes.isNotBlank()) Text("Notes: ${r.notes}", fontSize = 13.sp, color = TextSecondary)
        if (r.approverName.isNotBlank()) Text(
            "${r.status.label} by ${r.approverName}" + (if (r.decisionComment.isNotBlank()) ": “${r.decisionComment}”" else ""),
            fontSize = 13.sp, color = TextSecondary,
        )
        actions()
    }
}

private fun LazyListScope.myRequestsList(s: Session) {
    item { MyRequestsBody(s) }
}

@Composable
private fun MyRequestsBody(s: Session) {
    val mine by remember(s.me.uid) { Repo.myRequestsFlow(s.me.uid) }.collectAsState(initial = emptyList())
    var cancel by remember { mutableStateOf<TripRequest?>(null) }
    if (mine.isEmpty()) Empty("No requests yet. Tap “Request van”.")
    mine.forEach { r ->
        RequestCard(r, s) {
            if (r.status == RequestStatus.PENDING || r.status == RequestStatus.APPROVED) {
                TextButton(onClick = { cancel = r }) { Text("Cancel request", color = DangerRed) }
            }
        }
    }
    cancel?.let { r ->
        AlertDialog(
            onDismissRequest = { cancel = null },
            title = { Text("Cancel this request?") },
            text = { Text("${r.category}: ${r.purpose}") },
            confirmButton = { Button(onClick = { AppScope.launch { Repo.cancelRequest(r) }; cancel = null }) { Text("Cancel request") } },
            dismissButton = { TextButton(onClick = { cancel = null }) { Text("Keep") } },
        )
    }
}

@Composable
fun MyRequestsScreen(s: Session) {
    Scaffold(topBar = { AppBar("My requests", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) { myRequestsList(s) }
    }
}

/** Options for where an approved request goes: its own trip, or combined with another run. */
@Composable
private fun rememberJoinOptions(): List<Pair<String, String>> {
    val open by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val dayKey = Repo.dateKey()
    val list = mutableListOf("" to "New separate trip")
    open.forEach { list += "trip:${it.id}" to "Combine with: ${it.title} (${TripCalc.dateTime(it.startTime ?: it.plannedTime)})" }
    schedules.filter { it.active && TripCalc.isoDay(System.currentTimeMillis()) in it.days && open.none { t -> t.id == Repo.scheduleTripId(it.id, dayKey) } }
        .forEach { list += Repo.scheduleTarget(it) to "Join today's run: ${it.title} (${it.time})" }
    return list
}

@Composable
fun NewRequestScreen(s: Session, joinTarget: String, joinLabel: String) {
    val ctx = LocalContext.current
    var category by remember { mutableStateOf(CATEGORIES.first()) }
    var purpose by remember { mutableStateOf("") }
    val stops = remember { mutableStateListOf(Stop()) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis() + 30 * 60_000) }
    var passengers by remember { mutableStateOf("1") }
    var notes by remember { mutableStateOf("") }
    val options = rememberJoinOptions()
    var target by remember { mutableStateOf(joinTarget) }
    var targetLabel by remember { mutableStateOf(joinLabel) }
    var autoApprove by remember { mutableStateOf(false) }
    var pickIndex by remember { mutableStateOf(-1) }
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())

    Scaffold(topBar = { AppBar("Request the van", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("Category", category, CATEGORIES) { category = it }
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Business purpose") }, modifier = Modifier.fillMaxWidth())
            Text("Destination / stops", fontWeight = FontWeight.Bold)
            stops.forEachIndexed { i, st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(st.name, { v -> stops[i] = st.copy(name = v) }, label = { Text("Stop ${i + 1}") }, modifier = Modifier.weight(1f))
                    IconButton(onClick = { pickIndex = i }) {
                        Icon(Icons.Filled.Place, "Pick on map", tint = if (st.lat != null) OkGreen else TextSecondary)
                    }
                }
            }
            TextButton(onClick = { stops.add(Stop()) }) { Text("+ Add another stop") }
            OutlinedButton(onClick = { pickDateTime(ctx, time) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                Text("Departure: ${TripCalc.dateTime(time)}")
            }
            OutlinedTextField(passengers, { passengers = it.filter(Char::isDigit) }, label = { Text("Passengers") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Picker("Trip", options.firstOrNull { it.first == target }?.second ?: targetLabel.ifBlank { "New separate trip" }, options.map { it.second }) { l ->
                val o = options.first { it.second == l }; target = o.first; targetLabel = if (o.first.isBlank()) "" else o.second
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Notes for approver / driver") }, modifier = Modifier.fillMaxWidth())
            if (s.canApprove) Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(autoApprove, { autoApprove = it }); Text("Approve immediately (my own business trip)")
            }
            Button(
                enabled = purpose.isNotBlank() && stops.any { it.name.isNotBlank() },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                onClick = {
                    val r = TripRequest(
                        requesterUid = s.me.uid, requesterName = s.me.name, department = s.me.department,
                        category = category, purpose = purpose.trim(), stops = stops.filter { it.name.isNotBlank() },
                        requestedTime = time, passengers = passengers.toIntOrNull() ?: 1, notes = notes.trim(),
                        joinTarget = target, joinTargetLabel = targetLabel,
                    )
                    val id = Repo.submitRequest(r)
                    if (autoApprove) {
                        val me = s.me
                        AppScope.launch { Repo.approveRequest(r.copy(id = id), me, "Self-approved", target.ifBlank { null }, schedules) }
                    }
                    s.nav.back()
                },
            ) { Text(if (autoApprove) "Create trip" else "Send for approval") }
            Text("Your GM / Operations Manager will be notified. You'll see the decision here.", color = TextSecondary, fontSize = 13.sp)
        }
    }

    if (pickIndex >= 0) {
        val idx = pickIndex
        var picked by remember(idx) { mutableStateOf(stops.getOrNull(idx)?.let { if (it.lat != null) MapPin(it.lat, it.lng!!, it.name) else null }) }
        AlertDialog(
            onDismissRequest = { pickIndex = -1 },
            title = { Text("Tap the destination on the map") },
            text = {
                VanMap(
                    Modifier.fillMaxWidth().height(320.dp),
                    pins = listOfNotNull(picked) + if (s.settings.hotelSet) listOf(MapPin(s.settings.hotelLat, s.settings.hotelLng, s.settings.hotelName)) else emptyList(),
                    fitKey = "pick$idx",
                    onTap = { la, lo -> picked = MapPin(la, lo, "Stop") },
                )
            },
            confirmButton = {
                Button(onClick = {
                    picked?.let { p -> stops.getOrNull(idx)?.let { stops[idx] = it.copy(lat = p.lat, lng = p.lng) } }
                    pickIndex = -1
                }) { Text("Use this point") }
            },
            dismissButton = { TextButton(onClick = { pickIndex = -1 }) { Text("Cancel") } },
        )
    }
}

// ------------------------------------------------------------------ approvals (GM / OM)

private fun LazyListScope.approvalsList(s: Session, pending: List<TripRequest>) {
    if (pending.isEmpty()) item { Empty("Nothing waiting for approval 👍") }
    items(pending, key = { it.id }) { r -> ApprovalItem(s, r) }
}

@Composable
private fun ApprovalItem(s: Session, r: TripRequest) {
    var dialog by remember { mutableStateOf<Boolean?>(null) } // true approve, false reject
    val options = rememberJoinOptions()
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val open by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    // Suggest combining with a trip leaving within 90 minutes of the requested time.
    val suggestion = open.firstOrNull { abs((it.startTime ?: it.plannedTime) - r.requestedTime) < 90 * 60_000 }
    RequestCard(r, s) {
        if (suggestion != null && r.joinTarget.isBlank()) {
            Text("💡 ${suggestion.title} leaves around the same time – consider combining.", fontSize = 13.sp, color = InfoBlue)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { dialog = true }, colors = ButtonDefaults.buttonColors(containerColor = OkGreen)) { Text("Approve") }
            OutlinedButton(onClick = { dialog = false }) { Text("Reject", color = DangerRed) }
        }
    }
    val d = dialog ?: return
    var comment by remember(d) { mutableStateOf("") }
    var target by remember(d) { mutableStateOf(r.joinTarget.ifBlank { suggestion?.let { "trip:${it.id}" } ?: "" }) }
    AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(if (d) "Approve request" else "Reject request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.requesterName}: ${r.purpose}")
                if (d) Picker("Assign to", options.firstOrNull { it.first == target }?.second ?: "New separate trip", options.map { it.second }) { l ->
                    target = options.first { it.second == l }.first
                }
                OutlinedTextField(comment, { comment = it }, label = { Text(if (d) "Comment (optional)" else "Reason for rejection") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = d || comment.isNotBlank(), onClick = {
                val me = s.me
                if (d) AppScope.launch { Repo.approveRequest(r, me, comment.trim(), target.ifBlank { null }, schedules) }
                else Repo.rejectRequest(r, me, comment.trim())
                dialog = null
            }) { Text(if (d) "Approve" else "Reject") }
        },
        dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
    )
}

@Composable
fun ApprovalsScreen(s: Session) {
    val pending by remember { Repo.pendingRequestsFlow() }.collectAsState(initial = emptyList())
    Scaffold(topBar = { AppBar("Approvals", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) { approvalsList(s, pending) }
    }
}

// ------------------------------------------------------------------ trips

private fun LazyListScope.tripsList(s: Session) {
    item { TripsBody(s) }
}

@Composable
private fun TripsBody(s: Session) {
    var days by remember { mutableStateOf(7) }
    var onlyUnauthorized by remember { mutableStateOf(false) }
    val from = remember(days) { TripCalc.dayStart(System.currentTimeMillis()) - (days - 1) * 86_400_000L }
    val trips by remember(from) { Repo.tripsSinceFlow(from) }.collectAsState(initial = emptyList())
    val visible = trips.filter { t ->
        (s.seesEverything || s.me.department in t.departments) &&
            (!onlyUnauthorized || t.type == TripType.UNAUTHORIZED)
    }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(1 to "Today", 7 to "7 days", 30 to "30 days").forEach { (d, l) ->
            FilterChip(selected = days == d, onClick = { days = d }, label = { Text(l) })
        }
        if (s.seesEverything) FilterChip(selected = onlyUnauthorized, onClick = { onlyUnauthorized = !onlyUnauthorized }, label = { Text("Unapproved") })
    }
    val done = visible.filter { it.status == TripStatus.COMPLETED }
    SectionCard {
        InfoRow("Trips", visible.size.toString())
        InfoRow("Distance", TripCalc.km(done.sumOf { it.distanceKm }))
        InfoRow("Time on road", TripCalc.duration(done.sumOf { it.durationMin }))
        InfoRow("Fuel / cost", "${TripCalc.liters(done.sumOf { it.fuelLiters })} · ${TripCalc.money(done.sumOf { it.fuelCost }, s.settings.currency)}")
    }
    if (visible.isEmpty()) Empty("No trips in this period.")
    visible.forEach { t -> TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) }
}

@Composable
fun TripsScreen(s: Session) {
    Scaffold(topBar = { AppBar("Trip history", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) { tripsList(s) }
    }
}

@Composable
fun TripDetailScreen(s: Session, id: String) {
    val trip by remember(id) { Repo.tripFlow(id) }.collectAsState(initial = null)
    val requests by remember(id) { Repo.requestsForTripFlow(id) }.collectAsState(initial = emptyList())
    var points by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    val t = trip
    LaunchedEffect(id, t?.status) {
        points = Repo.trackPoints(id)
        while (t?.status == TripStatus.IN_PROGRESS) { delay(30_000); points = Repo.trackPoints(id) }
    }
    Scaffold(topBar = { AppBar("Trip details", onBack = { s.nav.back() }) }) { pad ->
        if (t == null) { Empty("Loading…"); return@Scaffold }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            VanMap(
                Modifier.fillMaxWidth().height(300.dp),
                path = points,
                pins = t.stops.filter { it.lat != null && it.lng != null }.map { MapPin(it.lat!!, it.lng!!, it.name) },
                fitKey = "$id-${points.size}",
            )
            TripCard(t, s)
            SectionCard("Time & cost") {
                InfoRow("Type", t.type.label)
                t.startTime?.let { InfoRow("Started", TripCalc.dateTime(it)) }
                t.endTime?.let { InfoRow("Ended", TripCalc.dateTime(it)) }
                InfoRow("Total time on road", TripCalc.duration(t.durationMin))
                InfoRow("Moving / stopped", "${TripCalc.duration(t.movingMin)} / ${TripCalc.duration(t.stoppedMin)}")
                InfoRow("GPS distance", TripCalc.km(t.distanceKm))
                InfoRow("Max speed", "${t.maxSpeedKmh.toInt()} km/h", if (t.maxSpeedKmh > 80) DangerRed else Color.Unspecified)
                InfoRow("Fuel used (est.)", TripCalc.liters(t.fuelLiters))
                InfoRow("Fuel cost", TripCalc.money(t.fuelCost, s.settings.currency))
                if (t.kmPerLiter > 0) InfoRow("Rates used", "${t.kmPerLiter} km/L @ ${TripCalc.money(t.fuelPrice, s.settings.currency)}/L")
                if (t.driverName.isNotBlank()) InfoRow("Driver", t.driverName)
                if (t.departments.size > 1 && t.fuelCost > 0) {
                    Text("Cost share per department: ${TripCalc.money(t.fuelCost / t.departments.size, s.settings.currency)}", fontSize = 13.sp)
                }
                if (s.me.isAdmin && t.status == TripStatus.COMPLETED) {
                    TextButton(onClick = { Repo.recalcTrip(t, s.settings, t.gpsDistanceKm) }) { Text("Recalculate with current fuel settings") }
                }
            }
            if (t.stops.isNotEmpty()) SectionCard("Stops") {
                t.stops.forEachIndexed { i, st ->
                    InfoRow("${i + 1}. ${st.name}", st.arrivedAt?.let { "arrived ${TripCalc.time(it)}" } ?: "–")
                }
            }
            if (t.type == TripType.UNAUTHORIZED) SectionCard("Unapproved movement", accent = DangerRed) {
                InfoRow("Reason", t.reason.ifBlank { "NOT GIVEN" }, if (t.reason.isBlank()) DangerRed else Color.Unspecified)
                t.reasonAt?.let { InfoRow("Given at", TripCalc.dateTime(it)) }
                if (t.reviewStatus.isNotBlank()) InfoRow("Review", "${t.reviewStatus} by ${t.reviewedBy}")
                if (s.canApprove && t.reason.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Repo.reviewReason(t.id, true, s.me.name) }) { Text("Accept reason") }
                    OutlinedButton(onClick = { Repo.reviewReason(t.id, false, s.me.name) }) { Text("Flag", color = DangerRed) }
                }
                if (s.me.isDriver && t.needsReason) Button(onClick = { s.nav.go(Screen.Reasons) }) { Text("Enter reason") }
            }
            if (requests.isNotEmpty()) {
                SectionHeader("Requests on this trip")
                requests.forEach { RequestCard(it, s) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

// ------------------------------------------------------------------ live map

@Composable
fun LiveMapScreen(s: Session) {
    val live by remember { Repo.liveFlow() }.collectAsState(initial = null)
    var path by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    val l = live
    LaunchedEffect(l?.tripId) {
        val id = l?.tripId.orEmpty()
        while (id.isNotBlank()) { path = Repo.trackPoints(id); delay(30_000) }
        path = emptyList()
    }
    Scaffold(topBar = { AppBar("Live van location", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val pins = buildList {
                if (l != null && l.updatedAt > 0) add(MapPin(l.lat, l.lng, "Van"))
                if (s.settings.hotelSet) add(MapPin(s.settings.hotelLat, s.settings.hotelLng, s.settings.hotelName))
            }
            VanMap(Modifier.fillMaxWidth().weight(1f), path = path, pins = pins, fitKey = "live-${l?.tripId}")
            if (l != null) SectionCard {
                InfoRow("Status", when (l.mode) { "TRIP" -> "On trip"; "UNAUTHORIZED" -> "Moving without approval"; "GUARD" -> "Parked"; else -> l.mode },
                    if (l.mode == "UNAUTHORIZED") DangerRed else Color.Unspecified)
                if (l.tripTitle.isNotBlank()) InfoRow("Trip", l.tripTitle)
                InfoRow("Speed", "${l.speedKmh.toInt()} km/h")
                InfoRow("Updated", if (l.updatedAt > 0) TripCalc.dateTime(l.updatedAt) else "never")
                if (l.battery >= 0) InfoRow("Van phone battery", "${l.battery}%" + if (l.charging) " (charging)" else "")
                if (l.tripId.isNotBlank()) TextButton(onClick = { s.nav.go(Screen.TripDetail(l.tripId)) }) { Text("Open trip") }
            }
        }
    }
}

// ------------------------------------------------------------------ alerts

private fun LazyListScope.alertsList(s: Session, alerts: List<AlertItem>) {
    if (alerts.isEmpty()) item { Empty("No alerts.") }
    items(alerts, key = { it.id }) { a ->
        val color = when (a.severity) { "HIGH" -> DangerRed; "WARN" -> WarnOrange; else -> InfoBlue }
        SectionCard(a.title, accent = if (a.acknowledged) Separator else color,
            onClick = if (a.tripId.isNotBlank()) ({ s.nav.go(Screen.TripDetail(a.tripId)) }) else null) {
            Text(a.message, fontSize = 14.sp)
            Text(TripCalc.dateTime(a.createdAt), fontSize = 12.sp, color = TextSecondary)
            if (a.acknowledged) Text("✔ ${a.acknowledgedBy}", fontSize = 12.sp, color = OkGreen)
            else TextButton(onClick = { Repo.ackAlert(a.id, s.me.name) }) { Text("Acknowledge") }
        }
    }
}

@Composable
fun AlertsScreen(s: Session) {
    val alerts by remember { Repo.alertsFlow() }.collectAsState(initial = emptyList())
    Scaffold(topBar = { AppBar("Alerts", onBack = { s.nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad)) { alertsList(s, alerts) }
    }
}

// ------------------------------------------------------------------ more

private fun LazyListScope.moreMenu(s: Session) {
    item { MoreBody(s) }
}

@Composable
private fun MoreBody(s: Session) {
    val ctx = LocalContext.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(Cinnamon.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Text(s.me.name.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""),
                    color = Cinnamon, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(s.me.name, style = MaterialTheme.typography.titleMedium)
                Text(s.me.role.label + if (s.me.isAdmin && s.me.role != Role.ADMIN) " · Admin" else "",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text("${s.me.department} · ${s.me.email}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    SectionHeader("Van")
    GroupCard {
        ListRow("🗺", "Live van location", last = !s.canApprove) { s.nav.go(Screen.LiveMap) }
        if (s.canApprove) {
            ListRow("📊", "Reports & costs") { s.nav.go(Screen.Reports) }
            ListRow("📝", "My own requests") { s.nav.go(Screen.MyRequests) }
            ListRow("⚠️", "Movements waiting for a reason", last = true) { s.nav.go(Screen.Reasons) }
        }
    }
    if (s.me.isAdmin) {
        SectionHeader("Administration")
        GroupCard {
            ListRow("⚙️", "Van, fuel & tracking settings") { s.nav.go(Screen.AdminSettings) }
            ListRow("👥", "Users & roles") { s.nav.go(Screen.Users) }
            ListRow("🔁", "Recurring schedules") { s.nav.go(Screen.Schedules) }
            ListRow("⛽", "Fuel fill-up log") { s.nav.go(Screen.FuelLogs) }
            ListRow("📲", "Share setup code with other phones", last = true) { s.nav.go(Screen.ShareSetup) }
        }
    }
    SectionHeader("Appearance")
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { AppearancePicker() }
    AboutCard()
    GroupCard { ListRow("🚪", "Sign out", last = true, tint = DangerRed) { signOut(ctx) } }
}
