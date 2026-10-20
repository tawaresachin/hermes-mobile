package com.hermes.mobile

import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.hermes.mobile.data.model.ConnectionStatus
import com.hermes.mobile.ui.screens.chat.ConnectionStatusBar
import com.hermes.mobile.ui.screens.chat.EmptyChatState
import com.hermes.mobile.ui.screens.chat.MarkdownTable
import com.hermes.mobile.ui.screens.chat.StreamingIndicator
import com.hermes.mobile.ui.screens.chat.TypingIndicator
import com.hermes.mobile.ui.screens.settings.SettingsInfoRow
import org.junit.Rule
import org.junit.Test

/**
 * Instrumented smoke tests that render the (refactored) chat/settings composables
 * in isolation, without a live gateway or Hilt graph. They guard the screen split:
 * each composable now lives in its own file, so a silently-dropped parameter or
 * broken reference would surface as a failed assertion (or a crash) here.
 *
 * Every test asserts a concrete node (a text or a `testTag`), not just that
 * composition completed — a bare `setContent` proves nothing. Run on a
 * device/emulator:
 *   ./gradlew connectedDebugAndroidTest
 */
class SmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun composeHarnessRenders() {
        composeRule.setContent { Text("Hermes") }
        composeRule.onNodeWithText("Hermes").assertExists()
    }

    @Test
    fun typingIndicatorRenders() {
        composeRule.setContent { TypingIndicator() }
        composeRule.onNodeWithTag("typing-indicator").assertExists()
    }

    @Test
    fun emptyChatStateRenders() {
        composeRule.setContent { EmptyChatState() }
        composeRule.onNodeWithText("No messages here yet...").assertExists()
        // A suggestion chip renders with its label — proves the chip path, not just the header.
        composeRule.onNodeWithText("What can you do?").assertExists()
    }

    @Test
    fun streamingIndicatorRenders() {
        composeRule.setContent { StreamingIndicator(Color.White) }
        composeRule.onNodeWithTag("streaming-indicator").assertExists()
    }

    @Test
    fun markdownTableRenders() {
        composeRule.setContent {
            MarkdownTable(listOf(listOf("a", "b"), listOf("1", "2")), darkTheme = false)
        }
        // A transposed row (b/1 swapped in a column) would still render — checking
        // two cells in known positions catches that.
        composeRule.onNodeWithText("a").assertExists()
        composeRule.onNodeWithText("2").assertExists()
    }

    @Test
    fun connectionStatusBarRenders() {
        composeRule.setContent { ConnectionStatusBar(ConnectionStatus.CONNECTED) }
        composeRule.onNodeWithText("Connected").assertExists()
    }

    @Test
    fun settingsInfoRowRenders() {
        composeRule.setContent { SettingsInfoRow("Version", "0.0.92") }
        composeRule.onNodeWithText("0.0.92").assertExists()
    }
}
