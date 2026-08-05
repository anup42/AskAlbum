package io.github.anup42.askalbum

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PeopleIndexLeaseFenceDatabaseTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun staleOwnerCannotFailAReclaimedFaceStage() {
        val name = "people-fail-fence-${UUID.randomUUID()}.db"
        var database: GalleryDatabase? = null
        try {
            val first = GalleryDatabase(context, name).also { database = it }
            first.seedDemoIfEmpty()
            first.ensureStageRows()
            first.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            val media = first.allItems().first()
            assertTrue(first.markFaces(media.id, "fixture-face-v1", "old-owner"))
            first.close()
            database = null

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw ->
                raw.execSQL(
                    "UPDATE media_index_stage SET lease_expires_at=? WHERE media_id=? AND stage='FACES'",
                    arrayOf<Any>(System.currentTimeMillis() - 1L, media.id),
                )
            }

            val reclaimed = GalleryDatabase(context, name).also { database = it }
            reclaimed.recoverInterruptedJobs(IndexingPipeline.PEOPLE)
            assertTrue(reclaimed.markFaces(media.id, "fixture-face-v2", "new-owner"))
            reclaimed.failFaces(media.id, "stale-worker", permanent = false, producerVersion = "fixture-face-v1", owner = "old-owner")

            val stage = reclaimed.stageRecords(media.id).single { it.stage == IndexStage.FACES }
            assertEquals(StageStatus.RUNNING, stage.status)
            assertNull(stage.error)
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
