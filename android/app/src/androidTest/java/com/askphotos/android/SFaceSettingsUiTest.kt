package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test

class SFaceSettingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun settingsDisclosePinnedSFaceModelAndLicense() {
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Ask your gallery").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Index").performClick()
        rule.onNodeWithText("Face identity model").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("OpenCV SFace 2021dec-fp32-v1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Replace pinned ONNX").performScrollTo().assertIsDisplayed()
    }
}
