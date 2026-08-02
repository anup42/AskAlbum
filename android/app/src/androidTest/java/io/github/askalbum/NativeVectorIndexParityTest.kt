package io.github.anup42.askalbum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NativeVectorIndexParityTest {
    @Test
    fun nativeFp16RankingMatchesReference() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-vector-parity").canonicalFile
        require(directory.toPath().startsWith(context.cacheDir.canonicalFile.toPath()))
        if (directory.exists()) require(directory.deleteRecursively())
        require(directory.mkdirs())
        try {
            val reference = ReferenceVectorIndex(DIMENSION)
            val mmap = MmapFp16VectorIndex(directory, DIMENSION)
            val vectors = linkedMapOf<String, FloatArray>()
            repeat(COUNT) { index ->
                val id = if (index % 2 == 0) "v$index" else "vector-$index-x"
                val vector = vector(index + 17)
                vectors[id] = vector
                reference.upsert(id, vector)
            }
            mmap.replaceAll(vectors)
            assertEquals("native-fp16", mmap.backendName)
            listOf(91, 0x5eed, 48_111).forEach { seed ->
                val query = vector(seed)
                val expected = reference.search(query, TOP_K)
                val actual = mmap.search(query, TOP_K)
                assertEquals(expected.map { it.mediaId }, actual.map { it.mediaId })
                expected.zip(actual).forEach { (left, right) ->
                    assertEquals(left.score, right.score, 0.003f)
                    assertTrue(right.score.isFinite())
                }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun vector(seed: Int): FloatArray {
        var state = seed.toLong() and 0xffffffffL
        return FloatArray(DIMENSION) {
            state = (1_664_525L * state + 1_013_904_223L) and 0xffffffffL
            ((state.toDouble() / 0xffffffffL) * 2.0 - 1.0).toFloat()
        }
    }

    private companion object {
        const val DIMENSION = 768
        const val COUNT = 1_024
        const val TOP_K = 50
    }
}
