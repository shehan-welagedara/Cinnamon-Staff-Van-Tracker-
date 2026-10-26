package com.cinnamon.vantracker.data

import com.google.firebase.firestore.DocumentSnapshot

/*
 * Firestore documents are mapped by hand (no reflection) so the code stays
 * R8-safe and tolerant of missing fields written by older app versions.
 */

enum class Role(val label: String) {
    PENDING("Pending approval"),
    DRIVER("Driver"),
    HOD("Head of Department"),
    GM("General Manager"),
    OM("Operations Manager"),
    ADMIN("Administrator");

    companion object {
        fun of(s: String?): Role = entries.firstOrNull { it.name == s } ?: PENDING
    }
}

val CATEGORIES = listOf("Bank", "Laundry", "Staff Transport", "Purchasing", "Guest Related", "Official Meeting", "Other")

val DEPARTMENTS = listOf(
    "Front Office", "Housekeeping", "F&B Service", "Kitchen", "Engineering", "Finance",
    "Human Resources", "Purchasing", "Sales & Marketing", "Security", "Administration", "Transport"
)

/** Department names that were renamed; old records are shown (and migrated) under the new name. */
fun normDept(d: String): String = if (d == "Management") "Administration" else d

data class UserProfile(
    val uid: String = "",
    val name: String = "",
    val email: String = "",
    val phone: String = "",
    val department: String = "",
    val role: Role = Role.PENDING,
    val requestedRole: Role = Role.HOD,
    val isAdmin: Boolean = false,
    val active: Boolean = true,
    /** Drivers only: available to take trips. Location is recorded only while on duty. */
    val onDuty: Boolean = false,
    val dutyChangedAt: Long = 0,
) {
    /** GM, OM and Administrators can approve trips. */
    val isManager get() = role == Role.GM || role == Role.OM || role == Role.ADMIN
    val isDriver get() = role == Role.DRIVER
    val isApproved get() = role != Role.PENDING && active

    fun toMap() = mapOf(
        "uid" to uid, "name" to name, "email" to email, "phone" to phone,
        "department" to department, "role" to role.name, "requestedRole" to requestedRole.name,
        "isAdmin" to isAdmin, "active" to active,
    )

    companion object {
        fun from(d: DocumentSnapshot) = UserProfile(
            uid = d.id,
            name = d.str("name"),
            email = d.str("email"),
            phone = d.str("phone"),
            department = normDept(d.str("department")),
            role = Role.of(d.getString("role")),
            requestedRole = Role.of(d.getString("requestedRole")),
            isAdmin = (d.getBoolean("isAdmin") ?: false) || Role.of(d.getString("role")) == Role.ADMIN,
            active = d.getBoolean("active") ?: true,
            onDuty = d.getBoolean("onDuty") ?: false,
            dutyChangedAt = d.long("dutyChangedAt"),
        )
    }
}

/** On/off duty history of drivers (collection dutyLogs) – for reports and the web dashboard. */
data class DutyLog(val id: String = "", val driverUid: String = "", val driverName: String = "", val onDuty: Boolean = false, val at: Long = 0) {
    companion object {
        fun from(d: DocumentSnapshot) = DutyLog(d.id, d.str("driverUid"), d.str("driverName"), d.getBoolean("onDuty") ?: false, d.long("at"))
    }
}

data class Stop(
    val name: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val requestId: String = "",
    val department: String = "",
    val arrivedAt: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name, "lat" to lat, "lng" to lng, "requestId" to requestId,
        "department" to department, "arrivedAt" to arrivedAt,
    )

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun listFrom(raw: Any?): List<Stop> = (raw as? List<Any?>).orEmpty().mapNotNull { item ->
            val m = item as? Map<String, Any?> ?: return@mapNotNull null
            Stop(
                name = m["name"] as? String ?: "",
                lat = (m["lat"] as? Number)?.toDouble(),
                lng = (m["lng"] as? Number)?.toDouble(),
                requestId = m["requestId"] as? String ?: "",
                department = normDept(m["department"] as? String ?: ""),
                arrivedAt = (m["arrivedAt"] as? Number)?.toLong(),
            )
        }
    }
}

enum class RequestStatus(val label: String) {
    PENDING("Pending"), APPROVED("Approved"), REJECTED("Rejected"),
    CANCELLED("Cancelled"), IN_PROGRESS("On the road"), COMPLETED("Completed");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: PENDING
    }
}

data class TripRequest(
    val id: String = "",
    val requesterUid: String = "",
    val requesterName: String = "",
    val department: String = "",
    val category: String = "",
    val purpose: String = "",
    val stops: List<Stop> = emptyList(),
    val requestedTime: Long = 0,
    val passengers: Int = 1,
    val notes: String = "",
    val status: RequestStatus = RequestStatus.PENDING,
    /** "trip:<id>" or "sched:<scheduleId>:<yyyyMMdd>" when the HOD asks to join an existing run. */
    val joinTarget: String = "",
    val joinTargetLabel: String = "",
    val approverName: String = "",
    val decidedAt: Long? = null,
    val decisionComment: String = "",
    val tripId: String = "",
    val createdAt: Long = 0,
    /** Driver who accepted (or was assigned) the trip carrying this request. */
    val driverName: String = "",
    val acceptedAt: Long? = null,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "requesterUid" to requesterUid, "requesterName" to requesterName, "department" to department,
        "category" to category, "purpose" to purpose, "stops" to stops.map { it.toMap() },
        "requestedTime" to requestedTime, "passengers" to passengers, "notes" to notes,
        "status" to status.name, "joinTarget" to joinTarget, "joinTargetLabel" to joinTargetLabel,
        "approverName" to approverName, "decidedAt" to decidedAt, "decisionComment" to decisionComment,
        "tripId" to tripId, "createdAt" to createdAt,
    )

    companion object {
        fun from(d: DocumentSnapshot) = TripRequest(
            id = d.id,
            requesterUid = d.str("requesterUid"),
            requesterName = d.str("requesterName"),
            department = normDept(d.str("department")),
            category = d.str("category"),
            purpose = d.str("purpose"),
            stops = Stop.listFrom(d.get("stops")),
            requestedTime = d.long("requestedTime"),
            passengers = d.long("passengers").toInt(),
            notes = d.str("notes"),
            status = RequestStatus.of(d.getString("status")),
            joinTarget = d.str("joinTarget"),
            joinTargetLabel = d.str("joinTargetLabel"),
            approverName = d.str("approverName"),
            decidedAt = d.getLong("decidedAt"),
            decisionComment = d.str("decisionComment"),
            tripId = d.str("tripId"),
            createdAt = d.long("createdAt"),
            driverName = d.str("driverName"),
            acceptedAt = d.getLong("acceptedAt"),
        )
    }
}

enum class TripType(val label: String) {
    REQUEST("Approved request"), SCHEDULED("Scheduled (pre-approved)"), UNAUTHORIZED("Unauthorized movement");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: REQUEST
    }
}

enum class TripStatus(val label: String) {
    /** Approved, waiting for a driver to accept. */
    READY("Waiting for driver"),
    /** A driver accepted / was assigned; ride not started yet. */
    ASSIGNED("Driver assigned"),
    IN_PROGRESS("On the road"), COMPLETED("Completed"), CANCELLED("Cancelled");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.name == s } ?: READY
    }
}

data class Trip(
    val id: String = "",
    val type: TripType = TripType.REQUEST,
    val status: TripStatus = TripStatus.READY,
    val title: String = "",
    val category: String = "",
    val purpose: String = "",
    val departments: List<String> = emptyList(),
    val requestIds: List<String> = emptyList(),
    val scheduleId: String = "",
    val stops: List<Stop> = emptyList(),
    val plannedTime: Long = 0,
    val passengers: Int = 0,
    val driverUid: String = "",
    val driverName: String = "",
    val startTime: Long? = null,
    val endTime: Long? = null,
    val distanceKm: Double = 0.0,
    /** Raw GPS distance before the admin's correction factor. */
    val gpsDistanceKm: Double = 0.0,
    val durationMin: Double = 0.0,
    val movingMin: Double = 0.0,
    val stoppedMin: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val fuelLiters: Double = 0.0,
    val fuelCost: Double = 0.0,
    val fuelPrice: Double = 0.0,
    val kmPerLiter: Double = 0.0,
    val needsReason: Boolean = false,
    val reason: String = "",
    val reasonAt: Long? = null,
    val reviewStatus: String = "",
    val reviewedBy: String = "",
    val createdAt: Long = 0,
    /** When the trip became open for drivers (approval, or a driver releasing it). */
    val openedAt: Long = 0,
    val assignedAt: Long? = null,
    val vehicleId: String = "",
    val vehicleName: String = "",
) {
    val isActive get() = status == TripStatus.IN_PROGRESS
    val isOpen get() = status == TripStatus.READY || status == TripStatus.ASSIGNED

    companion object {
        fun from(d: DocumentSnapshot) = Trip(
            id = d.id,
            type = TripType.of(d.getString("type")),
            status = TripStatus.of(d.getString("status")),
            title = d.str("title"),
            category = d.str("category"),
            purpose = d.str("purpose"),
            departments = d.strList("departments").map(::normDept).distinct(),
            requestIds = d.strList("requestIds"),
            scheduleId = d.str("scheduleId"),
            stops = Stop.listFrom(d.get("stops")),
            plannedTime = d.long("plannedTime"),
            passengers = d.long("passengers").toInt(),
            driverUid = d.str("driverUid"),
            driverName = d.str("driverName"),
            openedAt = d.long("openedAt").takeIf { it > 0 } ?: d.long("createdAt"),
            assignedAt = d.getLong("assignedAt"),
            vehicleId = d.str("vehicleId"),
            vehicleName = d.str("vehicleName"),
            startTime = d.getLong("startTime"),
            endTime = d.getLong("endTime"),
            distanceKm = d.dbl("distanceKm"),
            gpsDistanceKm = d.dbl("gpsDistanceKm").takeIf { it > 0 } ?: d.dbl("distanceKm"),
            durationMin = d.dbl("durationMin"),
            movingMin = d.dbl("movingMin"),
            stoppedMin = d.dbl("stoppedMin"),
            maxSpeedKmh = d.dbl("maxSpeedKmh"),
            fuelLiters = d.dbl("fuelLiters"),
            fuelCost = d.dbl("fuelCost"),
            fuelPrice = d.dbl("fuelPrice"),
            kmPerLiter = d.dbl("kmPerLiter"),
            needsReason = d.getBoolean("needsReason") ?: false,
            reason = d.str("reason"),
            reasonAt = d.getLong("reasonAt"),
            reviewStatus = d.str("reviewStatus"),
            reviewedBy = d.str("reviewedBy"),
            createdAt = d.long("createdAt"),
        )
    }
}

data class Schedule(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val purpose: String = "",
    val department: String = "",
    val stops: List<Stop> = emptyList(),
    /** ISO days 1 = Monday .. 7 = Sunday */
    val days: List<Int> = emptyList(),
    val time: String = "08:00",
    val active: Boolean = true,
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "title" to title, "category" to category, "purpose" to purpose, "department" to department,
        "stops" to stops.map { it.toMap() }, "days" to days, "time" to time, "active" to active,
    )

    companion object {
        fun from(d: DocumentSnapshot) = Schedule(
            id = d.id,
            title = d.str("title"),
            category = d.str("category"),
            purpose = d.str("purpose"),
            department = normDept(d.str("department")),
            stops = Stop.listFrom(d.get("stops")),
            days = (d.get("days") as? List<*>).orEmpty().mapNotNull { (it as? Number)?.toInt() },
            time = d.getString("time") ?: "08:00",
            active = d.getBoolean("active") ?: true,
        )
    }
}

data class AlertItem(
    val id: String = "",
    val type: String = "",
    val severity: String = "INFO",
    val title: String = "",
    val message: String = "",
    val tripId: String = "",
    val createdAt: Long = 0,
    val acknowledged: Boolean = false,
    val acknowledgedBy: String = "",
) {
    companion object {
        const val UNAUTHORIZED_MOVEMENT = "UNAUTHORIZED_MOVEMENT"
        const val MISSING_REASON = "MISSING_REASON"

        fun from(d: DocumentSnapshot) = AlertItem(
            id = d.id,
            type = d.str("type"),
            severity = d.getString("severity") ?: "INFO",
            title = d.str("title"),
            message = d.str("message"),
            tripId = d.str("tripId"),
            createdAt = d.long("createdAt"),
            acknowledged = d.getBoolean("acknowledged") ?: false,
            acknowledgedBy = d.str("acknowledgedBy"),
        )
    }
}

data class FuelLog(
    val id: String = "",
    val time: Long = 0,
    val liters: Double = 0.0,
    val cost: Double = 0.0,
    val odometerKm: Double = 0.0,
    val note: String = "",
    val byName: String = "",
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "time" to time, "liters" to liters, "cost" to cost, "odometerKm" to odometerKm,
        "note" to note, "byName" to byName,
    )

    companion object {
        fun from(d: DocumentSnapshot) = FuelLog(
            id = d.id, time = d.long("time"), liters = d.dbl("liters"), cost = d.dbl("cost"),
            odometerKm = d.dbl("odometerKm"), note = d.str("note"), byName = d.str("byName"),
        )
    }
}

/** Admin-configurable settings, stored in config/settings. */
data class AppSettings(
    /** Legacy single-vehicle fields – used to seed the first vehicle. */
    val vehicleName: String = "Staff Van",
    val plateNo: String = "",
    val currency: String = "LKR",
    /** Diesel price per litre. */
    val fuelPricePerLiter: Double = 300.0,
    val petrolPricePerLiter: Double = 310.0,
    /** Default km/L for vehicles that don't set their own. */
    val kmPerLiter: Double = 9.0,
    /** Correction applied to GPS distance, e.g. +3 if GPS reads 3% less than the odometer. */
    val distanceCorrectionPct: Double = 0.0,
    val hotelName: String = "Hotel",
    val hotelLat: Double = 0.0,
    val hotelLng: Double = 0.0,
    val geofenceRadiusM: Double = 300.0,
    val movementSpeedKmh: Double = 12.0,
    val minGpsAccuracyM: Double = 35.0,
    /** Dedicated vehicle phones: charger power = ignition on. */
    val usePowerTrigger: Boolean = true,
    /** Hour of the day (0-23) by which unauthorized movement reasons must be entered. */
    val reasonDeadlineHour: Int = 22,
    val autoEndUnauthorizedMin: Int = 5,
) {
    val hotelSet get() = hotelLat != 0.0 || hotelLng != 0.0

    fun priceFor(fuelType: String) = if (fuelType.equals("Petrol", ignoreCase = true)) petrolPricePerLiter else fuelPricePerLiter

    fun toMap(): Map<String, Any?> = mapOf(
        "vehicleName" to vehicleName, "plateNo" to plateNo, "currency" to currency,
        "fuelPricePerLiter" to fuelPricePerLiter, "petrolPricePerLiter" to petrolPricePerLiter, "kmPerLiter" to kmPerLiter,
        "distanceCorrectionPct" to distanceCorrectionPct, "hotelName" to hotelName,
        "hotelLat" to hotelLat, "hotelLng" to hotelLng, "geofenceRadiusM" to geofenceRadiusM,
        "movementSpeedKmh" to movementSpeedKmh, "minGpsAccuracyM" to minGpsAccuracyM,
        "usePowerTrigger" to usePowerTrigger,
        "reasonDeadlineHour" to reasonDeadlineHour, "autoEndUnauthorizedMin" to autoEndUnauthorizedMin,
    )

    companion object {
        fun from(d: DocumentSnapshot?): AppSettings {
            if (d == null || !d.exists()) return AppSettings()
            val def = AppSettings()
            return AppSettings(
                vehicleName = d.getString("vehicleName") ?: def.vehicleName,
                plateNo = d.str("plateNo"),
                currency = d.getString("currency") ?: def.currency,
                fuelPricePerLiter = d.getDouble("fuelPricePerLiter") ?: def.fuelPricePerLiter,
                petrolPricePerLiter = d.getDouble("petrolPricePerLiter") ?: def.petrolPricePerLiter,
                kmPerLiter = d.getDouble("kmPerLiter") ?: def.kmPerLiter,
                distanceCorrectionPct = d.getDouble("distanceCorrectionPct") ?: 0.0,
                hotelName = d.getString("hotelName") ?: def.hotelName,
                hotelLat = d.getDouble("hotelLat") ?: 0.0,
                hotelLng = d.getDouble("hotelLng") ?: 0.0,
                geofenceRadiusM = d.getDouble("geofenceRadiusM") ?: def.geofenceRadiusM,
                movementSpeedKmh = d.getDouble("movementSpeedKmh") ?: def.movementSpeedKmh,
                minGpsAccuracyM = d.getDouble("minGpsAccuracyM") ?: def.minGpsAccuracyM,
                usePowerTrigger = d.getBoolean("usePowerTrigger") ?: true,
                reasonDeadlineHour = d.getLong("reasonDeadlineHour")?.toInt() ?: def.reasonDeadlineHour,
                autoEndUnauthorizedMin = d.getLong("autoEndUnauthorizedMin")?.toInt() ?: def.autoEndUnauthorizedMin,
            )
        }
    }
}

val VEHICLE_TYPES = listOf("Van", "Car", "SUV", "Mini Bus", "Bus", "Lorry", "Three-wheeler", "Motorbike")
val FUEL_TYPES = listOf("Diesel", "Petrol")

/** A hotel vehicle (collection vehicles), managed by the administrator. */
data class Vehicle(
    val id: String = "",
    val name: String = "",
    val plateNo: String = "",
    val type: String = "Van",
    val seats: Int = 0,
    val fuelType: String = "Diesel",
    val kmPerLiter: Double = 9.0,
    val active: Boolean = true,
    val notes: String = "",
) {
    val label get() = if (plateNo.isBlank()) name else "$name · $plateNo"

    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name, "plateNo" to plateNo, "type" to type, "seats" to seats,
        "fuelType" to fuelType, "kmPerLiter" to kmPerLiter, "active" to active, "notes" to notes,
    )

    companion object {
        /** Id used for the original single van, so trips recorded before vehicles existed still match. */
        const val DEFAULT_ID = "default"

        fun from(d: DocumentSnapshot) = Vehicle(
            id = d.id, name = d.str("name"), plateNo = d.str("plateNo"), type = d.getString("type") ?: "Van",
            seats = d.long("seats").toInt(), fuelType = d.getString("fuelType") ?: "Diesel",
            kmPerLiter = d.dbl("kmPerLiter").takeIf { it > 0 } ?: 9.0, active = d.getBoolean("active") ?: true,
            notes = d.str("notes"),
        )

        /** Until the admin adds vehicles, the van from the old single-vehicle settings. */
        fun fallback(s: AppSettings) = Vehicle(DEFAULT_ID, s.vehicleName.ifBlank { "Staff Van" }, s.plateNo, "Van", 0, "Diesel", s.kmPerLiter)
    }
}

/** Live position of one vehicle, in live/{vehicleId}. */
data class LiveStatus(
    val vehicleId: String = "",
    val vehicleName: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val speedKmh: Double = 0.0,
    /** Direction of travel, degrees clockwise from north – rotates the car icon. */
    val bearing: Float = 0f,
    val updatedAt: Long = 0,
    val mode: String = "OFFLINE",
    val tripId: String = "",
    val tripTitle: String = "",
    val driverName: String = "",
    val driverUid: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
) {
    val onTrip get() = mode == "TRIP" || mode == "UNAUTHORIZED"

    companion object {
        fun from(d: DocumentSnapshot?): LiveStatus {
            if (d == null || !d.exists()) return LiveStatus()
            return LiveStatus(
                vehicleId = d.id, vehicleName = d.str("vehicleName"),
                lat = d.dbl("lat"), lng = d.dbl("lng"), speedKmh = d.dbl("speedKmh"),
                bearing = d.dbl("bearing").toFloat(),
                updatedAt = d.long("updatedAt"), mode = d.getString("mode") ?: "OFFLINE",
                tripId = d.str("tripId"), tripTitle = d.str("tripTitle"), driverName = d.str("driverName"),
                driverUid = d.str("driverUid"),
                battery = d.getLong("battery")?.toInt() ?: -1, charging = d.getBoolean("charging") ?: false,
            )
        }
    }
}

data class TrackPoint(val lat: Double, val lng: Double, val time: Long, val speedKmh: Double)

// ---- DocumentSnapshot helpers ----
fun DocumentSnapshot.str(f: String): String = getString(f) ?: ""
fun DocumentSnapshot.long(f: String): Long = (get(f) as? Number)?.toLong() ?: 0L
fun DocumentSnapshot.dbl(f: String): Double = (get(f) as? Number)?.toDouble() ?: 0.0
fun DocumentSnapshot.strList(f: String): List<String> = (get(f) as? List<*>).orEmpty().filterIsInstance<String>()
