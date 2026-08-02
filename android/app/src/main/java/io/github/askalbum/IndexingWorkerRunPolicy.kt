package io.github.anup42.askalbum

import android.os.SystemClock

internal class IndexingWorkerRunBudget(
    private val startedAtMillis: Long = SystemClock.elapsedRealtime(),
    private val maximumRunMillis: Long = MAXIMUM_RUN_MILLIS,
) {
    fun hasTimeRemaining(): Boolean = elapsedMillis() < maximumRunMillis

    fun elapsedMillis(): Long = SystemClock.elapsedRealtime() - startedAtMillis

    private companion object {
        const val MAXIMUM_RUN_MILLIS = 8L * 60L * 1_000L
    }
}
