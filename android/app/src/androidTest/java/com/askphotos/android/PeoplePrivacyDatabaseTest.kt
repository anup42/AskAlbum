package com.askphotos.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeoplePrivacyDatabaseTest {
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
    fun disabledByDefaultAndResetDeletesEveryDerivedPeopleRecord() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        val media = store.allItems().first { it.kind == MediaKind.IMAGE }

        val initial = store.peopleIndexStatus()
        assertFalse(initial.enabled)
        assertEquals(0, initial.faceInstanceCount)
        assertThrows(IllegalStateException::class.java) {
            store.completeFaces(media.id, listOf(face()), "test-face-v1")
        }

        val enabled = store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        assertTrue(enabled.enabled)
        assertEquals(GalleryDatabase.PEOPLE_CONSENT_VERSION, enabled.consentVersion)
        store.completeFaces(media.id, listOf(face()), "test-face-v1")
        store.saveReviewedPersonCluster("person_fixture", "Fixture Person", "friend", listOf("Friend", "दोस्त"))

        val populated = store.peopleIndexStatus()
        assertEquals(1, populated.faceInstanceCount)
        assertEquals(1, populated.personClusterCount)
        assertEquals(1, populated.reviewedClusterCount)
        assertEquals(0, populated.identityReadyFaceCount)

        val reset = store.resetPeopleIndex()
        assertFalse(reset.enabled)
        assertEquals(0, reset.consentVersion)
        assertEquals(0, reset.faceInstanceCount)
        assertEquals(0, reset.personClusterCount)
        assertEquals(0, reset.reviewedClusterCount)
        assertEquals(0, store.allItems().sumOf { it.faceCount })
        val faceStage = store.stageRecords(media.id).single { it.stage == IndexStage.FACES }
        assertEquals(StageStatus.SKIPPED, faceStage.status)
        assertEquals("disabled-until-opt-in", faceStage.producerVersion)
    }

    @Test
    fun embeddedFaceCanBeReviewedWithoutLosingClusterAssignment() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        val media = store.allItems().first { it.kind == MediaKind.IMAGE }
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_fixture")
        store.completeEmbeddedFaces(
            media.id,
            listOf(FaceInstance(listOf(.1f, .1f, .4f, .5f), normalizedEmbedding(), .8f)),
            listOf("person_fixture"),
            FaceModelCatalog.sface.producerVersion,
        )

        store.saveReviewedPersonCluster("person_fixture", "Fixture Person", "friend", listOf("Friend", "Dost"))

        val status = store.peopleIndexStatus()
        assertEquals(1, status.identityReadyFaceCount)
        assertEquals(setOf(media.id), store.mediaIdsForReviewedPeople(listOf("friend")))
        assertEquals(setOf(media.id), store.mediaIdsForReviewedPeople(listOf("dost")))
        assertEquals("person_fixture", store.clusterIdForFace("${media.id}:0"))
    }

    private fun face() = FaceDetectionRecord(.1f, .1f, .4f, .5f, .8f)

    private fun normalizedEmbedding() = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[0] = 1f }

    private companion object {
        const val TEST_DATABASE = "people-privacy-test.db"
    }
}
