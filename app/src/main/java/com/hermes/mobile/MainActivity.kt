package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.hermes.mobile.ui.theme.LocalDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: HermesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Notification permission (Android 13+) for "response ready" pings.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        }
        // Re-arm a previously enabled wake word now that we are foreground.
        com.hermes.mobile.wake.WakeWordService.reconcile(this)
        // Deep link from the "response ready" notification → open the session.
        val deepLinkSession = intent.getStringExtra(EXTRA_SESSION_ID)
        val deepLinkKanbanTask = intent.getStringExtra(EXTRA_KANBAN_TASK)
        // Full-screen wake alert / tap fallback → open Chat and dictate.
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) consumeWakeIntent()
        setContent {
            // Read saved dark theme preference (initial + reactive via listener)
            var isDarkTheme by remember {
                mutableStateOf<Boolean?>(
                    if (repository.hasDarkThemePreference()) repository.isDarkTheme()
                    else null
                )
            }

            // Reactively listen for SharedPreferences changes from Settings
            val prefs = repository.prefs()
            DisposableEffect(prefs) {
                val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
                    if (key == "dark_theme") {
                        isDarkTheme = sp.contains(key).let { if (it) sp.getBoolean(key, false) else null }
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
            }

            val actualDark = isDarkTheme ?: false
            // Direct-API posture: a saved base URL + API key IS the session.
            // Reactive: flips true right after Test saves.
            var apiPaired by remember {
                mutableStateOf(
                    repository.getSavedConfig()?.let {
                        it.baseUrl.isNotBlank() && !it.apiKey.isNullOrBlank()
                    } == true
                )
            }
            DisposableEffect(prefs) {
                val cfgListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                    if (key == "base_url") {
                        apiPaired = repository.getSavedConfig()?.let {
                            it.baseUrl.isNotBlank() && !it.apiKey.isNullOrBlank()
                        } == true
                    }
                }
                prefs.registerOnSharedPreferenceChangeListener(cfgListener)
                onDispose { prefs.unregisterOnSharedPreferenceChangeListener(cfgListener) }
            }
            CompositionLocalProvider(LocalDarkTheme provides actualDark) {
                HermesMobileTheme(darkTheme = actualDark) {
                    MainNavigation(
                        isLoggedIn = apiPaired,
                        initialSessionId = deepLinkSession,
                        initialKanbanTask = deepLinkKanbanTask,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_WAKE, false)) consumeWakeIntent()
        intent.getStringExtra(EXTRA_KANBAN_TASK)?.takeIf { it.isNotBlank() }?.let {
            com.hermes.mobile.KanbanNav.pendingTaskId = it
            intent.removeExtra(EXTRA_KANBAN_TASK)
        }
    }

    /** Clear the transient alert and hand off to the global wake coordinator. */
    private fun consumeWakeIntent() {
        com.hermes.mobile.wake.WakeWordService.cancelWakeAlert(this)
        // One-shot: a rotation must not re-read the extra and dictate twice.
        intent.removeExtra(EXTRA_WAKE)
        com.hermes.mobile.wake.WakeWordBus.publishWakeIntent()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_WAKE = "wake"
        const val EXTRA_KANBAN_TASK = "kanban_task"
    }
}
