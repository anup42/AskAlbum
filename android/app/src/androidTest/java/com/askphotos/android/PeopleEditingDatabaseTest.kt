package com.askphotos.android

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleEditingDatabaseTest {
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
    fun reviewRenameAliasMergeSplitHideAndResetAreTransactional() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        store.upsertImported(
            listOf(
                ImportedMedia(
                    stableId = "person-order-older",
                    uri = "content://people-test/older",
                    displayName = "older.jpg",
                    mimeType = "image/jpeg",
                    source = MediaSource.MEDIA_STORE,
                    capturedAt = 1_700_000_000_000L,
                    modifiedAt = 1_700_000_000_000L,
                    durationMs = null,
                    width = 1200,
                    height = 900,
                    sizeBytes = 1_000L,
                ),
                ImportedMedia(
                    stableId = "person-order-newer",
                    uri = "content://people-test/newer",
                    displayName = "newer.jpg",
                    mimeType = "image/jpeg",
                    source = MediaSource.MEDIA_STORE,
                    capturedAt = 1_800_000_000_000L,
                    modifiedAt = 1_800_000_000_000L,
                    durationMs = null,
                    width = 1200,
                    height = 900,
                    sizeBytes = 1_000L,
                ),
            ),
        )
        val media = store.allItems().filter { it.id == "person-order-older" || it.id == "person-order-newer" }
        assertEquals(2, media.size)
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_me")
        store.ensureAutomaticPersonCluster("person_brother")
        store.completeEmbeddedFaces(media[0].id, listOf(face()), listOf("person_me"), "fixture-face-v1")
        store.completeEmbeddedFaces(media[1].id, listOf(face()), listOf("person_brother"), "fixture-face-v1")

        store.saveReviewedPersonCluster("person_me", "Anup", "Me", listOf("मैं", "main"))
        store.saveReviewedPersonCluster("person_brother", "Ravi", "brother", listOf("भैया", "bhaiya"))
        assertEquals(setOf("person_me"), store.resolveReviewedPersonIds("photos with me"))
        assertEquals(setOf("person_brother"), store.resolveReviewedPersonIds("भैया वाली फोटो"))
        assertEquals(setOf("person_brother"), store.resolveReviewedPersonIds("bhaiya photos"))

        store.saveReviewedPersonCluster("person_me", "Anup Kumar", "Me", listOf("मैं", "main"))
        assertEquals("Anup Kumar", store.personClusterSummaries(true).single { it.id == "person_me" }.label)

        val brotherFaceId = "${media[1].id}:0"
        store.setPersonClusterRepresentative("person_brother", brotherFaceId)
        assertEquals(brotherFaceId, store.personClusterSummaries(true).single { it.id == "person_brother" }.representativeFaceId)
        store.excludeFaceFromCluster(brotherFaceId)
        assertNull(store.clusterIdForFace(brotherFaceId))
        assertNull(store.personClusterSummaries(true).single { it.id == "person_brother" }.representativeFaceId)
        store.completeEmbeddedFaces(media[1].id, listOf(face()), listOf("person_brother"), "fixture-face-v1-excluded")
        assertNull("A user exclusion must survive reindex", store.clusterIdForFace(brotherFaceId))

        val movedCluster = store.moveFaceToCluster(brotherFaceId)
        assertEquals(movedCluster, store.clusterIdForFace(brotherFaceId))
        store.completeEmbeddedFaces(media[1].id, listOf(face()), listOf("person_brother"), "fixture-face-v2")
        assertEquals("A user-corrected split must survive reindex", movedCluster, store.clusterIdForFace(brotherFaceId))

        store.mergePersonClusters("person_me", movedCluster)
        assertEquals("person_me", store.clusterIdForFace(brotherFaceId))
        val firstPage = store.personFacesForCluster("person_me", limit = 1, offset = 0)
        val secondPage = store.personFacesForCluster("person_me", limit = 1, offset = 1)
        assertEquals(1, firstPage.size)
        assertEquals(1, secondPage.size)
        assertTrue(firstPage.single().id != secondPage.single().id)
        val expectedNewest = media.maxWith(
            compareBy<GalleryItem> { it.capturedAt ?: it.modifiedAt ?: 0L }
                .thenBy { it.modifiedAt ?: 0L },
        )
        assertEquals(expectedNewest.id, firstPage.single().mediaId)
        store.setPersonClusterRepresentative("person_me", secondPage.single().id)
        assertEquals(secondPage.single().id, store.personClusterSummaries(true).single { it.id == "person_me" }.representativeFaceId)
        assertEquals(
            "Choosing a representative must not move an older photo above the latest photo",
            expectedNewest.id,
            store.personFacesForCluster("person_me", limit = 1, offset = 0).single().mediaId,
        )
        store.setPersonClusterHidden("person_me", true)
        assertTrue(store.personClusterSummaries(true).single { it.id == "person_me" }.hidden)
        assertFalse(store.resolveReviewedPersonIds("Anup Kumar").contains("person_me"))
        store.setPersonClusterHidden("person_me", false)
        assertTrue(store.resolveReviewedPersonIds("Anup Kumar").contains("person_me"))

        store.removePersonLabel("person_brother")
        assertFalse(store.personClusterSummaries(true).single { it.id == "person_brother" }.reviewed)
        val reset = store.resetPeopleIndex()
        assertEquals(0, reset.faceInstanceCount)
        assertEquals(0, reset.personClusterCount)
    }

    private fun face() = FaceInstance(
        bounds = listOf(.1f, .1f, .4f, .5f),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[0] = 1f },
        quality = .9f,
    )

    private companion object {
        const val TEST_DATABASE = "people-editing-test.db"
    }
}
