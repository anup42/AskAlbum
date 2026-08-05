package io.github.anup42.askalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticEnrichmentDatabaseTest {
    @Test
    fun representativeJobPreservesScopeAndSharesOnlyVerifiedExactDuplicates() {
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
            val digest = "sha256-file-v1:1200x900:verified-exact"
            database.recordExactContentDigest(media[0].id, digest)
            database.recordExactContentDigest(media[1].id, digest)
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.EXACT_DUPLICATE_GROUP,
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
                        scope = SemanticFactScope.EXACT_DUPLICATE_GROUP,
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

            val facts = database.allSemanticFacts()
            val source = facts.single { it.scope == SemanticFactScope.EXACT_DUPLICATE_GROUP }
            assertEquals(group.id, source.subjectId)
            assertEquals(media.first().id, source.evidenceMediaId)
            assertEquals("SAFE_FOR_EXACT_DUPLICATES", source.applicability)
            val shared = facts.filter { it.applicability == "EXACT_DUPLICATE_SHARED" }
            assertEquals(media.map(GalleryItem::id).toSet(), shared.map(SemanticFactRecord::subjectId).toSet())
            assertTrue(shared.all { it.scope == SemanticFactScope.MEDIA })
            assertTrue(shared.all { it.subjectId == it.evidenceMediaId })

            val eventJob = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.EVENT,
                subjectId = "event:42",
                representativeMediaId = media.first().id,
                reason = "event-context",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(
                SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(eventJob)),
            )
            val claimedEvent = requireNotNull(database.claimSemanticEnrichmentJob())
            database.completeSemanticEnrichment(
                claimedEvent,
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.EVENT,
                        subjectId = "event:42",
                        predicate = "occasion",
                        value = "shared meal",
                        confidence = 0.8f,
                        evidenceMediaId = media.first().id,
                        applicability = "CONTEXT_ONLY",
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )
            val event = database.allSemanticFacts().single { it.predicate == "occasion" }
            assertEquals(SemanticFactScope.EVENT, event.scope)
            assertEquals("event:42", event.subjectId)
            assertEquals("CONTEXT_ONLY", event.applicability)
            assertFalse(database.hasPendingSemanticEnrichmentJobs())
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
