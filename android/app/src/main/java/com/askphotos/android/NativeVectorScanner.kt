package com.askphotos.android

import java.nio.ByteBuffer

internal object NativeVectorScanner {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("gallery_vector")
        true
    }.getOrDefault(false)

    external fun dotFp16Matrix(
        buffer: ByteBuffer,
        vectorOffset: Long,
        dimension: Int,
        rowIndices: IntArray,
        query: FloatArray,
    ): FloatArray?
}
