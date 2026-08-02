package io.github.anup42.askalbum

import android.content.Context
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CaptionVectorStore(
    context: Context,
    private val packs: RetrievalModelPackManager,
    private val embeddings: ImageTextEmbeddingEngine,
) {
    private val root = File(context.filesDir, "vectors/caption")
    private val loadMutex = Mutex()
    @Volatile private var loaded: Loaded? = null

    fun producerVersion(): String? = packs.current()?.let(::version)

    suspend fun upsert(chunkId: String, vector: FloatArray) {
        currentIndex().index.upsert(chunkId, vector)
    }

    suspend fun embedTexts(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return (embeddings as? LiteRtImageTextEmbeddingEngine)?.embedTexts(texts)
            ?: texts.map { embeddings.embedText(it) }
    }

    suspend fun searchVariants(
        queries: List<String>,
        eligibleChunkIds: Set<String>,
        topK: Int,
    ): CaptionVectorSearchReport {
        val modelVersion = producerVersion()
            ?: return CaptionVectorSearchReport(
                ChannelStatus.UNAVAILABLE,
                eligibleChunkIds.size,
                0,
                0,
                emptyList(),
                null,
                "NO_VERIFIED_RETRIEVAL_PACK",
            )
        if (queries.isEmpty() || eligibleChunkIds.isEmpty()) {
            return CaptionVectorSearchReport(
                ChannelStatus.SUCCESS,
                eligibleChunkIds.size,
                0,
                0,
                emptyList(),
                modelVersion,
            )
        }
        return runCatching {
            val current = currentIndex()
            val indexed = current.index.ids() intersect eligibleChunkIds
            val perVariant = queries.map { query ->
                val vector = embeddings.embedText(query)
                query to current.index.search(vector, topK.coerceIn(1, 100), indexed)
                    .filter { it.score >= current.pack.manifest.minimumSimilarity }
            }
            val fused = HybridRankFusion.fuse(
                perVariant.map { RankedChannel(1.0, it.second.map(VectorHit::mediaId)) },
            )
            val best = perVariant.flatMap { (query, hits) ->
                hits.map { CaptionVectorHit(it.mediaId, it.score, query) }
            }.groupBy(CaptionVectorHit::chunkId).mapValues { (_, hits) -> hits.maxBy(CaptionVectorHit::score) }
            CaptionVectorSearchReport(
                status = if (indexed.size < eligibleChunkIds.size) ChannelStatus.PARTIAL else ChannelStatus.SUCCESS,
                eligibleChunkCount = eligibleChunkIds.size,
                indexedChunkCount = indexed.size,
                searchedChunkCount = indexed.size,
                hits = fused.mapNotNull { best[it.first] }.take(topK.coerceIn(1, 100)),
                modelVersion = modelVersion,
                errorCode = if (indexed.size < eligibleChunkIds.size) "PARTIAL_CAPTION_VECTOR_COVERAGE" else null,
            )
        }.getOrElse {
            CaptionVectorSearchReport(
                ChannelStatus.FAILED,
                eligibleChunkIds.size,
                0,
                0,
                emptyList(),
                modelVersion,
                "CAPTION_TEXT_EMBEDDING_OR_VECTOR_SEARCH_FAILED",
            )
        }
    }

    suspend fun reconcile(validChunkIds: Set<String>) {
        val index = currentIndex().index
        (index.ids() - validChunkIds).forEach { index.delete(it) }
    }

    private suspend fun currentIndex(): Loaded = loadMutex.withLock {
        val pack = packs.current() ?: error("No verified retrieval model pack is installed")
        val key = version(pack)
        loaded?.takeIf { it.key == key } ?: run {
            val directory = File(root, key.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            Loaded(key, pack, MmapFp16VectorIndex(directory, pack.manifest.embeddingDimension)).also { loaded = it }
        }
    }

    private fun version(pack: InstalledRetrievalPack): String = with(pack.manifest) {
        "$packId@$packVersion:${sourceRevision}:d$embeddingDimension"
    }

    private data class Loaded(
        val key: String,
        val pack: InstalledRetrievalPack,
        val index: MmapFp16VectorIndex,
    )
}
