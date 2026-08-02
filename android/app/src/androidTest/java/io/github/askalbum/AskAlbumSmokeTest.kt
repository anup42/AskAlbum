package io.github.anup42.askalbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class AskAlbumSmokeTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun localSearchProducesGroundedResults() {
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Ask").performClick()
        rule.onNodeWithContentDescription("Gallery question").performTextInput("Amsterdam")
        rule.onNodeWithContentDescription("Submit question").performClick()
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Found 4 matches").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Found 4 matches").assertIsDisplayed()
    }

    @Test
    fun privacyCanOpenOnboardingAndReturnToAsk() {
        rule.waitUntil(timeoutMillis = 10_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Privacy").performClick()
        rule.onNodeWithContentDescription("Privacy screen").assertIsDisplayed()
        rule.onNodeWithText("Review onboarding").performClick()
        rule.onNodeWithContentDescription("Onboarding screen").assertIsDisplayed()
        rule.onNodeWithText("Continue to Photos").performClick()
        rule.onNodeWithText("Photos").assertIsDisplayed()
    }
}
