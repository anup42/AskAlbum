package com.askphotos.android

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.RandomAccessFile
import java.nio.file.Files

class MmapFp16VectorIndexTest {
    @Test
    fun rankingsMatchReferenceAcrossCompactionReopenUpdateAndDelete() = withIndexDirectory { directory ->
        runBlocking {
            val reference = ReferenceVectorIndex(4)
            val mmap = MmapFp16VectorIndex(directory, 4, compactAfterMutations = 3)
            val vectors = linkedMapOf(
                "alpha" to floatArrayOf(1f, 0.2f, 0f, 0f),
                "bravo" to floatArrayOf(0.1f, 1f, 0f, 0f),
                "charlie" to floatArrayOf(0f, 0.1f, 1f, 0f),
                "delta" to floatArrayOf(0f, 0f, 0.1f, 1f),
            )
            vectors.forEach { (id, vector) ->
                reference.upsert(id, vector)
                mmap.upsert(id, vector)
            }
            assertParity(reference, mmap, floatArrayOf(0.9f, 0.4f, 0.1f, 0f))
            assertEquals(listOf("bravo"), mmap.search(floatArrayOf(1f, 1f, 0f, 0f), 4, setOf("bravo")).map { it.mediaId })

            val reopened = MmapFp16VectorIndex(directory, 4, compactAfterMutations = 3)
            assertParity(reference, reopened, floatArrayOf(0.9f, 0.4f, 0.1f, 0f))
            val replacement = floatArrayOf(0f, 0f, 0f, 1f)
            reference.upsert("alpha", replacement)
            reopened.upsert("alpha", replacement)
            reference.delete("charlie")
            reopened.delete("charlie")
            reopened.forceCompact()

            val secondReopen = MmapFp16VectorIndex(directory, 4)
            assertParity(reference, secondReopen, floatArrayOf(0f, 0f, 0.1f, 1f))
            assertEquals(3, secondReopen.status().size)
        }
    }

    @Test
    fun truncatedJournalTailKeepsAllCompleteRecords() = withIndexDirectory { directory ->
        runBlocking {
            val index = MmapFp16VectorIndex(directory, 2, compactAfterMutations = 100)
            index.upsert("a", floatArrayOf(1f, 0f))
            index.upsert("b", floatArrayOf(0f, 1f))
            directory.resolve("vectors.wal").appendBytes(byteArrayOf(0, 0, 0, 20, 1, 2, 3))

            val reopened = MmapFp16VectorIndex(directory, 2, compactAfterMutations = 100)
            assertTrue(reopened.status().recoveredWalTail)
            assertEquals(2, reopened.status().size)
            assertEquals(listOf("a", "b"), reopened.search(floatArrayOf(1f, 0f), 2).map { it.mediaId })
        }
    }

    @Test
    fun corruptSnapshotFailsClosedAndPersistsRebuildMarker() = withIndexDirectory { directory ->
        runBlocking {
            val index = MmapFp16VectorIndex(directory, 2)
            index.replaceAll(mapOf("a" to floatArrayOf(1f, 0f)))
            val active = requireNotNull(index.activeSnapshotFileForTest())
            RandomAccessFile(active, "rw").use {
                it.seek(12)
                it.writeInt(99)
                it.fd.sync()
            }

            val recovered = MmapFp16VectorIndex(directory, 2)
            assertTrue(recovered.status().needsRebuild)
            assertEquals(0, recovered.status().size)
            assertTrue(recovered.search(floatArrayOf(1f, 0f), 1).isEmpty())

            val reopened = MmapFp16VectorIndex(directory, 2)
            assertTrue(reopened.status().needsRebuild)
            reopened.replaceAll(mapOf("rebuilt" to floatArrayOf(0f, 1f)))
            assertFalse(reopened.status().needsRebuild)
        }
    }

    private suspend fun assertParity(reference: VectorIndex, mmap: VectorIndex, query: FloatArray) {
        val expected = reference.search(query, 20)
        val actual = mmap.search(query, 20)
        assertEquals(expected.map { it.mediaId }, actual.map { it.mediaId })
        expected.zip(actual).forEach { (left, right) -> assertEquals(left.score, right.score, 0.002f) }
    }

    private fun withIndexDirectory(block: (java.io.File) -> Unit) {
        val directory = Files.createTempDirectory("askphotos-vector-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
