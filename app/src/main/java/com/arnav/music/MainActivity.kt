package com.arnav.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.arnav.music.ui.AppViewModel
import com.arnav.music.ui.ArnavAppRoot
import com.arnav.music.ui.DeepLink
import org.koin.compose.viewmodel.koinViewModel
import org.koin.android.ext.android.inject
import com.arnav.music.core.settings.SettingsRepository

class MainActivity : ComponentActivity() {
    private val settingsRepo: SettingsRepository by inject()
    private val deepLink = mutableStateOf<DeepLink?>(null)
    private val openPlayer = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the system splash until settings are read, so returning users never see onboarding flash.
        splash.setKeepOnScreenCondition { !settingsRepo.loaded.value }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent {
            val vm: AppViewModel = koinViewModel()
            ArnavAppRoot(
                vm = vm,
                deepLink = deepLink.value,
                onDeepLinkHandled = { deepLink.value = null },
                openPlayer = openPlayer.value,
                onOpenPlayerHandled = { openPlayer.value = false },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) openPlayer.value = true
        val shared = if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { t ->
            Regex("""https?://\S+""").find(t)?.value?.let(android.net.Uri::parse)
        } else null
        DeepLink.parse(shared ?: intent.data)?.let { deepLink.value = it }
    }

    companion object { const val EXTRA_OPEN_PLAYER = "open_player" }
}
