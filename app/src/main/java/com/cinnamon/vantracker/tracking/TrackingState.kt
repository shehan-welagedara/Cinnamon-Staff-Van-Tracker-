package com.cinnamon.vantracker.tracking

import android.content.Context
import com.cinnamon.vantracker.data.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class TrackMode { GUARD, TRIP, UNAUTHORIZED }

/**
 * Recording state of the van phone. Persisted after every change so a reboot, crash or
 * app update in the middle of a trip resumes exactly where it stopped.
 */
data class TrackingState(
    val mode: TrackMode = TrackMode.GUARD,
    val tripId: String = "",
    val tripTitle: String = "",
    val vehicleId: String = "",
    val vehicleName: String = "",
    val requestIds: List<String> = emptyList(),
    val startTime: Long = 0,
    val distanceM: Double = 0.0,
    val movingMs: Long = 0,
    val stoppedMs: Long = 0,
    val maxSpeedKmh: Double = 0.0,
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastFixTime: Long = 0,
    val chunkSeq: Int = 0,
    val pending: List<TrackPoint> = emptyList(),
    val stationarySince: Long = 0,
    /** Where the van is considered parked while guarding (hotel, or where the last trip ended). */
    val anchorLat: Double? = null,
    val anchorLng: Double? = null,
) {
    fun save(ctx: Context) {
        val arr = JSONArray()
        pending.forEach { arr.put(JSONObject().put("a", it.lat).put("o", it.lng).put("t", it.time).put("s", it.speedKmh)) }
        val j = JSONObject()
            .put("mode", mode.name).put("tripId", tripId).put("tripTitle", tripTitle).put("vehicleId", vehicleId).put("vehicleName", vehicleName)
            .put("requestIds", JSONArray(requestIds)).put("startTime", startTime)
            .put("distanceM", distanceM).put("movingMs", movingMs).put("stoppedMs", stoppedMs)
            .put("maxSpeedKmh", maxSpeedKmh).put("lastFixTime", lastFixTime).put("chunkSeq", chunkSeq)
            .put("pending", arr).put("stationarySince", stationarySince)
        lastLat?.let { j.put("lastLat", it) }
        lastLng?.let { j.put("lastLng", it) }
        anchorLat?.let { j.put("anchorLat", it) }
        anchorLng?.let { j.put("anchorLng", it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("state", j.toString()).commit()
    }

    companion object {
        private const val PREFS = "tracking"

        fun load(ctx: Context): TrackingState = runCatching {
            val j = JSONObject(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("state", null)!!)
            val p = j.optJSONArray("pending") ?: JSONArray()
            val ids = j.optJSONArray("requestIds") ?: JSONArray()
            TrackingState(
                mode = TrackMode.valueOf(j.optString("mode", "GUARD")),
                tripId = j.optString("tripId"),
                tripTitle = j.optString("tripTitle"),
                vehicleId = j.optString("vehicleId"),
                vehicleName = j.optString("vehicleName"),
                requestIds = (0 until ids.length()).map { ids.getString(it) },
                startTime = j.optLong("startTime"),
                distanceM = j.optDouble("distanceM", 0.0),
                movingMs = j.optLong("movingMs"),
                stoppedMs = j.optLong("stoppedMs"),
                maxSpeedKmh = j.optDouble("maxSpeedKmh", 0.0),
                lastLat = if (j.has("lastLat")) j.getDouble("lastLat") else null,
                lastLng = if (j.has("lastLng")) j.getDouble("lastLng") else null,
                lastFixTime = j.optLong("lastFixTime"),
                chunkSeq = j.optInt("chunkSeq"),
                pending = (0 until p.length()).map {
                    val o = p.getJSONObject(it)
                    TrackPoint(o.getDouble("a"), o.getDouble("o"), o.getLong("t"), o.optDouble("s", 0.0))
                },
                stationarySince = j.optLong("stationarySince"),
                anchorLat = if (j.has("anchorLat")) j.getDouble("anchorLat") else null,
                anchorLng = if (j.has("anchorLng")) j.getDouble("anchorLng") else null,
            )
        }.getOrDefault(TrackingState())

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}

/** What the driver screens show; published by the service in-process. */
data class LiveTrack(
    val running: Boolean = false,
    val state: TrackingState = TrackingState(),
    val points: List<TrackPoint> = emptyList(),
    val lastLat: Double? = null,
    val lastLng: Double? = null,
    val lastAccuracy: Float = 0f,
    val lastFix: Long = 0,
    val speedKmh: Double = 0.0,
    val charging: Boolean = false,
    val btConnected: Boolean = false,
    val triggerActive: Boolean = false,
)

object TrackingBus {
    val live = MutableStateFlow(LiveTrack())
}
