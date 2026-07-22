package com.askphotos.android

import android.content.Context
import java.io.File

class FaceVectorStore(context: Context) {
    private val index = MmapFp16VectorIndex(
        File(context.filesDir, "vectors/face/${FaceModelCatalog.sface.packVersion}"),
        FaceModelCatalog.sface.embeddingDimension,
    )

    suspend fun ids(): Set<String> = index.ids()
    suspend fun nearest(vector: FloatArray, allowedIds: Set<String>? = null): VectorHit? =
        index.search(vector, 1, allowedIds).firstOrNull()
    suspend fun upsert(faceId: String, vector: FloatArray) = index.upsert(faceId, vector)
    suspend fun delete(faceId: String) = index.delete(faceId)
    suspend fun reconcile(validFaceIds: Set<String>) = (index.ids() - validFaceIds).forEach { index.delete(it) }
    suspend fun clear() = index.replaceAll(emptyMap())
}

internal object FaceClusterPolicy {
    fun matchingCluster(nearest: VectorHit?, clusterId: String?): String? =
        clusterId?.takeIf { nearest != null && nearest.score >= FaceModelCatalog.sface.cosineThreshold }
}
