package com.hermes.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import com.hermes.mobile.data.repository.HermesRepository
import com.hermes.mobile.ui.theme.HermesMobileTheme
import com.hermes.mobile.ui.theme.LocalDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var repository: HermesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
            CompositionLocalProvider(LocalDarkTheme provides actualDark) {
                HermesMobileTheme(darkTheme = actualDark) {
                    MainNavigation()
                }
            }
        }
    }
}
