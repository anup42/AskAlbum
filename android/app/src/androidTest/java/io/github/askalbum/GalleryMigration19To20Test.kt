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
class GalleryMigration19To20Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsGenerationColumnsWithoutChangingLegacyRows() {
        helper.createDatabase(TEST_DATABASE, 19).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "one.jpg", "One", "test"),
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region,applicability,model_version,prompt_version,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("fact-1", "MEDIA", "media-1", "scene", "room", 0.8, "media-1", null, "EVIDENCE_MEDIA_ONLY", "test", "v1", 1L),
            )
            execSQL(
                "INSERT INTO semantic_caption(id,scope,subject_id,text,confidence,evidence_media_id,representative_media_id,source_type,applicability,body_region_version,model_version,prompt_version,created_at,updated_at,chunk_policy_version,chunked_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("caption-1", "MEDIA", "media-1", "A room.", 0.8, "media-1", "media-1", "GEMMA_DIRECT", "EVIDENCE_MEDIA_ONLY", "person-body-regions-v1", "test", "v1", 1L, 1L, null, null),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            20,
            true,
            GalleryRoomDatabase.MIGRATION_19_20,
        ).use { database ->
            database.query("SELECT value,generation_id FROM semantic_fact WHERE id='fact-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("room", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            database.query("SELECT text,generation_id FROM semantic_caption WHERE id='caption-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A room.", cursor.getString(0))
                assertEquals("", cursor.getString(1))
            }
            listOf(
                "semantic_fact",
                "semantic_caption",
                "semantic_caption_chunk",
                "person_attribute_fact",
            ).forEach { table ->
                database.query("PRAGMA table_info($table)").use { cursor ->
                    var found = false
                    while (cursor.moveToNext()) {
                        if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "generation_id") {
                            found = true
                            break
                        }
                    }
                    assertTrue("$table lacks generation_id", found)
                }
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-19-20.db"
    }
}
