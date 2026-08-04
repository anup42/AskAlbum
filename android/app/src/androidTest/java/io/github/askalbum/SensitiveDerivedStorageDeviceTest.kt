package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SensitiveDerivedStorageDeviceTest {
    @Test
    fun protectedDerivedValuesRoundTripAndAreIdempotent() {
        val storage = SensitiveDataAtRest()
        val values = listOf(
            "Wi-Fi password mango-tree-2048",
            "Show my Singapore trip with Dad",
            "P1 is holding a gift",
        )

        values.forEach { value ->
            val protected = storage.protect(value)
            assertNotEquals(value, protected)
            assertEquals(value, storage.reveal(protected))
            assertEquals(protected, storage.protect(protected))
        }
    }
}
