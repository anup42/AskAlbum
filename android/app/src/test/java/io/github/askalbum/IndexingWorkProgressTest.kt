package io.github.anup42.askalbum

import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexingWorkProgressTest {
    @Test
    fun readsDurableProgressFields() {
        val progress = IndexingWorkProgress.from(
            workDataOf(
                "last_progress_at" to 1234L,
                "next_attempt_at" to 5678L,
                "delayed_retries" to 3,
                "quarantined" to 2,
            ),
        )

        assertEquals(1234L, progress.lastProgressAt)
        assertEquals(5678L, progress.nextAttemptAt)
        assertEquals(3, progress.delayedRetryCount)
        assertEquals(2, progress.quarantinedCount)
    }

    @Test
    fun missingProgressDoesNotInventTimestampsOrCounts() {
        val progress = IndexingWorkProgress.from(null)

        assertNull(progress.lastProgressAt)
        assertNull(progress.nextAttemptAt)
        assertEquals(0, progress.delayedRetryCount)
        assertEquals(0, progress.quarantinedCount)
    }

    @Test
    fun legacyRetryableFailureFieldFeedsDelayedRetryCount() {
        val progress = IndexingWorkProgress.from(workDataOf("retryable_failures" to 4))

        assertEquals(4, progress.delayedRetryCount)
    }

    @Test
    fun peopleCoverageUsesOnlyEligibleImages() {
        assertEquals(6, IndexingCoverageMath.peopleCompleted(faceScanned = 6, faceEligible = 10))
        assertEquals(3, IndexingCoverageMath.peoplePending(pending = 3, faceEligible = 10))
        assertEquals(1, IndexingCoverageMath.peopleFailed(faceScanned = 6, pending = 3, faceEligible = 10))
    }

    @Test
    fun peopleCoverageClampsImpossibleCounts() {
        assertEquals(4, IndexingCoverageMath.peopleCompleted(faceScanned = 9, faceEligible = 4))
        assertEquals(4, IndexingCoverageMath.peoplePending(pending = 8, faceEligible = 4))
        assertEquals(0, IndexingCoverageMath.peopleFailed(faceScanned = 9, pending = 8, faceEligible = 4))
    }
}
