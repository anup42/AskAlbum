package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableDerivedIdTest {
    @Test
    fun idsAreDeterministicAndCollisionResistantForDifferentEvidence() {
        val first = StableDerivedId.sha256("media", "caption", "red dress")
        assertEquals(first, StableDerivedId.sha256("media", "caption", "red dress"))
        assertNotEquals(first, StableDerivedId.sha256("media", "caption", "blue dress"))
        assertEquals(64, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
    }
}
