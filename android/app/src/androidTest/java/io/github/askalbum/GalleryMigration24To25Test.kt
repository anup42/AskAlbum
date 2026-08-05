package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration24To25Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @After
    fun cleanup() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun migrationAddsKeyframeFailureStateAndPreservesCompletion() {
        helper.createDatabase(TEST_DATABASE, 24).apply {
            insert(
                "video_keyframe",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "pending-frame")
                    put("media_id", "media-pending")
                    put("timestamp_ms", 100L)
                    put("preview_path", "video-keyframes/pending.jpg")
                    put("labels", "")
                    put("ocr_text", "")
                    put("perceptual_hash", "1")
                    put("quality_score", 0.8f)
                    put("producer_version", "video-keyframes-v1")
                    putNull("embedding_version")
                },
            )
            insert(
                "video_keyframe",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "complete-frame")
                    put("media_id", "media-complete")
                    put("timestamp_ms", 200L)
                    put("preview_path", "video-keyframes/complete.jpg")
                    put("labels", "")
                    put("ocr_text", "")
                    put("perceptual_hash", "2")
                    put("quality_score", 0.9f)
                    put("producer_version", "video-keyframes-v1")
                    put("embedding_version", "siglip-v1")
                },
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            25,
            true,
            GalleryRoomDatabase.MIGRATION_24_25,
        ).use { database ->
            database.query("SELECT embedding_state,embedding_attempt_count,embedding_error,embedding_next_attempt_at FROM video_keyframe WHERE id='pending-frame'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("PENDING", cursor.getString(0))
                assertEquals(0, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
                assertEquals(0L, cursor.getLong(3))
            }
            database.query("SELECT embedding_state FROM video_keyframe WHERE id='complete-frame'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("COMPLETE", cursor.getString(0))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-24-25.db"
    }
}
