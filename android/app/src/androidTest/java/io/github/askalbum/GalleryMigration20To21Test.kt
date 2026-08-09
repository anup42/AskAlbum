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
class GalleryMigration20To21Test {
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
    fun migrationAddsProvenanceColumnsWithoutChangingExistingFacts() {
        helper.createDatabase(TEST_DATABASE, 20).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "one.jpg", "One", "fixture-v1"),
            )
            insert(
                "semantic_fact",
                SQLiteDatabase.CONFLICT_NONE,
                ContentValues().apply {
                    put("id", "fact-1")
                    put("scope", SemanticFactScope.MEDIA.name)
                    put("subject_id", "media-1")
                    put("predicate", "scene")
                    put("value", "park")
                    put("confidence", 0.9f)
                    put("evidence_media_id", "media-1")
                    putNull("region")
                    put("applicability", "EVIDENCE_MEDIA_ONLY")
                    put("model_version", "fixture")
                    put("prompt_version", "fixture-v1")
                    put("updated_at", 1L)
                },
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            21,
            true,
            GalleryRoomDatabase.MIGRATION_20_21,
        ).use { database ->
            listOf("semantic_fact", "semantic_caption", "semantic_caption_chunk", "person_attribute_fact")
                .forEach { table ->
                    assertTrue("$table must have generation_id", columns(database, table).contains("generation_id"))
                }
            assertTrue(columns(database, "semantic_generation").containsAll(setOf("generation_id", "job_id", "scope_id")))
            database.query("SELECT COUNT(*),MIN(value),MIN(generation_id) FROM semantic_fact").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
                assertEquals("park", cursor.getString(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    private fun columns(database: androidx.sqlite.db.SupportSQLiteDatabase, table: String): Set<String> =
        database.query("PRAGMA table_info($table)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-20-21.db"
    }
}
