package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryProgressUiReducerTest {
    private val plan = QueryCompiler().compile("Show beach photos")
    private val hit = SearchHit(item("beach-1"), 1.0, emptyList())

    @Test
    fun initialResultsAreVisibleBeforeVerification() {
        val searching = QueryProgressUiReducer.reduce(GalleryUiState(), QueryProgress.PlanReady(plan))
        val initial = QueryProgressUiReducer.reduce(searching, QueryProgress.InitialResults(plan, listOf(hit)))
        val verifying = QueryProgressUiReducer.reduce(initial, QueryProgress.Verifying(1))

        assertEquals(QueryExecutionStage.SEARCHING, searching.executionStage)
        assertSame(plan, initial.progressivePlan)
        assertEquals(listOf(hit), initial.progressiveHits)
        assertEquals("Found 1 possible match", initial.executionStatus)
        assertEquals(QueryExecutionStage.VERIFYING, verifying.executionStage)
        assertTrue(verifying.executionStatus.orEmpty().contains("Gemma"))
        assertEquals(listOf(hit), verifying.progressiveHits)
    }

    @Test
    fun completionClearsPartialStateAndOpensFinalResults() {
        val answer = SearchAnswer("One match", "", emptyList(), ResultExactness.EXACT, 1, 1)
        val outcome = SearchOutcome(plan, listOf(hit), answer, 42)
        val partial = GalleryUiState(
            executionStatus = "Checking",
            executionStage = QueryExecutionStage.VERIFYING,
            progressivePlan = plan,
            progressiveHits = listOf(hit),
        )

        val completed = QueryProgressUiReducer.reduce(partial, QueryProgress.Completed(outcome))

        assertSame(outcome, completed.outcome)
        assertEquals(AppDestination.RESULTS, completed.destination)
        assertNull(completed.executionStatus)
        assertNull(completed.executionStage)
        assertNull(completed.progressivePlan)
        assertTrue(completed.progressiveHits.isEmpty())
    }

    private fun item(id: String) = GalleryItem(
        id = id,
        filename = "$id.jpg",
        title = id,
        creator = null,
        location = "",
        latitude = null,
        longitude = null,
        tags = listOf("beach"),
        description = "",
        license = "CC0",
        sourceUrl = "",
        assetPath = null,
    )
}
