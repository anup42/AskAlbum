package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GemmaSettingsUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun consumerSettingsOfferE2bAndApplyDevicePolicyToE4b() {
        assertTrue(BuildConfig.ALLOW_MODEL_DOWNLOAD)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val e4bSupported = GemmaDeviceCapability(context).assess(GemmaModelCatalog.e4b).let {
            it.supported && it.recommendedTier == GemmaModelTier.E4B
        }
        rule.waitUntil(timeoutMillis = 15_000) {
            runCatching { rule.onNodeWithText("Ask your gallery").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Index").performClick()
        rule.onNodeWithText("Gemma model pack").performScrollTo().assertIsDisplayed()

        rule.onNodeWithTag("gemma-tier-E2B").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching { rule.onNodeWithTag("download-gemma-E2B").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag("download-gemma-E2B").performScrollTo().assertIsEnabled()

        rule.onNodeWithTag("gemma-tier-E4B").performScrollTo().performClick()
        rule.waitUntil(timeoutMillis = 5_000) {
            runCatching { rule.onNodeWithTag("download-gemma-E4B").fetchSemanticsNode() }.isSuccess
        }
        val e4bButton = rule.onNodeWithTag("download-gemma-E4B").performScrollTo()
        if (e4bSupported) e4bButton.assertIsEnabled() else e4bButton.assertIsNotEnabled()
    }
}
