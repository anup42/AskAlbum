package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TestGalleryRunScopeTest {
    @Test
    fun reinstallRecoveryUsesOnlyExactReservedRunPaths() {
        assertEquals(
            listOf(
                "Pictures/AgenticGalleryTest/run_123456/",
                "Documents/AgenticGalleryTest/run_123456/",
            ),
            TestGalleryRunScope.relativePaths("run_123456"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TestGalleryRunScope.relativePaths("../personal")
        }
    }
}
