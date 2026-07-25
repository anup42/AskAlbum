package com.samsung.agenticgallery

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResultSetPersistenceDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var database: GalleryDatabase? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        database?.close()
        database = null
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun activeResultSetAndParentPatchSurviveDatabaseReopen() {
        var store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        val items = store.allItems().take(2)
        val first = store.persistResultSet(SESSION, outcome("Show local photos", items), null)
        val firstId = requireNotNull(first.resultSetId)
        val firstState = store.conversationState(SESSION)
        assertEquals(firstId, firstState.activeResultSetId)
        assertEquals(items.map { it.id }.toSet(), firstState.activeResultIds)

        val compiled = QueryCompiler().compile("Which is the best one?", firstState.activeResultIds)
        val (patch, resolved) = ResultSetPlanPatchResolver().createAndApply(compiled, firstState)
        val second = store.persistResultSet(
            SESSION,
            outcome("Which is the best one?", items.take(1), resolved).copy(planPatch = patch),
            firstId,
        )
        val secondId = requireNotNull(second.resultSetId)
        assertNotEquals(firstId, secondId)
        assertEquals(firstId, second.baseResultSetId)
        assertEquals(firstId, store.resultSetParent(secondId))
        assertThrows(IllegalStateException::class.java) {
            store.persistResultSet(SESSION, outcome("Only stale", items.take(1)), firstId)
        }

        store.close()
        database = null
        store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        val restored = store.conversationState(SESSION)
        assertEquals(secondId, restored.activeResultSetId)
        assertEquals(setOf(items.first().id), restored.activeResultIds)
        assertEquals("Which is the best one?", restored.lastQuery)
        assertNotNull(restored.activeResultSetId)
    }

    private fun outcome(query: String, items: List<GalleryItem>, plan: GalleryQueryPlan = QueryCompiler().compile("Show local photos")) =
        SearchOutcome(
            plan = plan.copy(originalQuery = query),
            hits = items.mapIndexed { rank, item ->
                SearchHit(
                    item,
                    score = 10.0 - rank,
                    evidence = listOf(EvidenceRecord("${item.id}:fixture", item.id, "metadata", item.title, 1f)),
                )
            },
            answer = SearchAnswer(
                headline = "Found ${items.size}",
                detail = "Fixture result",
                evidenceIds = items.map { "${it.id}:fixture" },
                exactness = ResultExactness.COMPLETE_MODEL_SCAN,
                indexedEligibleCount = items.size,
                totalEligibleCount = items.size,
            ),
            elapsedMs = 1,
        )

    private companion object {
        const val TEST_DATABASE = "result-set-persistence-test.db"
        const val SESSION = "persistence_test"
    }
}
