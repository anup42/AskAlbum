package com.askphotos.android

import kotlin.math.abs

object DuplicateCollapse {
    private const val MAX_HASH_DISTANCE = 6
    private const val BURST_WINDOW_MS = 15_000L

    fun collapse(hits: List<SearchHit>): List<SearchHit> {
        if (hits.size < 2) return hits
        val parents = IntArray(hits.size) { it }
        fun root(index: Int): Int {
            var current = index
            while (parents[current] != current) {
                parents[current] = parents[parents[current]]
                current = parents[current]
            }
            return current
        }
        fun union(left: Int, right: Int) {
            val leftRoot = root(left)
            val rightRoot = root(right)
            if (leftRoot != rightRoot) parents[rightRoot] = leftRoot
        }
        for (left in hits.indices) for (right in left + 1 until hits.size) {
            val a = hits[left].item
            val b = hits[right].item
            val aHash = a.perceptualHash ?: continue
            val bHash = b.perceptualHash ?: continue
            if (a.kind != b.kind) continue
            val distance = VisualFeatureExtractor.hammingDistance(aHash, bHash)
            val samePixels = aHash == bHash
            val sameBurst = a.capturedAt != null && b.capturedAt != null && abs(a.capturedAt - b.capturedAt) <= BURST_WINDOW_MS
            if (samePixels || (distance <= MAX_HASH_DISTANCE && sameBurst)) union(left, right)
        }
        val groups = hits.indices.groupBy(::root).values.sortedBy { members -> members.min() }
        return groups.map { members ->
            val representativeIndex = members.maxWithOrNull(
                compareBy<Int> { hits[it].item.qualityScore ?: 0f }
                    .thenBy { hits[it].item.width.toLong() * hits[it].item.height }
                    .thenByDescending { -it },
            ) ?: members.first()
            val representative = hits[representativeIndex]
            representative.copy(duplicateIds = members.filter { it != representativeIndex }.map { hits[it].item.id })
        }
    }
}

object EventDiversity {
    fun rerank(hits: List<SearchHit>, eventByMediaId: Map<String, Long>, window: Int = 20): List<SearchHit> {
        if (hits.size < 2 || window <= 1) return hits
        val headSize = minOf(window, hits.size)
        val groups = linkedMapOf<String, ArrayDeque<SearchHit>>()
        hits.take(headSize).forEach { hit ->
            val key = eventByMediaId[hit.item.id]?.let { "event:$it" } ?: "media:${hit.item.id}"
            groups.getOrPut(key) { ArrayDeque() }.addLast(hit)
        }
        if (groups.size <= 1) return hits
        val diversified = ArrayList<SearchHit>(headSize)
        while (diversified.size < headSize) {
            var added = false
            groups.values.forEach { queue ->
                if (queue.isNotEmpty() && diversified.size < headSize) {
                    diversified += queue.removeFirst()
                    added = true
                }
            }
            if (!added) break
        }
        return diversified + hits.drop(headSize)
    }
}
