package com.hermes.mobile.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** CR-1 regression: expanding the card with every section non-empty used to
 * crash from a nested LazyColumn / duplicate keys. Drive the stateless
 * overload with real typed state and assert all four sections render rows. */
class CapabilitiesCardRegressionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun expandedCardRendersAllNonEmptyCapabilitySections() {
        val state = CapabilitiesState(
            plugins = CapabilitySectionState(
                CapabilitySectionStatus.LOADED,
                listOf(CapabilityItem("plugin-alpha", "Plugin row", "1.0")),
            ),
            mcpServers = CapabilitySectionState(
                CapabilitySectionStatus.LOADED,
                listOf(CapabilityItem("mcp-alpha", "MCP row", "stdio")),
            ),
            projects = CapabilitySectionState(
                CapabilitySectionStatus.LOADED,
                listOf(CapabilityItem("project-alpha", "Project row", "/tmp/project")),
            ),
            skills = CapabilitySectionState(
                CapabilitySectionStatus.LOADED,
                listOf(CapabilityItem("skill-alpha", "Skill row", detail = "", category = "tools", enabled = true)),
            ),
        )

        composeRule.setContent {
            MaterialTheme {
                CapabilitiesCardContent(
                    capabilities = state,
                    onLoad = {},
                    onCancel = {},
                    onToggleSkill = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag("capabilities_header").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("plugin-alpha").assertExists()
        composeRule.onNodeWithText("mcp-alpha").assertExists()
        composeRule.onNodeWithText("project-alpha").assertExists()
        composeRule.onNodeWithText("skill-alpha").assertExists()
    }
}
