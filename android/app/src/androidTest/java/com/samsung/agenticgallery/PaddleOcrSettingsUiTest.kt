package com.samsung.agenticgallery

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class PaddleOcrSettingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDiscloseModularMultilingualOcrAndLicense() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Multilingual OCR engine").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Apache-2.0", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Hindi/Devanagari", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("provider registry", substring = true).performScrollTo().assertIsDisplayed()
    }
}
