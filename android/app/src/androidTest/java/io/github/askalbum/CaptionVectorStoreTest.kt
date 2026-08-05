package io.github.anup42.askalbum

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptionVectorStoreTest {
    @Test
    fun emptyQueryDoesNotRequireRetrievalPack() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val isolatedFiles = File(context.cacheDir, "caption-vector-${UUID.randomUUID()}").apply { mkdirs() }
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = isolatedFiles
        }
        try {
            val store = CaptionVectorStore(
                context = isolatedContext,
                packs = RetrievalModelPackManager(isolatedContext),
                embeddings = object : ImageTextEmbeddingEngine {
                    override suspend fun embedImage(image: ModelImage): FloatArray = error("unused")
                    override suspend fun embedText(text: String): FloatArray = error("unused")
                },
            )

            val emptyQuery = store.searchVariants(listOf("  ", ""), setOf("chunk-1"), 10)
            assertEquals(ChannelStatus.NOT_REQUIRED, emptyQuery.status)

            val noEligibleChunks = store.searchVariants(listOf("birthday"), emptySet(), 10)
            assertEquals(ChannelStatus.UNAVAILABLE, noEligibleChunks.status)
            assertEquals("NO_VERIFIED_RETRIEVAL_PACK", noEligibleChunks.errorCode)
        } finally {
            isolatedFiles.deleteRecursively()
        }
    }
}
