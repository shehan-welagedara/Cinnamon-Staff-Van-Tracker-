package com.cinnamon.vantracker.ui

import android.content.Context
import android.location.Geocoder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinnamon.vantracker.data.Stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** A place found by address search (Android's built-in geocoder – free, no API key). */
data class Place(val name: String, val lat: Double, val lng: Double)

private suspend fun searchPlaces(ctx: Context, query: String): List<Place> = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching emptyList()
        val q = if (query.contains(",")) query else "$query, Sri Lanka"
        @Suppress("DEPRECATION")
        Geocoder(ctx, Locale.getDefault()).getFromLocationName(q, 6).orEmpty().map { a ->
            val label = listOfNotNull(a.featureName, a.locality ?: a.subAdminArea).distinct().joinToString(", ")
                .ifBlank { a.getAddressLine(0) ?: query }
            Place(label, a.latitude, a.longitude)
        }
    }.getOrDefault(emptyList())
}

private suspend fun nameOf(ctx: Context, lat: Double, lng: Double): String? = withContext(Dispatchers.IO) {
    runCatching {
        if (!Geocoder.isPresent()) return@runCatching null
        @Suppress("DEPRECATION")
        Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lng, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(a.subLocality ?: a.thoroughfare ?: a.featureName, a.locality).distinct().joinToString(", ")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() }
}

/**
 * Choose a location: search an address or tap the map. Returns a display name (editable)
 * and the coordinates.
 */
@Composable
fun LocationPickerDialog(
    title: String,
    initialName: String,
    initial: Place?,
    s: Session,
    onDismiss: () -> Unit,
    onPick: (Place) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initialName) }
    var nameEdited by remember { mutableStateOf(initialName.isNotBlank()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(query, { query = it }, label = { Text("Search a place or address") }, singleLine = true, modifier = Modifier.weight(1f))
                    IconButton(enabled = query.isNotBlank() && !searching, onClick = {
                        searching = true
                        scope.launch { results = searchPlaces(ctx, query.trim()); searching = false }
                    }) { Icon(Icons.Filled.Search, "Search") }
                }
                if (searching) Text("Searching…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                results.forEach { p ->
                    Text(
                        "📍 ${p.name}", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().clickable {
                            picked = p; results = emptyList()
                            if (!nameEdited) name = p.name
                        }.padding(vertical = 6.dp),
                    )
                }
                VanMap(
                    Modifier.fillMaxWidth().height(260.dp),
                    pins = listOfNotNull(picked?.let { MapPin(it.lat, it.lng, name.ifBlank { "Selected" }) }) +
                        if (s.settings.hotelSet) listOf(MapPin(s.settings.hotelLat, s.settings.hotelLng, s.settings.hotelName)) else emptyList(),
                    fitKey = picked?.let { "${it.lat},${it.lng}" } ?: "pick",
                    onTap = { la, lo ->
                        picked = Place(name, la, lo)
                        if (!nameEdited) scope.launch { nameOf(ctx, la, lo)?.let { if (!nameEdited) name = it } }
                    },
                )
                Text("Tip: tap the map to drop the pin exactly.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it; nameEdited = true }, label = { Text("Name shown to the driver") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(enabled = picked != null && name.isNotBlank(), onClick = { picked?.let { onPick(it.copy(name = name.trim())) } }) { Text("Use this place") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Editable list of stops; each can be placed on the map. */
@Composable
fun StopsEditor(
    stops: SnapshotStateList<Stop>,
    s: Session,
    label: (Int) -> String = { "Stop ${it + 1}" },
    addText: String = "+ Add another stop",
    allowAdd: Boolean = true,
) {
    var pickIndex by remember { mutableStateOf(-1) }
    stops.forEachIndexed { i, st ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(st.name, { v -> stops[i] = st.copy(name = v) }, label = { Text(label(i)) }, modifier = Modifier.weight(1f))
            IconButton(onClick = { pickIndex = i }) {
                Icon(Icons.Filled.Place, "Pick on map", tint = if (st.hasLocation) OkGreen else Cinnamon)
            }
            if (stops.size > 1) IconButton(onClick = { stops.removeAt(i) }) { Icon(Icons.Filled.Clear, "Remove", tint = TextSecondary) }
        }
        if (st.hasLocation) Text("📍 Location set on map", color = OkGreen, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 4.dp))
    }
    if (allowAdd) TextButton(onClick = { stops.add(Stop()) }) { Text(addText) }
    if (pickIndex in stops.indices) {
        val i = pickIndex
        val st = stops[i]
        LocationPickerDialog(
            title = label(i),
            initialName = st.name,
            initial = if (st.hasLocation) Place(st.name, st.lat!!, st.lng!!) else null,
            s = s,
            onDismiss = { pickIndex = -1 },
        ) { p ->
            stops[i] = st.copy(name = p.name, lat = p.lat, lng = p.lng)
            pickIndex = -1
        }
    }
}
