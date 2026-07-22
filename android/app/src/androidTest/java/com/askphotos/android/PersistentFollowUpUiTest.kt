package com.askphotos.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PersistentFollowUpUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun qualityFollowUpUsesAndPersistsOnlyTheActiveResultSet() {
        rule.waitUntil(timeoutMillis = 20_000) {
            runCatching { rule.onNodeWithText("Photos").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Ask").performClick()
        rule.onNodeWithContentDescription("Gallery question").performTextClearance()
        rule.onNodeWithContentDescription("Gallery question").performTextInput("Show bicycles")
        rule.onNodeWithTag("submit-question").performClick()
        rule.waitUntil(timeoutMillis = 60_000) {
            runCatching { rule.onNodeWithTag("refine-results").fetchSemanticsNode() }.isSuccess
        }

        val first = readConversation()
        val firstResultSetId = requireNotNull(first.activeResultSetId)
        assertTrue(first.activeResultIds.isNotEmpty())
        rule.onNodeWithTag("refine-results").performScrollTo().assertIsDisplayed().performClick()
        rule.onNodeWithTag("active-result-set").performScrollTo().assertIsDisplayed()

        rule.onNodeWithContentDescription("Gallery question").performTextClearance()
        rule.onNodeWithContentDescription("Gallery question").performTextInput("Which is the best one?")
        rule.onNodeWithTag("submit-question").performClick()
        rule.waitUntil(timeoutMillis = 60_000) {
            val state = readConversation()
            state.activeResultSetId != null && state.activeResultSetId != firstResultSetId
        }

        val second = readConversation()
        val secondResultSetId = requireNotNull(second.activeResultSetId)
        assertNotEquals(firstResultSetId, secondResultSetId)
        assertTrue(second.activeResultIds.isNotEmpty())
        assertTrue(first.activeResultIds.containsAll(second.activeResultIds))
        val store = GalleryDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            assertEquals(firstResultSetId, store.resultSetParent(secondResultSetId))
        } finally {
            store.close()
        }
    }

    private fun readConversation(): ConversationSearchState {
        val store = GalleryDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        return try {
            store.conversationState(GalleryDatabase.PRIMARY_QUERY_SESSION)
        } finally {
            store.close()
        }
    }
}
