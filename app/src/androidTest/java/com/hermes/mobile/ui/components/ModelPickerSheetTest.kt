package com.hermes.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.hermes.mobile.data.local.ModelFavoritesStore
import com.hermes.mobile.data.model.ModelInfo
import org.junit.After
import org.junit.Rule
import org.junit.Test

/**
 * CR-2 regression: a favourited model used to be emitted twice (Favorites
 * section + All-Models list) and the LazyColumn crashed with
 * "Key \"Agnes Paid|agnes-3.0-flash\" was already used". These tests seed that
 * exact favourite and drive search + All Models — the combination that
 * triggered the crash — and check that the row stays in its group with a
 * filled star and visible Favorites feedback.
 */
class ModelPickerSheetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
    private val favoritesStore = ModelFavoritesStore.getInstance(context)

    private val agnes = ModelInfo(
        id = "agnes-3.0-flash",
        name = "Agnes 3.0 Flash",
        provider = "Agnes Paid",
        providerSlug = "agnes",
    )
    private val other = ModelInfo(
        id = "other-model",
        name = "Other Model",
        provider = "Other",
        providerSlug = "other",
    )

    @After
    fun tearDown() {
        favoritesStore.clearAll()
        context.getSharedPreferences("hermes_model_picker", 0).edit().clear().apply()
    }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                ModelPickerSheetWithContext(
                    context = context,
                    availableModels = listOf(agnes, other),
                    currentModel = "",
                    modelsLoading = false,
                    onSelect = { _, _, _ -> },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun favoritePlusSearchAndAllModelsRendersRowExactlyOnce() {
        favoritesStore.clearAll()
        favoritesStore.addFavorite("agnes", "agnes-3.0-flash")
        render()

        // Grouped view: the favourite stays in its provider group AND the
        // Favorites anchor is visible; the row is emitted only once.
        composeRule.onNodeWithText("Favorites").assertExists()
        composeRule.onNodeWithText("AGNES PAID").assertExists()
        composeRule.onAllNodes(hasText("Agnes 3.0 Flash")).assertCountEquals(1)

        // The exact CR-2 trigger: typing a query used to hide the Favorites
        // section and emit the same key twice. A duplicate key throws before
        // this assertion can run.
        composeRule.onNode(hasSetTextAction()).performTextInput("agnes")
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Favorites").assertExists()
        composeRule.onNodeWithText("AGNES PAID").assertExists()
        composeRule.onAllNodes(hasText("Agnes 3.0 Flash")).assertCountEquals(1)

        // All Models is the other crash path; it honours the same search.
        composeRule.onNodeWithContentDescription("All Models mode").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Showing: all models (flat)").assertExists()
        composeRule.onAllNodes(hasText("Agnes 3.0 Flash")).assertCountEquals(1)
        composeRule.onAllNodes(hasText("Other Model")).assertCountEquals(0)
    }

    @Test
    fun starringKeepsRowInGroupWithFilledStarAndShowsFavorites() {
        favoritesStore.clearAll()
        render()
        composeRule.onNode(hasSetTextAction()).performTextInput("agnes")

        composeRule.onNodeWithContentDescription("Favorite").performClick()
        composeRule.waitForIdle()

        // HI-1: no vanishing row, filled star, and a visible anchor.
        composeRule.onNodeWithText("Favorites").assertExists()
        composeRule.onNodeWithText("AGNES PAID").assertExists()
        composeRule.onNodeWithContentDescription("Unfavorite").assertExists()
        composeRule.onAllNodes(hasText("Agnes 3.0 Flash")).assertCountEquals(1)
    }

    @Test
    fun allModelsModeChoicePersistsAcrossPickerReopen() {
        favoritesStore.clearAll()
        render()
        composeRule.onNodeWithContentDescription("All Models mode").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Showing: all models (flat)").assertExists()

        render() // simulates closing and reopening the sheet
        composeRule.onNodeWithText("Showing: all models (flat)").assertExists()
        composeRule.onAllNodes(hasText("Agnes 3.0 Flash")).assertCountEquals(1)
        composeRule.onAllNodes(hasText("Other Model")).assertCountEquals(1)
    }
}
