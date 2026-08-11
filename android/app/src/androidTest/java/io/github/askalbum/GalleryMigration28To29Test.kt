package io.github.anup42.askalbum

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
class GalleryMigration28To29Test {
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
    fun addsMediaIndexesWithoutChangingPersistedScanRows() {
        helper.createDatabase(TEST_DATABASE, 28).apply {
            insertMedia("scan-media")
            execSQL(
                """
                INSERT INTO semantic_predicate_scan(
                    id,query_key,query_text,model_version,scope_hash,eligible_count,indexed_count,
                    searched_count,next_ordinal,hit_count,status,attempt_count,next_attempt_at,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf("scan-1", "dog", "dog", "fixture-vector-v1", "scope-hash", 1, 1, 1, 1, 1, "COMPLETE", 0, 0L, 1L, 1L),
            )
            execSQL(
                "INSERT INTO semantic_predicate_scan_scope(scan_id,media_id,ordinal) VALUES(?,?,?)",
                arrayOf("scan-1", "scan-media", 0),
            )
            execSQL(
                "INSERT INTO semantic_predicate_scan_hit(scan_id,media_id,score) VALUES(?,?,?)",
                arrayOf("scan-1", "scan-media", 0.93f),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            29,
            true,
            GalleryRoomDatabase.MIGRATION_28_29,
        ).use { database ->
            assertEquals(1, rowCount(database, "semantic_predicate_scan"))
            assertEquals(1, rowCount(database, "semantic_predicate_scan_scope"))
            assertEquals(1, rowCount(database, "semantic_predicate_scan_hit"))
            assertTrue(indexExists(database, "semantic_predicate_scan_scope_media_idx"))
            assertTrue(indexExists(database, "semantic_predicate_scan_hit_media_idx"))
        }
    }

    private fun SupportSQLiteDatabase.insertMedia(id: String) {
        insert(
            "media_item",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("filename", "$id.jpg")
                put("title", id)
                put("index_version", "fixture-v1")
            },
        )
    }

    private fun rowCount(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun indexExists(database: SupportSQLiteDatabase, name: String): Boolean =
        database.query(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='index' AND name=?",
            arrayOf(name),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0) == 1
        }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-28-29.db"
    }
}
