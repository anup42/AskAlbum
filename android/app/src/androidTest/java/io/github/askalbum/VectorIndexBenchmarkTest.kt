package io.github.anup42.askalbum

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class VectorIndexBenchmarkTest {
    @Test
    fun exactFp16ScanMeetsReferenceDeviceGateAt5kAnd20k() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val benchmarkRoot = File(context.filesDir, "benchmarks/vector-index").canonicalFile
        require(benchmarkRoot.toPath().startsWith(context.filesDir.canonicalFile.toPath()))
        if (benchmarkRoot.exists()) require(benchmarkRoot.deleteRecursively())
        require(benchmarkRoot.mkdirs())
        val profiles = JSONArray()
        val failures = mutableListOf<String>()
        listOf(5_000, 20_000).forEach { count ->
            val directory = File(benchmarkRoot, count.toString()).canonicalFile
            require(directory.toPath().startsWith(benchmarkRoot.toPath()))
            require(directory.mkdirs())
            val vectors = LinkedHashMap<String, FloatArray>(count)
            repeat(count) { index -> vectors["stress-%05d".format(index)] = vector(index + 1) }
            val index = MmapFp16VectorIndex(directory, DIMENSION, compactAfterMutations = 512)
            val buildNanos = measureNanoTime { index.replaceAll(vectors) }
            vectors.clear()
            val reopened = MmapFp16VectorIndex(directory, DIMENSION)
            val query = vector(0x5eed)
            val samplesMs = LongArray(SAMPLES)
            repeat(SAMPLES) { sample ->
                samplesMs[sample] = measureNanoTime {
                    val hits = reopened.search(query, TOP_K)
                    assertEquals(TOP_K, hits.size)
                    assertTrue(hits.all { it.score.isFinite() })
                } / 1_000_000
            }
            val sorted = samplesMs.sorted()
            val p95 = sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceAtLeast(0)]
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val profile = JSONObject()
                .put("count", count)
                .put("dimension", DIMENSION)
                .put("buildMs", buildNanos / 1_000_000)
                .put("coldScanMs", samplesMs.first())
                .put("warmMedianMs", sorted[sorted.size / 2])
                .put("p95Ms", p95)
                .put("samplesMs", JSONArray(samplesMs.toList()))
                .put("snapshotBytes", directory.listFiles()?.sumOf(File::length) ?: 0L)
                .put("runtimeUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                .put("totalPssKb", memory.totalPss)
                .put("nativeHeapAllocatedBytes", Debug.getNativeHeapAllocatedSize())
                .put("backend", reopened.backendName)
            profiles.put(profile)
            if (reopened.backendName != "native-fp16") failures += "$count-vector scan did not load the native FP16 backend"
            if (p95 > MAX_P95_MS) failures += "$count-vector exact scan p95 ${p95}ms exceeds ${MAX_P95_MS}ms"
        }
        File(benchmarkRoot, "results.json").writeText(
            JSONObject().put("state", "COMPLETE").put("profiles", profiles).toString(2),
        )
        assertTrue(failures.joinToString("; "), failures.isEmpty())
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
        const val TOP_K = 20
        const val SAMPLES = 20
        const val MAX_P95_MS = 500L
    }
}
