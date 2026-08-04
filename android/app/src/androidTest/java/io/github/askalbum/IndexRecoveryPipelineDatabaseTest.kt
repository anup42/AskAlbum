package io.github.anup42.askalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexRecoveryPipelineDatabaseTest {
    @Test
    fun embeddingRecoveryCannotResetMediaLeaseOrLowerAttempts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "recovery-scope-test-${System.currentTimeMillis()}.db"
        try {
            val database = GalleryDatabase(context, databaseName)
            database.seedDemoIfEmpty()
            database.ensureStageRows()
            val mediaId = database.allItems().first().id
            val sql = GalleryRoomDatabase.open(context, databaseName).openHelper.writableDatabase
            sql.execSQL(
                "UPDATE media_index_stage SET status='RUNNING', lease_owner='media-owner', lease_expires_at=0, attempt_count=3 " +
                    "WHERE media_id=? AND stage='THUMBNAIL'",
                arrayOf(mediaId),
            )
            sql.execSQL(
                "UPDATE media_index_stage SET status='RUNNING', lease_owner='embedding-owner', lease_expires_at=0, attempt_count=3 " +
                    "WHERE media_id=? AND stage='EMBEDDING'",
                arrayOf(mediaId),
            )

            database.recoverInterruptedJobs(setOf(IndexRecoveryPipeline.EMBEDDING))

            val stages = sql.query(
                "SELECT stage,status,attempt_count FROM media_index_stage WHERE media_id=? AND stage IN ('THUMBNAIL','EMBEDDING')",
                arrayOf(mediaId),
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getString(1) to cursor.getInt(2))
                    }
                }
            }
            assertEquals("RUNNING" to 3, stages[IndexStage.THUMBNAIL.name])
            assertEquals("PENDING" to 3, stages[IndexStage.EMBEDDING.name])
        } finally {
            context.deleteDatabase(databaseName)
        }
    }
}
