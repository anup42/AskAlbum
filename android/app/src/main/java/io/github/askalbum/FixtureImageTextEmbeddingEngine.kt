package io.github.anup42.askalbum

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.sqrt

/** Deterministic, model-free seam for CI and fixture builds. Never selected by production model-bearing variants. */
class FixtureImageTextEmbeddingEngine(
    private val dimension: Int = 64,
) : ImageTextEmbeddingEngine {
    override suspend fun embedImage(image: ModelImage): FloatArray = vector("image", image.rgbBytes, image.fixtureText)

    override suspend fun embedText(text: String): FloatArray = vector("text", text.toByteArray(Charsets.UTF_8), text)

    private fun vector(kind: String, bytes: ByteArray, label: String?): FloatArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(kind.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(bytes)
        label?.let {
            digest.update(0)
            digest.update(it.toByteArray(Charsets.UTF_8))
        }
        val seed = digest.digest()
        val output = FloatArray(dimension)
        var norm = 0.0
        for (index in output.indices) {
            val offset = (index * 4) % seed.size
            val bits = ByteBuffer.wrap(seed, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            val value = ((bits.toDouble() / Int.MAX_VALUE.toDouble()).coerceIn(-1.0, 1.0))
            output[index] = value.toFloat()
            norm += value * value
        }
        val scale = sqrt(norm).takeIf { it > 0.0 } ?: 1.0
        return output.map { (it / scale).toFloat() }.toFloatArray()
    }
}
