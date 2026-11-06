package com.hermes.mobile.ui.screens.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalFindStatusTest {

    @Test
    fun emptyQueryHidesStatus() {
        assertEquals("", findStatus(0, 0, false))
    }

    @Test
    fun noMatches() {
        assertEquals("No matches", findStatus(0, 0, true))
    }

    @Test
    fun countsAreOneBased() {
        assertEquals("1/3", findStatus(0, 3, true))
        assertEquals("3/3", findStatus(2, 3, true))
    }

    @Test
    fun cappedHighlightsAreMarked() {
        // The addon reports index -1 once it stops tracking individual
        // matches; the count is still the number it highlighted.
        assertEquals("1000+ matches", findStatus(-1, 1000, true))
    }
}
