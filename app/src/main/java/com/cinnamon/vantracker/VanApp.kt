package com.cinnamon.vantracker

import android.app.Application
import com.cinnamon.vantracker.data.Backend
import com.cinnamon.vantracker.notify.Notifier
import org.osmdroid.config.Configuration
import java.io.File

class VanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        Configuration.getInstance().apply {
            load(this@VanApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        Backend.init(this)
    }
}
