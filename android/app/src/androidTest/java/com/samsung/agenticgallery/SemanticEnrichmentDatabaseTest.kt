package com.samsung.agenticgallery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticEnrichmentDatabaseTest {
    @Test
    fun representativeJobAndExactDuplicateFactsAreTransactional() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "semantic-facts-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val media = database.allItems().take(2)
            assertEquals(2, media.size)
            val group = VisualGroupPlan(
                id = "exact:test",
                kind = "EXACT_DUPLICATE",
                canonicalMediaId = media.first().id,
                members = media.map(GalleryItem::id),
                representatives = listOf(media.first().id),
            )
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.VISUAL_GROUP,
                subjectId = group.id,
                representativeMediaId = media.first().id,
                reason = "exact_duplicate_canonical",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(
                SemanticEnrichmentPlan(listOf(group), emptyList(), listOf(job)),
            )
            val claimed = requireNotNull(database.claimSemanticEnrichmentJob())
            database.completeSemanticEnrichment(
                claimed,
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.VISUAL_GROUP,
                        subjectId = group.id,
                        predicate = "scene",
                        value = "beach",
                        confidence = 0.9f,
                        evidenceMediaId = media.first().id,
                        applicability = "SAFE_FOR_EXACT_DUPLICATES",
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )

            val facts = database.semanticFacts(media.map(GalleryItem::id))
            assertEquals(media.map(GalleryItem::id).toSet(), facts.map(SemanticFactRecord::subjectId).toSet())
            assertFalse(database.hasPendingSemanticEnrichmentJobs())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
