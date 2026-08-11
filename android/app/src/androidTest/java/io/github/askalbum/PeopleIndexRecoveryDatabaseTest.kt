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

    @Test
    fun expiredOcrLeaseDoesNotResetCompletedThumbnailStage() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }

        database.close()
        store = null
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "UPDATE media_index_stage SET status='COMPLETE',lease_owner=NULL,lease_expires_at=NULL WHERE media_id=? AND stage='THUMBNAIL'",
                arrayOf<Any>(image.id),
            )
            raw.execSQL(
                "UPDATE media_index_stage SET status='RUNNING',lease_owner=?,lease_expires_at=? WHERE media_id=? AND stage='OCR'",
                arrayOf<Any>("ocr-owner", System.currentTimeMillis() - 1L, image.id),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        reopened.recoverInterruptedJobs(IndexingPipeline.MEDIA_ANALYSIS)
        assertEquals(StageStatus.COMPLETE, reopened.stageRecords(image.id).single { it.stage == IndexStage.THUMBNAIL }.status)
        assertEquals(StageStatus.PENDING, reopened.stageRecords(image.id).single { it.stage == IndexStage.OCR }.status)
    }

    @Test
    fun pendingThumbnailRepairsReadyParentIntoSelectableWork() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }

        database.close()
        store = null
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "UPDATE media_item SET source_kind='MEDIA_STORE',content_uri=?,index_state='READY' WHERE id=?",
                arrayOf<Any>("content://askalbum.test/recovery/${image.id}", image.id),
            )
            raw.execSQL(
                "UPDATE media_index_stage SET status='PENDING',lease_owner=NULL,lease_expires_at=NULL " +
                    "WHERE media_id=? AND stage='THUMBNAIL'",
                arrayOf<Any>(image.id),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        reopened.recoverInterruptedJobs(IndexingPipeline.MEDIA_ANALYSIS)

        assertEquals(IndexState.PENDING, reopened.itemById(image.id)?.indexState)
        assertEquals(listOf(image.id), reopened.pendingItems(10).map { it.id })
    }

    @Test
    fun orphanedThumbnailRecoveryClearsLiveLeaseAndMakesItemSelectable() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }

        database.close()
        store = null
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "UPDATE media_item SET source_kind='MEDIA_STORE',content_uri=?,index_state='PENDING' WHERE id=?",
                arrayOf<Any>("content://askalbum.test/orphan/${image.id}", image.id),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        assertTrue(reopened.markIndexing(image.id, "repository-direct"))

        reopened.recoverInterruptedJobs(
            pipeline = IndexingPipeline.MEDIA_ANALYSIS,
            reclaimOrphanedLeases = true,
        )

        val thumbnail = reopened.stageRecords(image.id).single { it.stage == IndexStage.THUMBNAIL }
        assertEquals(StageStatus.PENDING, thumbnail.status)
        assertEquals(0, thumbnail.attemptCount)
        assertEquals(IndexState.PENDING, reopened.itemById(image.id)?.indexState)
        assertEquals(listOf(image.id), reopened.pendingItems(10).map { it.id })
    }

    @Test
    fun canceledWorkerReleasesOnlyItsOwnedEmbeddingLease() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val images = database.allItems().filter { it.kind == MediaKind.IMAGE }.take(2)
        assertEquals(2, images.size)
        assertTrue(database.markEmbedding(images[0].id, "embedding-test-v1", "embedding-owner-a"))
        assertTrue(database.markEmbedding(images[1].id, "embedding-test-v1", "embedding-owner-b"))

        database.releaseIndexingLeases(IndexingPipeline.EMBEDDINGS, "embedding-owner-a")

        assertEquals(StageStatus.PENDING, database.stageRecords(images[0].id).single { it.stage == IndexStage.EMBEDDING }.status)
        assertEquals(StageStatus.RUNNING, database.stageRecords(images[1].id).single { it.stage == IndexStage.EMBEDDING }.status)
    }

    @Test
    fun completingMediaAnalysisPreservesAnotherPipelinesEmbeddingLease() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }
        assertTrue(database.markEmbedding(image.id, "embedding-test-v1", "embedding-owner"))

        database.completeIndex(
            id = image.id,
            labels = emptyList(),
            description = "",
            ocrText = "",
            faceCount = 0,
            previewPath = null,
            blocks = emptyList(),
            entities = emptyList(),
            ocrAttempted = false,
            ocrProducerVersion = null,
            visualFeatures = VisualFeatures(1L, 1f, 1f, 1f),
            keyframes = emptyList(),
        )
        database.releaseIndexingLeases(IndexingPipeline.EMBEDDINGS, "embedding-owner")

        assertEquals(StageStatus.PENDING, database.stageRecords(image.id).single { it.stage == IndexStage.EMBEDDING }.status)
    }

    @Test
    fun ownerlessRunningEmbeddingClaimIsRecovered() {
        val database = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        val image = database.allItems().first { it.kind == MediaKind.IMAGE }

        database.close()
        store = null
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(TEST_DATABASE).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            raw.execSQL(
                "UPDATE media_index_stage SET status='RUNNING',lease_owner=NULL,lease_expires_at=NULL " +
                    "WHERE media_id=? AND stage='EMBEDDING'",
                arrayOf<Any>(image.id),
            )
        }

        val reopened = GalleryDatabase(context, TEST_DATABASE).also { store = it }
        reopened.recoverInterruptedJobs(IndexingPipeline.EMBEDDINGS)

        assertEquals(StageStatus.PENDING, reopened.stageRecords(image.id).single { it.stage == IndexStage.EMBEDDING }.status)
    }

    private companion object {
        const val TEST_DATABASE = "people-index-recovery-test.db"
    }
}
