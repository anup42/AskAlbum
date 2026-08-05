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
    fun contextualFactsDoNotInflateDirectMediaCoverage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "semantic-coverage-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val mediaId = database.allItems().first().id
            val eventJob = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.EVENT,
                subjectId = "event:coverage",
                representativeMediaId = mediaId,
                reason = "event-context",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(eventJob)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob()),
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.EVENT,
                        subjectId = "event:coverage",
                        predicate = "occasion",
                        value = "shared meal",
                        confidence = 0.8f,
                        evidenceMediaId = mediaId,
                        applicability = "CONTEXT_ONLY",
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )
            assertEquals(0, database.summary().semanticFactsReady)

            val mediaJob = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                representativeMediaId = mediaId,
                reason = "media-fact",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(mediaJob)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob()),
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = mediaId,
                        predicate = "scene",
                        value = "living room",
                        confidence = 0.9f,
                        evidenceMediaId = mediaId,
                        applicability = "EVIDENCE_MEDIA_ONLY",
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )
            assertEquals(1, database.summary().semanticFactsReady)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun staleSemanticOwnerCannotCommitFactsOrFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "semantic-lease-fence-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val mediaId = database.allItems().first().id
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                representativeMediaId = mediaId,
                reason = "lease-fence",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(job)))
            val claimed = requireNotNull(database.claimSemanticEnrichmentJob(owner = "owner-1"))
            val stale = claimed.copy(leaseOwner = "owner-2")
            val fact = SemanticFactRecord(
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                predicate = "scene",
                value = "stale owner must not persist",
                confidence = 0.9f,
                evidenceMediaId = mediaId,
                modelVersion = "fixture",
                promptVersion = "fixture-v1",
            )

            database.completeSemanticEnrichment(stale, listOf(fact))
            database.failSemanticEnrichment(stale, "stale failure", retryable = false)
            assertTrue(database.allSemanticFacts().isEmpty())

            database.completeSemanticEnrichment(claimed, listOf(fact))
            assertEquals(1, database.allSemanticFacts().count { it.value == fact.value })
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun contextualCaptionDoesNotInflateDirectCaptionCoverage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "caption-coverage-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val mediaId = database.allItems().first().id
            val eventJob = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.EVENT,
                subjectId = "event:caption-coverage",
                representativeMediaId = mediaId,
                reason = "event-caption",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(eventJob)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob(owner = "event-caption-owner")),
                SemanticEnrichmentResult(
                    facts = emptyList(),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.EVENT,
                        subjectId = eventJob.subjectId,
                        text = "A shared event scene",
                        confidence = 0.8f,
                        evidenceMediaId = mediaId,
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )
            assertEquals(0, database.semanticCaptionEvidenceCount(setOf(mediaId)))

            val mediaJob = eventJob.copy(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                reason = "media-caption",
                status = SemanticEnrichmentStatus.PENDING,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(mediaJob)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob(owner = "media-caption-owner")),
                SemanticEnrichmentResult(
                    facts = emptyList(),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = mediaId,
                        text = "A direct media scene",
                        confidence = 0.9f,
                        evidenceMediaId = mediaId,
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )
            assertEquals(1, database.semanticCaptionEvidenceCount(setOf(mediaId)))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun staleMediaCaptionDoesNotCountAsCurrentCaptionCoverage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "stale-caption-coverage-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val mediaId = database.allItems().first().id
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.MEDIA,
                subjectId = mediaId,
                representativeMediaId = mediaId,
                reason = "stale-caption",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(emptyList(), emptyList(), listOf(job)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob(owner = "stale-caption-owner")),
                SemanticEnrichmentResult(
                    facts = emptyList(),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = mediaId,
                        text = "An outdated personal caption",
                        confidence = 0.8f,
                        evidenceMediaId = mediaId,
                        applicability = SemanticProvenanceApplicability.STALE_PERSON_BINDING,
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )

            assertEquals(0, database.semanticCaptionEvidenceCount(setOf(mediaId)))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun exactDuplicateCaptionCoverageIncludesEveryVerifiedMember() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "exact-caption-coverage-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            val media = database.allItems().take(2)
            assertEquals(2, media.size)
            val group = VisualGroupPlan(
                id = "exact:caption-coverage",
                kind = "EXACT_DUPLICATE",
                canonicalMediaId = media.first().id,
                members = media.map(GalleryItem::id),
                representatives = listOf(media.first().id),
            )
            val job = SemanticEnrichmentJobRecord(
                id = UUID.randomUUID().toString(),
                scope = SemanticFactScope.EXACT_DUPLICATE_GROUP,
                subjectId = group.id,
                representativeMediaId = media.first().id,
                reason = "exact-caption",
                status = SemanticEnrichmentStatus.PENDING,
                attemptCount = 0,
                userRequested = false,
            )
            database.replaceSemanticEnrichmentPlan(SemanticEnrichmentPlan(listOf(group), emptyList(), listOf(job)))
            database.completeSemanticEnrichment(
                requireNotNull(database.claimSemanticEnrichmentJob(owner = "exact-caption-owner")),
                SemanticEnrichmentResult(
                    facts = emptyList(),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.EXACT_DUPLICATE_GROUP,
                        subjectId = group.id,
                        text = "A verified duplicate scene",
                        confidence = 0.9f,
                        evidenceMediaId = media.first().id,
                        applicability = SemanticProvenanceApplicability.SAFE_FOR_EXACT_DUPLICATES,
                        modelVersion = "fixture",
                        promptVersion = "fixture-v1",
                    ),
                ),
            )

            assertEquals(2, database.semanticCaptionEvidenceCount(media.map(GalleryItem::id).toSet()))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

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
