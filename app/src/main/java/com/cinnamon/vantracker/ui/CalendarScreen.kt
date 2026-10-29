package com.cinnamon.vantracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.KIND_DELIVERY
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Schedule
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.tracking.TripCalc
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val DAY = 24 * 3600_000L

/** A trip counts as "away" (vehicle not available for local runs) when it lasts 6 h or more. */
private fun Trip.isAway(): Boolean = busyUntil - plannedStart() >= 6 * 3600_000L
private fun Trip.plannedStart(): Long = plannedTime.takeIf { it > 0 } ?: startTime ?: createdAt
private fun Trip.touches(dayStart: Long) = plannedStart() < dayStart + DAY && busyUntil > dayStart

private fun monthStart(t: Long): Long = Calendar.getInstance().apply {
    timeInMillis = t; set(Calendar.DAY_OF_MONTH, 1)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun addMonths(t: Long, n: Int): Long = Calendar.getInstance().apply { timeInMillis = t; add(Calendar.MONTH, n) }.timeInMillis

@Composable
fun CalendarScreen(s: Session) {
    Scaffold(topBar = { AppBar("Vehicle calendar", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            CalendarBody(s)
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Month view of the vehicles: booked trips, long / all-day trips (e.g. Colombo) and the
 * recurring runs. Tapping a day lists what is planned, with "I'm going too", "Book my seat"
 * and "Bring something back" actions.
 */
@Composable
fun CalendarBody(s: Session) {
    val b = Brand.colors
    val today = TripCalc.dayStart(System.currentTimeMillis())
    var month by remember { mutableLongStateOf(monthStart(today)) }
    var selected by remember { mutableLongStateOf(today) }
    // Load from the start of the shown month (minus a week so multi-day trips show up).
    val from = month - 7 * DAY
    val trips by remember(from) { Repo.tripsPlannedFromFlow(from) }.collectAsState(initial = emptyList())
    val schedules by remember { Repo.schedulesFlow() }.collectAsState(initial = emptyList())

    fun runsOn(dayStart: Long): List<Schedule> {
        val key = Repo.dateKey(dayStart)
        return schedules.filter { sc ->
            sc.active && TripCalc.isoDay(dayStart) in sc.days && trips.none { it.id == Repo.scheduleTripId(sc.id, key) }
        }
    }

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { month = addMonths(month, -1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous month", tint = b.accent) }
            Text(
                SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(month)),
                style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { month = addMonths(month, 1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next month", tint = b.accent) }
        }
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, fontSize = 12.sp, color = TextSecondary)
            }
        }
        val cal = Calendar.getInstance().apply { timeInMillis = month }
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val lead = TripCalc.isoDay(month) - 1
        val cells = lead + daysInMonth
        for (week in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val i = week * 7 + col - lead
                    if (i < 0 || i >= daysInMonth) { Spacer(Modifier.weight(1f)); continue }
                    val day = TripCalc.dayStart(month + i * DAY + 3 * 3600_000L) // +3h survives DST shifts
                    val onDay = trips.filter { it.touches(day) }
                    val away = onDay.any { it.isAway() }
                    val isSel = day == selected
                    Column(
                        Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                when {
                                    isSel -> b.accent
                                    away -> WarnOrange.copy(alpha = 0.22f)
                                    else -> Color.Transparent
                                }
                            )
                            .then(if (day == today && !isSel) Modifier.border(1.dp, b.accent, RoundedCornerShape(10.dp)) else Modifier)
                            .clickable { selected = day },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            "${i + 1}", fontSize = 14.sp,
                            fontWeight = if (day == today) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSel) Color.White else if (day < today) TextSecondary else b.text,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (onDay.any { !it.isAway() }) Dot(if (isSel) Color.White else InfoBlue)
                            if (away) Dot(if (isSel) Color.White else WarnOrange)
                            if (runsOn(day).isNotEmpty()) Dot(if (isSel) Color.White else Cinnamon)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Legend(InfoBlue, "Booked"); Legend(WarnOrange, "Away / long trip"); Legend(Cinnamon, "Regular run")
        }
    }

    DayAgenda(s, selected, trips.filter { it.touches(selected) }, runsOn(selected))
}

@Composable
private fun Dot(c: Color) = Box(Modifier.size(5.dp).clip(CircleShape).background(c))

@Composable
private fun Legend(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Dot(c); Spacer(Modifier.size(4.dp)); Text(label, fontSize = 11.sp, color = TextSecondary)
}

@Composable
private fun DayAgenda(s: Session, day: Long, trips: List<Trip>, runs: List<Schedule>) {
    val now = System.currentTimeMillis()
    val past = day + DAY < now
    SectionHeader(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(day)))
    if (trips.isEmpty() && runs.isEmpty()) {
        SectionCard {
            Text("Nothing booked – the vehicles are free this day.", color = TextSecondary)
        }
    }
    trips.forEach { t ->
        val away = t.isAway()
        SectionCard(accent = if (away) WarnOrange else InfoBlue, onClick = { s.nav.go(Screen.TripDetail(t.id)) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title.ifBlank { t.purpose.ifBlank { t.category } }, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip(if (away) "Away" else t.status.label, if (away) WarnOrange else InfoBlue)
            }
            InfoRow("Leaves", TripCalc.dateTime(t.plannedStart()))
            InfoRow(if (t.endTime != null) "Returned" else "Back by", TripCalc.dateTime(t.busyUntil))
            t.stops.lastOrNull()?.let { InfoRow("Going to", t.stops.joinToString(" → ") { it.name }) }
            if (t.vehicleName.isNotBlank()) InfoRow("Vehicle", t.vehicleName)
            if (t.driverName.isNotBlank()) InfoRow("Driver", t.driverName)
            if (t.passengers > 0) InfoRow("Passengers", t.passengers.toString())
            val open = t.status == TripStatus.READY || t.status == TripStatus.ASSIGNED
            if (open && !past && !s.me.isDriver) {
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { s.nav.go(Screen.NewRequest("trip:${t.id}", t.title, t.plannedStart())) }) { Text("I'm going too") }
                    OutlinedButton(onClick = {
                        s.nav.go(Screen.NewRequest("trip:${t.id}", t.title, t.plannedStart(), KIND_DELIVERY))
                    }) { Text("📦 Bring something") }
                }
            }
        }
    }
    runs.forEach { sc ->
        val key = Repo.dateKey(day)
        val at = Repo.plannedTimeFor(sc, key)
        SectionCard(accent = Cinnamon) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sc.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Chip("Regular run", Cinnamon)
            }
            InfoRow("Departs", sc.time)
            sc.destination?.let { InfoRow("Goes to", it.name) }
            if (at > now && !s.me.isDriver) {
                Spacer(Modifier.height(6.dp))
                Button(onClick = { s.nav.go(Screen.NewRequest(Repo.scheduleTarget(sc, key), "${sc.title} (${sc.time})")) }) { Text("Book my seat") }
            }
        }
    }
    if (!past && !s.me.isDriver) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val preset = maxOf(day + 8 * 3600_000L, now + 30 * 60_000L)
            OutlinedButton(onClick = { s.nav.go(Screen.NewRequest(presetTime = preset)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Request a vehicle for this day")
            }
            OutlinedButton(onClick = { s.nav.go(Screen.NewRequest(presetTime = preset, kind = KIND_DELIVERY)) }, modifier = Modifier.fillMaxWidth()) {
                Text("📦 Request a pickup / delivery")
            }
        }
    }
}
