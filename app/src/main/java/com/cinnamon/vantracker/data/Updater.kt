package com.cinnamon.vantracker.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.cinnamon.vantracker.BuildConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer APK. Installing the new APK over the old one keeps
 * everything on the phone (setup, sign-in, an in-progress trip) because every build is
 * signed with the same key; all business data lives in Firestore anyway.
 */
object Updater {
    private const val REPO = "shehan-welagedara/Cinnamon-Staff-Van-Tracker-"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Latest published release, or null when offline / unavailable. */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            try {
                if (c.responseCode != 200) return@runCatching null
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val tag = j.getString("tag_name") // v1.0.<build>
                val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
                val assets = j.optJSONArray("assets")
                var apk = RELEASES_PAGE
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) apk = a.optString("browser_download_url", apk)
                }
                Release(code, tag, apk, j.optString("body"))
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    suspend fun newerRelease(): Release? = latest()?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }

    /** Downloads the release APK into the app's cache, reporting progress 0..1. */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(dir, "VanTracker-${r.name}.apk")
        var url = URL(r.apkUrl)
        // Follow GitHub's redirect to the file host by hand (keeps working across hosts).
        var c = url.openConnection() as HttpURLConnection
        var hops = 0
        while (true) {
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, c.getHeaderField("Location"))
                c.disconnect()
                c = url.openConnection() as HttpURLConnection
                continue
            }
            if (code != 200) throw IllegalStateException("Download failed (HTTP $code)")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: -1L
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        c.disconnect()
        out
    }

    /** Android must allow this app to install updates ("Install unknown apps"), once per phone. */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Hands the APK to Android's installer – it installs over the current app, keeping all data. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
