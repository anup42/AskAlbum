package io.github.anup42.askalbum

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersonVerificationBindingsDatabaseTest {
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
    fun verificationLabelsOtherVisibleFacesWithoutGivingThemIdentityTerms() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_me")
        store.ensureAutomaticPersonCluster("person_unreviewed")
        val item = store.allItems().first { it.kind == MediaKind.IMAGE }
        store.completeEmbeddedFaces(
            item.id,
            listOf(
                face(.08f, .1f, .28f, .5f),
                face(.55f, .12f, .76f, .52f),
            ),
            listOf("person_me", "person_unreviewed"),
            "fixture-face-verification",
        )
        store.saveReviewedPersonCluster("person_me", "Me", "Me", emptyList())

        val bindings = store.verificationFaceBindingsForMedia(item.id)

        assertEquals(listOf("P1", "U1"), bindings.map(PersonVerificationBinding::stableLabel))
        assertEquals("person_me", bindings.first().clusterId)
        assertTrue(bindings.first().identityTerms.contains("Me"))
        assertTrue(bindings[1].clusterId.startsWith("unreviewed-face-"))
        assertTrue(bindings[1].identityTerms.isEmpty())
    }

    @Test
    fun missingIdentityEmbeddingCannotSatisfyPeopleSearchOrReceiveReviewedLabel() {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster("person_ready")
        store.ensureAutomaticPersonCluster("person_waiting")
        val item = store.allItems().first { it.kind == MediaKind.IMAGE }
        store.completeEmbeddedFaces(
            item.id,
            listOf(
                face(.08f, .1f, .28f, .5f),
                face(.55f, .12f, .76f, .52f),
            ),
            listOf("person_ready", "person_waiting"),
            "fixture-face-verification",
        )
        store.saveReviewedPersonCluster("person_ready", "Ready", "friend", emptyList())
        store.saveReviewedPersonCluster("person_waiting", "Waiting", "friend", emptyList())
        store.requestFaceEmbeddingRepair(setOf("${item.id}:1"), "fixture-face-repair")

        val bindings = store.verificationFaceBindingsForMedia(item.id)

        assertEquals(listOf("P1", "U1"), bindings.map(PersonVerificationBinding::stableLabel))
        assertEquals("person_ready", bindings.first().clusterId)
        assertTrue(bindings[1].clusterId?.startsWith("unreviewed-face-") == true)
        assertTrue(bindings[1].identityTerms.isEmpty())
        assertTrue(store.mediaIdsForReviewedPeople(listOf("person_waiting")).isEmpty())
    }

    private fun face(left: Float, top: Float, right: Float, bottom: Float) = FaceInstance(
        bounds = listOf(left, top, right, bottom),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[0] = 1f },
        quality = .9f,
    )

    private companion object {
        const val TEST_DATABASE = "person-verification-bindings-test.db"
    }
}
