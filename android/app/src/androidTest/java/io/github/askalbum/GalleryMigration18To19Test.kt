package io.github.anup42.askalbum

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration18To19Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsResumableScanTablesWithoutChangingMedia() {
        helper.createDatabase(TEST_DATABASE, 18).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "dog.jpg", "Dog", "analysis-v1"),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            19,
            true,
            GalleryRoomDatabase.MIGRATION_18_19,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM media_item").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            database.query("PRAGMA table_info(semantic_predicate_scan)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.containsAll(setOf("query_key", "scope_hash", "next_ordinal", "lease_owner", "last_progress_at")))
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('semantic_predicate_scan_scope','semantic_predicate_scan_hit')",
            ).use { cursor ->
                var count = 0
                while (cursor.moveToNext()) count += 1
                assertEquals(2, count)
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-18-19.db"
    }
}
