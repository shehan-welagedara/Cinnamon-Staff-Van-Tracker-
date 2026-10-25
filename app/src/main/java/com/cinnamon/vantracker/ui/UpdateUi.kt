package com.cinnamon.vantracker.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cinnamon.vantracker.data.Updater
import kotlinx.coroutines.launch

/** Remembers the newest release once per app session (no repeated network calls). */
private object UpdateCache {
    var checked = false
    var release by mutableStateOf<Updater.Release?>(null)
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Shown at the top of the home screens when a newer APK is published. */
@Composable
fun UpdateBanner() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (!UpdateCache.checked) {
            UpdateCache.checked = true
            UpdateCache.release = Updater.newerRelease()
        }
    }
    val r = UpdateCache.release ?: return
    Banner("Update available: ${r.name}. Tap to download, then open it to install – your data and settings are kept.", InfoBlue) {
        openUrl(ctx, r.apkUrl)
    }
}

/** Version info with a manual "check for updates" button. */
@Composable
fun AboutCard() {
    val ctx = LocalContext.current
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
                UpdateCache.release = latest?.takeIf { it.versionCode > com.cinnamon.vantracker.BuildConfig.VERSION_CODE }
                status = when {
                    latest == null -> "Couldn't reach GitHub – check the internet connection."
                    UpdateCache.release != null -> "A newer version is available."
                    else -> "You have the latest version."
                }
                UpdateCache.release?.let { openUrl(ctx, it.apkUrl) }
            }
        }) { Text("Check for updates") }
    }
}
