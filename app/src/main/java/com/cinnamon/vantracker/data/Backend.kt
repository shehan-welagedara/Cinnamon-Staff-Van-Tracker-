package com.cinnamon.vantracker.data

import android.content.Context
import android.util.Base64
import com.cinnamon.vantracker.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import org.json.JSONObject

/**
 * The backend is the hotel's own free Firebase project. Its connection details are
 * either baked in at build time (GitHub secrets) or entered once per phone on the
 * setup screen (paste google-services.json, or a setup code shared from another phone).
 */
data class FbConfig(val apiKey: String, val appId: String, val projectId: String) {
    val valid get() = apiKey.isNotBlank() && appId.isNotBlank() && projectId.isNotBlank()

    /** Compact code that an admin can share (WhatsApp/email) to set up other phones. */
    fun toSetupCode(): String {
        val json = JSONObject().put("k", apiKey).put("a", appId).put("p", projectId).toString()
        return "VT1:" + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        /** Accepts a setup code (VT1:...) or the full google-services.json text. */
        fun parse(text: String): FbConfig? = runCatching {
            val t = text.trim()
            if (t.startsWith("VT1:")) {
                val j = JSONObject(String(Base64.decode(t.removePrefix("VT1:"), Base64.DEFAULT)))
                return FbConfig(j.getString("k"), j.getString("a"), j.getString("p"))
            }
            val j = JSONObject(t)
            val projectId = j.getJSONObject("project_info").getString("project_id")
            val clients = j.getJSONArray("client")
            // Prefer the client registered for this package, else take the first one.
            var client = clients.getJSONObject(0)
            for (i in 0 until clients.length()) {
                val c = clients.getJSONObject(i)
                val pkg = c.getJSONObject("client_info").getJSONObject("android_client_info").optString("package_name")
                if (pkg == "com.cinnamon.vantracker") client = c
            }
            val appId = client.getJSONObject("client_info").getString("mobilesdk_app_id")
            val apiKey = client.getJSONArray("api_key").getJSONObject(0).getString("current_key")
            FbConfig(apiKey, appId, projectId)
        }.getOrNull()?.takeIf { it.valid }
    }
}

object Backend {
    private const val PREFS = "backend"

    @Volatile
    var initialized = false
        private set

    fun storedConfig(ctx: Context): FbConfig? {
        val built = FbConfig(BuildConfig.FB_API_KEY, BuildConfig.FB_APP_ID, BuildConfig.FB_PROJECT_ID)
        if (built.valid) return built
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return FbConfig(
            p.getString("apiKey", "") ?: "",
            p.getString("appId", "") ?: "",
            p.getString("projectId", "") ?: "",
        ).takeIf { it.valid }
    }

    fun saveConfig(ctx: Context, c: FbConfig) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("apiKey", c.apiKey).putString("appId", c.appId).putString("projectId", c.projectId)
            .apply()
    }

    fun clearConfig(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** Returns true when Firebase is ready to use. Safe to call repeatedly. */
    @Synchronized
    fun init(ctx: Context): Boolean {
        if (initialized) return true
        val cfg = storedConfig(ctx) ?: return false
        if (FirebaseApp.getApps(ctx).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(cfg.apiKey)
                .setApplicationId(cfg.appId)
                .setProjectId(cfg.projectId)
                .build()
            FirebaseApp.initializeApp(ctx.applicationContext, options)
        }
        // Offline-first: every read is served from the on-device cache when there is no
        // signal, and writes are queued and synced automatically once back online.
        FirebaseFirestore.getInstance().firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder().setSizeBytes(100L * 1024 * 1024).build()
            )
            .build()
        initialized = true
        return true
    }

    val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
}
