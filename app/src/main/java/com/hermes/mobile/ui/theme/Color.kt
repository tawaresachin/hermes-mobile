package com.hermes.mobile.ui.theme

import androidx.compose.ui.graphics.Color

// ═══════════════════════════════════════════════════════════
// Telegram-inspired color palette
// Reference: Telegram Android design tokens
// ═══════════════════════════════════════════════════════════

// ── Brand accent — Telegram Blue ──
val TelegramBlue        = Color(0xFF0088CC)
val TelegramBlueLight   = Color(0xFF33A0D6)
val TelegramBlueDark    = Color(0xFF2AABEE)

// ── Dark theme (Telegram navy) ──
val DarkBg            = Color(0xFF17212B)
val DarkSurface       = Color(0xFF242F3D)   // cards
val DarkSurface2      = Color(0xFF1E2B39)   // elevated / variant
val DarkBorder        = Color(0xFF37474F)
val DarkFg            = Color(0xFFFFFFFF)   // primary text
val DarkFgMuted       = Color(0xFF6D7885)   // secondary

// ── Light theme (Telegram white) ──
val LightBg            = Color(0xFFFFFFFF)
val LightSurface       = Color(0xFFF4F4F5)
val LightSurface2      = Color(0xFFEAEAED)  // variant
val LightBorder        = Color(0xFFE5E5EA)
val LightFg            = Color(0xFF000000)
val LightFgMuted       = Color(0xFF8E8E93)

// ── Semantic ──
val TelegramRed        = Color(0xFFE53935)
val TelegramGreen      = Color(0xFF43A047)
val TelegramAmber      = Color(0xFFFB8C00)

// ── Chat bubbles ──
val UserBubbleLight    = Color(0xFFE1FFC7)   // classic Telegram green
val UserBubbleDark     = Color(0xFF2B5278)   // dark blue on dark
val OtherBubbleLight   = Color(0xFFFFFFFF)   // white
val OtherBubbleDark    = Color(0xFF1E2B39)   // dark navy

// ── Code blocks ──

// ═══════════════════════════════════════════════════════════
// Voice sphere — FRIDAY/Siri neon palette
// (drives SphereGLRenderer uniforms + VoiceScreen chrome)
// ═══════════════════════════════════════════════════════════
val VoiceNeonCyan      = Color(0xFF69E5E5)
val VoiceNeonBlue      = Color(0xFF3D8BFF)
val VoiceNeonViolet    = Color(0xFF8A3BFF)
val VoiceNeonMagenta   = Color(0xFFFF4DD2)
val VoiceNeonRed       = Color(0xFFFF4D4D)
val VoiceCoreWhite     = Color(0xFFF2F7FF)

// ═══════════════════════════════════════════════════════════
// BACKWARD-COMPATIBLE ALIASES
// ═══════════════════════════════════════════════════════════

// Brand
val HermesPrimary       = TelegramBlue
val HermesPrimaryLight  = TelegramBlueLight
val HermesPrimaryDark   = TelegramBlueDark
val HermesSecondary     = TelegramBlue
// Backward-compatible aliases
val HermesSecondaryDark = Color(0xFF1D4ED8)  // darker blue
val HermesAccent        = TelegramBlue

// Semantic aliases
val ErrorRed      = TelegramRed
val SuccessGreen  = TelegramGreen
val WarningAmber  = TelegramAmber
