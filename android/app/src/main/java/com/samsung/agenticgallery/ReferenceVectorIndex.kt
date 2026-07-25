package com.samsung.agenticgallery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Correctness-first exact cosine index used as the parity oracle for optimized stores. */
class ReferenceVectorIndex(private val dimension: Int) : VectorIndex {
    private val mutex = Mutex()
    private val vectors = linkedMapOf<String, FloatArray>()

    init {
        require(dimension > 0)
    }

    override suspend fun upsert(mediaId: String, vector: FloatArray) = mutex.withLock {
        requireValidId(mediaId)
        vectors[mediaId] = normalizeVector(vector, dimension)
    }

    override suspend fun delete(mediaId: String) = mutex.withLock {
        vectors.remove(mediaId)
        Unit
    }

    override suspend fun search(query: FloatArray, topK: Int, allowedIds: Set<String>?): List<VectorHit> = mutex.withLock {
        require(topK in 1..MAX_TOP_K) { "topK must be in 1..$MAX_TOP_K" }
        val normalizedQuery = normalizeVector(query, dimension)
        vectors.asSequence()
            .filter { (id) -> allowedIds == null || id in allowedIds }
            .map { (id, vector) -> VectorHit(id, dotProduct(vector, normalizedQuery)) }
            .sortedWith(HIT_ORDER)
            .take(topK)
            .toList()
    }

    suspend fun size(): Int = mutex.withLock { vectors.size }

    private fun requireValidId(mediaId: String) {
        require(mediaId.isNotBlank() && mediaId.toByteArray(Charsets.UTF_8).size <= MAX_ID_BYTES) {
            "mediaId must contain 1..$MAX_ID_BYTES UTF-8 bytes"
        }
    }

    companion object {
        internal val HIT_ORDER = compareByDescending<VectorHit> { it.score }.thenBy { it.mediaId }
        internal const val MAX_TOP_K = 1_000
        internal const val MAX_ID_BYTES = 4_096
    }
}
