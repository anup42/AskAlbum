package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test

class ProgressiveQueryUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeQueryCanBeCancelledWithoutPublishingPartialAnswer() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Ask your gallery").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithContentDescription("Gallery question")
            .performTextInput("Show the image where Person A has a yellow hat and Person B has a blue suit")
        rule.onNodeWithTag("submit-question").performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching { rule.onNodeWithTag("cancel-query").fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithTag("cancel-query").performScrollTo().assertIsDisplayed().performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithTag("cancel-query").fetchSemanticsNode() }.isFailure
        }
        rule.onNodeWithText("Query cancelled; no partial answer was saved").assertIsDisplayed()
        rule.onNodeWithTag("submit-question").assertIsEnabled()
    }
}
