package com.cinnamon.vantracker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import com.cinnamon.vantracker.notify.Notifier
import com.cinnamon.vantracker.ui.AppRoot
import com.cinnamon.vantracker.ui.Brand
import com.cinnamon.vantracker.ui.GlassBackdrop
import com.cinnamon.vantracker.ui.ThemePrefs
import com.cinnamon.vantracker.ui.VanTheme
import com.cinnamon.vantracker.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    /** Screen requested by a tapped notification ("approvals", "alerts", "reasons", ...). */
    private val openRequest = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.load(this)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra(Notifier.EXTRA_OPEN) ?: ""
        setContent {
            val dark = isDarkTheme()
            // Status/navigation bar icons follow the in-app Light/Dark choice, not just the phone's.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            VanTheme {
                Box(Modifier.fillMaxSize().background(Brand.colors.background)) {
                    GlassBackdrop(Modifier.fillMaxSize())
                    AppRoot(openRequest = openRequest.value, onOpenHandled = { openRequest.value = "" })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(Notifier.EXTRA_OPEN) ?: ""
    }
}
