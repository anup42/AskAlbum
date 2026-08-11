package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexingLeaseRecoveryPolicyTest {
    @Test
    fun normalRecoveryUsesOnlyLeaseExpiry() {
        val filter = IndexingLeaseRecoveryPolicy.runningFilter(reclaimOrphanedLeases = false, now = 123L)

        assertTrue(filter.sql.contains("lease_expires_at"))
        assertTrue(filter.sql.contains("lease_owner IS NULL"))
        assertTrue(filter.sql.contains("lease_expires_at IS NULL"))
        assertFalse(filter.sql.contains("updated_at"))
        assertFalse(filter.sql.contains("last_progress_at"))
        assertEquals(1, filter.args.size)
        assertEquals(123L, filter.args[0])
        val semanticFilter = IndexingLeaseRecoveryPolicy.semanticFilter(false, 123L)
        assertTrue(semanticFilter.contains("lease_owner IS NULL"))
        assertTrue(semanticFilter.contains("lease_expires_at IS NULL"))
        assertFalse(semanticFilter.contains("updated_at"))
    }

    @Test
    fun explicitOrphanRecoveryRemainsUnconditional() {
        val filter = IndexingLeaseRecoveryPolicy.runningFilter(reclaimOrphanedLeases = true, now = 123L)

        assertEquals("status='RUNNING'", filter.sql)
        assertTrue(filter.args.isEmpty())
        assertEquals("1=1", IndexingLeaseRecoveryPolicy.semanticFilter(true, 123L))
    }
}
