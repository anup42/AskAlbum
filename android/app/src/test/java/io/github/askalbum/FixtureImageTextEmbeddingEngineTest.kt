package io.github.anup42.askalbum

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FixtureImageTextEmbeddingEngineTest {
    @Test
    fun fixtureVectorsAreDeterministicAndNormalized() = runBlocking {
        val engine = FixtureImageTextEmbeddingEngine()
        val first = engine.embedText("birthday cake")
        val second = engine.embedText("birthday cake")
        val other = engine.embedText("beach sunset")

        assertEquals(64, first.size)
        assertArrayEquals(first, second, 0f)
        assertNotEquals(first.toList(), other.toList())
        assertEquals(1f, kotlin.math.sqrt(first.map { it * it }.sum()), 0.0001f)
    }
}
