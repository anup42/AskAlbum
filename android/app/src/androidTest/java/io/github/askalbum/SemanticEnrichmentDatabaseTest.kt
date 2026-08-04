package io.github.anup42.askalbum

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
            assertEquals(
                setOf(SemanticFactScope.MEDIA),
                facts.map(SemanticFactRecord::scope).toSet(),
            )
            assertEquals(
                setOf("EXACT_DUPLICATE_SHARED"),
                facts.map(SemanticFactRecord::applicability).toSet(),
            )
            assertFalse(database.hasPendingSemanticEnrichmentJobs())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun eventFactsKeepEventScopeWhenSubjectDiffersFromEvidenceMedia() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "semantic-event-facts-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val media = database.allItems().first()
            database.completeSemanticEnrichment(
                SemanticEnrichmentJobRecord(
                    id = UUID.randomUUID().toString(),
                    scope = SemanticFactScope.EVENT,
                    subjectId = "event-1",
                    representativeMediaId = media.id,
                    reason = "event_representative",
                    status = SemanticEnrichmentStatus.PENDING,
                    attemptCount = 0,
                    userRequested = false,
                ),
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.EVENT,
                        subjectId = "event-1",
                        predicate = "occasion",
                        value = "birthday celebration",
                        confidence = 0.8f,
                        evidenceMediaId = media.id,
                        applicability = "POSSIBLE_INFERENCE",
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )

            val fact = database.semanticFacts(listOf(media.id)).single()
            assertEquals(SemanticFactScope.EVENT, fact.scope)
            assertEquals("event-1", fact.subjectId)
            assertEquals(media.id, fact.evidenceMediaId)
            assertEquals("POSSIBLE_INFERENCE", fact.applicability)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
