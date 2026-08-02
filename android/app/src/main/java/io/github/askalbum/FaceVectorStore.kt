package io.github.anup42.askalbum

import android.content.Context
import java.io.File

class FaceVectorStore(context: Context) {
    private val index = MmapFp16VectorIndex(
        File(context.filesDir, "vectors/face/${FaceModelCatalog.sface.packVersion}"),
        FaceModelCatalog.sface.embeddingDimension,
    )

    suspend fun ids(): Set<String> = index.ids()
    suspend fun nearest(vector: FloatArray, allowedIds: Set<String>? = null): VectorHit? =
        index.search(vector, 1, allowedIds).firstOrNull()
    suspend fun nearestNeighbors(vector: FloatArray, limit: Int, allowedIds: Set<String>? = null): List<VectorHit> =
        index.search(vector, limit.coerceIn(1, ReferenceVectorIndex.MAX_TOP_K), allowedIds)
    suspend fun similarities(referenceFaceId: String, candidateIds: Collection<String>): Map<String, Float> {
        val reference = index.vector(referenceFaceId) ?: error("Representative face embedding is unavailable")
        return candidateIds.distinct().chunked(ReferenceVectorIndex.MAX_TOP_K).flatMap { chunk ->
            index.search(reference, chunk.size, chunk.toSet())
        }.associate { it.mediaId to it.score }
    }
    suspend fun upsert(faceId: String, vector: FloatArray) = index.upsert(faceId, vector)
    suspend fun delete(faceId: String) = index.delete(faceId)
    suspend fun reconcile(validFaceIds: Set<String>) = (index.ids() - validFaceIds).forEach { index.delete(it) }
    suspend fun clear() = index.replaceAll(emptyMap())
}

data class FaceClusterReference(
    val clusterId: String,
    val reviewed: Boolean,
    val hidden: Boolean,
    val userCorrected: Boolean = false,
)

internal data class FaceClusterCandidate(
    val hit: VectorHit,
    val reference: FaceClusterReference?,
)

data class FaceClusterMembership(
    val faceId: String,
    val userCorrected: Boolean,
)

internal data class FaceClusterRefinementDecision(
    val keptFaceIds: Set<String>,
    val rejectedFaceIds: Set<String>,
)

internal object FaceClusterPolicy {
    private const val MIN_SCORE = .45f
    private const val SUPPORT_SCORE = .40f
    private const val STRONG_SINGLE_SCORE = .58f
    private const val MIN_RUNNER_UP_MARGIN = .05f

    fun matchingCluster(nearest: VectorHit?, clusterId: String?): String? =
        clusterId?.takeIf { nearest != null && nearest.score >= FaceModelCatalog.sface.cosineThreshold }

    fun matchingCluster(candidates: List<FaceClusterCandidate>): String? {
        val eligible = candidates.filter { candidate ->
            candidate.reference != null && !candidate.reference.hidden
        }
        val ranked = eligible.groupBy { requireNotNull(it.reference).clusterId }
            .map { (clusterId, matches) -> clusterId to matches.map { it.hit.score }.sortedDescending() }
            .sortedWith(compareByDescending<Pair<String, List<Float>>> { it.second.first() }.thenBy { it.first })
        val best = ranked.firstOrNull() ?: return null
        val bestScore = best.second.first()
        val supportCount = best.second.count { it >= SUPPORT_SCORE }
        if (bestScore < MIN_SCORE) return null
        if (supportCount < 2 && bestScore < STRONG_SINGLE_SCORE) return null
        val runnerUp = ranked.getOrNull(1)?.second?.first()
        if (runnerUp != null && bestScore - runnerUp < MIN_RUNNER_UP_MARGIN) return null
        return best.first
    }
}

internal object FaceClusterRefinementPolicy {
    const val REPRESENTATIVE_MATCH_THRESHOLD = .42f

    fun decide(
        memberships: List<FaceClusterMembership>,
        representativeFaceId: String,
        similarities: Map<String, Float>,
    ): FaceClusterRefinementDecision {
        require(memberships.any { it.faceId == representativeFaceId }) { "Representative is outside this cluster" }
        val kept = memberships.filter { membership ->
            membership.faceId == representativeFaceId ||
                membership.userCorrected ||
                similarities[membership.faceId] == null ||
                requireNotNull(similarities[membership.faceId]) >= REPRESENTATIVE_MATCH_THRESHOLD
        }.mapTo(linkedSetOf(), FaceClusterMembership::faceId)
        return FaceClusterRefinementDecision(
            keptFaceIds = kept,
            rejectedFaceIds = memberships.mapTo(linkedSetOf(), FaceClusterMembership::faceId) - kept,
        )
    }
}
