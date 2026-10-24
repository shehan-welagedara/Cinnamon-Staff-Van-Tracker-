package com.cinnamon.vantracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import com.cinnamon.vantracker.notify.Notifier
import com.cinnamon.vantracker.ui.AppRoot
import com.cinnamon.vantracker.ui.VanTheme

class MainActivity : ComponentActivity() {
    /** Screen requested by a tapped notification ("approvals", "alerts", "reasons", ...). */
    private val openRequest = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openRequest.value = intent?.getStringExtra(Notifier.EXTRA_OPEN) ?: ""
        setContent {
            VanTheme {
                AppRoot(openRequest = openRequest.value, onOpenHandled = { openRequest.value = "" })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = intent.getStringExtra(Notifier.EXTRA_OPEN) ?: ""
    }
}
