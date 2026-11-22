package com.hermes.mobile.ui.screens.home

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.hermes.mobile.data.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Regression: one tap on the hamburger opens the drawer only — it must not
 *  create a session or activate a drawer item. Drawer items still work once
 *  the opening gesture has finished. */
class HomeDrawerGuardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var now = 0L
    private lateinit var gate: MenuActionGate

    @Before
    fun setUp() {
        now = SystemClock.uptimeMillis()
        gate = MenuActionGate { now }
    }

    private fun render(
        onNewChat: () -> Unit,
        onVoice: () -> Unit,
        onSessions: () -> Unit,
        onTerminal: () -> Unit
    ) {
        composeRule.setContent {
            MaterialTheme {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize()) {
                    HomeHeader(
                        greeting = "Good Morning",
                        emoji = "🌅",
                        status = ConnectionStatus.CONNECTED,
                        serverUrl = "",
                        latency = 0L,
                        onRefresh = {},
                        panelOpen = open,
                        onTogglePanel = {
                            if (!open) gate.arm()
                            open = !open
                        },
                        onNewChat = { gate.run(onNewChat) },
                        newChatEnabled = true
                    )
                    if (open) {
                        FeatureDrawerCard(
                            onDismiss = { gate.run { open = false } },
                            onVoiceInput = { gate.run { open = false; onVoice() } },
                            onResumeSession = { gate.run { open = false; onSessions() } },
                            onTerminal = { gate.run { open = false; onTerminal() } }
                        )
                    }
                }
            }
        }
    }

    @Test
    fun openingMenuTapsNeverRunAnAction() {
        var newChat = 0
        var voice = 0
        var sessions = 0
        var terminal = 0
        render({ newChat++ }, { voice++ }, { sessions++ }, { terminal++ })

        composeRule.onAllNodesWithText("Voice").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Open features menu").performClick()
        composeRule.waitForIdle()

        // Drawer is displayed; nothing else happened on that tap.
        composeRule.onNodeWithText("Voice").assertIsDisplayed()
        composeRule.onNodeWithText("Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Terminal").assertIsDisplayed()
        assertEquals(0, newChat)
        assertEquals(0, voice)
        assertEquals(0, sessions)
        assertEquals(0, terminal)
    }

    @Test
    fun drawerItemsNavigateAfterTheOpeningTapCompletes() {
        var voice = 0
        var sessions = 0
        var terminal = 0
        render({}, { voice++ }, { sessions++ }, { terminal++ })

        fun openAndWait() {
            composeRule.onNodeWithContentDescription("Open features menu").performClick()
            composeRule.waitForIdle()
            now += 300 // opening gesture has fully completed
        }

        openAndWait()
        composeRule.onNodeWithText("Voice").performClick()
        composeRule.waitForIdle()
        assertEquals(1, voice)
        composeRule.onAllNodesWithText("Voice").assertCountEquals(0)

        openAndWait()
        composeRule.onNodeWithText("Sessions").performClick()
        composeRule.waitForIdle()
        assertEquals(1, sessions)

        openAndWait()
        composeRule.onNodeWithText("Terminal").performClick()
        composeRule.waitForIdle()
        assertEquals(1, terminal)
    }
}
