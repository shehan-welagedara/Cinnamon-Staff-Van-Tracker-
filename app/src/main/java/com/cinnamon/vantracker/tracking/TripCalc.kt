package com.cinnamon.vantracker.tracking

import com.cinnamon.vantracker.data.AppSettings
import com.cinnamon.vantracker.data.Vehicle
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object TripCalc {
    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Fuel is derived purely from GPS distance:
     *   km      = gpsKm × (1 + correction%)
     *   litres  = km ÷ the vehicle's km-per-litre
     *   cost    = litres × price-per-litre of the vehicle's fuel (diesel / petrol)
     */
    fun fuel(gpsKm: Double, s: AppSettings, v: Vehicle? = null): Triple<Double, Double, Double> {
        val km = gpsKm * (1 + s.distanceCorrectionPct / 100.0)
        val kmpl = kmPerLiter(s, v)
        val liters = if (kmpl > 0) km / kmpl else 0.0
        val cost = liters * s.priceFor(v?.fuelType ?: "Diesel")
        return Triple(round(km, 2), round(liters, 2), round(cost, 2))
    }

    fun kmPerLiter(s: AppSettings, v: Vehicle?) = v?.kmPerLiter?.takeIf { it > 0 } ?: s.kmPerLiter

    /** Compass bearing (degrees clockwise from north) from point 1 to point 2. */
    fun bearing(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lng2 - lng1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    fun round(v: Double, d: Int): Double {
        var f = 1.0
        repeat(d) { f *= 10 }
        return Math.round(v * f) / f
    }

    private val num2 = DecimalFormat("#,##0.00")
    private val num1 = DecimalFormat("#,##0.0")

    fun km(v: Double) = "${num1.format(v)} km"
    fun liters(v: Double) = "${num2.format(v)} L"
    fun money(v: Double, currency: String) = "$currency ${num2.format(v)}"

    fun duration(minutes: Double): String {
        val m = minutes.toLong()
        return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }

    fun durationMs(ms: Long) = duration(ms / 60000.0)

    fun time(t: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
    fun date(t: Long): String = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(t))

    fun dayStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun monthStart(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(t)
        set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    /** ISO day of week, 1 = Monday .. 7 = Sunday. */
    fun isoDay(t: Long): Int {
        val d = Calendar.getInstance().apply { timeInMillis = t }.get(Calendar.DAY_OF_WEEK)
        return if (d == Calendar.SUNDAY) 7 else d - 1
    }
}
