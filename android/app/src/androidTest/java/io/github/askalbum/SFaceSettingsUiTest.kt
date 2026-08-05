package io.github.anup42.askalbum

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SFaceSettingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDisclosePinnedSFaceModelAndLicense() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Menu").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Face identity model").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OpenCV SFace 2021dec-fp32-v1").performScrollTo().assertIsDisplayed()
        assertTrue(
            "Obsolete SFace replacement control is still exposed",
            runCatching { rule.onNodeWithText("Replace pinned ONNX").fetchSemanticsNode() }.isFailure,
        )
        assertTrue(
            "Obsolete signed-pack replacement control is still exposed",
            runCatching { rule.onNodeWithText("Replace signed pack").fetchSemanticsNode() }.isFailure,
        )
    }
}
