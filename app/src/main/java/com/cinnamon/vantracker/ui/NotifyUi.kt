package com.cinnamon.vantracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cinnamon.vantracker.data.Repo
import com.cinnamon.vantracker.notify.Notifier
import com.cinnamon.vantracker.notify.Push
import com.cinnamon.vantracker.tracking.DevicePrefs
import com.cinnamon.vantracker.tracking.TripCalc
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Notification permission request launcher (Android 13+). */
@Composable
fun rememberNotificationPermission(onResult: (Boolean) -> Unit = {}): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return { if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}

/**
 * Right after sign-in: ask for notification permission once, and register this phone for
 * instant push notifications.
 */
@Composable
fun NotificationBootstrap(uid: String) {
    val ctx = LocalContext.current
    val ask = rememberNotificationPermission()
    LaunchedEffect(uid) {
        if (!Notifier.canNotify(ctx) && !DevicePrefs.getBool(ctx, "askedNotifications")) {
            DevicePrefs.putBool(ctx, "askedNotifications", true)
            ask()
        }
        Push.register(ctx.applicationContext, uid)
    }
}

/** Banner on the home screen while notifications are blocked on this phone. */
@Composable
fun NotificationsOffBanner(s: Session) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val resume = rememberResumeCount()
    val ask = rememberNotificationPermission { tick++ }
    key(tick, resume) {
        if (!Notifier.canNotify(ctx)) Banner("🔕 Notifications are off – you won't hear about requests and trips. Tap to turn them on.", DangerRed) {
            if (Build.VERSION.SDK_INT >= 33 && !DevicePrefs.getBool(ctx, "askedNotifications2")) {
                DevicePrefs.putBool(ctx, "askedNotifications2", true); ask()
            } else s.nav.go(Screen.Notifications)
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
fun NotificationsScreen(s: Session) {
    val ctx = LocalContext.current
    val resume = rememberResumeCount()
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberNotificationPermission { tick++ }
    var msg by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Scaffold(topBar = { AppBar("Notifications", onBack = { s.nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            key(tick, resume) {
                val allowed = Notifier.canNotify(ctx)
                val battery = DriverPermissions.battery(ctx)
                val token = Push.token(ctx)
                val received = Push.lastReceivedAt(ctx)
                SectionCard("This phone") {
                    InfoRow("Notifications allowed", if (allowed) "Yes" else "No – turn on", if (allowed) OkGreen else DangerRed)
                    InfoRow("Battery restrictions", if (battery) "None" else "Restricted", if (battery) OkGreen else WarnOrange)
                    InfoRow("Instant push", if (token.isNotBlank()) "Registered" else "Not registered", if (token.isNotBlank()) OkGreen else WarnOrange)
                    if (received > 0) InfoRow("Last push received", TripCalc.dateTime(received))
                    if (token.isBlank() && Push.lastError(ctx).isNotBlank()) Text(Push.lastError(ctx), color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!allowed) Button(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && !DevicePrefs.getBool(ctx, "askedNotifications3")) {
                                DevicePrefs.putBool(ctx, "askedNotifications3", true); ask()
                            } else openAppNotificationSettings(ctx)
                        }, modifier = Modifier.fillMaxWidth()) { Text("Turn on notifications") }
                        if (!battery) OutlinedButton(onClick = {
                            runCatching { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Allow running in the background") }
                        OutlinedButton(onClick = { openAppNotificationSettings(ctx) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Sound & pop-up settings")
                        }
                    }
                }
            }
            SectionCard("Test") {
                Text(
                    "“Test this phone” shows a notification straight away. “Test instant push” asks the server to send one – " +
                        "it should arrive within seconds, even with the app closed.",
                    color = TextSecondary, fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        Notifier.show(ctx, 2951, Notifier.CH_ALERTS, "Test notification", "Notifications work on this phone ✔")
                        msg = if (Notifier.canNotify(ctx)) "Sent – you should see it at the top of the screen." else "Notifications are off on this phone."
                    }) { Text("Test this phone") }
                    Button(onClick = {
                        msg = "Asking the server…"
                        scope.launch {
                            Push.register(ctx.applicationContext, s.me.uid)
                            msg = runCatching {
                                Repo.pushTests.add(mapOf("uid" to s.me.uid, "at" to System.currentTimeMillis())).await()
                                "Requested – close the app and wait a few seconds. If nothing arrives, instant push isn't set up yet (see README)."
                            }.getOrElse { "Couldn't reach the server: ${it.message}" }
                        }
                    }) { Text("Test instant push") }
                }
                if (msg.isNotBlank()) Text(msg, fontSize = 13.sp, color = InfoBlue)
            }
            SectionCard("Tips") {
                Text(
                    "• Keep notifications on for Van Tracker and its “Trip requests” and “Alerts” categories.\n" +
                        "• Samsung: Settings → Battery → Background usage limits → make sure Van Tracker is not sleeping.\n" +
                        "• Xiaomi / Redmi: Settings → Apps → Van Tracker → Autostart on, Battery saver: No restrictions.\n" +
                        "• Huawei / Honor: Battery → App launch → Van Tracker → Manage manually, all on.",
                    fontSize = 13.sp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

fun openAppNotificationSettings(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
    }
}

/** Increments every time the screen resumes (e.g. coming back from Android settings). */
@Composable
fun rememberResumeCount(): Int {
    var n by remember { mutableIntStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) n++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    return n
}
