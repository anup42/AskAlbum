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
class GalleryMigration27To28Test {
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
    fun repairsCurrentLegacySharedRowsWithoutDeletingCaptionsOrFacts() {
        helper.createDatabase(TEST_DATABASE, 27).apply {
            insertMedia("source", "digest-same")
            insertMedia("target", "digest-same")
            insertMedia("unsafe", "digest-other")
            insertMedia("event-media", "digest-event")
            insertEvent()
            insertGeneration("generation-valid", "source")
            insertFact("valid-fact", "target", "generation-valid")
            insertFact("unsafe-fact", "unsafe", null)
            insertFact("event-fact", "7", null, "event-media")
            insertCaption("valid-caption", "target", "source", "generation-valid")
            insertCaption("unsafe-caption", "unsafe", null, null)
            insertChunk("valid-chunk", "valid-caption", "target")
            insertChunk("unsafe-chunk", "unsafe-caption", "unsafe")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            28,
            true,
            GalleryRoomDatabase.MIGRATION_27_28,
        ).use { database ->
            assertEquals(
                SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED,
                factApplicability(database, "valid-fact"),
            )
            assertEquals(
                SemanticProvenanceApplicability.LEGACY_SCOPE_UNCERTAIN,
                factApplicability(database, "unsafe-fact"),
            )
            assertEquals(
                listOf("EVENT", SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY),
                factScope(database, "event-fact"),
            )
            assertEquals(
                SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED,
                captionApplicability(database, "valid-caption"),
            )
            assertEquals(
                SemanticProvenanceApplicability.LEGACY_SCOPE_UNCERTAIN,
                captionApplicability(database, "unsafe-caption"),
            )
            database.query("SELECT COUNT(*) FROM semantic_caption").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM semantic_caption_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT chunk_policy_version FROM semantic_caption WHERE id='unsafe-caption'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(null, cursor.getString(0))
            }
        }
    }

    private fun SupportSQLiteDatabase.insertMedia(id: String, digest: String) {
        insert(
            "media_item",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("filename", "$id.jpg")
                put("title", id)
                put("index_version", "fixture-v1")
                put("exact_content_digest", digest)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertEvent() {
        insert(
            "gallery_event",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", 7L)
                put("start_time", 1L)
                put("end_time", 2L)
                put("title", "fixture event")
                put("event_type", "OTHER")
                put("member_count", 1)
                put("confidence", 0.8)
                put("search_text", "fixture")
                put("producer_version", "fixture")
                put("user_corrected", 0)
            },
        )
        insert(
            "event_media",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("event_id", 7L)
                put("media_id", "event-media")
            },
        )
    }

    private fun SupportSQLiteDatabase.insertGeneration(id: String, sourceMediaId: String) {
        insert(
            "semantic_generation",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("generation_id", id)
                putNull("caption_id")
                put("job_id", "job-$id")
                put("scope", "MEDIA")
                put("scope_id", sourceMediaId)
                put("evidence_media_id", sourceMediaId)
                put("model_version", "model-v1")
                put("prompt_version", "prompt-v1")
                put("body_region_version", "body-v1")
                put("created_at", 1L)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertFact(id: String, subjectId: String, generationId: String?, evidenceMediaId: String = subjectId) {
        insert(
            "semantic_fact",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("scope", "MEDIA")
                put("subject_id", subjectId)
                put("predicate", "scene")
                put("value", "fixture fact")
                put("confidence", 0.9)
                put("evidence_media_id", evidenceMediaId)
                putNull("region")
                put("applicability", SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED)
                put("model_version", "model-v1")
                put("prompt_version", "prompt-v1")
                put("updated_at", 1L)
                if (generationId == null) putNull("generation_id") else put("generation_id", generationId)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertCaption(id: String, evidenceMediaId: String, representativeMediaId: String?, generationId: String?) {
        insert(
            "semantic_caption",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("scope", "MEDIA")
                put("subject_id", evidenceMediaId)
                put("text", "Legacy caption text")
                put("confidence", 0.8)
                put("evidence_media_id", evidenceMediaId)
                if (representativeMediaId == null) putNull("representative_media_id") else put("representative_media_id", representativeMediaId)
                put("source_type", "EXACT_DUPLICATE_REUSE")
                put("applicability", SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED)
                put("body_region_version", "body-v1")
                put("model_version", "model-v1")
                put("prompt_version", "prompt-v1")
                put("created_at", 1L)
                put("updated_at", 1L)
                put("chunk_policy_version", SemanticCaptionChunker.POLICY_VERSION)
                putNull("chunked_at")
                if (generationId == null) putNull("generation_id") else put("generation_id", generationId)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertChunk(id: String, captionId: String, mediaId: String) {
        insert(
            "semantic_caption_chunk",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", id)
                put("caption_id", captionId)
                put("media_id", mediaId)
                put("scope", "MEDIA")
                put("scope_id", mediaId)
                put("evidence_media_id", mediaId)
                putNull("cluster_id")
                put("chunk_type", "SCENE")
                put("exact_text", "old chunk")
                put("confidence", 0.8)
                put("applicability", SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED)
                put("caption_model_version", "model-v1")
                put("caption_prompt_version", "prompt-v1")
                put("chunk_policy_version", SemanticCaptionChunker.POLICY_VERSION)
                putNull("embedding_model_version")
                put("embedding_state", "PENDING")
                put("attempt_count", 0)
                putNull("error")
                putNull("lease_owner")
                putNull("lease_expires_at")
                put("next_attempt_at", 0L)
                putNull("last_progress_at")
                put("created_at", 1L)
                put("updated_at", 1L)
                putNull("generation_id")
            },
        )
    }

    private fun factApplicability(database: SupportSQLiteDatabase, id: String): String =
        database.query("SELECT applicability FROM semantic_fact WHERE id=?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun factScope(database: SupportSQLiteDatabase, id: String): List<String> =
        database.query("SELECT scope,applicability FROM semantic_fact WHERE id=?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1))
        }

    private fun captionApplicability(database: SupportSQLiteDatabase, id: String): String =
        database.query("SELECT applicability FROM semantic_caption WHERE id=?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-27-28.db"
    }
}
