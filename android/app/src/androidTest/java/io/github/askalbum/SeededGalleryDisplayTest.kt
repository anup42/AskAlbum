package io.github.anup42.askalbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SeededGalleryDisplayTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun productionGalleryDisplaysEveryImportedSeededItem() {
        val arguments = InstrumentationRegistry.getArguments()
        val runId = arguments.getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val expected = arguments.getString("galleryExpectedCount")?.toIntOrNull()
        assumeTrue("galleryExpectedCount was not supplied", expected != null)

        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithContentDescription("Gallery screen; $expected imported items").assertIsDisplayed()
    }
}
