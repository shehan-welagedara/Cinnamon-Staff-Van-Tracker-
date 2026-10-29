package com.cinnamon.vantracker.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cinnamon.vantracker.data.AlertItem
import com.cinnamon.vantracker.data.AppSettings
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.RequestStatus
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.data.Trip
import com.cinnamon.vantracker.data.TripRequest
import com.cinnamon.vantracker.data.TripStatus
import com.cinnamon.vantracker.tracking.TripCalc
import com.cinnamon.vantracker.data.UserProfile
import com.cinnamon.vantracker.tracking.DevicePrefs
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

private suspend fun settingsMin(): Int = runCatching {
    AppSettings.from(Repo.settingsDoc.get().await()).minVersionCode
}.getOrDefault(0)

/**
 * Zero-cost notifications (no paid Cloud Functions / push server): every phone checks
 * Firestore every 15 minutes for news relevant to its user. Screens that are open
 * update instantly through live listeners.
 */
class WatchWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val NAME = "watch"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<WatchWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context) = WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Backend.init(ctx)) return Result.success()
        val uid = Backend.auth.currentUser?.uid ?: return Result.success()
        val now = System.currentTimeMillis()
        val last = DevicePrefs.getLong(ctx, "lastWatch")
        DevicePrefs.putLong(ctx, "lastWatch", now)
        if (last == 0L) return Result.success()

        runCatching {
            val me = Repo.users.document(uid).get().await().let { if (it.exists()) UserProfile.from(it) else null }
                ?: return Result.success()
            val settings = AppSettings.from(runCatching { Repo.settingsDoc.get().await() }.getOrNull())

            if (me.isManager || me.isAdmin) {
                val pending = Repo.requests.whereEqualTo("status", RequestStatus.PENDING.name).get().await()
                    .documents.map(TripRequest::from).filter { it.createdAt > last }
                if (pending.isNotEmpty()) {
                    Notifier.show(
                        ctx, 2001, Notifier.CH_REQUESTS,
                        if (pending.size == 1) "New trip request" else "${pending.size} new trip requests",
                        pending.joinToString("\n") { "${it.requesterName} (${it.department}): ${it.category} – ${it.purpose}" },
                        open = "approvals",
                    )
                }
                val newAlerts = Repo.alerts.whereGreaterThan("createdAt", last).get().await()
                    .documents.map(AlertItem::from).filter { !it.acknowledged }
                newAlerts.forEach {
                    Notifier.show(ctx, it.id.hashCode(), Notifier.CH_ALERTS, it.title, it.message, open = "alerts")
                }
            }

            if (me.isDriver) {
                // Drivers who are off duty / not in the app still hear about new trips.
                val open = Repo.trips.whereIn("status", listOf(TripStatus.READY.name, TripStatus.ASSIGNED.name)).get().await()
                    .documents.map(Trip::from)
                open.filter { it.status == TripStatus.READY && it.driverUid.isBlank() && it.openedAt > last }.forEach {
                    Notifier.show(ctx, it.id.hashCode(), Notifier.CH_ALERTS, "New trip request – tap to accept",
                        "${it.title} · ${TripCalc.dateTime(it.plannedTime)}")
                }
                open.filter { it.status == TripStatus.ASSIGNED && it.driverUid == uid && (it.assignedAt ?: 0) > last }.forEach {
                    Notifier.show(ctx, it.id.hashCode(), Notifier.CH_ALERTS, "Trip assigned to you",
                        "${it.title} · ${TripCalc.dateTime(it.plannedTime)}")
                }
            }

            val mine = Repo.requests.whereEqualTo("requesterUid", uid).get().await().documents.map(TripRequest::from)
            mine.filter { (it.acceptedAt ?: 0) > last && it.driverName.isNotBlank() }.forEach {
                Notifier.show(ctx, it.id.hashCode() + 1, Notifier.CH_REQUESTS, "Driver confirmed",
                    "${it.driverName} will take your ${it.category} trip (${TripCalc.dateTime(it.requestedTime)}).", open = "requests")
            }
            val decided = mine.filter { (it.decidedAt ?: 0) > last }
            decided.forEach {
                val verb = when (it.status) {
                    RequestStatus.APPROVED -> "approved"
                    RequestStatus.REJECTED -> "rejected"
                    else -> return@forEach
                }
                Notifier.show(
                    ctx, it.id.hashCode(), Notifier.CH_REQUESTS, "Trip request $verb",
                    "${it.category}: ${it.purpose}" + (if (it.decisionComment.isNotBlank()) "\n“${it.decisionComment}” – ${it.approverName}" else ""),
                    open = "requests",
                )
            }

            Repo.runReasonDeadlineCheck(settings)
        }

        // About twice a day: tell the phone a newer app version is ready (one tap installs it).
        if (now - DevicePrefs.getLong(ctx, "lastUpdateCheck") > 12 * 3600_000L) {
            DevicePrefs.putLong(ctx, "lastUpdateCheck", now)
            com.cinnamon.vantracker.data.Updater.newerRelease()?.let { r ->
                val required = com.cinnamon.vantracker.BuildConfig.VERSION_CODE < settingsMin()
                Notifier.show(
                    ctx, 2900, if (required) Notifier.CH_ALERTS else Notifier.CH_REQUESTS,
                    if (required) "Van Tracker update required" else "Van Tracker ${r.name} available",
                    "Tap to install – your data and settings are kept.", open = "update",
                )
            }
        }
        return Result.success()
    }
}
