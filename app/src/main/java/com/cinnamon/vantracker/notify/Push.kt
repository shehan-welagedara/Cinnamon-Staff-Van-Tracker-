package com.cinnamon.vantracker.notify

import android.content.Context
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.data.Repo
import com.google.firebase.firestore.FieldValue
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.tasks.await

/**
 * Instant notifications through Firebase Cloud Messaging. Each phone stores its push token
 * on the signed-in user's profile (users/{uid}.fcmTokens); the server functions in
 * functions/index.js send a message to the right people the moment a request, trip or
 * alert changes. The 15-minute background check (WatchWorker) stays as a fallback, and
 * both paths use the same notification keys so nothing is shown twice.
 */
object Push {
    private const val PREFS = "push"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context) = p(ctx).getString("token", "") ?: ""
    fun lastReceivedAt(ctx: Context) = p(ctx).getLong("lastReceivedAt", 0L)
    fun lastError(ctx: Context) = p(ctx).getString("error", "") ?: ""

    /** Gets this phone's push token and saves it on the user's profile. Safe to call often. */
    suspend fun register(ctx: Context, uid: String) {
        runCatching {
            val t = FirebaseMessaging.getInstance().token.await()
            val old = token(ctx)
            Repo.users.document(uid).update(
                mapOf("fcmTokens" to FieldValue.arrayUnion(t), "fcmUpdatedAt" to System.currentTimeMillis())
            ).await()
            if (old.isNotBlank() && old != t) runCatching { Repo.users.document(uid).update("fcmTokens", FieldValue.arrayRemove(old)) }
            p(ctx).edit().putString("token", t).putString("uid", uid).putString("error", "").apply()
        }.onFailure { p(ctx).edit().putString("error", it.message ?: it.javaClass.simpleName).apply() }
    }

    /**
     * On sign-out: remove the token from the profile (while still signed in) and retire it,
     * so this phone never receives the previous user's notifications.
     */
    suspend fun unregister(ctx: Context) {
        val t = token(ctx)
        val uid = savedUid(ctx)
        if (t.isNotBlank() && uid.isNotBlank()) {
            kotlinx.coroutines.withTimeoutOrNull(4000) {
                runCatching { Repo.users.document(uid).update("fcmTokens", FieldValue.arrayRemove(t)).await() }
            }
        }
        runCatching { kotlinx.coroutines.withTimeoutOrNull(4000) { FirebaseMessaging.getInstance().deleteToken().await() } }
        p(ctx).edit().remove("uid").remove("token").apply()
    }

    internal fun markReceived(ctx: Context) { p(ctx).edit().putLong("lastReceivedAt", System.currentTimeMillis()).apply() }

    internal fun savedUid(ctx: Context) = p(ctx).getString("uid", "") ?: ""
}

/** Receives push messages (also when the app is closed) and shows them as notifications. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val d = message.data
        val title = d["title"] ?: message.notification?.title ?: return
        val body = d["body"] ?: message.notification?.body ?: ""
        Push.markReceived(this)
        if (d["test"] == "1") {
            Notifier.show(this, 2950, Notifier.CH_ALERTS, title, body)
            return
        }
        Notifier.showOnce(
            this, d["key"] ?: message.messageId ?: title,
            if (d["channel"] == "alerts") Notifier.CH_ALERTS else Notifier.CH_REQUESTS,
            title, body, d["open"] ?: "",
        )
    }

    override fun onNewToken(token: String) {
        val uid = Push.savedUid(this)
        if (uid.isBlank() || !Backend.init(this)) return
        runCatching {
            Repo.users.document(uid).update(mapOf("fcmTokens" to FieldValue.arrayUnion(token), "fcmUpdatedAt" to System.currentTimeMillis()))
        }
        getSharedPreferences("push", MODE_PRIVATE).edit().putString("token", token).apply()
    }
}
