package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageVectorRepairPolicyTest {
    @Test
    fun identifiesOnlyDurableCompleteRecordsMissingFromTheIndex() {
        assertEquals(
            linkedSetOf("media-b", "frame-c"),
            ImageVectorRepairPolicy.missingVectorIds(
                expectedCompleteIds = linkedSetOf("media-a", "media-b", "frame-c"),
                indexedIds = setOf("media-a", "stale-vector"),
            ),
        )
    }

    @Test
    fun completePersistedCoverageNeedsNoRepair() {
        assertEquals(
            emptySet<String>(),
            ImageVectorRepairPolicy.missingVectorIds(setOf("media-a"), setOf("media-a", "stale-vector")),
        )
    }
}
