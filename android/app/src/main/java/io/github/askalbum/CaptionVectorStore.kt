package io.github.anup42.askalbum

import android.content.Context
import java.io.File
import java.util.concurrent.CancellationException
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
        searchableChunkIds: Set<String>,
        eligibleMediaCount: Int,
        topK: Int,
        captionedMediaCount: Int = eligibleMediaCount,
    ): CaptionVectorSearchReport {
        val normalizedQueries = queries
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
        if (normalizedQueries.isEmpty()) {
            return CaptionVectorSearchReport(
                status = ChannelStatus.NOT_REQUIRED,
                eligibleChunkCount = eligibleChunkIds.size,
                indexedChunkCount = 0,
                searchedChunkCount = 0,
                hits = emptyList(),
                modelVersion = null,
                errorCode = null,
            )
        }
        if (eligibleMediaCount <= 0) {
            return CaptionVectorSearchReport(
                status = ChannelStatus.NOT_REQUIRED,
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
                searchedChunkCount = 0,
                hits = emptyList(),
                modelVersion = null,
                errorCode = null,
            )
        }
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
        if (eligibleChunkIds.isEmpty()) {
            return CaptionVectorSearchReport(
                status = CaptionVectorCoveragePolicy.status(
                    queryRequired = true,
                    eligibleMediaCount = eligibleMediaCount,
                    eligibleChunkCount = 0,
                    indexedChunkCount = 0,
                    captionedMediaCount = captionedMediaCount,
                ),
                eligibleChunkCount = 0,
                indexedChunkCount = 0,
                searchedChunkCount = 0,
                hits = emptyList(),
                modelVersion = modelVersion,
                errorCode = if (eligibleMediaCount > 0) "NO_ELIGIBLE_CAPTION_CHUNKS" else null,
            )
        }
        if (searchableChunkIds.isEmpty()) {
            return CaptionVectorSearchReport(
                status = CaptionVectorCoveragePolicy.status(
                    queryRequired = true,
                    eligibleMediaCount = eligibleMediaCount,
                    eligibleChunkCount = eligibleChunkIds.size,
                    indexedChunkCount = 0,
                    captionedMediaCount = captionedMediaCount,
                ),
                eligibleChunkCount = eligibleChunkIds.size,
                indexedChunkCount = 0,
                searchedChunkCount = 0,
                hits = emptyList(),
                modelVersion = modelVersion,
                errorCode = "NO_SEARCHABLE_CAPTION_CHUNKS",
            )
        }
        return try {
            val current = currentIndex()
            val indexed = current.index.ids() intersect searchableChunkIds intersect eligibleChunkIds
            val perVariant = normalizedQueries.map { query ->
                val vector = embeddings.embedTextInteractive(query)
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
                status = CaptionVectorCoveragePolicy.status(
                    queryRequired = true,
                    eligibleMediaCount = eligibleMediaCount,
                    eligibleChunkCount = eligibleChunkIds.size,
                    indexedChunkCount = indexed.size,
                    captionedMediaCount = captionedMediaCount,
                ),
                eligibleChunkCount = eligibleChunkIds.size,
                indexedChunkCount = indexed.size,
                searchedChunkCount = indexed.size,
                hits = fused.mapNotNull { best[it.first] }.take(topK.coerceIn(1, 100)),
                modelVersion = modelVersion,
                errorCode = when {
                    captionedMediaCount < eligibleMediaCount -> "PARTIAL_CAPTION_MEDIA_COVERAGE"
                    indexed.size < eligibleChunkIds.size -> "PARTIAL_CAPTION_VECTOR_COVERAGE"
                    else -> null
                },
            )
        } catch (error: Throwable) {
            if (CaptionVectorSearchFailurePolicy.shouldPropagate(error)) throw error
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

    suspend fun indexedChunkIds(): Set<String> = currentIndex().index.ids()

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

internal object CaptionVectorSearchFailurePolicy {
    fun shouldPropagate(error: Throwable): Boolean = error is CancellationException
}

internal object CaptionVectorRepairPolicy {
    fun missingVectorIds(expectedCompleteIds: Set<String>, indexedIds: Set<String>): Set<String> =
        expectedCompleteIds - indexedIds
}
