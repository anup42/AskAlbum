package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StableRecordIdTest {
    @Test
    fun samePartsProduceStableIdsAndDifferentPartsDoNotCollide() {
        val first = StableRecordId.of("ocr_text", "media-1", "password")
        val second = StableRecordId.of("ocr_text", "media-1", "password")
        val different = StableRecordId.of("ocr_text", "media-1", "passcode")

        assertEquals(first, second)
        assertNotEquals(first, different)
        assertFalse(first.contains("password"))
    }
}
