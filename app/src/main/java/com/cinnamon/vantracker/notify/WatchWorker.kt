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
import com.cinnamon.vantracker.data.TripRequest
import com.cinnamon.vantracker.data.UserProfile
import com.cinnamon.vantracker.tracking.DevicePrefs
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

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

            val decided = Repo.requests.whereEqualTo("requesterUid", uid).get().await()
                .documents.map(TripRequest::from).filter { (it.decidedAt ?: 0) > last }
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
        return Result.success()
    }
}
