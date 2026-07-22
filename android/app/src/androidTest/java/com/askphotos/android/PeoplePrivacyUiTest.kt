package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test

class PeoplePrivacyUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun disabledPeopleIndexRequiresExplicitConfirmationAndCanBeCancelled() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as AskPhotosApplication
        assumeFalse("This non-mutating UI check requires people indexing to be off", app.repository.peopleIndexStatus().enabled)
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Privacy").performClick()
        rule.onNodeWithText("People indexing off").performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag("enable-people-index").performScrollTo().performClick()
        rule.onNodeWithText("Enable private face detection?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()

        rule.onNodeWithText("People indexing off").assertIsDisplayed()
        rule.onNodeWithTag("enable-people-index").assertIsDisplayed()
    }
}
