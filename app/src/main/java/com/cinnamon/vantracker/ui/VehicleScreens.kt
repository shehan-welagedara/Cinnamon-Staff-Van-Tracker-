package com.cinnamon.vantracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.FUEL_TYPES
import com.cinnamon.vantracker.data.LiveStatus
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.data.VEHICLE_TYPES
import com.cinnamon.vantracker.data.Vehicle
import com.cinnamon.vantracker.tracking.TripCalc

/**
 * The hotel's vehicles. Until the admin adds any, the single van from the old settings is
 * used, so nothing breaks for existing installs.
 */
@Composable
fun rememberVehicles(s: Session, includeInactive: Boolean = false): List<Vehicle> {
    val stored by remember { Repo.vehiclesFlow() }.collectAsState(initial = emptyList())
    val list = stored.ifEmpty { listOf(Vehicle.fallback(s.settings)) }
    return if (includeInactive) list else list.filter { it.active }
}

/** Vehicle id → driver name, for vehicles currently out on a ride. */
fun busyVehicles(open: List<Trip>): Map<String, String> = open
    .filter { it.status == TripStatus.IN_PROGRESS }
    .associate { it.vehicleId.ifBlank { Vehicle.DEFAULT_ID } to it.driverName }

fun vehicleIcon(type: String) = when (type) {
    "Bus", "Mini Bus" -> "🚌"
    "Lorry" -> "🚚"
    "Car", "SUV" -> "🚗"
    "Motorbike" -> "🏍"
    "Three-wheeler" -> "🛺"
    else -> "🚐"
}

// ------------------------------------------------------------------ choose a vehicle (driver)

@Composable
fun VehiclePickerDialog(
    title: String,
    vehicles: List<Vehicle>,
    busy: Map<String, String>,
    preselect: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onPick: (Vehicle) -> Unit,
) {
    val free = vehicles.filter { it.id !in busy }
    var chosen by remember { mutableStateOf(free.firstOrNull { it.id == preselect }?.id ?: free.firstOrNull()?.id ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Which vehicle are you driving?", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                vehicles.forEach { v ->
                    val takenBy = busy[v.id]
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = takenBy == null) { chosen = v.id }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == v.id, onClick = { chosen = v.id }, enabled = takenBy == null)
                        Column(Modifier.weight(1f)) {
                            Text("${vehicleIcon(v.type)}  ${v.label}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (takenBy != null) "Out with $takenBy" else "${v.type}${if (v.seats > 0) " · ${v.seats} seats" else ""} · ${v.fuelType}",
                                color = if (takenBy != null) WarnOrange else TextSecondary, style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                if (free.isEmpty()) Text("All vehicles are out right now.", color = DangerRed)
            }
        },
        confirmButton = {
            Button(enabled = chosen.isNotBlank(), onClick = { vehicles.firstOrNull { it.id == chosen }?.let(onPick) }) { Text(confirmText) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ fleet now (staff home / live map)

/** Every vehicle with its live state; vehicles with a position show as cars on the map. */
@Composable
fun FleetCard(s: Session, showMap: Boolean = true, mapHeightDp: Int = 180) {
    val vehicles = rememberVehicles(s)
    val live by remember { Repo.liveAllFlow() }.collectAsState(initial = emptyList())
    val byId = live.associateBy { it.vehicleId }
    val pins = vehicles.mapNotNull { v ->
        byId[v.id]?.let { l -> MapPin(l.lat, l.lng, v.label, vehicle = true, bearing = l.bearing, snippet = liveLabel(l)) }
    }
    SectionCard(if (vehicles.size > 1) "Vehicles now" else "${vehicles.firstOrNull()?.name ?: "Vehicle"} now", onClick = { s.nav.go(Screen.LiveMap) }) {
        if (showMap && pins.isNotEmpty()) {
            VanMap(Modifier.fillMaxWidth().height(mapHeightDp.dp).padding(bottom = 8.dp), pins = pins, fitKey = "fleet-${pins.size}")
        }
        vehicles.forEachIndexed { i, v -> FleetRow(v, byId[v.id], last = i == vehicles.lastIndex) }
    }
}

fun liveLabel(l: LiveStatus): String = when {
    l.mode == "TRIP" -> "On ride with ${l.driverName.ifBlank { "driver" }}"
    l.mode == "UNAUTHORIZED" -> "Moving WITHOUT approval"
    else -> "Parked"
}

@Composable
fun FleetRow(v: Vehicle, l: LiveStatus?, last: Boolean) {
    val (status, color) = when {
        l == null -> "No position yet" to TextSecondary
        l.mode == "TRIP" -> "On ride" to OkGreen
        l.mode == "UNAUTHORIZED" -> "Unapproved" to DangerRed
        else -> "Parked" to InfoBlue
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(vehicleIcon(v.type), fontSize = 20.sp, modifier = Modifier.padding(end = 10.dp))
        Column(Modifier.weight(1f)) {
            Text(v.label, style = MaterialTheme.typography.titleSmall)
            if (l != null) Text(
                buildString {
                    if (l.onTrip) append("${l.driverName.ifBlank { "Driver" }} · ${l.speedKmh.toInt()} km/h · ")
                    else if (l.driverName.isNotBlank()) append("Last: ${l.driverName} · ")
                    append(TripCalc.dateTime(l.updatedAt))
                },
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
            )
        }
        Chip(status, color)
    }
    if (!last) Box(Modifier.fillMaxWidth().height(0.5.dp).background(Separator))
}

// ------------------------------------------------------------------ admin: vehicles

@Composable
fun VehiclesScreen(s: Session) {
    val stored by remember { Repo.vehiclesFlow() }.collectAsState(initial = emptyList())
    var edit by remember { mutableStateOf<Vehicle?>(null) }
    Scaffold(
        topBar = { AppBar("Vehicles", onBack = { s.nav.back() }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { edit = Vehicle(kmPerLiter = s.settings.kmPerLiter) }, containerColor = Cinnamon,
                contentColor = MaterialTheme.colorScheme.onPrimary) { Icon(Icons.Filled.Add, "Add vehicle") }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            item {
                Text(
                    "Add every hotel vehicle once. Drivers pick the vehicle when they start a ride, fuel cost uses that " +
                        "vehicle's km/L and fuel type, and each vehicle appears on the live map.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp),
                )
            }
            if (stored.isEmpty()) item {
                val legacy = Vehicle.fallback(s.settings)
                SectionCard("Start with your current van") {
                    Text("Trips so far were recorded for “${legacy.label}”. Save it as the first vehicle, then add the others.",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { edit = legacy }) { Text("Set up ${legacy.name}") }
                }
            }
            items(stored, key = { it.id }) { v ->
                SectionCard(onClick = { edit = v }, accent = if (v.active) OkGreen else TextSecondary) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${vehicleIcon(v.type)}  ${v.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (!v.active) Chip("Inactive", TextSecondary)
                    }
                    InfoRow("Type", v.type + if (v.seats > 0) " · ${v.seats} seats" else "")
                    InfoRow("Fuel", "${v.fuelType} · ${v.kmPerLiter} km/L")
                    if (v.notes.isNotBlank()) Text(v.notes, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    edit?.let { v -> VehicleEditDialog(v) { edit = null } }
}

@Composable
private fun VehicleEditDialog(v: Vehicle, onClose: () -> Unit) {
    var name by remember(v) { mutableStateOf(v.name) }
    var plate by remember(v) { mutableStateOf(v.plateNo) }
    var type by remember(v) { mutableStateOf(v.type) }
    var seats by remember(v) { mutableStateOf(if (v.seats > 0) v.seats.toString() else "") }
    var fuel by remember(v) { mutableStateOf(v.fuelType) }
    var kmpl by remember(v) { mutableStateOf(v.kmPerLiter.toString()) }
    var active by remember(v) { mutableStateOf(v.active) }
    var notes by remember(v) { mutableStateOf(v.notes) }
    val num = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (v.id.isBlank()) "New vehicle" else "Edit vehicle") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name, e.g. Staff Van 1") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(plate, { plate = it.uppercase() }, label = { Text("Registration no.") }, modifier = Modifier.fillMaxWidth())
                Picker("Type", type, VEHICLE_TYPES) { type = it }
                OutlinedTextField(seats, { seats = it.filter(Char::isDigit) }, label = { Text("Seats") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Picker("Fuel", fuel, FUEL_TYPES) { fuel = it }
                OutlinedTextField(kmpl, { kmpl = it }, label = { Text("Efficiency (km per litre)") }, keyboardOptions = num, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(active, { active = it }); Text("In service")
                }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank() && (kmpl.toDoubleOrNull() ?: 0.0) > 0, onClick = {
                Repo.saveVehicle(
                    v.copy(
                        name = name.trim(), plateNo = plate.trim(), type = type, seats = seats.toIntOrNull() ?: 0,
                        fuelType = fuel, kmPerLiter = kmpl.toDoubleOrNull() ?: v.kmPerLiter, active = active, notes = notes.trim(),
                    )
                )
                onClose()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}
