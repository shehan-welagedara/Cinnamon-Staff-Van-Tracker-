package com.cinnamon.vantracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cinnamon.vantracker.BuildConfig
import com.cinnamon.vantracker.data.TrackPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker as OsmMarker
import org.osmdroid.views.overlay.Polyline as OsmPolyline
import org.osmdroid.views.overlay.TilesOverlay

data class MapPin(val lat: Double, val lng: Double, val title: String)

/** Default camera: Kandy, until real data arrives. */
private const val DEFAULT_LAT = 7.2906
private const val DEFAULT_LNG = 80.6337
private const val ROUTE_COLOR = 0xFF0A84FF

/** True when the build carries a Google Maps API key (GitHub secret MAPS_API_KEY). */
val googleMapsEnabled: Boolean get() = BuildConfig.MAPS_API_KEY.isNotBlank()

/**
 * Map used everywhere in the app.
 *
 * - With a Google Maps key: Google Maps (most up-to-date roads, buildings and place
 *   names in Sri Lanka) with a Map / Satellite switch.
 * - Without a key: OpenStreetMap – free and keyless; viewed tiles are cached for offline.
 *
 * [fitKey]: whenever it changes the camera re-fits to the path and pins; [follow] keeps
 * the latest point centred (live tracking).
 */
@Composable
fun VanMap(
    modifier: Modifier = Modifier,
    path: List<TrackPoint> = emptyList(),
    pins: List<MapPin> = emptyList(),
    fitKey: Any? = null,
    follow: Boolean = false,
    onTap: ((Double, Double) -> Unit)? = null,
) {
    if (googleMapsEnabled) GoogleVanMap(modifier, path, pins, fitKey, follow, onTap)
    else OsmVanMap(modifier, path, pins, fitKey, follow, onTap)
}

// ------------------------------------------------------------------ Google Maps

@Composable
private fun GoogleVanMap(
    modifier: Modifier, path: List<TrackPoint>, pins: List<MapPin>, fitKey: Any?, follow: Boolean,
    onTap: ((Double, Double) -> Unit)?,
) {
    val dark = isDarkTheme()
    var satellite by rememberSaveable { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(DEFAULT_LAT, DEFAULT_LNG), 13f)
    }
    val route = path.map { LatLng(it.lat, it.lng) }
    val all = route + pins.map { LatLng(it.lat, it.lng) }

    LaunchedEffect(fitKey, loaded, all.isEmpty()) {
        if (!loaded || all.isEmpty()) return@LaunchedEffect
        runCatching {
            if (all.size == 1) camera.move(CameraUpdateFactory.newLatLngZoom(all.first(), 16f))
            else {
                val b = LatLngBounds.builder()
                all.forEach { b.include(it) }
                camera.move(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
            }
        }
    }
    LaunchedEffect(route.lastOrNull()) {
        val last = route.lastOrNull()
        if (follow && loaded && last != null) runCatching { camera.animate(CameraUpdateFactory.newLatLng(last)) }
    }

    Box(modifier) {
        GoogleMap(
            modifier = Modifier.matchParentSize(),
            cameraPositionState = camera,
            properties = MapProperties(
                mapType = if (satellite) MapType.HYBRID else MapType.NORMAL,
                mapStyleOptions = if (dark && !satellite) MapStyleOptions(DARK_MAP_STYLE) else null,
            ),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, mapToolbarEnabled = false),
            onMapLoaded = { loaded = true },
            onMapClick = { onTap?.invoke(it.latitude, it.longitude) },
        ) {
            if (route.size >= 2) Polyline(points = route, color = Color(ROUTE_COLOR), width = 12f)
            route.firstOrNull()?.let { Marker(state = MarkerState(it), title = "Start") }
            if (route.size > 1) Marker(state = MarkerState(route.last()), title = if (follow) "Van" else "End")
            pins.forEach { Marker(state = MarkerState(LatLng(it.lat, it.lng)), title = it.title) }
        }
        MapToggle(if (satellite) "Map" else "Satellite", Modifier.align(Alignment.TopEnd)) { satellite = !satellite }
    }
}

@Composable
private fun MapToggle(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = Brand.colors.text,
        modifier = modifier
            .padding(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Brand.colors.card.copy(alpha = 0.92f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

// ------------------------------------------------------------------ OpenStreetMap

@Composable
private fun OsmVanMap(
    modifier: Modifier, path: List<TrackPoint>, pins: List<MapPin>, fitKey: Any?, follow: Boolean,
    onTap: ((Double, Double) -> Unit)?,
) {
    val dark = isDarkTheme()
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
                // Sharper labels on high-density screens.
                isTilesScaledToDpi = true
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                controller.setZoom(14.0)
                controller.setCenter(GeoPoint(DEFAULT_LAT, DEFAULT_LNG))
                mapHolder.value = this
            }
        },
        update = { map ->
            map.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERTED_COLORS else null)
            map.overlays.clear()
            if (onTap != null) {
                map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint): Boolean { onTap(p.latitude, p.longitude); return true }
                    override fun longPressHelper(p: GeoPoint): Boolean = false
                }))
            }
            val geo = path.map { GeoPoint(it.lat, it.lng) }
            if (geo.size >= 2) {
                map.overlays.add(OsmPolyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = ROUTE_COLOR.toInt()
                    outlinePaint.strokeWidth = 12f
                })
            }
            if (geo.isNotEmpty()) {
                map.overlays.add(OsmMarker(map).apply {
                    position = geo.first(); title = "Start"
                    setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                })
                if (geo.size > 1) map.overlays.add(OsmMarker(map).apply {
                    position = geo.last(); title = if (follow) "Van" else "End"
                    setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                })
            }
            pins.forEach { p ->
                map.overlays.add(OsmMarker(map).apply {
                    position = GeoPoint(p.lat, p.lng); title = p.title
                    setAnchor(OsmMarker.ANCHOR_CENTER, OsmMarker.ANCHOR_BOTTOM)
                })
            }
            val all = geo + pins.map { GeoPoint(it.lat, it.lng) }
            if (map.tag != fitKey && all.isNotEmpty()) {
                map.tag = fitKey
                map.post {
                    if (all.size == 1) {
                        map.controller.setZoom(17.0); map.controller.setCenter(all.first())
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

/** Compact night style for Google Maps (matches the app's dark mode). */
private const val DARK_MAP_STYLE = """
[
  {"elementType":"geometry","stylers":[{"color":"#1d1d1f"}]},
  {"elementType":"labels.text.fill","stylers":[{"color":"#a1a1a6"}]},
  {"elementType":"labels.text.stroke","stylers":[{"color":"#1d1d1f"}]},
  {"featureType":"poi","elementType":"geometry","stylers":[{"color":"#2a2a2c"}]},
  {"featureType":"poi.park","elementType":"geometry","stylers":[{"color":"#1f2b22"}]},
  {"featureType":"road","elementType":"geometry","stylers":[{"color":"#3a3a3c"}]},
  {"featureType":"road","elementType":"geometry.stroke","stylers":[{"color":"#2c2c2e"}]},
  {"featureType":"road.highway","elementType":"geometry","stylers":[{"color":"#6b4a35"}]},
  {"featureType":"transit","elementType":"geometry","stylers":[{"color":"#2c2c2e"}]},
  {"featureType":"water","elementType":"geometry","stylers":[{"color":"#0e1a2b"}]}
]
"""
