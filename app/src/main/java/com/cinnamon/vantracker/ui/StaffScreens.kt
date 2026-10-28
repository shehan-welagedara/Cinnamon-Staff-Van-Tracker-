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

@Composable
fun StaffHome(s: Session) {
    val pending by remember { Repo.pendingRequestsFlow() }.collectAsState(initial = emptyList())
    val alerts by remember { Repo.alertsFlow() }.collectAsState(initial = emptyList())
    val openAlerts = alerts.count { !it.acknowledged }
    val tabs = if (s.canApprove) listOf(
        GlassTab("Home", Icons.Filled.Home), GlassTab("Approvals", Icons.Filled.CheckCircle, pending.size),
        GlassTab("Trips", Icons.Filled.List), GlassTab("Alerts", Icons.Filled.Notifications, openAlerts), GlassTab("More", Icons.Filled.Menu),
    ) else listOf(
        GlassTab("Home", Icons.Filled.Home), GlassTab("My requests", Icons.Filled.CheckCircle),
        GlassTab("Trips", Icons.Filled.List), GlassTab("More", Icons.Filled.Menu),
    )
    val online = rememberOnline()
    val tab = s.nav.homeTab.coerceIn(0, tabs.lastIndex)
    val label = tabs[tab].label
    GlassScaffold(
        topBar = { AppBar(if (label == "Home") "Transport" else label) },
        bottomBar = { GlassTabBar(tabs, tab) { s.nav.homeTab = it } },
        floatingActionButton = {
            if (label == "Home" || label == "My requests") ExtendedFloatingActionButton(
                onClick = { s.nav.go(Screen.NewRequest()) },
                icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Request vehicle") },
                containerColor = Cinnamon, contentColor = MaterialTheme.colorScheme.onPrimary, shape = leafShape(28.dp, 8.dp),
            )
        },
    ) { pad ->
        // Content scrolls beneath the glass bars, so they blur it.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
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
            item { Spacer(Modifier.height(64.dp)) }
        }
    }
}

private fun LazyListScope.homeTab(s: Session, pendingCount: Int, openAlerts: Int) {
    item { FleetCard(s) }
    if (s.canApprove && (pendingCount > 0 || openAlerts > 0)) item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (pendingCount > 0) Button(onClick = { s.nav.homeTab = 1 }, modifier = Modifier.weight(1f)) { Text("$pendingCount to approve") }
            if (openAlerts > 0) Button(
                onClick = { s.nav.homeTab = 3 }, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
            ) { Text("$openAlerts alert(s)") }
        }
    }
    item { DriversCard() }
    item { UpcomingTrips(s) }
    item { MyRecentRequests(s) }
}

/** Who can drive right now: on trip / on duty / off duty, with a tap-to-call number. */
@Composable
private fun DriversCard() {
    val ctx = LocalContext.current
    val drivers by remember { Repo.driversFlow() }.collectAsState(initial = emptyList())
    val open by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    if (drivers.isEmpty()) return
    SectionHeader("Drivers")
    GroupCard {
        drivers.forEachIndexed { i, d ->
            val onTrip = open.firstOrNull { it.status == TripStatus.IN_PROGRESS && it.driverUid == d.uid }
            val assigned = open.count { it.status == TripStatus.ASSIGNED && it.driverUid == d.uid }
            val (status, color) = when {
                onTrip != null -> "On trip" to OkGreen
                d.onDuty -> "On duty" to InfoBlue
                else -> "Off duty" to TextSecondary
            }
            Row(
                Modifier.fillMaxWidth().clickable(enabled = d.phone.isNotBlank()) {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:${d.phone}"))) }
                }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            onTrip != null -> onTrip.title
                            assigned > 0 -> "$assigned trip(s) accepted"
                            d.dutyChangedAt > 0 -> (if (d.onDuty) "Since " else "Last on duty ") + TripCalc.dateTime(d.dutyChangedAt)
                            else -> d.phone
                        },
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                    )
                }
                Chip(status, color)
            }
            if (i < drivers.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(0.5.dp).background(Separator))
        }
    }
}

@Composable
private fun UpcomingTrips(s: Session) {
    val open by remember { Repo.openTripsFlow() }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    val today = remember { TripCalc.dayStart(System.currentTimeMillis()) }
    val todayTrips by remember { Repo.tripsSinceFlow(today) }.collectAsState(initial = emptyList())
    val mine by remember(s.me.uid) { Repo.myRequestsFlow(s.me.uid) }.collectAsState(initial = emptyList())
    val dayKey = Repo.dateKey()
    val runs = schedules.filter { it.active && TripCalc.isoDay(System.currentTimeMillis()) in it.days }.sortedBy { it.time }

    SectionHeader("Today's scheduled runs – book a seat")
    if (runs.isEmpty()) Empty("No scheduled runs today.")
    runs.forEach { sc ->
        val tripId = Repo.scheduleTripId(sc.id, dayKey)
        val trip = open.firstOrNull { it.id == tripId } ?: todayTrips.firstOrNull { it.id == tripId }
        val target = Repo.scheduleTarget(sc, dayKey)
        val myBooking = mine.firstOrNull { it.joinTarget == target && it.status != RequestStatus.CANCELLED && it.status != RequestStatus.REJECTED }
        val departed = trip != null && !trip.isOpen
        val (status, color) = when {
            trip == null -> "Seats open" to OkGreen
            trip.status == TripStatus.IN_PROGRESS -> "On the road" to InfoBlue
            trip.status == TripStatus.COMPLETED -> "Completed" to TextSecondary
            trip.status == TripStatus.CANCELLED -> "Cancelled" to TextSecondary
            else -> "Seats open" to OkGreen
        }
        SectionCard(accent = Cinnamon, onClick = trip?.let { { s.nav.go(Screen.TripDetail(it.id)) } }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sc.title, style = MaterialTheme.typography.titleMedium)
                    Text("Departs ${sc.time}" + (sc.destination?.name?.let { " · to $it" } ?: ""), color = TextSecondary, fontSize = 13.sp)
                }
                Chip(status, color)
            }
            if (sc.stops.isNotEmpty()) Text("Via: " + sc.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
            if (trip != null) {
                InfoRow("Booked passengers", trip.passengers.toString())
                if (trip.driverName.isNotBlank()) InfoRow("Driver", trip.driverName)
            }
            if (myBooking != null) {
                Text("✓ Your seat is booked" + (myBooking.stops.firstOrNull()?.name?.let { " – drop-off: $it" } ?: ""),
                    color = OkGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            } else if (!departed) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = { s.nav.go(Screen.NewRequest(target, "${sc.title} (${sc.time})")) }) { Text("Book my seat") }
            }
        }
    }

    val others = open.filter { it.type != TripType.SCHEDULED }
    if (others.isNotEmpty()) {
        SectionHeader("Other trips – join one going your way")
        others.forEach { t ->
            TripCard(t, s, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) {
                if (t.isOpen || t.status == TripStatus.IN_PROGRESS) {
                    OutlinedButton(onClick = { s.nav.go(Screen.NewRequest("trip:${t.id}", t.title)) }) { Text("+ Add my stop to this trip") }
                }
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
        InfoRow("Passenger(s)", r.travellers)
        if (r.passengerNames.isNotBlank() && r.passengerNames != r.requesterName) InfoRow("Requested by", r.requesterName)
        InfoRow("When", TripCalc.dateTime(r.requestedTime))
        InfoRow("Passengers", r.passengers.toString())
        if (r.stops.isNotEmpty()) Text("Stops: " + r.stops.joinToString(" → ") { it.name }, fontSize = 13.sp)
        if (r.joinTargetLabel.isNotBlank()) Text(
            (if (r.joinTarget.startsWith("sched:")) "Seat on: " else "Wants to join: ") + r.joinTargetLabel, color = InfoBlue, fontSize = 13.sp,
        )
        if (r.notes.isNotBlank()) Text("Notes: ${r.notes}", fontSize = 13.sp, color = TextSecondary)
        if (r.approverName.isNotBlank()) Text(
            "${r.status.label} by ${r.approverName}" + (if (r.decisionComment.isNotBlank()) ": “${r.decisionComment}”" else ""),
            fontSize = 13.sp, color = TextSecondary,
        )
        when {
            r.driverName.isNotBlank() -> InfoRow("Driver", r.driverName, OkGreen)
            r.status == RequestStatus.APPROVED && r.joinTarget.startsWith("sched:") -> Text("Seat confirmed", fontSize = 13.sp, color = OkGreen)
            r.status == RequestStatus.APPROVED -> Text("Waiting for a driver to accept…", fontSize = 13.sp, color = WarnOrange)
        }
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
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())
    // "sched:<id>:<day>" = booking a seat on a pre-approved scheduled run.
    val schedParts = if (joinTarget.startsWith("sched:")) joinTarget.split(":") else null
    val schedule = schedParts?.let { p -> schedules.firstOrNull { it.id == p.getOrNull(1) } }
    if (schedParts != null) {
        if (schedule == null) {
            Scaffold(topBar = { AppBar("Book a seat", onBack = { s.nav.back() }) }) { pad -> Box(Modifier.padding(pad)) { Empty("Loading run…") } }
        } else {
            BookSeatScreen(s, schedule, schedParts.getOrNull(2) ?: Repo.dateKey())
        }
        return
    }

    var category by remember { mutableStateOf(CATEGORIES.first()) }
    var purpose by remember { mutableStateOf("") }
    val stops = remember { mutableStateListOf(Stop()) }
    var time by remember { mutableLongStateOf(System.currentTimeMillis() + 30 * 60_000) }
    var passengers by remember { mutableStateOf("1") }
    var passengerNames by remember { mutableStateOf(s.me.name) }
    var notes by remember { mutableStateOf("") }
    val options = rememberJoinOptions()
    var target by remember { mutableStateOf(joinTarget) }
    var targetLabel by remember { mutableStateOf(joinLabel) }
    var autoApprove by remember { mutableStateOf(false) }

    Scaffold(topBar = { AppBar("Request a vehicle", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Picker("Category", category, CATEGORIES) { category = it }
            OutlinedTextField(purpose, { purpose = it }, label = { Text("Business purpose") }, modifier = Modifier.fillMaxWidth())
            SectionHeader("Destination / stops")
            StopsEditor(stops, s, label = { if (it == 0) "Destination" else "Stop ${it + 1}" })
            OutlinedButton(onClick = { pickDateTime(ctx, time) { time = it } }, modifier = Modifier.fillMaxWidth()) {
                Text("Departure: ${TripCalc.dateTime(time)}")
            }
            OutlinedTextField(passengerNames, { passengerNames = it }, label = { Text("Passenger name(s)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(passengers, { passengers = it.filter(Char::isDigit) }, label = { Text("Number of passengers") },
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
                        joinTarget = target, joinTargetLabel = targetLabel, passengerNames = passengerNames.trim(),
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
}

/** Book a seat on a scheduled run (e.g. night transport) with your own drop-off point. */
@Composable
private fun BookSeatScreen(s: Session, sc: Schedule, day: String) {
    val stops = remember { mutableStateListOf(Stop()) }
    var passengerNames by remember { mutableStateOf(s.me.name) }
    var seats by remember { mutableStateOf("1") }
    var notes by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val instant = s.settings.autoApproveScheduled
    Scaffold(topBar = { AppBar("Book a seat", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            SectionCard(sc.title, accent = Cinnamon) {
                InfoRow("Departs", "${sc.time}, ${if (day == Repo.dateKey()) "today" else day}")
                sc.destination?.let { InfoRow("Goes to", it.name) }
                if (sc.stops.isNotEmpty()) InfoRow("Via", sc.stops.joinToString(" → ") { it.name })
                Text(
                    "The run goes to its planned destination; your own drop-off point is added to the driver's route.",
                    color = TextSecondary, fontSize = 13.sp,
                )
            }
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Where should the driver drop you?")
                StopsEditor(stops, s, label = { "Drop-off point (search or tap the map)" }, allowAdd = false)
                if (sc.destination != null) TextButton(onClick = { stops[0] = sc.destination.copy(requestId = "", arrivedAt = null) }) {
                    Text("Same as the run: ${sc.destination.name}")
                }
                OutlinedTextField(passengerNames, { passengerNames = it }, label = { Text("Passenger name(s)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(seats, { seats = it.filter(Char::isDigit) }, label = { Text("Seats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Note for the driver (optional)") }, modifier = Modifier.fillMaxWidth())
                Button(
                    enabled = !saving && stops.first().name.isNotBlank() && passengerNames.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    onClick = {
                        saving = true
                        val r = TripRequest(
                            requesterUid = s.me.uid, requesterName = s.me.name, department = s.me.department,
                            category = sc.category.ifBlank { "Staff Transport" }, purpose = sc.purpose.ifBlank { sc.title },
                            stops = stops.filter { it.name.isNotBlank() }, requestedTime = Repo.plannedTimeFor(sc, day),
                            passengers = seats.toIntOrNull()?.coerceAtLeast(1) ?: 1, notes = notes.trim(),
                            joinTarget = Repo.scheduleTarget(sc, day), joinTargetLabel = "${sc.title} (${sc.time})",
                            passengerNames = passengerNames.trim(),
                        )
                        if (instant) AppScope.launch { Repo.bookScheduledSeat(r, sc, day) } else Repo.submitRequest(r)
                        s.nav.back()
                    },
                ) { Text(if (instant) "Confirm my seat" else "Send for approval") }
                Text(
                    if (instant) "Scheduled runs are pre-approved – your seat is confirmed straight away and the driver sees your drop-off."
                    else "Your GM / Operations Manager will confirm the seat.",
                    color = TextSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
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
    val drivers by remember { Repo.driversFlow() }.collectAsState(initial = emptyList())
    val d = dialog ?: return
    var comment by remember(d) { mutableStateOf("") }
    var target by remember(d) { mutableStateOf(r.joinTarget.ifBlank { suggestion?.let { "trip:${it.id}" } ?: "" }) }
    // "" = alert all drivers, first to accept; otherwise a driver uid.
    var driverUid by remember(d) { mutableStateOf("") }
    val anyDriver = "Any driver (first to accept)"
    val driverOptions = listOf(anyDriver) + drivers.map { it.name + if (it.onDuty) " · on duty" else " · off duty" }
    val vehicles = rememberVehicles(s)
    var vehicleId by remember(d) { mutableStateOf("") }
    val anyVehicle = "Driver chooses when starting"
    val vehicleOptions = listOf(anyVehicle) + vehicles.map { it.label }
    AlertDialog(
        onDismissRequest = { dialog = null },
        title = { Text(if (d) "Approve request" else "Reject request") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.requesterName}: ${r.purpose}")
                if (d) Picker("Assign to", options.firstOrNull { it.first == target }?.second ?: "New separate trip", options.map { it.second }) { l ->
                    target = options.first { it.second == l }.first
                }
                // Driver choice only applies to a new trip; combined trips keep their driver.
                if (d && target.isBlank()) {
                    val idx = drivers.indexOfFirst { it.uid == driverUid }
                    Picker("Driver", if (idx >= 0) driverOptions[idx + 1] else anyDriver, driverOptions) { l ->
                        val i = driverOptions.indexOf(l)
                        driverUid = if (i <= 0) "" else drivers[i - 1].uid
                    }
                    if (vehicles.size > 1) {
                        Picker("Vehicle", vehicles.firstOrNull { it.id == vehicleId }?.label ?: anyVehicle, vehicleOptions) { l ->
                            vehicleId = vehicles.firstOrNull { it.label == l }?.id ?: ""
                        }
                    }
                }
                OutlinedTextField(comment, { comment = it }, label = { Text(if (d) "Comment (optional)" else "Reason for rejection") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = d || comment.isNotBlank(), onClick = {
                val me = s.me
                val driver = drivers.firstOrNull { it.uid == driverUid }.takeIf { target.isBlank() }
                val vehicle = vehicles.firstOrNull { it.id == vehicleId }.takeIf { target.isBlank() }
                if (d) AppScope.launch { Repo.approveRequest(r, me, comment.trim(), target.ifBlank { null }, schedules, driver, vehicle) }
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
    val vehicles = rememberVehicles(s, includeInactive = true)
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
                follow = t.status == TripStatus.IN_PROGRESS,
                vehicleTitle = t.vehicleName.ifBlank { "Vehicle" },
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
                    TextButton(onClick = { Repo.recalcTrip(t, s.settings, t.gpsDistanceKm,
                        vehicles.firstOrNull { it.id == t.vehicleId.ifBlank { com.cinnamon.vantracker.data.Vehicle.DEFAULT_ID } }) }) { Text("Recalculate with current fuel settings") }
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
    val vehicles = rememberVehicles(s)
    val live by remember { Repo.liveAllFlow() }.collectAsState(initial = emptyList())
    val byId = live.associateBy { it.vehicleId }
    // Follow one vehicle's route, or show the whole fleet.
    var focus by remember { mutableStateOf("") }
    val focused = byId[focus]
    var path by remember { mutableStateOf<List<TrackPoint>>(emptyList()) }
    LaunchedEffect(focused?.tripId) {
        val id = focused?.tripId.orEmpty()
        path = emptyList()
        while (id.isNotBlank()) { path = Repo.trackPoints(id); delay(30_000) }
    }
    Scaffold(topBar = { AppBar("Live map", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            val pins = buildList {
                vehicles.forEach { v ->
                    byId[v.id]?.let { l -> add(MapPin(l.lat, l.lng, v.label, vehicle = true, bearing = l.bearing, snippet = liveLabel(l))) }
                }
                if (s.settings.hotelSet) add(MapPin(s.settings.hotelLat, s.settings.hotelLng, s.settings.hotelName))
            }
            VanMap(
                Modifier.fillMaxWidth().weight(1f),
                path = if (focused != null) path else emptyList(),
                pins = if (focused != null) pins.filter { !it.vehicle || it.title == vehicles.firstOrNull { v -> v.id == focus }?.label } else pins,
                fitKey = "live-$focus-${pins.size}",
            )
            LazyColumn(Modifier.weight(0.8f)) {
                item {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = focus.isBlank(), onClick = { focus = "" }, label = { Text("All vehicles") })
                    }
                }
                items(vehicles, key = { it.id }) { v ->
                    val l = byId[v.id]
                    SectionCard(onClick = { focus = if (focus == v.id) "" else v.id }, accent = if (focus == v.id) Cinnamon else null) {
                        FleetRow(v, l, last = true)
                        if (l != null) {
                            if (l.tripTitle.isNotBlank()) InfoRow("Ride", l.tripTitle)
                            if (l.battery >= 0 && l.onTrip) InfoRow("Driver phone battery", "${l.battery}%" + if (l.charging) " (charging)" else "")
                            if (l.tripId.isNotBlank()) TextButton(onClick = { s.nav.go(Screen.TripDetail(l.tripId)) }) { Text("Open ride") }
                        }
                    }
                }
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
        ListRow("🗺", "Live map", last = !s.canApprove) { s.nav.go(Screen.LiveMap) }
        if (s.canApprove) {
            ListRow("📊", "Reports & costs") { s.nav.go(Screen.Reports) }
            ListRow("📝", "My own requests") { s.nav.go(Screen.MyRequests) }
            ListRow("⚠️", "Movements waiting for a reason", last = true) { s.nav.go(Screen.Reasons) }
        }
    }
    if (s.me.isAdmin) {
        SectionHeader("Administration")
        GroupCard {
            ListRow("🚐", "Vehicles") { s.nav.go(Screen.Vehicles) }
            ListRow("⚙️", "Fuel, hotel & tracking settings") { s.nav.go(Screen.AdminSettings) }
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
