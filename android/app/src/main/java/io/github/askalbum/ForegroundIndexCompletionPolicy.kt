package io.github.anup42.askalbum

internal object ForegroundIndexCompletionPolicy {
    fun terminalReason(
        admissionAllowed: Boolean,
        cancelled: Boolean,
        gallery: IndexBatchResult,
        embeddings: IndexBatchResult,
    ): ForegroundIndexStopReason? = when {
        !admissionAllowed -> ForegroundIndexStopReason.THERMAL
        cancelled -> ForegroundIndexStopReason.CANCELLED
        !gallery.hasMore && embeddings.unavailable -> ForegroundIndexStopReason.UNAVAILABLE
        !gallery.hasMore && !embeddings.hasMore -> ForegroundIndexStopReason.COMPLETE
        else -> null
    }
}
