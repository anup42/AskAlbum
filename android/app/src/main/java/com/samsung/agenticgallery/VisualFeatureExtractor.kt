package com.samsung.agenticgallery

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor

object VisualFeatureExtractor {
    private const val HASH_SIZE = 32
    private const val DCT_SIZE = 8

    fun extract(argb: IntArray, width: Int, height: Int): VisualFeatures {
        require(width > 0 && height > 0 && argb.size == width * height)
        val gray = resizeGray(argb, width, height, HASH_SIZE, HASH_SIZE)
        val coefficients = dctLowFrequencies(gray)
        val median = coefficients.drop(1).sorted().let { it[it.size / 2] }
        var hash = 0L
        coefficients.forEachIndexed { index, value -> if (value >= median) hash = hash or (1L shl index) }

        val laplacian = ArrayList<Double>((HASH_SIZE - 2) * (HASH_SIZE - 2))
        for (y in 1 until HASH_SIZE - 1) for (x in 1 until HASH_SIZE - 1) {
            val center = gray[y * HASH_SIZE + x]
            laplacian += gray[(y - 1) * HASH_SIZE + x] + gray[(y + 1) * HASH_SIZE + x] +
                gray[y * HASH_SIZE + x - 1] + gray[y * HASH_SIZE + x + 1] - 4.0 * center
        }
        val lapMean = laplacian.average()
        val lapVariance = laplacian.sumOf { (it - lapMean) * (it - lapMean) } / laplacian.size
        val blur = (1.0 - exp(-lapVariance / 400.0)).coerceIn(0.0, 1.0).toFloat()
        val mean = gray.average()
        val centeredExposure = (1.0 - kotlin.math.abs(mean - 127.5) / 127.5).coerceIn(0.0, 1.0)
        val clippedFraction = gray.count { it <= 5.0 || it >= 250.0 }.toDouble() / gray.size
        val exposure = (centeredExposure * (1.0 - clippedFraction)).coerceIn(0.0, 1.0).toFloat()
        val quality = (blur * 0.65f + exposure * 0.35f).coerceIn(0f, 1f)
        return VisualFeatures(hash, blur, exposure, quality)
    }

    fun hammingDistance(left: Long, right: Long): Int = (left xor right).countOneBits()

    private fun resizeGray(
        argb: IntArray,
        width: Int,
        height: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): DoubleArray {
        val result = DoubleArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y + 0.5) * height / targetHeight - 0.5
            val y0 = floor(sourceY).toInt().coerceIn(0, height - 1)
            val y1 = (y0 + 1).coerceAtMost(height - 1)
            val fy = (sourceY - floor(sourceY)).coerceIn(0.0, 1.0)
            for (x in 0 until targetWidth) {
                val sourceX = (x + 0.5) * width / targetWidth - 0.5
                val x0 = floor(sourceX).toInt().coerceIn(0, width - 1)
                val x1 = (x0 + 1).coerceAtMost(width - 1)
                val fx = (sourceX - floor(sourceX)).coerceIn(0.0, 1.0)
                fun gray(pixel: Int): Double {
                    val red = pixel shr 16 and 0xff
                    val green = pixel shr 8 and 0xff
                    val blue = pixel and 0xff
                    return 0.299 * red + 0.587 * green + 0.114 * blue
                }
                val top = gray(argb[y0 * width + x0]) * (1.0 - fx) + gray(argb[y0 * width + x1]) * fx
                val bottom = gray(argb[y1 * width + x0]) * (1.0 - fx) + gray(argb[y1 * width + x1]) * fx
                result[y * targetWidth + x] = top * (1.0 - fy) + bottom * fy
            }
        }
        return result
    }

    private fun dctLowFrequencies(gray: DoubleArray): DoubleArray {
        val output = DoubleArray(DCT_SIZE * DCT_SIZE)
        for (v in 0 until DCT_SIZE) for (u in 0 until DCT_SIZE) {
            var sum = 0.0
            for (y in 0 until HASH_SIZE) for (x in 0 until HASH_SIZE) {
                sum += gray[y * HASH_SIZE + x] *
                    cos((2 * x + 1) * u * PI / (2 * HASH_SIZE)) *
                    cos((2 * y + 1) * v * PI / (2 * HASH_SIZE))
            }
            val cu = if (u == 0) 1.0 / kotlin.math.sqrt(2.0) else 1.0
            val cv = if (v == 0) 1.0 / kotlin.math.sqrt(2.0) else 1.0
            output[v * DCT_SIZE + u] = 0.25 * cu * cv * sum
        }
        return output
    }
}
