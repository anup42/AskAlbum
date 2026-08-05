package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration21To22Test {
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
    @Throws(IOException::class)
    fun migrationAddsPriorityAndPreservesExistingJobs() {
        helper.createDatabase(TEST_DATABASE, 21).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "one.jpg", "One", "fixture-v1"),
            )
            insertJob(
                id = "personal-job",
                reason = "personal_media:fixture",
                userRequested = false,
                error = "old failure",
            )
            insertJob(
                id = "event-job",
                reason = "diverse_event_representative",
                userRequested = true,
                error = null,
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            22,
            true,
            GalleryRoomDatabase.MIGRATION_21_22,
        ).use { database ->
            assertTrue(columns(database, "semantic_enrichment_job").contains("priority"))
            database.query(
                "SELECT reason,priority,error FROM semantic_enrichment_job ORDER BY id",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("diverse_event_representative", cursor.getString(0))
                val eventPriority = cursor.getInt(1)
                cursor.moveToNext()
                assertEquals("personal_media:fixture", cursor.getString(0))
                val personalPriority = cursor.getInt(1)
                assertEquals("old failure", cursor.getString(2))
                assertTrue(personalPriority > eventPriority)
            }
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertJob(
        id: String,
        reason: String,
        userRequested: Boolean,
        error: String?,
    ) {
        insert(
            "semantic_enrichment_job",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("scope", SemanticFactScope.MEDIA.name)
                put("subject_id", "media-1")
                put("representative_media_id", "media-1")
                put("reason", reason)
                put("status", SemanticEnrichmentStatus.FAILED.name)
                put("attempt_count", 1)
                put("user_requested", userRequested)
                putNull("model_version")
                if (error == null) putNull("error") else put("error", error)
                put("updated_at", 1L)
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                putNull("last_progress_at")
            },
        )
    }

    private fun columns(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-21-22.db"
    }
}
