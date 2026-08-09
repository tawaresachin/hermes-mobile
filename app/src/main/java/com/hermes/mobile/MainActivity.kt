package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.hermes.mobile.auth.AuthManager
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

    @Inject
    lateinit var authManager: AuthManager

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
        // Deep link from the "response ready" notification → open the session.
        val deepLinkSession = intent.getStringExtra(EXTRA_SESSION_ID)
        // Silent re-auth: refresh token first, else the paired device account.
        // Best-effort — never blocks UI, never crashes on failure.
        lifecycleScope.launch {
            try {
                if (!authManager.isLoggedIn.value) {
                    val cfg = repository.getSavedConfig()
                    val baseUrl = cfg?.baseUrl?.trimEnd('/')
                    if (baseUrl.isNullOrBlank()) return@launch
                    val refreshed = authManager.refreshToken(baseUrl)
                    if (!refreshed) {
                        val creds = repository.getDeviceCredentials()
                        if (creds != null) {
                            authManager.login(baseUrl, creds.first, creds.second)
                        }
                    }
                }
            } catch (_: Exception) {
                // Silent — user can re-pair or log in from Settings.
            }
        }
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
            val loggedIn by authManager.isLoggedIn.collectAsState()
            CompositionLocalProvider(LocalDarkTheme provides actualDark) {
                HermesMobileTheme(darkTheme = actualDark) {
                    MainNavigation(isLoggedIn = loggedIn, initialSessionId = deepLinkSession)
                }
            }
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }
}
