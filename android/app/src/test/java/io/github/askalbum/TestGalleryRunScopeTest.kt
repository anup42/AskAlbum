package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TestGalleryRunScopeTest {
    @Test
    fun reinstallRecoveryUsesOnlyExactReservedRunPaths() {
        assertEquals(
            listOf(
                "Pictures/AskAlbumTest/run_123456/",
                "Documents/AskAlbumTest/run_123456/",
            ),
            TestGalleryRunScope.relativePaths("run_123456"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            TestGalleryRunScope.relativePaths("../personal")
        }
    }
}
