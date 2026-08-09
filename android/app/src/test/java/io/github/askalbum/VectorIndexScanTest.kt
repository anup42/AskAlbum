package io.github.anup42.askalbum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorIndexScanTest {
    @Test
    fun scanReturnsMatchesBeyondTopKAndHonorsEligibility() = runBlocking {
        val index = ReferenceVectorIndex(2)
        index.upsert("low", floatArrayOf(1f, 0f))
        index.upsert("high", floatArrayOf(0f, 1f))
        index.upsert("other", floatArrayOf(-1f, 0f))

        val all = index.scan(floatArrayOf(0f, 1f))
        assertEquals(listOf("high", "low", "other"), all.map(VectorHit::mediaId))
        assertTrue(index.scan(floatArrayOf(0f, 1f), setOf("low", "other")).none { it.mediaId == "high" })
    }
}
