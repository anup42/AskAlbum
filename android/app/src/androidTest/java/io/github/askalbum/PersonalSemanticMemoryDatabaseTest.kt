package io.github.anup42.askalbum

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonalSemanticMemoryDatabaseTest {
    @Test
    fun reviewedFamilyQueuesAllMediaWhileFriendAndHiddenPeopleDoNot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "personal-semantic-${UUID.randomUUID()}.db"
        val store = GalleryDatabase(context, name)
        try {
            store.seedDemoIfEmpty()
            val media = store.allItems().take(3)
            store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            listOf("person_me", "person_family", "person_friend").forEach(store::ensureAutomaticPersonCluster)
            media.forEachIndexed { index, item ->
                store.completeEmbeddedFaces(
                    item.id,
                    listOf(face()),
                    listOf(listOf("person_me", "person_family", "person_friend")[index]),
                    "fixture-face",
                )
            }
            store.saveReviewedPersonCluster("person_me", "Me", "Me", emptyList())
            store.saveReviewedPersonCluster("person_family", "Family", "sister", emptyList())
            store.saveReviewedPersonCluster("person_friend", "Friend", "friend", emptyList())

            assertEquals(2, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma", true))
            val progress = store.semanticMemoryProgress()
            assertEquals(2, progress.personalEligibleCount)
            assertEquals(2, progress.personalPendingCount)

            assertEquals(2, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma-v2", true))
            val replacementProgress = store.semanticMemoryProgress()
            assertEquals(2, replacementProgress.totalJobs)
            assertEquals(2, replacementProgress.pendingJobs)
            assertEquals(0, replacementProgress.completedJobs)

            assertTrue(store.personClusterSummaries(true).single { it.id == "person_me" }.includeInPersonalSemanticMemory)
            assertFalse(store.personClusterSummaries(true).single { it.id == "person_friend" }.includeInPersonalSemanticMemory)

            store.setPersonClusterHidden("person_family", true)
            assertEquals(1, store.semanticMemoryProgress().personalEligibleCount)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun validMediaCaptionCountsAsPersonalCoverage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "personal-semantic-caption-${UUID.randomUUID()}.db"
        val store = GalleryDatabase(context, name)
        try {
            store.seedDemoIfEmpty()
            val media = store.allItems().first()
            store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            store.ensureAutomaticPersonCluster("person_me")
            store.completeEmbeddedFaces(media.id, listOf(face()), listOf("person_me"), "fixture-face")
            store.saveReviewedPersonCluster("person_me", "Me", "Me", emptyList())

            assertEquals(1, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma", true))
            val job = requireNotNull(store.claimSemanticEnrichmentJob(owner = "caption-owner"))
            store.completeSemanticEnrichment(
                job,
                SemanticEnrichmentResult(
                    facts = emptyList(),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = media.id,
                        text = "Me is standing outdoors beside a lake.",
                        confidence = .9f,
                        evidenceMediaId = media.id,
                        sourceType = "GEMMA_MEDIA_DIRECT",
                        modelVersion = "fixture-gemma",
                        promptVersion = SemanticEnrichmentCodec.PROMPT_VERSION,
                    ),
                ),
            )

            val progress = store.semanticMemoryProgress()
            assertEquals(1, progress.personalEligibleCount)
            assertEquals(1, progress.personalCompletedCount)
            assertEquals(0, progress.personalPendingCount)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }
    @Test
    fun policyReplacementPreservesLiveLeaseAndRejectsStaleCompletion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "personal-semantic-live-${UUID.randomUUID()}.db"
        val store = GalleryDatabase(context, name)
        try {
            store.seedDemoIfEmpty()
            val media = store.allItems().first()
            store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            store.ensureAutomaticPersonCluster("person_me")
            store.completeEmbeddedFaces(media.id, listOf(face()), listOf("person_me"), "fixture-face")
            store.saveReviewedPersonCluster("person_me", "Me", "Me", emptyList())

            assertEquals(1, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma", true))
            val oldJob = requireNotNull(store.claimSemanticEnrichmentJob(owner = "old-owner"))
            assertEquals(1, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma-v2", true))

            val progress = store.semanticMemoryProgress()
            assertEquals(1, progress.personalPendingCount)
            assertEquals(0, progress.personalCompletedCount)
            assertEquals("personal_media:fixture-gemma-v2:${PersonalSemanticMemoryPolicy.PROMPT_VERSION}:${PersonalSemanticMemoryPolicy.BODY_REGION_VERSION}:${PersonalSemanticMemoryPolicy.CAPTION_POLICY_VERSION}", requireNotNull(store.claimSemanticEnrichmentJob(owner = "new-owner")).reason)

            store.completeSemanticEnrichment(
                oldJob,
                listOf(
                    SemanticFactRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = media.id,
                        predicate = "stale",
                        value = "must not persist",
                        confidence = .9f,
                        evidenceMediaId = media.id,
                        modelVersion = "old-model",
                        promptVersion = "old-prompt",
                    ),
                ),
            )
            assertTrue(store.semanticFacts(listOf(media.id)).none { it.predicate == "stale" })
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun hidingOnePersonInvalidatesOnlyThatIdentityEvidence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "personal-semantic-scoped-${UUID.randomUUID()}.db"
        val store = GalleryDatabase(context, name)
        try {
            store.seedDemoIfEmpty()
            val media = store.allItems().first()
            store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            store.ensureAutomaticPersonCluster("person_me")
            store.ensureAutomaticPersonCluster("person_wife")
            store.completeEmbeddedFaces(
                media.id,
                listOf(face(0), face(1)),
                listOf("person_me", "person_wife"),
                "fixture-face",
            )
            store.saveReviewedPersonCluster("person_me", "Me", "Me", emptyList())
            store.saveReviewedPersonCluster("person_wife", "Wife", "partner", emptyList())
            assertEquals(1, store.queueEligiblePersonalSemanticMemoryJobs("fixture-gemma", true))
            val job = requireNotNull(store.claimSemanticEnrichmentJob(owner = "caption-owner"))
            val generationId = "generation-${UUID.randomUUID()}"
            val promptVersion = SemanticEnrichmentCodec.PROMPT_VERSION
            val faceRegion = listOf(.1f, .1f, .4f, .6f)
            store.completeSemanticEnrichment(
                job,
                SemanticEnrichmentResult(
                    facts = listOf(
                        SemanticFactRecord(
                            scope = SemanticFactScope.MEDIA,
                            subjectId = media.id,
                            predicate = "scene_setting",
                            value = "outdoors beside a lake",
                            confidence = .92f,
                            evidenceMediaId = media.id,
                            modelVersion = "fixture-gemma",
                            promptVersion = promptVersion,
                            generationId = generationId,
                        ),
                    ),
                    caption = SemanticCaptionRecord(
                        scope = SemanticFactScope.MEDIA,
                        subjectId = media.id,
                        text = "P1 is wearing red clothing beside P2, who is wearing a white dress.",
                        confidence = .95f,
                        evidenceMediaId = media.id,
                        sourceType = "GEMMA_MEDIA_DIRECT",
                        modelVersion = "fixture-gemma",
                        promptVersion = promptVersion,
                        personRefs = listOf(
                            SemanticCaptionPersonRefRecord("P1", "person_me", faceRegion = faceRegion, associationStatus = PersonAssociationStatus.CONFIDENT),
                            SemanticCaptionPersonRefRecord("P2", "person_wife", faceRegion = faceRegion, associationStatus = PersonAssociationStatus.CONFIDENT),
                        ),
                        generationId = generationId,
                    ),
                    personFacts = listOf(
                        personFact(media.id, "person_me", "P1", "red clothing", generationId, promptVersion),
                        personFact(media.id, "person_wife", "P2", "white dress", generationId, promptVersion),
                    ),
                    generation = SemanticGenerationProvenance(
                        generationId = generationId,
                        jobId = job.id,
                        scope = SemanticFactScope.MEDIA,
                        scopeId = media.id,
                        evidenceMediaId = media.id,
                        modelVersion = "fixture-gemma",
                        promptVersion = promptVersion,
                        bodyRegionVersion = PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
                        createdAt = System.currentTimeMillis(),
                    ),
                ),
            )

            val chunksBeforeHide = store.semanticCaptionChunksForMedia(media.id)
            val neutralChunkIds = chunksBeforeHide
                .filter { it.clusterId == null && it.chunkType != CaptionChunkType.PERSON_RELATION }
                .mapTo(linkedSetOf(), SemanticCaptionChunkRecord::id)
            assertTrue(neutralChunkIds.isNotEmpty())
            assertEquals(
                setOf("person_me", "person_wife"),
                store.personVisualFactsForMedia(media.id).mapTo(linkedSetOf(), PersonVisualFactRecord::clusterId),
            )
            assertTrue(chunksBeforeHide.any { it.clusterId == "person_me" })
            assertTrue(chunksBeforeHide.any { it.clusterId == "person_wife" })

            store.setPersonClusterHidden("person_me", true)

            val chunksAfterFirstHide = store.semanticCaptionChunksForMedia(media.id)
            assertEquals(
                setOf("person_wife"),
                store.personVisualFactsForMedia(media.id).mapTo(linkedSetOf(), PersonVisualFactRecord::clusterId),
            )
            assertFalse(chunksAfterFirstHide.any { it.clusterId == "person_me" })
            assertTrue(chunksAfterFirstHide.any { it.clusterId == "person_wife" })
            assertTrue(chunksAfterFirstHide.mapTo(linkedSetOf(), SemanticCaptionChunkRecord::id).containsAll(neutralChunkIds))
            assertEquals(
                setOf("person_wife"),
                store.semanticCaptionsForMedia(media.id).single().personRefs.mapTo(linkedSetOf(), SemanticCaptionPersonRefRecord::clusterId),
            )
            assertEquals("STALE_PERSON_BINDING", store.semanticCaptionsForMedia(media.id).single().applicability)
            assertEquals(1, store.semanticMemoryProgress().personalPendingCount)

            store.setPersonClusterHidden("person_wife", true)

            assertTrue(store.personVisualFactsForMedia(media.id).isEmpty())
            assertFalse(store.semanticCaptionChunksForMedia(media.id).any { it.clusterId != null })
            assertEquals(0, store.semanticMemoryProgress().personalEligibleCount)
            assertEquals(0, store.semanticMemoryProgress().personalPendingCount)
        } finally {
            store.close()
            context.deleteDatabase(name)
        }
    }

    private fun face(axis: Int = 0) = FaceInstance(
        bounds = listOf(.1f, .1f, .4f, .5f),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[axis] = 1f },
        quality = .9f,
    )

    private fun personFact(
        mediaId: String,
        clusterId: String,
        personRef: String,
        value: String,
        generationId: String,
        promptVersion: String,
    ) = PersonVisualFactRecord(
        mediaId = mediaId,
        clusterId = clusterId,
        personRef = personRef,
        relation = PersonVisualRelation.WEARING,
        category = WornItemCategory.CLOTHING,
        itemType = "clothing",
        value = value,
        attributes = mapOf("colors" to listOf(value.substringBefore(' '))),
        bodyRegion = BodyRegion.UPPER_BODY,
        confidence = .95f,
        faceRegion = listOf(.1f, .1f, .4f, .6f),
        evidenceRegion = listOf(.1f, .2f, .4f, .8f),
        modelVersion = "fixture-gemma",
        promptVersion = promptVersion,
        generationId = generationId,
    )
}
