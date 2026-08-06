package io.github.anup42.askalbum

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleClusterRefreshPolicyTest {
    @Test
    fun unchangedRevisionSkipsExpensiveClusterQuery() {
        assertFalse(PeopleClusterRefreshPolicy.shouldReload("3:100:12", "3:100:12"))
    }

    @Test
    fun changedOrUnknownRevisionReloads() {
        assertTrue(PeopleClusterRefreshPolicy.shouldReload(null, "3:100:12"))
        assertTrue(PeopleClusterRefreshPolicy.shouldReload("3:100:12", "3:101:12"))
        assertTrue(PeopleClusterRefreshPolicy.shouldReload("3:100:12", "3:100:12", force = true))
    }
}
