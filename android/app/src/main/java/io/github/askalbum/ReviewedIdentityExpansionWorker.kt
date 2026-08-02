package io.github.anup42.askalbum

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal class ReviewedIdentityClusterExpander(
    private val application: AskAlbumApplication,
) {
    private val repository = application.repository
    private val vectors = application.services.faceVectorStore
    private val imageLoader = GalleryImageLoader(application)

    suspend fun ensureEmbedding(face: PersonFaceReviewItem): Boolean {
        if (face.id in vectors.ids()) return false
        val lease = application.services.faceEngines.acquireOrNull()
            ?: error("SFace is unavailable; the representative embedding will be repaired when the face model is ready")
        try {
            val detected = lease.engine.detectAndEmbed(imageLoader.loadJpeg(face.item).toModelImage())
            val matched = FaceEmbeddingRepairPolicy.match(face, detected)
                ?: throw FaceEmbeddingRepairException("The representative face could not be matched safely in its source image")
            vectors.upsert(face.id, matched.embedding)
            repository.markFaceEmbeddingAvailable(face.id, matched.embedding.size, lease.descriptor.producerVersion)
            return true
        } finally {
            lease.close()
        }
    }

    suspend fun expand(clusterId: String): Int {
        val target = repository.personClusterSummaries(includeHidden = true)
            .firstOrNull { it.id == clusterId && it.reviewed && !it.hidden }
            ?: return 0
        val memberships = repository.faceClusterMemberships(clusterId)
        if (memberships.isEmpty()) return 0
        val representativeId = target.representativeFaceId
            ?: target.representativeFace?.id
            ?: memberships.first().faceId
        val representative = repository.personFace(representativeId)
            ?: target.representativeFace
            ?: return 0
        ensureEmbedding(representative)

        val indexedIds = vectors.ids()
        val targetFaceIds = memberships.mapTo(linkedSetOf(), FaceClusterMembership::faceId)
        val seedIds = buildList {
            add(representativeId)
            addAll(memberships.filter(FaceClusterMembership::userCorrected).map(FaceClusterMembership::faceId))
        }.distinct().filter { it in indexedIds }.take(MAX_IDENTITY_SEEDS)
        if (seedIds.isEmpty()) return 0
        val candidateIds = indexedIds - targetFaceIds
        if (candidateIds.isEmpty()) return 0

        val references = repository.faceClusterReferences(candidateIds.toList())
        val scoresByCandidate = candidateIds.associateWith { mutableListOf<Float>() }
        seedIds.forEach { seedId ->
            vectors.similarities(seedId, candidateIds).forEach { (candidateId, score) ->
                scoresByCandidate[candidateId]?.add(score)
            }
        }
        val accepted = ReviewedIdentityExpansionPolicy.acceptedFaces(scoresByCandidate, references)
        return repository.assignAutomaticFacesToReviewedCluster(clusterId, accepted)
    }

    private fun ByteArray.toModelImage(): ModelImage {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(this, 0, size)) { "Face image could not be decoded" }
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val rgb = ByteArray(pixels.size * 3)
            pixels.forEachIndexed { index, pixel ->
                rgb[index * 3] = Color.red(pixel).toByte()
                rgb[index * 3 + 1] = Color.green(pixel).toByte()
                rgb[index * 3 + 2] = Color.blue(pixel).toByte()
            }
            ModelImage(rgb, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val MAX_IDENTITY_SEEDS = 8
    }
}

internal class FaceEmbeddingRepairException(message: String) : IllegalStateException(message)

internal object FaceEmbeddingRepairPolicy {
    fun match(expected: PersonFaceReviewItem, detected: List<FaceInstance>): FaceInstance? {
        val expectedBounds = listOf(expected.left, expected.top, expected.right, expected.bottom)
        val best = detected.map { it to intersectionOverUnion(expectedBounds, it.bounds) }
            .maxByOrNull { it.second }
            ?: return null
        return best.first.takeIf { best.second >= MIN_REPAIR_IOU }
    }

    private fun intersectionOverUnion(a: List<Float>, b: List<Float>): Float {
        if (a.size != 4 || b.size != 4) return 0f
        val width = (min(a[2], b[2]) - max(a[0], b[0])).coerceAtLeast(0f)
        val height = (min(a[3], b[3]) - max(a[1], b[1])).coerceAtLeast(0f)
        val intersection = width * height
        val areaA = (a[2] - a[0]).coerceAtLeast(0f) * (a[3] - a[1]).coerceAtLeast(0f)
        val areaB = (b[2] - b[0]).coerceAtLeast(0f) * (b[3] - b[1]).coerceAtLeast(0f)
        val union = areaA + areaB - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private const val MIN_REPAIR_IOU = .35f
}

internal object ReviewedIdentityExpansionPolicy {
    fun acceptedFaces(
        scoresByCandidate: Map<String, List<Float>>,
        references: Map<String, FaceClusterReference>,
    ): Set<String> = scoresByCandidate.mapNotNullTo(linkedSetOf()) { (faceId, rawScores) ->
        val reference = references[faceId]
        if (reference?.reviewed == true || reference?.hidden == true || reference?.userCorrected == true) {
            return@mapNotNullTo null
        }
        val scores = rawScores.sortedDescending()
        val strongSingle = scores.firstOrNull()?.let { it >= STRONG_SINGLE_SCORE } == true
        val supported = scores.size >= 2 &&
            scores[1] >= SUPPORT_SCORE &&
            (scores[0] + scores[1]) / 2f >= SUPPORT_AVERAGE_SCORE
        faceId.takeIf { strongSingle || supported }
    }

    private const val STRONG_SINGLE_SCORE = .58f
    private const val SUPPORT_SCORE = .46f
    private const val SUPPORT_AVERAGE_SCORE = .50f
}

class ReviewedIdentityExpansionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val application = applicationContext as AskAlbumApplication
        if (!application.repository.peopleIndexStatus().enabled) return@withContext Result.success()
        val clusterId = inputData.getString(KEY_CLUSTER_ID) ?: return@withContext Result.failure()
        runCatching { ReviewedIdentityClusterExpander(application).expand(clusterId) }.fold(
            onSuccess = { Result.success(workDataOf(KEY_MATCHED_COUNT to it)) },
            onFailure = { error ->
                if (error is FaceEmbeddingRepairException ||
                    error is SecurityException ||
                    error is java.io.FileNotFoundException
                ) {
                    Result.failure(workDataOf(KEY_ERROR to (error.message ?: error::class.java.simpleName).take(300)))
                } else {
                    Result.retry()
                }
            },
        )
    }
}

internal object ReviewedIdentityExpansionScheduler {
    fun schedule(context: Context, clusterId: String) {
        val request = OneTimeWorkRequestBuilder<ReviewedIdentityExpansionWorker>()
            .setInputData(workDataOf(KEY_CLUSTER_ID to clusterId))
            .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_PREFIX$clusterId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private const val WORK_PREFIX = "reviewed-identity-expansion-"
}

private const val KEY_CLUSTER_ID = "cluster_id"
private const val KEY_MATCHED_COUNT = "matched_count"
private const val KEY_ERROR = "error"
