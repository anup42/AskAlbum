package com.samsung.agenticgallery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object IndexingRetryPolicy {
    const val MAX_ITEM_ATTEMPTS = 3
    const val LEASE_MILLIS = 12L * 60L * 1_000L

    fun nextAttemptAt(nowMillis: Long, attemptCount: Int): Long =
        nowMillis + retryDelayMillis(attemptCount)

    fun retryDelayMillis(attemptCount: Int): Long {
        val exponent = (attemptCount - 1).coerceIn(0, MAX_ITEM_ATTEMPTS - 1)
        return (30_000L shl exponent).coerceAtMost(5L * 60L * 1_000L)
    }

    fun failedStatus(permanent: Boolean, attemptCount: Int): StageStatus = when {
        permanent -> StageStatus.FAILED_PERMANENT
        attemptCount >= MAX_ITEM_ATTEMPTS -> StageStatus.FAILED_EXHAUSTED
        else -> StageStatus.FAILED_RETRYABLE
    }
}

internal object IndexingWorkerResultPolicy {
    fun shouldRetryWorker(
        processed: Int,
        retryableFailures: Int,
        stopped: Boolean,
        admissionAllowed: Boolean,
        hasImmediateWork: Boolean,
    ): Boolean =
        stopped ||
            !admissionAllowed ||
            (processed == 0 && retryableFailures > 0 && hasImmediateWork)
}

internal object IndexingResourceCoordinator {
    private val backgroundInference = Mutex()

    suspend fun <T> withBackgroundPermit(block: suspend () -> T): T =
        backgroundInference.withLock { block() }
}

internal object ForegroundIndexRuntime {
    @Volatile
    var active: Boolean = false
        private set

    fun started() {
        active = true
    }

    fun stopped() {
        active = false
    }
}
