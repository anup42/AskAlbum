package io.github.anup42.askalbum

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionVectorStoreTest {
    @Test
    fun emptyQueryDoesNotRequireRetrievalPack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CaptionVectorStore(
            context = context,
            packs = RetrievalModelPackManager(context),
            embeddings = object : ImageTextEmbeddingEngine {
                override suspend fun embedImage(image: ModelImage): FloatArray = error("unused")
                override suspend fun embedText(text: String): FloatArray = error("unused")
            },
        )

        val emptyQuery = store.searchVariants(listOf("  ", ""), setOf("chunk-1"), 10)
        assertEquals(ChannelStatus.NOT_REQUIRED, emptyQuery.status)

        val noEligibleChunks = store.searchVariants(listOf("birthday"), emptySet(), 10)
        assertEquals(ChannelStatus.NOT_REQUIRED, noEligibleChunks.status)
    }
}
