package io.github.anup42.askalbum

internal data class IndexingLeaseSqlFilter(
    val sql: String,
    val args: Array<Any?>,
)

internal object IndexingLeaseRecoveryPolicy {
    fun runningFilter(reclaimOrphanedLeases: Boolean, now: Long): IndexingLeaseSqlFilter =
        if (reclaimOrphanedLeases) {
            IndexingLeaseSqlFilter("status='RUNNING'", emptyArray())
        } else {
            IndexingLeaseSqlFilter(
                "status='RUNNING' AND (lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at<=?)",
                arrayOf(now),
            )
        }

    fun semanticFilter(reclaimOrphanedLeases: Boolean, now: Long): String =
        if (reclaimOrphanedLeases) {
            "1=1"
        } else {
            "(lease_owner IS NULL OR lease_expires_at IS NULL OR lease_expires_at<=$now)"
        }
}
