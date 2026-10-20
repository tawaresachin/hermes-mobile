package com.hermes.mobile.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * JVM unit tests for the pure formatting helper `formatTokens`
 * (top-level in SettingsViewModel.kt). Pure Kotlin/Java — no Android needed.
 */
class SettingsFormattingTest {

    @Test
    fun rawCountsBelowThresholdPassThrough() {
        assertEquals("0", formatTokens(0))
        assertEquals("42", formatTokens(42))
        assertEquals("999", formatTokens(999))
    }

    @Test
    fun thousandsRoundToK() {
        assertEquals("1K", formatTokens(1_000))
        assertEquals("1.5K", formatTokens(1_500))
        assertEquals("990K", formatTokens(990_000))
        // Pre-existing quirk: just under 1M rounds up in the K band and does not
        // carry to "1M". Pinned so a future "fix" is a conscious change.
        assertEquals("1000K", formatTokens(999_999))
    }

    @Test
    fun millionsRoundToM() {
        assertEquals("1M", formatTokens(1_000_000))
        assertEquals("2.5M", formatTokens(2_500_000))
    }

    @Test
    fun billionsRoundToB() {
        assertEquals("1B", formatTokens(1_000_000_000))
        assertEquals("3.7B", formatTokens(3_700_000_000))
    }
}
