package com.cinnamon.vantracker.data

import com.cinnamon.vantracker.BuildConfig
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
}
