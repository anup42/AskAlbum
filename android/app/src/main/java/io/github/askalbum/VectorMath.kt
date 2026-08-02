package io.github.anup42.askalbum

import kotlin.math.sqrt

internal fun normalizeVector(vector: FloatArray, expectedDimension: Int): FloatArray {
    require(expectedDimension > 0)
    require(vector.size == expectedDimension) { "Expected $expectedDimension dimensions, received ${vector.size}" }
    var squaredNorm = 0.0
    vector.forEach { value ->
        require(value.isFinite()) { "Vectors must contain only finite values" }
        squaredNorm += value.toDouble() * value.toDouble()
    }
    require(squaredNorm > 0.0) { "Zero vectors are not searchable" }
    val inverseNorm = (1.0 / sqrt(squaredNorm)).toFloat()
    return FloatArray(vector.size) { vector[it] * inverseNorm }
}

internal fun dotProduct(left: FloatArray, right: FloatArray): Float {
    require(left.size == right.size)
    var score = 0.0
    for (index in left.indices) score += left[index].toDouble() * right[index].toDouble()
    return score.toFloat()
}
