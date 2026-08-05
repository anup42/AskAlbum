package io.github.anup42.askalbum

internal data class IndexingProgressEstimate(
    val ratePerMinute: Double?,
    val etaMillis: Long?,
) {
    companion object {
        fun calculate(
            processed: Int,
            remaining: Int,
            startedAtMillis: Long,
            nowMillis: Long = System.currentTimeMillis(),
        ): IndexingProgressEstimate {
            if (processed <= 0) return IndexingProgressEstimate(null, null)
            val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(1_000L)
            val rate = processed * 60_000.0 / elapsedMillis
            val eta = if (remaining > 0 && rate > 0.0) {
                (remaining * 60_000.0 / rate).toLong().coerceAtLeast(1_000L)
            } else {
                null
            }
            return IndexingProgressEstimate(rate, eta)
        }
    }
}
