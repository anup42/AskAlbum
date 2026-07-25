package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GemmaSettingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsExposeOneGemmaModelActionWithoutPackOrTierControls() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Gemma model").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("choose-gemma-model").performScrollTo().assertIsDisplayed()

        assertTrue(runCatching { rule.onNodeWithText("Gemma model pack").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithText("Import .agemma pack").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithText("Replace signed pack").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithTag("gemma-tier-E2B").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithTag("gemma-tier-E4B").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithTag("download-gemma-E2B").fetchSemanticsNode() }.isFailure)
        assertTrue(runCatching { rule.onNodeWithTag("download-gemma-E4B").fetchSemanticsNode() }.isFailure)
    }
}
