package com.cinnamon.vantracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinnamon.vantracker.BuildConfig
import com.cinnamon.vantracker.data.Updater
import kotlinx.coroutines.launch
import java.io.File

/** App-wide update state: newest release, download progress and the downloaded file. */
object UpdateCache {
    var checked = false
    var release by mutableStateOf<Updater.Release?>(null)
    var progress by mutableFloatStateOf(-1f)   // -1 = not downloading
    var file by mutableStateOf<File?>(null)
    var error by mutableStateOf("")
    var dialog by mutableStateOf(false)

    suspend fun check() {
        checked = true
        release = Updater.newerRelease()
    }
}

/** Shown at the top of the home screens when a newer APK is published. */
@Composable
fun UpdateBanner() {
    LaunchedEffect(Unit) { if (!UpdateCache.checked) UpdateCache.check() }
    val r = UpdateCache.release ?: return
    Banner("Update ${r.name} is ready. Tap to install – your data and settings are kept.", InfoBlue) {
        UpdateCache.dialog = true
    }
}

/** The update dialog lives at the app root so it survives scrolling and screen changes. */
@Composable
fun UpdateDialogHost() {
    LaunchedEffect(Unit) { if (!UpdateCache.checked) UpdateCache.check() }
}
/**
 * One-tap update: downloads the APK inside the app and opens Android's installer, which
 * installs it over the current version (nothing is lost).
 */
@Composable
fun UpdateDialog(r: Updater.Release, onDismiss: (() -> Unit)?) {
    val ctx = LocalContext.current
    val downloading = UpdateCache.progress >= 0f && UpdateCache.file == null
    AlertDialog(
        onDismissRequest = { onDismiss?.invoke() },
        title = { Text("Update to ${r.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("You have ${Updater.currentVersion}. The update installs over this version – sign-in, settings and trips are kept.")
                if (r.notes.isNotBlank()) Text(r.notes.take(400), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                if (downloading) {
                    LinearProgressIndicator(progress = { UpdateCache.progress }, modifier = Modifier.fillMaxWidth())
                    Text("Downloading… ${(UpdateCache.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (!Updater.canInstall(ctx)) Text(
                    "First time only: Android will ask you to allow Van Tracker to install updates. Turn it on, come back, and tap Install.",
                    color = WarnOrange, style = MaterialTheme.typography.bodySmall,
                )
                if (UpdateCache.error.isNotBlank()) Text(UpdateCache.error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(enabled = !downloading, onClick = {
                UpdateCache.error = ""
                if (!Updater.canInstall(ctx)) { Updater.openInstallPermission(ctx); return@Button }
                val f = UpdateCache.file
                if (f != null && f.exists()) { runCatching { Updater.install(ctx, f) }.onFailure { UpdateCache.error = it.message ?: "Install failed" }; return@Button }
                UpdateCache.progress = 0f
                AppScope.launch {
                    runCatching { Updater.download(ctx.applicationContext, r) { UpdateCache.progress = it } }
                        .onSuccess { UpdateCache.file = it; runCatching { Updater.install(ctx, it) } }
                        .onFailure { UpdateCache.error = "Download failed – check the internet connection. (${it.message})"; UpdateCache.progress = -1f }
                }
            }) { Text(if (UpdateCache.file != null) "Install" else if (downloading) "Downloading…" else "Download & install") }
        },
        dismissButton = { if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Later") } },
    )
}

/** Blocks the app when the administrator requires a newer version on every phone. */
@Composable
fun RequiredUpdateScreen(minVersion: Int) {
    LaunchedEffect(Unit) { UpdateCache.check() }
    val r = UpdateCache.release
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Update required", style = MaterialTheme.typography.headlineMedium, color = CinnamonDark)
        Spacer(Modifier.height(10.dp))
        Text("Your administrator asks every phone to use version 1.0.$minVersion or newer. You have ${Updater.currentVersion}.")
        Spacer(Modifier.height(20.dp))
        if (r != null && r.versionCode >= minVersion) UpdateDialog(r, onDismiss = null)
        else Text(if (UpdateCache.checked) "Looking for the update… check the internet connection." else "Checking for the update…", color = TextSecondary)
    }
}

/** Version info with a manual "check for updates" button. */
@Composable
fun AboutCard() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    SectionCard("About") {
        InfoRow("Version", Updater.currentVersion)
        UpdateCache.release?.let { InfoRow("Latest", it.name, InfoBlue) }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            status = "Checking…"
            scope.launch {
                val latest = Updater.latest()
                UpdateCache.checked = true
                UpdateCache.release = latest?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
                status = when {
                    latest == null -> "Couldn't reach GitHub – check the internet connection."
                    UpdateCache.release != null -> "A newer version is available."
                    else -> "You have the latest version."
                }
                if (UpdateCache.release != null) UpdateCache.dialog = true
            }
        }) { Text("Check for updates") }
    }
    val r = UpdateCache.release
    if (UpdateCache.dialog && r != null) UpdateDialog(r, onDismiss = { UpdateCache.dialog = false })
}
