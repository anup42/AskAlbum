package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundIndexCompletionPolicyTest {
    @Test
    fun missingEmbeddingProducerIsUnavailableNotComplete() {
        val reason = ForegroundIndexCompletionPolicy.terminalReason(
            admissionAllowed = true,
            cancelled = false,
            gallery = IndexBatchResult(processed = 12, hasMore = false),
            embeddings = IndexBatchResult(
                processed = 0,
                hasMore = false,
                unavailable = true,
                errorCode = "NO_VERIFIED_RETRIEVAL_PACK",
            ),
        )

        assertEquals(ForegroundIndexStopReason.UNAVAILABLE, reason)
    }

    @Test
    fun availablePipelinesCanComplete() {
        val reason = ForegroundIndexCompletionPolicy.terminalReason(
            admissionAllowed = true,
            cancelled = false,
            gallery = IndexBatchResult(processed = 12, hasMore = false),
            embeddings = IndexBatchResult(processed = 12, hasMore = false),
        )

        assertEquals(ForegroundIndexStopReason.COMPLETE, reason)
    }
}
