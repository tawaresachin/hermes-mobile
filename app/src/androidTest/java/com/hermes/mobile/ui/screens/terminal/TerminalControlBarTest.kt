package com.hermes.mobile.ui.screens.terminal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * The strip must never clip a control. Widths are the screen minus the 16dp
 * horizontal padding TerminalScreen applies on each side.
 */
class TerminalControlBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun renderAtScreenWidth(screenWidthDp: Int) {
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width((screenWidthDp - 32).dp)) {
                    TerminalControlBar(
                        onFind = {},
                        onCopy = {},
                        onKey = {},
                        onDisconnect = {}
                    )
                }
            }
        }
    }

    private fun assertInside(cd: String) {
        val bar = composeRule.onNodeWithTag("terminal_control_bar").getBoundsInRoot()
        val node = composeRule.onNodeWithContentDescription(cd)
        node.assertIsDisplayed()
        val bounds = node.getBoundsInRoot()
        check(bounds.left >= bar.left && bounds.right <= bar.right) {
            "$cd is clipped: $bounds outside $bar"
        }
    }

    @Test
    fun fullStripFitsOn373dpPhone() {
        renderAtScreenWidth(373)
        listOf(
            "Find in terminal",
            "Copy selection",
            "Ctrl-C",
            "Esc",
            "Tab",
            "Previous history line",
            "Next history line",
            "Disconnect"
        ).forEach(::assertInside)
    }

    @Test
    fun disconnectStaysPinnedOn320dpPhone() {
        renderAtScreenWidth(320)
        assertInside("Disconnect")
    }

    @Test
    fun arrowsAreOneSegmentedPill() {
        renderAtScreenWidth(373)
        val pill = composeRule.onNodeWithTag("terminal_arrows")
        pill.assertIsDisplayed()
        val pillBounds = pill.getBoundsInRoot()
        val up = composeRule.onNodeWithContentDescription("Previous history line")
            .getBoundsInRoot()
        val down = composeRule.onNodeWithContentDescription("Next history line")
            .getBoundsInRoot()
        check(up.left >= pillBounds.left && down.right <= pillBounds.right) {
            "arrow halves left their pill: up=$up down=$down pill=$pillBounds"
        }
    }
}
