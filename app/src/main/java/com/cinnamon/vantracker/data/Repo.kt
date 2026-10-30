package com.cinnamon.vantracker.data

import android.util.Log
import com.cinnamon.vantracker.tracking.TripCalc
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * All Firestore access. Writes are fire-and-forget on purpose: Firestore applies them to
 * the local cache immediately (so the UI updates through the listeners) and syncs them to
 * the server when the phone is online. Awaiting them would hang while offline.
 */
object Repo {
    private const val TAG = "Repo"
    private val db get() = Backend.db

    val users get() = db.collection("users")
    val requests get() = db.collection("requests")
    val trips get() = db.collection("trips")
    val schedules get() = db.collection("schedules")
    val alerts get() = db.collection("alerts")
    val fuelLogs get() = db.collection("fuelLogs")
    val dutyLogs get() = db.collection("dutyLogs")
    val settingsDoc get() = db.collection("config").document("settings")
    val vehicles get() = db.collection("vehicles")
    /** One live-position document per vehicle id. */
    val live get() = db.collection("live")
    val bootstrapDoc get() = db.collection("meta").document("bootstrap")

    fun now() = System.currentTimeMillis()
    fun dateKey(t: Long = now()): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(t))

    // ------------------------------------------------------------------ listeners

    private fun docFlow(ref: DocumentReference): Flow<DocumentSnapshot?> = callbackFlow {
        val reg = ref.addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "listen ${ref.path}: ${err.message}")
            trySend(snap)
        }
        awaitClose { reg.remove() }
    }

    private fun queryFlow(q: Query): Flow<QuerySnapshot?> = callbackFlow {
        val reg = q.addSnapshotListener { snap, err ->
            if (err != null) Log.w(TAG, "query: ${err.message}")
            if (snap != null) trySend(snap)
        }
        awaitClose { reg.remove() }
    }

    fun profileFlow(uid: String) = docFlow(users.document(uid)).map { d ->
        if (d != null && d.exists()) {
            // One-time data migration for renamed departments (e.g. Management → Administration).
            val raw = d.getString("department") ?: ""
            if (raw.isNotBlank() && normDept(raw) != raw) users.document(uid).update("department", normDept(raw))
            UserProfile.from(d)
        } else null
    }

    fun settingsFlow() = docFlow(settingsDoc).map { AppSettings.from(it) }

    /** Message an administrator posts from the web dashboard for every app user (config/announcement). */
    data class Announcement(val message: String, val level: String, val until: Long)

    fun announcementFlow() = docFlow(db.collection("config").document("announcement")).map { d ->
        val msg = d?.getString("message").orEmpty()
        val until = d?.getLong("until") ?: 0L
        if (d == null || msg.isBlank() || d.getBoolean("active") == false || (until > 0 && until < now())) null
        else Announcement(msg, d.getString("level") ?: "info", until)
    }
    fun liveAllFlow() = queryFlow(live).map { s -> s?.documents?.map { LiveStatus.from(it) }.orEmpty().filter { it.updatedAt > 0 } }

    fun vehiclesFlow() = queryFlow(vehicles).map { s -> s?.documents?.map(Vehicle::from).orEmpty().sortedBy { it.name } }

    fun saveVehicle(v: Vehicle) {
        val ref = if (v.id.isBlank()) vehicles.document() else vehicles.document(v.id)
        ref.set(v.toMap())
    }
    fun tripFlow(id: String) = docFlow(trips.document(id)).map { d -> if (d != null && d.exists()) Trip.from(d) else null }

    fun usersFlow() = queryFlow(users).map { s -> s?.documents?.map(UserProfile::from).orEmpty().sortedBy { it.name } }

    fun pendingRequestsFlow() = queryFlow(requests.whereEqualTo("status", RequestStatus.PENDING.name))
        .map { s -> s?.documents?.map(TripRequest::from).orEmpty().sortedBy { it.requestedTime } }

    fun myRequestsFlow(uid: String) = queryFlow(requests.whereEqualTo("requesterUid", uid))
        .map { s -> s?.documents?.map(TripRequest::from).orEmpty().sortedByDescending { it.createdAt } }

    fun requestsForTripFlow(tripId: String) = queryFlow(requests.whereEqualTo("tripId", tripId))
        .map { s -> s?.documents?.map(TripRequest::from).orEmpty() }

    fun openTripsFlow() = queryFlow(
        trips.whereIn("status", listOf(TripStatus.READY.name, TripStatus.ASSIGNED.name, TripStatus.IN_PROGRESS.name))
    ).map { s -> s?.documents?.map(Trip::from).orEmpty().sortedBy { it.plannedTime } }

    fun driversFlow() = queryFlow(users.whereEqualTo("role", Role.DRIVER.name))
        .map { s -> s?.documents?.map(UserProfile::from).orEmpty().filter { it.active }.sortedBy { it.name } }

    fun dutyLogsSinceFlow(from: Long) = queryFlow(dutyLogs.whereGreaterThanOrEqualTo("at", from))
        .map { s -> s?.documents?.map(DutyLog::from).orEmpty().sortedBy { it.at } }

    fun tripsSinceFlow(from: Long) = queryFlow(trips.whereGreaterThanOrEqualTo("createdAt", from))
        .map { s -> s?.documents?.map(Trip::from).orEmpty().sortedByDescending { it.startTime ?: it.createdAt } }

    fun needsReasonFlow() = queryFlow(trips.whereEqualTo("needsReason", true))
        .map { s -> s?.documents?.map(Trip::from).orEmpty().sortedByDescending { it.startTime ?: 0 } }

    fun schedulesFlow() = queryFlow(schedules).map { s -> s?.documents?.map(Schedule::from).orEmpty().sortedBy { it.time } }

    fun alertsFlow() = queryFlow(alerts.orderBy("createdAt", Query.Direction.DESCENDING).limit(200))
        .map { s -> s?.documents?.map(AlertItem::from).orEmpty() }

    fun fuelLogsFlow() = queryFlow(fuelLogs.orderBy("time", Query.Direction.DESCENDING).limit(200))
        .map { s -> s?.documents?.map(FuelLog::from).orEmpty() }

    suspend fun trackPoints(tripId: String): List<TrackPoint> = runCatching {
        trips.document(tripId).collection("track").get().await().documents
            .sortedBy { it.long("seq") }
            .flatMap { d ->
                (d.get("p") as? List<*>).orEmpty().mapNotNull { raw ->
                    val m = raw as? Map<*, *> ?: return@mapNotNull null
                    TrackPoint(
                        (m["a"] as? Number)?.toDouble() ?: return@mapNotNull null,
                        (m["o"] as? Number)?.toDouble() ?: return@mapNotNull null,
                        (m["t"] as? Number)?.toLong() ?: 0L,
                        (m["s"] as? Number)?.toDouble() ?: 0.0,
                    )
                }
            }
    }.getOrElse { Log.w(TAG, "track: ${it.message}"); emptyList() }

    // ------------------------------------------------------------------ users

    /** Registers a profile. The very first account may claim the administrator role. */
    fun createProfile(p: UserProfile, bootstrapAdmin: Boolean) = db.batch().apply {
        set(users.document(p.uid), p.toMap())
        if (bootstrapAdmin) set(bootstrapDoc, mapOf("adminUid" to p.uid, "at" to now()))
    }.commit()

    fun updateUser(uid: String, fields: Map<String, Any?>) {
        users.document(uid).update(fields)
    }

    /** Driver goes on / off duty; every change is logged for reports. */
    fun setDuty(driver: UserProfile, onDuty: Boolean) {
        val t = now()
        val batch = db.batch()
        batch.update(users.document(driver.uid), mapOf("onDuty" to onDuty, "dutyChangedAt" to t))
        batch.set(dutyLogs.document(), mapOf("driverUid" to driver.uid, "driverName" to driver.name, "onDuty" to onDuty, "at" to t))
        batch.commit()
    }

    // ------------------------------------------------------------------ requests

    fun submitRequest(r: TripRequest): String {
        val ref = requests.document()
        ref.set(r.copy(id = ref.id, status = RequestStatus.PENDING, createdAt = now()).toMap())
        return ref.id
    }

    /** Pre-filled join target for today's scheduled run of [s]. */
    fun scheduleTarget(s: Schedule, day: String = dateKey()) = "sched:${s.id}:$day"
    fun scheduleTripId(scheduleId: String, day: String) = "sched_${scheduleId}_$day"

    /**
     * Approves [r]. [target] is null for a new dedicated trip, "trip:<id>" to combine with an
     * existing trip, or "sched:<scheduleId>:<yyyyMMdd>" to ride along a scheduled run.
     */
    suspend fun approveRequest(
        r: TripRequest, approver: UserProfile, comment: String, target: String?, schedules: List<Schedule>,
        driver: UserProfile? = null,
        vehicle: Vehicle? = null,
    ) {
        val stops = r.stops.map { it.copy(requestId = r.id, department = r.department) }
        // Pre-assigned driver (optional) – otherwise every driver is alerted and the first to accept takes it.
        val driverFields: Map<String, Any?> = if (driver != null) mapOf(
            "status" to TripStatus.ASSIGNED.name, "driverUid" to driver.uid, "driverName" to driver.name, "assignedAt" to now(),
        ) else emptyMap<String, Any?>()
        val vehicleFields: Map<String, Any?> = if (vehicle != null) mapOf("vehicleId" to vehicle.id, "vehicleName" to vehicle.label) else emptyMap()
        val tripRef: DocumentReference
        val batch = db.batch()
        when {
            target != null && target.startsWith("trip:") -> {
                tripRef = trips.document(target.removePrefix("trip:"))
                batch.update(
                    tripRef, mapOf(
                        "requestIds" to FieldValue.arrayUnion(r.id),
                        "departments" to FieldValue.arrayUnion(r.department),
                        "stops" to FieldValue.arrayUnion(*stops.map { it.toMap() }.toTypedArray()),
                        "passengers" to FieldValue.increment(r.passengers.toLong()),
                    )
                )
            }
            target != null && target.startsWith("sched:") -> {
                val parts = target.split(":")
                val sched = schedules.firstOrNull { it.id == parts.getOrNull(1) }
                val day = parts.getOrNull(2) ?: dateKey()
                tripRef = trips.document(scheduleTripId(parts.getOrNull(1) ?: "", day))
                val exists = runCatching { tripRef.get().await().exists() }.getOrDefault(false)
                val data = mutableMapOf<String, Any?>(
                    "requestIds" to FieldValue.arrayUnion(r.id),
                    "departments" to FieldValue.arrayUnion(r.department),
                    "stops" to FieldValue.arrayUnion(*stops.map { it.toMap() }.toTypedArray()),
                    "passengers" to FieldValue.increment(r.passengers.toLong()),
                )
                if (!exists && sched != null) {
                    data += mapOf(
                        "type" to TripType.SCHEDULED.name, "status" to TripStatus.READY.name,
                        "title" to sched.title, "category" to sched.category, "purpose" to sched.purpose,
                        "scheduleId" to sched.id, "plannedTime" to plannedTimeFor(sched, day),
                        "createdAt" to now(), "openedAt" to now(), "createdBy" to approver.name,
                        "destination" to sched.destination?.toMap(),
                    )
                    data += driverFields
                    data += vehicleFields
                    // Keep the schedule's own stops ahead of the extra ones.
                    data["stops"] = FieldValue.arrayUnion(*(sched.stops + stops).map { it.toMap() }.toTypedArray())
                    data["departments"] = FieldValue.arrayUnion(*listOf(sched.department, r.department).filter { it.isNotBlank() }.toTypedArray())
                }
                batch.set(tripRef, data, SetOptions.merge())
            }
            else -> {
                tripRef = trips.document()
                batch.set(
                    tripRef, mapOf(
                        "type" to TripType.REQUEST.name, "status" to TripStatus.READY.name,
                        "title" to "${r.category} – ${r.department}", "category" to r.category,
                        "purpose" to r.purpose, "departments" to listOf(r.department),
                        "requestIds" to listOf(r.id), "stops" to stops.map { it.toMap() },
                        "plannedTime" to r.requestedTime, "passengers" to r.passengers, "awayUntil" to r.awayUntil,
                        "createdAt" to now(), "openedAt" to now(), "createdBy" to approver.name,
                        "driverUid" to "", "driverName" to "",
                    ) + driverFields + vehicleFields
                )
            }
        }
        batch.update(
            requests.document(r.id), mapOf(
                "status" to RequestStatus.APPROVED.name, "approverName" to approver.name,
                "approverUid" to approver.uid, "decidedAt" to now(), "decisionComment" to comment,
                "tripId" to tripRef.id,
            ) + (if (driver != null && target == null) mapOf("driverName" to driver.name, "acceptedAt" to now()) else emptyMap())
        )
        batch.commit()
    }

    /**
     * A driver accepts an open trip. Runs as a transaction so two drivers can never take the
     * same trip (needs internet). Returns null on success or a message why it failed.
     */
    suspend fun acceptTrip(tripId: String, driver: UserProfile): String? = runCatching {
        db.runTransaction { tx ->
            val snap = tx.get(trips.document(tripId))
            if (!snap.exists()) throw IllegalStateException("This trip no longer exists.")
            val t = Trip.from(snap)
            if (t.status != TripStatus.READY) {
                throw IllegalStateException(
                    if (t.driverName.isNotBlank() && t.driverUid != driver.uid) "Already accepted by ${t.driverName}."
                    else "This trip is ${t.status.label.lowercase()}."
                )
            }
            val at = now()
            tx.update(
                trips.document(tripId), mapOf(
                    "status" to TripStatus.ASSIGNED.name, "driverUid" to driver.uid,
                    "driverName" to driver.name, "assignedAt" to at,
                )
            )
            t.requestIds.forEach { tx.update(requests.document(it), mapOf("driverName" to driver.name, "acceptedAt" to at)) }
            true
        }.await()
        null as String?
    }.getOrElse { e ->
        if (e is com.google.firebase.firestore.FirebaseFirestoreException &&
            e.code == com.google.firebase.firestore.FirebaseFirestoreException.Code.UNAVAILABLE
        ) "No internet – accepting a trip needs a connection so two drivers don't take the same trip."
        else e.cause?.message ?: e.message ?: "Could not accept the trip."
    }

    /** The assigned driver hands an unstarted trip back; all drivers are alerted again. */
    fun releaseTrip(t: Trip) {
        val batch = db.batch()
        batch.update(
            trips.document(t.id), mapOf(
                "status" to TripStatus.READY.name, "driverUid" to "", "driverName" to "",
                "assignedAt" to null, "openedAt" to now(),
            )
        )
        t.requestIds.forEach { batch.update(requests.document(it), mapOf("driverName" to "", "acceptedAt" to null)) }
        batch.commit()
    }

    /**
     * Books a seat on today's (or a given day's) run of a pre-approved schedule. The request is
     * confirmed immediately and the passenger's drop-off is added to that run's trip.
     */
    suspend fun bookScheduledSeat(r: TripRequest, sched: Schedule, day: String) {
        val reqRef = requests.document()
        val tripRef = trips.document(scheduleTripId(sched.id, day))
        val stops = r.stops.map { it.copy(requestId = reqRef.id, department = r.department) }
        val exists = runCatching { tripRef.get().await().exists() }.getOrDefault(false)
        val tripData = mutableMapOf<String, Any?>(
            "requestIds" to FieldValue.arrayUnion(reqRef.id),
            "departments" to FieldValue.arrayUnion(r.department),
            "stops" to FieldValue.arrayUnion(*stops.map { it.toMap() }.toTypedArray()),
            "passengers" to FieldValue.increment(r.passengers.toLong()),
        )
        if (!exists) {
            tripData += mapOf(
                "type" to TripType.SCHEDULED.name, "status" to TripStatus.READY.name,
                "title" to sched.title, "category" to sched.category, "purpose" to sched.purpose,
                "scheduleId" to sched.id, "plannedTime" to plannedTimeFor(sched, day),
                "createdAt" to now(), "openedAt" to now(), "createdBy" to "Schedule booking",
                "destination" to sched.destination?.toMap(),
                "driverUid" to "", "driverName" to "",
            )
            // The run's own stops come first.
            tripData["stops"] = FieldValue.arrayUnion(*(sched.stops + stops).map { it.toMap() }.toTypedArray())
            tripData["departments"] = FieldValue.arrayUnion(*listOf(sched.department, r.department).filter { it.isNotBlank() }.toTypedArray())
        }
        val batch = db.batch()
        batch.set(
            reqRef, r.copy(
                id = reqRef.id, status = RequestStatus.APPROVED, createdAt = now(), tripId = tripRef.id,
                approverName = "Pre-approved schedule", decidedAt = now(),
                joinTarget = scheduleTarget(sched, day), joinTargetLabel = "${sched.title} (${sched.time})",
            ).toMap()
        )
        batch.set(tripRef, tripData, SetOptions.merge())
        batch.commit()
    }

    /** Trips planned from [from] on (approved, scheduled or done) – for the availability calendar. */
    fun tripsPlannedFromFlow(from: Long) = queryFlow(trips.whereGreaterThanOrEqualTo("plannedTime", from))
        .map { s -> s?.documents?.map(Trip::from).orEmpty().filter { it.status != TripStatus.CANCELLED }.sortedBy { it.plannedTime } }

    fun setMinVersion(code: Int) {
        settingsDoc.update("minVersionCode", code)
    }

    fun requestsSinceFlow(from: Long) = queryFlow(requests.whereGreaterThanOrEqualTo("requestedTime", from))
        .map { s -> s?.documents?.map(TripRequest::from).orEmpty().sortedByDescending { it.requestedTime } }

    fun rejectRequest(r: TripRequest, approver: UserProfile, comment: String) {
        requests.document(r.id).update(
            mapOf(
                "status" to RequestStatus.REJECTED.name, "approverName" to approver.name,
                "approverUid" to approver.uid, "decidedAt" to now(), "decisionComment" to comment,
            )
        )
    }

    /** Cancels a request; detaches it from its trip (or cancels the trip if it was the only one). */
    suspend fun cancelRequest(r: TripRequest) {
        val batch = db.batch()
        batch.update(requests.document(r.id), mapOf("status" to RequestStatus.CANCELLED.name, "decidedAt" to now()))
        if (r.tripId.isNotBlank()) {
            val trip = runCatching { trips.document(r.tripId).get().await() }.getOrNull()?.takeIf { it.exists() }?.let(Trip::from)
            if (trip != null && trip.isOpen) {
                val remaining = trip.requestIds - r.id
                if (remaining.isEmpty() && trip.type == TripType.REQUEST) {
                    batch.update(trips.document(trip.id), "status", TripStatus.CANCELLED.name)
                } else {
                    batch.update(
                        trips.document(trip.id), mapOf(
                            "requestIds" to remaining,
                            "stops" to trip.stops.filter { it.requestId != r.id }.map { it.toMap() },
                            "passengers" to (trip.passengers - r.passengers).coerceAtLeast(0),
                        )
                    )
                }
            }
        }
        batch.commit()
    }

    fun plannedTimeFor(s: Schedule, day: String): Long = runCatching {
        SimpleDateFormat("yyyyMMdd HH:mm", Locale.US).parse("$day ${s.time}")!!.time
    }.getOrDefault(now())

    // ------------------------------------------------------------------ trips (driver)

    fun startTrip(trip: Trip, driver: UserProfile, startTime: Long, vehicle: Vehicle) {
        val batch = db.batch()
        batch.update(
            trips.document(trip.id), mapOf(
                "status" to TripStatus.IN_PROGRESS.name, "startTime" to startTime,
                "driverUid" to driver.uid, "driverName" to driver.name,
                "vehicleId" to vehicle.id, "vehicleName" to vehicle.label,
            )
        )
        trip.requestIds.forEach { batch.update(requests.document(it), "status", RequestStatus.IN_PROGRESS.name) }
        batch.commit()
    }

    /** Starts today's run of a pre-approved schedule (no approval needed). Returns the trip id. */
    fun startScheduled(s: Schedule, driver: UserProfile, startTime: Long, vehicle: Vehicle): String {
        val day = dateKey(startTime)
        val ref = trips.document(scheduleTripId(s.id, day))
        ref.set(
            mapOf(
                "type" to TripType.SCHEDULED.name, "status" to TripStatus.IN_PROGRESS.name,
                "title" to s.title, "category" to s.category, "purpose" to s.purpose,
                "scheduleId" to s.id, "plannedTime" to plannedTimeFor(s, day),
                "departments" to FieldValue.arrayUnion(*listOf(s.department).filter { it.isNotBlank() }.toTypedArray()),
                "stops" to FieldValue.arrayUnion(*s.stops.map { it.toMap() }.toTypedArray()),
                "startTime" to startTime, "driverUid" to driver.uid, "driverName" to driver.name,
                "vehicleId" to vehicle.id, "vehicleName" to vehicle.label,
                "destination" to s.destination?.toMap(),
                "createdAt" to now(), "createdBy" to "Schedule",
            ),
            SetOptions.merge(),
        )
        return ref.id
    }

    fun createUnauthorizedTrip(driverUid: String, driverName: String, startTime: Long, vehicleId: String, vehicleName: String): String {
        val ref = trips.document()
        val batch = db.batch()
        batch.set(
            ref, mapOf(
                "type" to TripType.UNAUTHORIZED.name, "status" to TripStatus.IN_PROGRESS.name,
                "title" to "Movement without approved trip", "category" to "Unauthorized",
                "purpose" to "", "departments" to emptyList<String>(), "requestIds" to emptyList<String>(),
                "stops" to emptyList<Any>(), "plannedTime" to startTime, "startTime" to startTime,
                "driverUid" to driverUid, "driverName" to driverName, "needsReason" to true,
                "vehicleId" to vehicleId, "vehicleName" to vehicleName,
                "reason" to "", "createdAt" to now(), "createdBy" to "Auto-detected",
            )
        )
        batch.set(
            alerts.document("move_${ref.id}"), mapOf(
                "type" to AlertItem.UNAUTHORIZED_MOVEMENT, "severity" to "WARN",
                "title" to "${vehicleName.ifBlank { "Vehicle" }} moving without an approved trip",
                "message" to "Movement detected at ${TripCalc.time(startTime)} (driver: ${driverName.ifBlank { "unknown" }}). The driver has been asked for a reason.",
                "tripId" to ref.id, "createdAt" to now(), "acknowledged" to false,
            )
        )
        batch.commit()
        return ref.id
    }

    fun markStopArrived(trip: Trip, index: Int) {
        val updated = trip.stops.mapIndexed { i, s -> if (i == index) s.copy(arrivedAt = now()) else s }
        trips.document(trip.id).update("stops", updated.map { it.toMap() })
    }

    fun completeTrip(tripId: String, summary: Map<String, Any?>, requestIds: List<String>) {
        val batch = db.batch()
        batch.update(trips.document(tripId), summary + mapOf("status" to TripStatus.COMPLETED.name))
        requestIds.forEach { batch.update(requests.document(it), "status", RequestStatus.COMPLETED.name) }
        batch.commit()
    }

    fun appendTrack(tripId: String, seq: Int, points: List<TrackPoint>) {
        trips.document(tripId).collection("track").document(seq.toString().padStart(5, '0')).set(
            mapOf(
                "seq" to seq,
                "p" to points.map { mapOf("a" to it.lat, "o" to it.lng, "t" to it.time, "s" to it.speedKmh) },
            )
        )
    }

    fun updateLive(vehicleId: String, fields: Map<String, Any?>) {
        live.document(vehicleId.ifBlank { Vehicle.DEFAULT_ID }).set(fields, SetOptions.merge())
    }

    fun submitReason(tripId: String, reason: String) {
        val batch = db.batch()
        batch.update(trips.document(tripId), mapOf("reason" to reason, "reasonAt" to now(), "needsReason" to false, "purpose" to reason))
        batch.set(alerts.document("missing_$tripId"), mapOf("acknowledged" to true, "acknowledgedBy" to "Reason submitted"), SetOptions.merge())
        batch.commit()
    }

    fun reviewReason(tripId: String, accepted: Boolean, by: String) {
        trips.document(tripId).update(mapOf("reviewStatus" to if (accepted) "ACCEPTED" else "FLAGGED", "reviewedBy" to by))
    }

    fun recalcTrip(t: Trip, s: AppSettings, gpsKm: Double, v: Vehicle?) {
        val (km, liters, cost) = TripCalc.fuel(gpsKm, s, v)
        trips.document(t.id).update(
            mapOf(
                "distanceKm" to km, "fuelLiters" to liters, "fuelCost" to cost,
                "fuelPrice" to s.priceFor(v?.fuelType ?: "Diesel"), "kmPerLiter" to TripCalc.kmPerLiter(s, v),
            )
        )
    }

    // ------------------------------------------------------------------ alerts

    fun ackAlert(id: String, by: String) {
        alerts.document(id).update(mapOf("acknowledged" to true, "acknowledgedBy" to by))
    }

    /**
     * End-of-day rule: any unauthorized movement still without a reason after the deadline
     * raises a high-severity alert for management. Idempotent (fixed alert ids).
     */
    suspend fun runReasonDeadlineCheck(settings: AppSettings) {
        val cal = java.util.Calendar.getInstance()
        val todayDeadline = (cal.clone() as java.util.Calendar).apply {
            set(java.util.Calendar.HOUR_OF_DAY, settings.reasonDeadlineHour)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }.timeInMillis
        val pending = runCatching { trips.whereEqualTo("needsReason", true).get().await().documents.map(Trip::from) }
            .getOrElse { return }
        for (t in pending) {
            val start = t.startTime ?: t.createdAt
            // Movements from earlier days are always overdue; today's ones after the deadline.
            val overdue = TripCalc.dayStart(start) < TripCalc.dayStart(now()) || now() >= todayDeadline
            if (!overdue) continue
            val ref = alerts.document("missing_${t.id}")
            val exists = runCatching { ref.get().await().exists() }.getOrDefault(true)
            if (!exists) {
                ref.set(
                    mapOf(
                        "type" to AlertItem.MISSING_REASON, "severity" to "HIGH",
                        "title" to "No reason given for van movement",
                        "message" to "Driver ${t.driverName.ifBlank { "" }} did not give a reason for the movement on ${TripCalc.dateTime(start)} (${TripCalc.km(t.distanceKm)}).",
                        "tripId" to t.id, "createdAt" to now(), "acknowledged" to false,
                    )
                )
            }
        }
    }

    // ------------------------------------------------------------------ admin

    fun saveSettings(s: AppSettings) {
        settingsDoc.set(s.toMap())
    }

    fun saveSchedule(s: Schedule) {
        val ref = if (s.id.isBlank()) schedules.document() else schedules.document(s.id)
        ref.set(s.toMap())
    }

    fun deleteSchedule(id: String) {
        schedules.document(id).delete()
    }

    fun addFuelLog(f: FuelLog) {
        fuelLogs.document().set(f.toMap())
    }
}
