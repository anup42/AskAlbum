package io.github.anup42.askalbum

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionEmbeddingLeaseFenceDatabaseTest {
    @Test
    fun staleOwnerCannotCompleteOrFailReclaimedChunk() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "caption-lease-fence-${UUID.randomUUID()}.db"
        var database: GalleryDatabase? = null
        try {
            val seeded = GalleryDatabase(context, name).also { database = it }
            seeded.seedDemoIfEmpty()
            val mediaId = seeded.allItems().first().id
            seeded.close()
            database = null

            val captionId = UUID.randomUUID().toString()
            val chunkId = UUID.randomUUID().toString()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw ->
                raw.execSQL(
                    "INSERT INTO semantic_caption " +
                        "(id,scope,subject_id,text,confidence,evidence_media_id,applicability," +
                        "model_version,prompt_version,updated_at,representative_media_id,source_type," +
                        "body_region_version,created_at,chunk_policy_version,chunked_at,generation_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        captionId,
                        SemanticFactScope.MEDIA.name,
                        mediaId,
                        "fixture caption",
                        0.9f,
                        mediaId,
                        "EVIDENCE_MEDIA_ONLY",
                        "fixture-model",
                        "fixture-prompt",
                        1L,
                        mediaId,
                        "GEMMA_DIRECT",
                        PersonalSemanticMemoryPolicy.BODY_REGION_VERSION,
                        1L,
                        SemanticCaptionChunker.POLICY_VERSION,
                        1L,
                        null,
                    ),
                )
                raw.execSQL(
                    "INSERT INTO semantic_caption_chunk " +
                        "(id,caption_id,media_id,scope,scope_id,evidence_media_id,cluster_id,chunk_type," +
                        "exact_text,confidence,applicability,caption_model_version,caption_prompt_version," +
                        "chunk_policy_version,embedding_model_version,embedding_state,attempt_count,error," +
                        "lease_owner,lease_expires_at,next_attempt_at,last_progress_at,created_at,updated_at,generation_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(
                        chunkId,
                        captionId,
                        mediaId,
                        SemanticFactScope.MEDIA.name,
                        mediaId,
                        mediaId,
                        null,
                        CaptionChunkType.SCENE.name,
                        "fixture caption",
                        0.9f,
                        "EVIDENCE_MEDIA_ONLY",
                        "fixture-model",
                        "fixture-prompt",
                        SemanticCaptionChunker.POLICY_VERSION,
                        null,
                        CaptionEmbeddingState.PENDING.name,
                        0,
                        null,
                        null,
                        null,
                        0L,
                        null,
                        1L,
                        1L,
                        null,
                    ),
                )
            }

            val first = GalleryDatabase(context, name).also { database = it }
            val stale = requireNotNull(first.claimCaptionEmbeddingChunks("owner-1", "fixture-pack", 1)).single()
            first.close()
            database = null

            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw ->
                raw.execSQL(
                    "UPDATE semantic_caption_chunk SET lease_expires_at=? WHERE id=?",
                    arrayOf<Any>(System.currentTimeMillis() - 1L, chunkId),
                )
            }

            val reclaimedDatabase = GalleryDatabase(context, name).also { database = it }
            reclaimedDatabase.recoverCaptionEmbeddingClaims()
            val current = requireNotNull(
                reclaimedDatabase.claimCaptionEmbeddingChunks("owner-2", "fixture-pack", 1),
            ).single()
            assertEquals(stale.id, current.id)
            assertFalse(reclaimedDatabase.completeCaptionEmbedding(stale.id, "fixture-pack", "owner-1"))
            assertFalse(
                reclaimedDatabase.failCaptionEmbedding(
                    stale.id,
                    "fixture-pack",
                    "owner-1",
                    "stale failure",
                    retryable = false,
                ),
            )
            val stillRunning = reclaimedDatabase.semanticCaptionChunksForMedia(mediaId).single()
            assertEquals(CaptionEmbeddingState.RUNNING, stillRunning.embeddingState)
            assertEquals("owner-2", stillRunning.leaseOwner)
            assertTrue(reclaimedDatabase.completeCaptionEmbedding(current.id, "fixture-pack", "owner-2"))
            assertEquals(
                CaptionEmbeddingState.COMPLETE,
                reclaimedDatabase.semanticCaptionChunksForMedia(mediaId).single().embeddingState,
            )
        } finally {
            database?.close()
            context.deleteDatabase(name)
        }
    }
}
