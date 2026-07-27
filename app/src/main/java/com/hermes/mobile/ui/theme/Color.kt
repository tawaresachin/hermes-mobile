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
val DarkChrome        = Color(0xFF1F2C3A)   // nav bars
val DarkBorder        = Color(0xFF37474F)
val DarkFg            = Color(0xFFFFFFFF)   // primary text
val DarkFgMuted       = Color(0xFF6D7885)   // secondary
val DarkFgDim         = Color(0xFF8E93A5)   // tertiary

// ── Light theme (Telegram white) ──
val LightBg            = Color(0xFFFFFFFF)
val LightSurface       = Color(0xFFF4F4F5)
val LightSurface2      = Color(0xFFEAEAED)  // variant
val LightChrome        = Color(0xFFFFFFFF)  // nav bars
val LightBorder        = Color(0xFFE5E5EA)
val LightFg            = Color(0xFF000000)
val LightFgMuted       = Color(0xFF8E8E93)
val LightFgDim         = Color(0xFFAEAEB2)

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
val CodeBgDark  = Color(0xFF0D1C2B)
val CodeBgLight = Color(0xFFF4F4F5)

// ═══════════════════════════════════════════════════════════
// BACKWARD-COMPATIBLE ALIASES
// ═══════════════════════════════════════════════════════════

// Brand
val HermesPrimary       = TelegramBlue
val HermesPrimaryLight  = TelegramBlueLight
val HermesPrimaryDark   = TelegramBlueDark
val HermesSecondary     = TelegramBlue
// Glassmorphism — kept for compatibility, use flat colors going forward
val GlassWhite = Color(0x33FFFFFF)
val GlassWhiteStrong = Color(0x66FFFFFF)

// Backward-compatible aliases
val HermesSecondaryDark = Color(0xFF1D4ED8)  // darker blue
val HermesAccent        = TelegramBlue

// Dark theme aliases
val DarkBackground      = DarkBg
val DarkSurfaceVariant  = DarkSurface2
val DarkOnSurface        = DarkFg
val DarkOnSurfaceVariant = DarkFgMuted

// Light theme aliases
val LightBackground      = LightBg
val LightOnSurface        = LightFg
val LightOnSurfaceVariant = LightFgMuted

// Semantic aliases
val ErrorRed      = TelegramRed
val SuccessGreen  = TelegramGreen
val WarningAmber  = TelegramAmber

// Bubble aliases (used by ChatScreen imports)
val UserBubble      = UserBubbleLight    // default light
val AssistantBubble = OtherBubbleLight   // default light
