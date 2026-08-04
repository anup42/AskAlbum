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
class GalleryMigration20To21Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesExistingSemanticRowsAndAddsProvenanceTable() {
        helper.createDatabase(TEST_DATABASE, 20).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "one.jpg", "One", "test"),
            )
            execSQL(
                "INSERT INTO semantic_fact(id,scope,subject_id,predicate,value,confidence,evidence_media_id,region,applicability,model_version,prompt_version,updated_at,generation_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                arrayOf("fact-1", "MEDIA", "media-1", "scene", "room", 0.8, "media-1", null, "EVIDENCE_MEDIA_ONLY", "test", "v1", 1L, "generation-1"),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            21,
            true,
            GalleryRoomDatabase.MIGRATION_20_21,
        ).use { database ->
            database.query("SELECT value,generation_id FROM semantic_fact WHERE id='fact-1'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("room", cursor.getString(0))
                assertEquals("generation-1", cursor.getString(1))
            }
            database.query("PRAGMA table_info(semantic_generation_provenance)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(
                    columns.containsAll(
                        setOf(
                            "generation_id",
                            "caption_id",
                            "job_id",
                            "scope",
                            "scope_id",
                            "evidence_media_id",
                            "model_version",
                            "prompt_version",
                            "body_region_version",
                            "created_at",
                        ),
                    ),
                )
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-20-21.db"
    }
}
