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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryMigration22To23Test {
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
    fun repairsProvenanceAndInvalidatesOnlyUnsafeCaptionChunks() {
        helper.createDatabase(TEST_DATABASE, 22).apply {
            insertMedia("media-event", "digest-event")
            insertMedia("media-group", "digest-group")
            insertMedia("media-source", "digest-safe")
            insertMedia("media-target", "digest-safe")
            insertMedia("media-unsafe", "digest-unsafe")
            insertEventAndGroup()

            insertFact("event-fact", "7", "media-event", "EXACT_DUPLICATE_SHARED")
            insertFact("group-fact", "group-1", "media-group", "EXACT_DUPLICATE_SHARED")
            insertFact("safe-fact", "media-source", "media-target", "EXACT_DUPLICATE_SHARED")
            insertFact("unsafe-fact", "media-source", "media-unsafe", "EXACT_DUPLICATE_SHARED")
            insertCaption("legacy-caption", "media-unsafe", "EXACT_DUPLICATE_SHARED")
            insertChunk("legacy-chunk", "legacy-caption", "media-unsafe", clusterId = "old-cluster")
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DATABASE,
            23,
            true,
            GalleryRoomDatabase.MIGRATION_22_23,
        ).use { database ->
            assertEquals(
                listOf("EVENT", SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY),
                factScope(database, "event-fact"),
            )
            assertEquals(
                listOf("VISUAL_GROUP", SemanticProvenanceApplicability.GROUP_CONTEXT_ONLY),
                factScope(database, "group-fact"),
            )
            assertEquals(
                listOf("MEDIA", SemanticProvenanceApplicability.EXACT_DUPLICATE_SHARED),
                factScope(database, "safe-fact"),
            )
            assertEquals(
                listOf("MEDIA", SemanticProvenanceApplicability.LEGACY_SCOPE_UNCERTAIN),
                factScope(database, "unsafe-fact"),
            )
            database.query("SELECT COUNT(*) FROM semantic_caption_chunk").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
            database.query("SELECT chunk_policy_version FROM semantic_caption WHERE id='legacy-caption'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertNull(cursor.getString(0))
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

    private fun SupportSQLiteDatabase.insertEventAndGroup() {
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
                put("media_id", "media-event")
            },
        )
        insert(
            "visual_group",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "group-1")
                put("kind", "BURST")
                put("canonical_media_id", "media-group")
                put("producer_version", "fixture")
                put("updated_at", 1L)
            },
        )
        insert(
            "visual_group_member",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("group_id", "group-1")
                put("media_id", "media-group")
                put("role", "MEMBER")
                put("diversity_score", 0.1)
            },
        )
    }

    private fun SupportSQLiteDatabase.insertFact(
        id: String,
        subjectId: String,
        evidenceMediaId: String,
        applicability: String,
    ) {
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
                put("applicability", applicability)
                put("model_version", "fixture")
                put("prompt_version", "fixture")
                put("updated_at", 1L)
                putNull("generation_id")
            },
        )
    }

    private fun SupportSQLiteDatabase.insertCaption(
        id: String,
        evidenceMediaId: String,
        applicability: String,
    ) {
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
                putNull("representative_media_id")
                put("source_type", "LEGACY_MEDIA_DIRECT")
                put("applicability", applicability)
                put("body_region_version", "fixture")
                put("model_version", "fixture")
                put("prompt_version", "fixture")
                put("created_at", 1L)
                put("updated_at", 1L)
                put("chunk_policy_version", "caption-chunks-v3")
                putNull("chunked_at")
                putNull("generation_id")
            },
        )
    }

    private fun SupportSQLiteDatabase.insertChunk(
        id: String,
        captionId: String,
        mediaId: String,
        clusterId: String,
    ) {
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
                put("cluster_id", clusterId)
                put("chunk_type", "PERSON_APPEARANCE")
                put("exact_text", "old person fact")
                put("confidence", 0.8)
                put("applicability", "MEDIA_DIRECT")
                put("caption_model_version", "fixture")
                put("caption_prompt_version", "fixture")
                put("chunk_policy_version", "caption-chunks-v3")
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

    private fun factScope(database: SupportSQLiteDatabase, id: String): List<String> =
        database.query("SELECT scope,applicability FROM semantic_fact WHERE id=?", arrayOf(id)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            listOf(cursor.getString(0), cursor.getString(1))
        }

    private companion object {
        const val TEST_DATABASE = "gallery-migration-22-23.db"
    }
}
