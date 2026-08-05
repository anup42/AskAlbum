package io.github.anup42.askalbum

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

enum class SemanticPredicateScanStatus { PENDING, RUNNING, COMPLETE, FAILED }

@Entity(
    tableName = "semantic_predicate_scan",
    indices = [
        Index(name = "semantic_predicate_scan_key_idx", value = ["query_key"], unique = true),
        Index(name = "semantic_predicate_scan_queue_idx", value = ["status", "next_attempt_at"]),
    ],
)
data class SemanticPredicateScanEntity(
    @androidx.room.PrimaryKey val id: String,
    @ColumnInfo(name = "query_key") val queryKey: String,
    @ColumnInfo(name = "query_text") val queryText: String,
    @ColumnInfo(name = "model_version") val modelVersion: String,
    @ColumnInfo(name = "scope_hash") val scopeHash: String,
    @ColumnInfo(name = "eligible_count") val eligibleCount: Int,
    @ColumnInfo(name = "indexed_count") val indexedCount: Int,
    @ColumnInfo(name = "indexed_coverage_hash") val indexedCoverageHash: String?,
    @ColumnInfo(name = "searched_count") val searchedCount: Int,
    @ColumnInfo(name = "next_ordinal") val nextOrdinal: Int,
    @ColumnInfo(name = "hit_count") val hitCount: Int,
    val status: String,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    val error: String?,
    @ColumnInfo(name = "lease_owner") val leaseOwner: String?,
    @ColumnInfo(name = "lease_expires_at") val leaseExpiresAt: Long?,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
    @ColumnInfo(name = "last_progress_at") val lastProgressAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "semantic_predicate_scan_scope",
    primaryKeys = ["scan_id", "media_id"],
    indices = [Index(name = "semantic_predicate_scan_scope_ordinal_idx", value = ["scan_id", "ordinal"], unique = true)],
    foreignKeys = [
        ForeignKey(
            entity = SemanticPredicateScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SemanticPredicateScanScopeEntity(
    @ColumnInfo(name = "scan_id") val scanId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val ordinal: Int,
)

@Entity(
    tableName = "semantic_predicate_scan_hit",
    primaryKeys = ["scan_id", "media_id"],
    indices = [Index(name = "semantic_predicate_scan_hit_score_idx", value = ["scan_id", "score"])],
    foreignKeys = [
        ForeignKey(
            entity = SemanticPredicateScanEntity::class,
            parentColumns = ["id"],
            childColumns = ["scan_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SemanticPredicateScanHitEntity(
    @ColumnInfo(name = "scan_id") val scanId: String,
    @ColumnInfo(name = "media_id") val mediaId: String,
    val score: Float,
)

data class SemanticPredicateScanRecord(
    val id: String,
    val queryKey: String,
    val queryText: String,
    val modelVersion: String,
    val scopeHash: String,
    val eligibleCount: Int,
    val indexedCount: Int,
    val indexedCoverageHash: String?,
    val searchedCount: Int,
    val nextOrdinal: Int,
    val hitCount: Int,
    val status: SemanticPredicateScanStatus,
    val attemptCount: Int,
    val error: String?,
    val leaseOwner: String?,
    val leaseExpiresAt: Long?,
    val nextAttemptAt: Long,
    val lastProgressAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val completeCoverage: Boolean
        get() = status == SemanticPredicateScanStatus.COMPLETE &&
            indexedCount >= eligibleCount && searchedCount >= eligibleCount
            && indexedCoverageHash == scopeHash
}

data class SemanticPredicateScanBatch(
    val scanId: String,
    val queryText: String,
    val ordinal: Int,
    val mediaIds: List<String>,
)

internal object SemanticPredicateScanPolicy {
    const val BATCH_SIZE = 64
    const val LEASE_MS = 2 * 60_000L
    private val explicitFullScan = Regex(
        "\\b(exact|exactly|exhaustive|complete|every|all|full\\s+scan)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun requested(plan: GalleryQueryPlan): Boolean {
        if (plan.intent != QueryIntent.COUNT) return false
        if (plan.semanticClauses.none { it.polarity == Polarity.POSITIVE } && plan.terms.isEmpty()) return false
        return explicitFullScan.containsMatchIn(plan.originalQuery)
    }

    fun queryText(plan: GalleryQueryPlan): String = plan.originalQuery.trim()

    fun canCommitBatch(report: RetrievalChannelReport<*>): Boolean =
        report.status == ChannelStatus.SUCCESS && report.errorCode == null

    fun coverageHash(eligibleMediaIds: Set<String>, indexedMediaIds: Set<String>): String =
        scopeHash(indexedMediaIds.intersect(eligibleMediaIds))

    fun requiresCoverageReset(
        status: SemanticPredicateScanStatus,
        storedCoverageHash: String?,
        currentCoverageHash: String,
    ): Boolean = status != SemanticPredicateScanStatus.RUNNING && storedCoverageHash != currentCoverageHash

    fun scopeHash(mediaIds: Set<String>): String = digest(mediaIds.toList().sorted().joinToString("\n"))

    fun queryKey(query: String, modelVersion: String, scopeHash: String): String =
        digest("$modelVersion\n$scopeHash\n${query.trim()}")

    fun scanId(queryKey: String): String = UUID.nameUUIDFromBytes(
        "semantic-predicate-scan-v1:$queryKey".toByteArray(Charsets.UTF_8),
    ).toString()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal object SemanticPredicateScanResultPolicy {
    /**
     * Complete counts must come from the durable scan hit set, not the bounded
     * retrieval channel that was used to discover or preview the scan.
     */
    fun completeMatchCount(
        record: SemanticPredicateScanRecord?,
        persistedHits: List<VectorHit>,
    ): Int? = record
        ?.takeIf { it.completeCoverage }
        ?.let { persistedHits.map(VectorHit::mediaId).distinct().size }
}

/** Checkpointed runner shared by the interactive query and the recovery worker. */
internal class SemanticPredicateScanRunner(
    private val database: GalleryDatabase,
    private val vectors: SemanticVectorStore,
) {
    suspend fun run(
        scanId: String,
        onProgress: suspend (SemanticPredicateScanRecord) -> Unit = {},
    ): SemanticPredicateScanRecord? {
        val owner = "exact-scan-${UUID.randomUUID()}"
        val claimed = database.claimSemanticPredicateScan(scanId, owner) ?: return database.semanticPredicateScan(scanId)
        try {
            while (true) {
                val batch = database.nextSemanticPredicateScanBatch(
                    scanId = scanId,
                    owner = owner,
                    limit = SemanticPredicateScanPolicy.BATCH_SIZE,
                ) ?: return database.semanticPredicateScan(scanId)
                if (batch.mediaIds.isEmpty()) return database.semanticPredicateScan(scanId)

                val vectorIds = database.vectorIdsForMedia(batch.mediaIds.toSet())
                val scanReport = vectors.scanTextBatchReport(batch.queryText, vectorIds)
                if (!SemanticPredicateScanPolicy.canCommitBatch(scanReport)) {
                    database.failSemanticPredicateScan(
                        scanId = scanId,
                        owner = owner,
                        error = scanReport.errorCode ?: "SEMANTIC_SCAN_BATCH_UNAVAILABLE",
                        retryable = true,
                    )
                    return database.semanticPredicateScan(scanId)
                }
                val rawHits = scanReport.hits
                val keyframes = database.videoKeyframesByIds(rawHits.mapTo(mutableSetOf(), VectorHit::mediaId))
                val resolvedHits = rawHits
                    .groupBy { keyframes[it.mediaId]?.mediaId ?: it.mediaId }
                    .map { (mediaId, hits) -> VectorHit(mediaId, hits.maxOf(VectorHit::score)) }
                val progress = database.commitSemanticPredicateScanBatch(
                    scanId = scanId,
                    owner = owner,
                    batch = batch,
                    hits = resolvedHits,
                ) ?: return database.semanticPredicateScan(scanId)
                onProgress(progress)
                if (progress.status == SemanticPredicateScanStatus.COMPLETE) return progress
            }
        } catch (cancelled: CancellationException) {
            database.releaseSemanticPredicateScan(scanId, owner)
            throw cancelled
        } catch (error: Throwable) {
            database.failSemanticPredicateScan(scanId, owner, error.message ?: error::class.java.simpleName, retryable = true)
            return database.semanticPredicateScan(scanId)
        }
    }
}

class SemanticPredicateScanWorker(
    appContext: Context,
    params: androidx.work.WorkerParameters,
) : androidx.work.CoroutineWorker(appContext, params) {
    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        val scanId = inputData.getString(INPUT_SCAN_ID) ?: return androidx.work.ListenableWorker.Result.success()
        val application = applicationContext as AskAlbumApplication
        val services = application.services
        if (services.semanticVectorStore.producerVersion() == null) {
            return androidx.work.ListenableWorker.Result.retry()
        }
        val record = SemanticPredicateScanRunner(
            services.galleryDatabase,
            services.semanticVectorStore,
        ).run(scanId)
        return when (record?.status) {
            SemanticPredicateScanStatus.PENDING,
            SemanticPredicateScanStatus.RUNNING,
                -> androidx.work.ListenableWorker.Result.retry()
            else -> androidx.work.ListenableWorker.Result.success()
        }
    }

    private companion object {
        const val INPUT_SCAN_ID = "scan_id"
    }
}

internal object SemanticPredicateScanScheduler {
    fun schedule(context: Context, scanId: String) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "semantic-predicate-scan-$scanId",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SemanticPredicateScanWorker>()
                .setInputData(androidx.work.workDataOf("scan_id" to scanId))
                .setConstraints(indexingWorkerConstraints(context))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag("semantic-predicate-scan")
                .build(),
        )
    }

    fun reconcile(context: Context) {
        val application = context.applicationContext as? AskAlbumApplication ?: return
        application.services.galleryDatabase.runnableSemanticPredicateScanIds().forEach { scanId ->
            schedule(context, scanId)
        }
    }
}
