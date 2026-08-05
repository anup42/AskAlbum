package io.github.anup42.askalbum

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleIndexRecoveryDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var store: GalleryDatabase? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        store?.close()
        store = null
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun expiredPeopleLeaseIsRecoveredWithoutReclaimingEmbeddingLease() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }
        database.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        assertTrue(database.markFaces(image.id, "face-test-v1", "people-owner"))
        assertTrue(database.markEmbedding(image.id, "embedding-test-v1", "embedding-owner"))

        database.close()
        store = null
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "UPDATE media_index_stage SET lease_expires_at=? WHERE media_id=? AND stage='FACES'",
                arrayOf<Any>(System.currentTimeMillis() - 1L, image.id),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        reopened.recoverInterruptedJobs(IndexingPipeline.PEOPLE)
        assertEquals(StageStatus.PENDING, reopened.stageRecords(image.id).single { it.stage == IndexStage.FACES }.status)
        assertEquals(StageStatus.RUNNING, reopened.stageRecords(image.id).single { it.stage == IndexStage.EMBEDDING }.status)
    }

    private companion object {
        const val TEST_DATABASE = "people-index-recovery-test.db"
    }
}
