package io.github.anup42.askalbum

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LexicalSearchChannelDatabaseTest {
    @Test
    fun corruptFtsReportsFailureInsteadOfSuccessfulEmptySearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "lexical-channel-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw -> raw.execSQL("DROP TABLE media_fts") }

            val result = database.fullTextMatches(listOf("beach"))
            assertEquals(ChannelStatus.FAILED, result.status)
            assertTrue(result.ids.isEmpty())
            assertEquals("MEDIA_FTS_SEARCH_FAILED", result.errorCode)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun corruptCaptionFtsReportsPartialFallbackInsteadOfSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "caption-lexical-channel-${UUID.randomUUID()}.db"
        val database = GalleryDatabase(context, name)
        try {
            database.seedDemoIfEmpty()
            SQLiteDatabase.openDatabase(
                context.getDatabasePath(name).path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { raw -> raw.execSQL("DROP TABLE semantic_caption_chunk_fts") }

            val result = database.searchSemanticCaptions(
                queries = listOf("birthday"),
                allowedIds = database.allItems().map(GalleryItem::id).toSet(),
            )
            assertEquals(ChannelStatus.PARTIAL, result.status)
            assertEquals("CAPTION_FTS_SEARCH_FAILED_LEGACY_FALLBACK", result.errorCode)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
