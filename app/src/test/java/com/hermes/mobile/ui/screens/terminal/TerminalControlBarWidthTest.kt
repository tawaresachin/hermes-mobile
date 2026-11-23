package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Width contract for the terminal strip. Screen widths are dp; TerminalScreen
 * pads the content 16dp on each side.
 */
class TerminalControlBarWidthTest {

    @Test
    fun everyControlFitsA373dpPhone() {
        // Find, Copy, ^C, Esc, Tab, arrows pill and Disconnect all visible
        // without scrolling at the app's normal phone width.
        assertTrue(TerminalControlBarLayout.fitsWithoutKeyScroll(373))
    }

    @Test
    fun keyButtonsScrollButStayCompactOn320dpPhone() {
        // Too narrow for every key at once: the key row scrolls instead, and
        // Disconnect stays outside that scroll container so it is never clipped.
        assertFalse(TerminalControlBarLayout.fitsWithoutKeyScroll(320))
        assertTrue(TerminalControlBarLayout.keyRowWidthDp > 0)
    }
}
