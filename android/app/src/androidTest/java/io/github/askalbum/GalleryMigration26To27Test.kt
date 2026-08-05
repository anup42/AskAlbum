package io.github.anup42.askalbum

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
class GalleryMigration26To27Test {
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
    fun migrationAddsCoverageFingerprintWithoutChangingExistingScans() {
        helper.createDatabase(TEST_DATABASE, 26).apply {
            execSQL(
                """
                INSERT INTO semantic_predicate_scan(
                    id,query_key,query_text,model_version,scope_hash,eligible_count,indexed_count,
                    searched_count,next_ordinal,hit_count,status,attempt_count,next_attempt_at,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf("scan-1", "key-1", "exact dog", "siglip-v1", "scope-1", 2, 2, 2, 2, 1, "COMPLETE", 0, 0L, 1L, 1L),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            27,
            true,
            GalleryRoomDatabase.MIGRATION_26_27,
        ).use { database ->
            database.query("PRAGMA table_info(semantic_predicate_scan)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(columns.contains("indexed_coverage_hash"))
            }
            database.query("SELECT id,indexed_count,indexed_coverage_hash FROM semantic_predicate_scan WHERE id='scan-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("scan-1", cursor.getString(0))
                assertEquals(2, cursor.getInt(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-26-27.db"
    }
}
