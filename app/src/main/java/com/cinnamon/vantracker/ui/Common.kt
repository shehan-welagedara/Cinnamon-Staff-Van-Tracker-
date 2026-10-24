package com.cinnamon.vantracker.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import com.cinnamon.vantracker.data.TrackPoint
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Calendar

// ------------------------------------------------------------------ theme

val Cinnamon = Color(0xFF8B4513)
val CinnamonDark = Color(0xFF5E2E0D)
val Gold = Color(0xFFF2C14E)
val OkGreen = Color(0xFF2E7D32)
val WarnOrange = Color(0xFFEF6C00)
val DangerRed = Color(0xFFC62828)
val InfoBlue = Color(0xFF1565C0)

@Composable
fun VanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Cinnamon,
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFDCC4),
            onPrimaryContainer = CinnamonDark,
            secondary = Color(0xFF795548),
            tertiary = Gold,
            background = Color(0xFFFFFBF8),
            surface = Color(0xFFFFFBF8),
        ),
        content = content,
    )
}

// ------------------------------------------------------------------ building blocks

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Cinnamon, titleContentColor = Color.White,
            navigationIconContentColor = Color.White, actionIconContentColor = Color.White,
        ),
    )
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            if (accent != null) Box(Modifier.width(5.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(14.dp).fillMaxWidth()) {
                if (title != null) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = CinnamonDark)
                    Spacer(Modifier.height(6.dp))
                }
                content()
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, fontSize = 14.sp)
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = valueColor)
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(color, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun Empty(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.Gray)
    }
}

@Composable
fun Banner(text: String, color: Color, onClick: (() -> Unit)? = null) {
    Text(
        text,
        color = Color.White,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Simple drop-down chooser that works the same on every Material3 version. */
@Composable
fun Picker(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Transparent layer that captures taps (text fields swallow clicks otherwise).
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false })
            }
        }
    }
}

fun pickDateTime(ctx: Context, initial: Long, onPicked: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(ctx, { _, y, m, d ->
        TimePickerDialog(ctx, { _, h, min ->
            c.set(y, m, d, h, min, 0)
            onPicked(c.timeInMillis)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

fun pickTime(ctx: Context, hhmm: String, onPicked: (String) -> Unit) {
    val parts = hhmm.split(":")
    TimePickerDialog(ctx, { _, h, m ->
        onPicked("%02d:%02d".format(h, m))
    }, parts.getOrNull(0)?.toIntOrNull() ?: 8, parts.getOrNull(1)?.toIntOrNull() ?: 0, true).show()
}

// ------------------------------------------------------------------ connectivity

@Composable
fun rememberOnline(): Boolean {
    val ctx = LocalContext.current
    var online by remember { mutableStateOf(isOnline(ctx)) }
    DisposableEffect(Unit) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { online = true }
            override fun onLost(network: Network) { online = isOnline(ctx) }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }
        onDispose { runCatching { cm.unregisterNetworkCallback(cb) } }
    }
    return online
}

fun isOnline(ctx: Context): Boolean {
    val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// ------------------------------------------------------------------ map (OpenStreetMap)

data class MapPin(val lat: Double, val lng: Double, val title: String)

/**
 * OpenStreetMap view (osmdroid). Free, no API key; tiles already viewed are cached on the
 * phone, so recently seen areas still show offline.
 *
 * [fitKey]: whenever it changes the camera re-fits to the path/pins; [follow] keeps the
 * last point centred (live tracking).
 */
@Composable
fun OsmMap(
    modifier: Modifier = Modifier,
    path: List<TrackPoint> = emptyList(),
    pins: List<MapPin> = emptyList(),
    fitKey: Any? = null,
    follow: Boolean = false,
    onTap: ((Double, Double) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapHolder = remember { mutableStateOf<MapView?>(null) }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapHolder.value?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapHolder.value?.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapHolder.value?.onDetach()
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
                controller.setZoom(14.0)
                controller.setCenter(GeoPoint(6.9271, 79.8612)) // Colombo until data arrives
                mapHolder.value = this
            }
        },
        update = { map ->
            map.overlays.clear()
            if (onTap != null) {
                map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap(p.latitude, p.longitude); return true }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }))
            }
            val geo = path.map { GeoPoint(it.lat, it.lng) }
            if (geo.size >= 2) {
                map.overlays.add(Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = android.graphics.Color.rgb(21, 101, 192)
                    outlinePaint.strokeWidth = 10f
                })
            }
            if (geo.isNotEmpty()) {
                map.overlays.add(Marker(map).apply {
                    position = geo.first(); title = "Start"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
                if (geo.size > 1) map.overlays.add(Marker(map).apply {
                    position = geo.last(); title = if (follow) "Van" else "End"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            pins.forEach { p ->
                map.overlays.add(Marker(map).apply {
                    position = GeoPoint(p.lat, p.lng); title = p.title
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                })
            }
            val all = geo + pins.map { GeoPoint(it.lat, it.lng) }
            if (map.tag != fitKey && all.isNotEmpty()) {
                map.tag = fitKey
                map.post {
                    if (all.size == 1) {
                        map.controller.setZoom(16.0); map.controller.setCenter(all.first())
                    } else {
                        runCatching { map.zoomToBoundingBox(BoundingBox.fromGeoPoints(all), false, 80) }
                    }
                }
            } else if (follow && geo.isNotEmpty()) {
                map.controller.animateTo(geo.last())
            }
            map.invalidate()
        },
    )
}
