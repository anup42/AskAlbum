package io.github.anup42.askalbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class OneUiGalleryShellTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryNavigationIsGalleryFirstAndDeveloperChromeIsAbsent() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }

        rule.onNodeWithText("Photos").assertIsDisplayed()
        rule.onNodeWithText("Albums").assertIsDisplayed()
        rule.onNodeWithText("Ask").assertIsDisplayed()
        rule.onNodeWithText("Menu").assertIsDisplayed()
        assertFalse(runCatching { rule.onNodeWithText("AskAlbum").fetchSemanticsNode() }.isSuccess)
        assertFalse(runCatching { rule.onNodeWithText("Private gallery intelligence").fetchSemanticsNode() }.isSuccess)
        assertFalse(runCatching { rule.onNodeWithText("ON-DEVICE").fetchSemanticsNode() }.isSuccess)

        rule.onNodeWithText("Albums").performClick()
        rule.onNodeWithContentDescription("Albums screen").assertIsDisplayed()

        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithContentDescription("Gallery menu").assertIsDisplayed()
    }
}
