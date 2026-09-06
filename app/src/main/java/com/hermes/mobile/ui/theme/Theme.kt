package com.hermes.mobile.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ─── Dark color scheme (Telegram inspired) ───
private val DarkColorScheme = darkColorScheme(
    primary = HermesPrimary,
    onPrimary = DarkBg,
    primaryContainer = HermesPrimaryDark,
    secondary = HermesSecondary,
    onSecondary = DarkBg,
    tertiary = HermesPrimary,
    background = DarkBg,
    onBackground = DarkFg,
    surface = DarkSurface,
    onSurface = DarkFg,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkFgMuted,
    outline = DarkBorder,
    outlineVariant = DarkBorder,
    error = ErrorRed,
    onError = DarkBg,
    inverseSurface = LightSurface,
    inverseOnSurface = LightFg,
)

// ─── Light color scheme (Telegram inspired) ───
private val LightColorScheme = lightColorScheme(
    primary = HermesPrimary,
    onPrimary = LightBg,
    primaryContainer = HermesPrimaryLight,
    secondary = HermesSecondary,
    onSecondary = LightBg,
    tertiary = HermesPrimary,
    background = LightBg,
    onBackground = LightFg,
    surface = LightSurface,
    onSurface = LightFg,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightFgMuted,
    outline = LightBorder,
    outlineVariant = LightBorder,
    error = ErrorRed,
    onError = LightBg,
    inverseSurface = DarkSurface,
    inverseOnSurface = DarkFg,
)

// ─── LocalDarkTheme — allows Settings to override system theme ───
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun HermesMobileTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HermesTypography,
        content = content
    )
}
