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
            assertTrue(store.personClusterSummaries(true).single { it.id == "person_me" }.includeInPersonalSemanticMemory)
            assertFalse(store.personClusterSummaries(true).single { it.id == "person_friend" }.includeInPersonalSemanticMemory)

            store.setPersonClusterHidden("person_family", true)
            assertEquals(1, store.semanticMemoryProgress().personalEligibleCount)
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
