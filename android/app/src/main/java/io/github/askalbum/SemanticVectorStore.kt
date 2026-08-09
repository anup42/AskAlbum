package io.github.anup42.askalbum

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class SemanticVectorStore(
    context: Context,
    private val packs: RetrievalModelPackManager,
    private val embeddings: ImageTextEmbeddingEngine,
) {
    private val root = File(context.filesDir, "vectors/image")
    private val loadMutex = Mutex()
    @Volatile private var loaded: Loaded? = null

    fun producerVersion(): String? = packs.current()?.let(::version)

    suspend fun upsert(mediaId: String, vector: FloatArray) {
        currentIndex().index.upsert(mediaId, vector)
    }

    suspend fun scanTextBatchReport(
        query: String,
        allowedIds: Set<String>,
        eligibleCount: Int = allowedIds.size,
    ): RetrievalChannelReport<VectorHit> {
        if (query.isBlank()) return SemanticChannelReporter.notRequired()
        if (allowedIds.isEmpty()) {
            return SemanticBatchCoveragePolicy.noVectorIds(eligibleCount, producerVersion())
        }
        return SemanticChannelReporter.execute(
            query = query,
            modelVersion = producerVersion(),
            eligibleCount = allowedIds.size,
            eligibleVectorIds = allowedIds,
            topK = allowedIds.size,
            indexedIds = { currentIndex().index.ids() },
            search = { text, _, eligibleIds ->
                val current = currentIndex()
                val vector = embeddings.embedTextInteractive(text)
                current.index.scan(vector, eligibleIds)
                    .filter { it.score >= current.pack.manifest.minimumSimilarity }
            },
        )
    }

    suspend fun searchTextReport(
        query: String,
        topK: Int,
        eligibleCount: Int,
        allowedIds: Set<String>,
    ): RetrievalChannelReport<VectorHit> = SemanticChannelReporter.execute(
        query = query,
        modelVersion = producerVersion(),
        eligibleCount = eligibleCount,
        eligibleVectorIds = allowedIds,
        topK = topK,
        indexedIds = { currentIndex().index.ids() },
        search = { text, limit, eligibleIds ->
            val current = currentIndex()
            val vector = embeddings.embedTextInteractive(text)
            current.index.search(vector, limit, eligibleIds)
                .filter { it.score >= current.pack.manifest.minimumSimilarity }
        },
    )

    suspend fun reconcile(accessibleIds: Set<String>) {
        val index = currentIndex().index
        (index.ids() - accessibleIds).forEach { index.delete(it) }
    }

    suspend fun indexedIds(): Set<String> = if (packs.current() == null) emptySet() else currentIndex().index.ids()

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

internal object SemanticBatchCoveragePolicy {
    fun noVectorIds(
        eligibleCount: Int,
        modelVersion: String?,
    ): RetrievalChannelReport<VectorHit> {
        val safeEligibleCount = eligibleCount.coerceAtLeast(0)
        return RetrievalChannelReport(
            channel = RetrievalChannel.SEMANTIC,
            status = if (safeEligibleCount == 0) ChannelStatus.NOT_REQUIRED else ChannelStatus.PARTIAL,
            eligibleCount = safeEligibleCount,
            indexedCount = 0,
            searchedCount = 0,
            hits = emptyList(),
            modelVersion = modelVersion,
            errorCode = if (safeEligibleCount == 0) null else "SCAN_BATCH_VECTOR_IDS_UNAVAILABLE",
        )
    }
}

data class RankedChannel(val weight: Double, val ids: List<String>)

object HybridRankFusion {
    fun fuse(channels: List<RankedChannel>, k: Int = 60): List<Pair<String, Double>> {
        require(k > 0)
        val scores = linkedMapOf<String, Double>()
        channels.forEach { channel ->
            require(channel.weight >= 0.0)
            channel.ids.distinct().forEachIndexed { index, id ->
                scores[id] = (scores[id] ?: 0.0) + channel.weight / (k + index + 1)
            }
        }
        return scores.entries.sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }
}
