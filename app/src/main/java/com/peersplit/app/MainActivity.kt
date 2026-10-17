package com.peersplit.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.peersplit.app.ui.navigation.NavGraph
import com.peersplit.app.ui.navigation.PendingInvite
import com.peersplit.app.ui.theme.PeerSplitTheme
import com.peersplit.app.ui.theme.ThemeManager
import com.peersplit.app.ui.theme.ThemeMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            val themeMode by ThemeManager.themeMode.collectAsState()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            PeerSplitTheme(darkTheme = isDark, dynamicColor = false) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    NavGraph(navController = navController)
                }
            }
        }
    }

    private var lastResumeSyncTime = 0L

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastResumeSyncTime > 30_000L) {
            lastResumeSyncTime = now
            PeerSplitApp.instance.syncManager.syncAllGroupsNow(broadcastChains = false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            // Use the raw string: Base64 payload is case-sensitive
            PendingInvite.offer(intent.dataString)
        }
    }
}
