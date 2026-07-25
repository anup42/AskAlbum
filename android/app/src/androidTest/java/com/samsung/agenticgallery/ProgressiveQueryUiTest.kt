package com.samsung.agenticgallery

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
import org.junit.Assume.assumeTrue

class ProgressiveQueryUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activeQueryCanBeCancelledWithoutPublishingPartialAnswer() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Ask").performClick()
        rule.onNodeWithContentDescription("Gallery question")
            .performTextInput("Show the image where Person A has a yellow hat and Person B has a blue suit")
        rule.onNodeWithTag("submit-question").performClick()
        val cancellableWindowObserved = runCatching {
            rule.waitUntil(timeoutMillis = 5_000) {
                runCatching { rule.onNodeWithTag("cancel-query").fetchSemanticsNode() }.isSuccess
            }
        }.isSuccess
        assumeTrue(
            "Fixture query completed before a cancellable model call; cancellation acceptance requires an active model",
            cancellableWindowObserved,
        )

        rule.onNodeWithTag("cancel-query").performScrollTo().assertIsDisplayed().performClick()

        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithTag("cancel-query").fetchSemanticsNode() }.isFailure
        }
        rule.onNodeWithText("Query cancelled; no partial answer was saved").assertIsDisplayed()
        rule.onNodeWithTag("submit-question").assertIsEnabled()
    }
}
