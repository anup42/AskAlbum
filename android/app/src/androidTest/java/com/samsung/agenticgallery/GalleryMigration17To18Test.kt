package com.samsung.agenticgallery

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
class GalleryMigration17To18Test {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        GalleryRoomDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationPreservesCaptionAndAddsCaptionChunkIndex() {
        helper.createDatabase(TEST_DATABASE, 17).apply {
            execSQL(
                "INSERT INTO media_item(id,filename,title,index_version) VALUES(?,?,?,?)",
                arrayOf("media-1", "birthday.jpg", "Birthday", "analysis-v1"),
            )
            execSQL(
                """
                INSERT INTO semantic_caption(
                    id,scope,subject_id,text,confidence,evidence_media_id,representative_media_id,
                    source_type,applicability,body_region_version,model_version,prompt_version,
                    created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                arrayOf(
                    "caption-1", "MEDIA", "media-1", "A birthday beside a cake.", 0.9,
                    "media-1", "media-1", "GEMMA_DIRECT", "EVIDENCE_MEDIA_ONLY",
                    "person-body-regions-v1", "gemma-e2b", "caption-v3", 100L, 100L,
                ),
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            18,
            true,
            GalleryRoomDatabase.MIGRATION_17_18,
        ).use { database ->
            database.query(
                "SELECT text,chunk_policy_version,chunked_at FROM semantic_caption WHERE id='caption-1'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("A birthday beside a cake.", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
            database.query("PRAGMA table_info(semantic_caption_chunk)").use { cursor ->
                val columns = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
                assertTrue(
                    columns.containsAll(
                        setOf(
                            "caption_id",
                            "media_id",
                            "cluster_id",
                            "chunk_type",
                            "exact_text",
                            "embedding_state",
                            "lease_owner",
                            "next_attempt_at",
                        ),
                    ),
                )
            }
            database.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='semantic_caption_chunk_fts'",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
            }
        }
    }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-17-18.db"
    }
}
