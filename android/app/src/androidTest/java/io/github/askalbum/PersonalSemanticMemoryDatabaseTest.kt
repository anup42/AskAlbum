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

    private fun face() = FaceInstance(
        bounds = listOf(.1f, .1f, .4f, .5f),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[0] = 1f },
        quality = .9f,
    )
}
