package com.samsung.agenticgallery

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceVectorIndexTest {
    @Test
    fun enforcesDimensionFiniteValuesAndNonzeroNorm() {
        runBlocking {
            val index = ReferenceVectorIndex(2)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { index.upsert("short", floatArrayOf(1f)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { index.upsert("nan", floatArrayOf(Float.NaN, 1f)) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { index.upsert("zero", floatArrayOf(0f, 0f)) } }
        }
    }

    @Test
    fun updateAndStableTieOrderingAreDeterministic() {
        runBlocking {
            val index = ReferenceVectorIndex(2)
            index.upsert("z", floatArrayOf(1f, 0f))
            index.upsert("a", floatArrayOf(1f, 0f))
            assertEquals(listOf("a", "z"), index.search(floatArrayOf(1f, 0f), 2).map { it.mediaId })
            index.upsert("a", floatArrayOf(0f, 1f))
            assertEquals(listOf("z", "a"), index.search(floatArrayOf(1f, 0f), 2).map { it.mediaId })
            assertEquals(2, index.size())
        }
    }
}
