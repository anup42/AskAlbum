package io.github.anup42.askalbum

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withContext
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap

internal fun shouldExecuteCapabilityWithoutMediaHits(
    intent: QueryIntent,
    verificationApplied: Boolean,
): Boolean = !verificationApplied && intent in setOf(
    QueryIntent.LIST,
    QueryIntent.COUNT,
    QueryIntent.ANSWER_FACT,
    QueryIntent.DOCUMENT_QA,
    QueryIntent.SUM,
    QueryIntent.MIN_MAX,
    QueryIntent.EVENT_SUMMARY,
    QueryIntent.TIMELINE,
    QueryIntent.COMPARE,
)

internal fun isDeterministicMetadataCount(
    plan: GalleryQueryPlan,
    terms: List<String>,
    semanticQueries: List<String>,
    verificationApplied: Boolean,
): Boolean = plan.intent == QueryIntent.COUNT &&
    plan.semanticClauses.isEmpty() &&
    terms.isEmpty() &&
    semanticQueries.isEmpty() &&
    !verificationApplied

internal fun requiresAuthenticationForAnswer(
    plan: GalleryQueryPlan,
    rankedHits: List<SearchHit>,
    deterministicAnswerHits: List<SearchHit>,
): Boolean {
    val requested = when (plan.intent) {
        QueryIntent.ANSWER_FACT,
        QueryIntent.DOCUMENT_QA,
        QueryIntent.LIST,
        -> OcrFactAllowlist.resolve(plan.ocrClause?.requestedField)
        QueryIntent.SUM,
        QueryIntent.MIN_MAX,
        -> OcrFactAllowlist.resolve(plan.aggregation?.field)
        else -> null
    }?.takeIf(OcrFactField::sensitive) ?: return false
    return (rankedHits + deterministicAnswerHits)
        .asSequence()
        .flatMap(SearchHit::evidence)
        .any { evidence ->
            evidence.sourceField == requested.sourceField && SensitiveEvidencePolicy.requiresAuthentication(evidence)
        }
}

private data class GalleryRepositoryOverrides(
    val database: GalleryDatabase,
    val planner: GalleryPlanCompiler,
    val visualVerifier: CandidateVerifier,
)

class GalleryRepository private constructor(
    context: Context,
    overrides: GalleryRepositoryOverrides?,
) {
    constructor(context: Context) : this(context, null)

    /** Immutable per-instance seam for connected acceptance tests; production wiring remains fixed. */
    internal constructor(
        context: Context,
        database: GalleryDatabase,
        planner: GalleryPlanCompiler,
        visualVerifier: CandidateVerifier,
    ) : this(
        context = context,
        overrides = GalleryRepositoryOverrides(database, planner, visualVerifier),
    )

    private val appContext = context.applicationContext
    private val services = (context.applicationContext as AskAlbumApplication).services
    private val database = overrides?.database ?: services.galleryDatabase
    private val planner = overrides?.planner ?: services.queryPlanCompiler
    private val semanticVectors = services.semanticVectorStore
    private val captionVectors = services.captionVectorStore
    private val visualVerifier = overrides?.visualVerifier ?: services.visualVerifier
    private val groundedAnswerComposer = services.groundedAnswerComposer
    private val importer = MediaImporter(context.applicationContext)
    private val indexingRunCriteriaStore = IndexingRunCriteriaStore(appContext)
    private val indexingJobControlsStore = IndexingJobControlsStore(appContext)
    private val planPatchResolver = ResultSetPlanPatchResolver()
    private val sessionPlans = ConcurrentHashMap<String, GalleryQueryPlan>()
    private val sensitiveAnswers = OneTimeSensitiveAnswerStore()

    fun revealSensitiveAnswer(token: String): SearchAnswer? = sensitiveAnswers.take(token)

    fun initialize(): IndexSummary {
        val restartWorkersAfterUpdate = consumeWorkerUpdateRestart()
        database.recoverInterruptedJobs(
            pipeline = IndexingPipeline.MEDIA_ANALYSIS,
            reclaimOrphanedLeases = IndexingRecoveryAdmissionPolicy.shouldReclaimOrphanedLeases(
                foregroundActive = ForegroundIndexRuntime.active,
                scheduledWorkActive = IndexScheduler.hasActiveWork(appContext),
            ),
        )
        database.recoverInterruptedJobs(
            pipeline = IndexingPipeline.EMBEDDINGS,
            reclaimOrphanedLeases = IndexingRecoveryAdmissionPolicy.shouldReclaimOrphanedLeases(
                foregroundActive = ForegroundIndexRuntime.active,
                scheduledWorkActive = EmbeddingIndexScheduler.hasActiveWork(appContext),
            ),
        )
        database.recoverInterruptedJobs(
            pipeline = IndexingPipeline.PEOPLE,
            reclaimOrphanedLeases = IndexingRecoveryAdmissionPolicy.shouldReclaimOrphanedLeases(
                foregroundActive = ForegroundIndexRuntime.active,
                scheduledWorkActive = PeopleIndexScheduler.hasActiveWork(appContext),
            ),
        )
        database.recoverInterruptedJobs(
            pipeline = IndexingPipeline.SEMANTIC_MEMORY,
            reclaimOrphanedLeases = IndexingRecoveryAdmissionPolicy.shouldReclaimOrphanedLeases(
                foregroundActive = ForegroundIndexRuntime.active,
                scheduledWorkActive = SemanticEnrichmentScheduler.hasActiveWork(appContext),
            ),
        )
        database.recoverInterruptedJobs(
            pipeline = IndexingPipeline.CAPTION_EMBEDDINGS,
            reclaimOrphanedLeases = IndexingRecoveryAdmissionPolicy.shouldReclaimOrphanedLeases(
                foregroundActive = ForegroundIndexRuntime.active,
                scheduledWorkActive = CaptionEmbeddingScheduler.hasActiveWork(appContext),
            ),
        )
        val legacyCaptionJobs = database.queueLegacySemanticCaptionJobs()
        if (legacyCaptionJobs > 0 && indexingJobControlsStore.load().semanticMemoryEnabled) {
            SemanticEnrichmentScheduler.schedule(
                appContext,
                userRequested = database.hasUserRequestedPendingSemanticEnrichmentJobs(),
            )
        }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        queuePersonalSemanticMemory()
        services.ocrModelPackManager.current()?.let { database.requestOcrReindex(it.spec.producerVersion) }
        if (database.pendingItems(1).isNotEmpty()) {
            if (restartWorkersAfterUpdate) IndexScheduler.restart(appContext) else IndexScheduler.schedule(appContext)
        }
        if (services.retrievalModelPackManager.status().installed) {
            if (restartWorkersAfterUpdate) {
                EmbeddingIndexScheduler.restart(appContext)
                CaptionEmbeddingScheduler.restart(appContext)
            } else {
                EmbeddingIndexScheduler.schedule(appContext)
                CaptionEmbeddingScheduler.schedule(appContext)
            }
        }
        if (database.peopleIndexStatus().enabled) {
            services.faceEngines.activeDescriptor()?.let { database.requestFaceEmbeddingReindex(it.producerVersion) }
            if (restartWorkersAfterUpdate) PeopleIndexScheduler.restart(appContext) else PeopleIndexScheduler.schedule(appContext)
        }
        if (
            indexingJobControlsStore.load().semanticMemoryEnabled &&
            database.hasPendingSemanticEnrichmentJobs()
        ) {
            if (restartWorkersAfterUpdate) {
                SemanticEnrichmentScheduler.restart(appContext, userRequested = true)
            } else {
                SemanticEnrichmentScheduler.schedule(appContext, userRequested = true)
            }
        }
        SemanticPredicateScanScheduler.reconcile(appContext)
        return database.summary()
    }

    private fun consumeWorkerUpdateRestart(): Boolean {
        val updateTime = appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .lastUpdateTime
        val preferences = appContext.getSharedPreferences(
            "indexing-worker-update-recovery-v1",
            Context.MODE_PRIVATE,
        )
        if (preferences.getLong("last_update_time", -1L) == updateTime) return false
        preferences.edit().putLong("last_update_time", updateTime).commit()
        return true
    }

    fun allItems(): List<GalleryItem> = database.allItems()

    fun indexSummary(): IndexSummary = database.summary()
    fun indexingRunCriteria(): IndexingRunCriteria = indexingRunCriteriaStore.load()
    fun indexingAdmission(): BackgroundWorkAdmission = BackgroundWorkAdmissionPolicy(appContext).evaluate()
    fun saveIndexingRunCriteria(criteria: IndexingRunCriteria): IndexingRunCriteria =
        indexingRunCriteriaStore.save(criteria)
    fun indexingJobControls(): IndexingJobControls = indexingJobControlsStore.load()
    fun setIndexingJobEnabled(job: IndexingJob, enabled: Boolean): IndexingJobControls =
        indexingJobControlsStore.setEnabled(job, enabled)
    fun setForegroundIndexingPaused(paused: Boolean): IndexingJobControls =
        indexingJobControlsStore.setForegroundPaused(paused)
    fun indexCoverageForContentUris(contentUris: Collection<String>): ScopedIndexCoverage =
        database.indexCoverageForContentUris(contentUris)

    fun importUris(uris: List<Uri>, source: MediaSource): Int {
        val changed = database.upsertImported(importer.inspectUris(uris, source))
        if (changed > 0) IndexScheduler.schedule(appContext)
        if (changed > 0) scheduleEmbeddingsIfAvailable()
        return changed
    }

    fun scanAccessibleGallery(): Int {
        val snapshot = importer.scanAccessibleMediaStore()
        val changed = database.upsertImported(snapshot.items)
        val plan = MediaReconciler.plan(database.mediaStoreItemsIncludingInaccessible(), snapshot)
        val reconciled = database.applyReconciliation(plan)
        if (database.pendingItems(1).isNotEmpty()) IndexScheduler.schedule(appContext)
        scheduleEmbeddingsIfAvailable()
        return changed + reconciled
    }

    fun markMediaStoreInaccessible(permissionSnapshotAt: Long): Int =
        database.markMediaStoreInaccessible(permissionSnapshotAt)

    fun removeImportedUris(uris: Collection<Uri>, reason: String = "source_removed"): MediaRemovalResult {
        IndexScheduler.cancelAndWait(appContext)
        val result = database.removeImportedByUris(uris.map(Uri::toString), reason)
        if (database.pendingItems(1).isNotEmpty()) IndexScheduler.schedule(appContext)
        scheduleEmbeddingsIfAvailable()
        return result
    }

    fun tombstoneCount(): Int = database.tombstoneCount()
    fun stageRecords(mediaId: String): List<MediaIndexStageRecord> = database.stageRecords(mediaId)
    fun itemById(mediaId: String): GalleryItem? = database.itemById(mediaId)
    fun videoKeyframes(mediaId: String): List<VideoKeyframeRecord> = database.videoKeyframes(mediaId)
    fun indexedMetadata(mediaId: String, includeSensitiveContent: Boolean = false): IndexedMediaMetadata {
        val item = requireNotNull(database.itemById(mediaId)) { "Media item is no longer available" }
        val blocks = database.ocrBlocks(mediaId)
        val entities = database.ocrEntities(mediaId)
        val keyframes = database.videoKeyframes(mediaId)
        val facts = database.semanticFactsForMedia(mediaId)
        val captions = database.semanticCaptionsForMedia(mediaId)
        val captionChunks = database.semanticCaptionChunksForMedia(mediaId)
        val personVisualFacts = database.personVisualFactsForMedia(mediaId)
        val protectedTypes = setOf(OcrEntityType.PASSWORD, OcrEntityType.EMAIL, OcrEntityType.PHONE, OcrEntityType.ORDER_ID)
        val protectedEntities = entities.filter {
            it.type in protectedTypes ||
                SensitiveContentClassifier.isSensitive("${it.label.orEmpty()} ${it.rawText} ${it.normalizedValue}")
        }
        val sensitiveFreeText = SensitiveContentClassifier.isSensitive(item.ocrText) ||
            blocks.any { SensitiveContentClassifier.isSensitive(it.text) } ||
            keyframes.any { SensitiveContentClassifier.isSensitive(it.ocrText) } ||
            facts.any { SensitiveContentClassifier.isSensitive("${it.predicate} ${it.value}") } ||
            captions.any { SensitiveContentClassifier.isSensitive(it.text) }
        val hasProtectedContent = protectedEntities.isNotEmpty() ||
            database.hasAuthenticationProtectedOcr(mediaId) ||
            sensitiveFreeText
        val locked = hasProtectedContent && !includeSensitiveContent
        val visibleFacts = if (locked) {
            facts.filterNot { SensitiveContentClassifier.isSensitive("${it.predicate} ${it.value}") }
        } else {
            facts
        }
        return IndexedMediaMetadata(
            stages = database.stageRecords(mediaId),
            ocrBlocks = if (locked) emptyList() else blocks,
            ocrEntities = if (locked) entities - protectedEntities.toSet() else entities,
            people = database.indexedPeopleForMedia(mediaId),
            event = database.eventsForMedia(listOf(mediaId))[mediaId],
            videoKeyframes = if (locked) keyframes.map { it.copy(ocrText = "") } else keyframes,
            semanticFacts = visibleFacts,
            sensitiveContentLocked = locked,
            protectedValueCount = protectedEntities.size + if (sensitiveFreeText) 1 else 0,
            semanticCaptions = if (locked) captions.filterNot { SensitiveContentClassifier.isSensitive(it.text) } else captions,
            semanticCaptionChunks = captionChunks.filterNot { SensitiveContentClassifier.isSensitive(it.exactText) },
            personVisualFacts = personVisualFacts,
        )
    }
    fun peopleIndexStatus(): PeopleIndexStatus = database.peopleIndexStatus()

    fun enablePeopleIndexing(): PeopleIndexStatus {
        val status = database.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        indexingJobControlsStore.setEnabled(IndexingJob.PEOPLE, true)
        PeopleIndexScheduler.schedule(appContext)
        return status
    }

    fun resetPeopleIndex(): PeopleIndexStatus {
        PeopleIndexScheduler.cancelAndWait(appContext)
        kotlinx.coroutines.runBlocking { services.faceVectorStore.clear() }
        val status = database.resetPeopleIndex()
        indexingJobControlsStore.setEnabled(IndexingJob.PEOPLE, false)
        return status
    }

    fun onFaceModelInstalled(): PeopleIndexStatus {
        val engine = services.faceEngines.activeDescriptor() ?: return database.peopleIndexStatus()
        database.requestFaceEmbeddingReindex(engine.producerVersion)
        PeopleIndexScheduler.schedule(appContext)
        return database.peopleIndexStatus()
    }

    fun onOcrModelInstalled(): Int {
        val installed = services.ocrModelPackManager.current() ?: return 0
        val changed = database.requestOcrReindex(installed.spec.producerVersion)
        if (changed > 0) IndexScheduler.schedule(appContext)
        return changed
    }

    fun onGemmaModelInstalled(): Int = queuePersonalSemanticMemory()

    fun saveReviewedPersonCluster(
        id: String,
        label: String,
        relationship: String?,
        aliases: List<String>,
        includeInPersonalSemanticMemory: Boolean? = null,
    ): PeopleIndexStatus = database.saveReviewedPersonCluster(
        id,
        label,
        relationship,
        aliases,
        includeInPersonalSemanticMemory,
    ).also {
        queuePersonalSemanticMemory(userRequested = true)
        ReviewedIdentityExpansionScheduler.schedule(appContext, id)
    }

    fun personClustersPendingReview(): List<PersonClusterReviewItem> = database.personClustersPendingReview()
    fun personClusterSummaries(includeHidden: Boolean = true): List<PersonClusterReviewItem> =
        database.personClusterSummaries(includeHidden)
    fun personClusterRevision(): String = database.personClusterRevision()
    fun personFacesForCluster(id: String, limit: Int, offset: Int): List<PersonFaceReviewItem> =
        database.personFacesForCluster(id, limit, offset)
    fun personFace(faceId: String): PersonFaceReviewItem? = database.personFace(faceId)
    fun setPersonClusterRepresentative(id: String, faceId: String) =
        database.setPersonClusterRepresentative(id, faceId).also {
            ReviewedIdentityExpansionScheduler.schedule(appContext, id)
        }
    fun excludeFaceFromCluster(faceId: String): String = database.excludeFaceFromCluster(faceId)
        .also { queuePersonalSemanticMemory(userRequested = true) }
    fun removePersonLabel(id: String): PeopleIndexStatus = database.removePersonLabel(id)
        .also { queuePersonalSemanticMemory() }
    fun setPersonClusterHidden(id: String, hidden: Boolean): PeopleIndexStatus = database.setPersonClusterHidden(id, hidden)
        .also {
            if (!hidden) {
                queuePersonalSemanticMemory(userRequested = true)
                ReviewedIdentityExpansionScheduler.schedule(appContext, id)
            }
        }
    fun mergePersonClusters(targetId: String, sourceId: String): PeopleIndexStatus =
        database.mergePersonClusters(targetId, sourceId).also {
            queuePersonalSemanticMemory(userRequested = true)
            ReviewedIdentityExpansionScheduler.schedule(appContext, targetId)
        }
    fun moveFaceToCluster(faceId: String, targetId: String? = null): String =
        database.moveFaceToCluster(faceId, targetId).also { resolvedTargetId ->
            queuePersonalSemanticMemory(userRequested = true)
            ReviewedIdentityExpansionScheduler.schedule(appContext, resolvedTargetId)
        }

    fun pendingItems(limit: Int): List<GalleryItem> = database.pendingItems(limit)
    fun pendingItemsForIds(mediaIds: Set<String>, limit: Int): List<GalleryItem> = database.pendingItemsForIds(mediaIds, limit)
    fun requestGalleryReindex(mediaIds: Set<String>) = database.requestGalleryReindex(mediaIds)
    fun ocrBlocks(mediaId: String): List<OcrBlockRecord> = database.ocrBlocks(mediaId)
    fun facePendingItems(limit: Int): List<GalleryItem> = database.facePendingItems(limit)
    fun markFaces(mediaId: String, producerVersion: String = "mlkit-face-detection-v1", owner: String = "people-direct"): Boolean =
        database.markFaces(mediaId, producerVersion, owner)
    fun completeFaces(
        mediaId: String,
        detections: List<FaceDetectionRecord>,
        producerVersion: String,
        owner: String? = null,
    ) = database.completeFaces(mediaId, detections, producerVersion, owner)
    fun completeEmbeddedFaces(
        mediaId: String,
        faces: List<FaceInstance>,
        clusterIds: List<String>,
        producerVersion: String,
        owner: String? = null,
    ) {
        database.completeEmbeddedFaces(mediaId, faces, clusterIds, producerVersion, owner)
        queuePersonalSemanticMemory(mediaIds = setOf(mediaId))
    }
    fun faceIdsForMedia(mediaId: String): List<String> = database.faceIdsForMedia(mediaId)
    fun allEmbeddedFaceIds(): Set<String> = database.allEmbeddedFaceIds()
    fun clusterIdForFace(faceId: String): String? = database.clusterIdForFace(faceId)
    fun faceClusterReferences(faceIds: List<String>): Map<String, FaceClusterReference> =
        database.faceClusterReferences(faceIds)
    fun faceClusterMemberships(clusterId: String): List<FaceClusterMembership> =
        database.faceClusterMemberships(clusterId)
    fun markFaceEmbeddingAvailable(faceId: String, dimension: Int, producerVersion: String) =
        database.markFaceEmbeddingAvailable(faceId, dimension, producerVersion)
    fun requestFaceEmbeddingRepair(faceIds: Set<String>, producerVersion: String) =
        database.requestFaceEmbeddingRepair(faceIds, producerVersion)
    fun assignAutomaticFacesToReviewedCluster(clusterId: String, faceIds: Set<String>): Int =
        database.assignAutomaticFacesToReviewedCluster(clusterId, faceIds).also { movedCount ->
            if (movedCount > 0) queuePersonalSemanticMemory(userRequested = true)
        }
    fun refineReviewedPersonCluster(clusterId: String, representativeFaceId: String, rejectedFaceIds: Set<String>): Int =
        database.refineReviewedPersonCluster(clusterId, representativeFaceId, rejectedFaceIds)
            .also { if (it > 0) queuePersonalSemanticMemory(userRequested = true) }
    fun ensureAutomaticPersonCluster(id: String) = database.ensureAutomaticPersonCluster(id)
    fun failFaces(
        mediaId: String,
        message: String,
        permanent: Boolean,
        producerVersion: String = "mlkit-face-detection-v1",
        owner: String? = null,
    ) = database.failFaces(mediaId, message, permanent, producerVersion, owner)
    fun embeddingPendingItems(producerVersion: String, limit: Int): List<GalleryItem> =
        database.embeddingPendingItems(producerVersion, limit)
    fun embeddingPendingItemsForIds(producerVersion: String, mediaIds: Set<String>, limit: Int): List<GalleryItem> =
        database.embeddingPendingItemsForIds(producerVersion, mediaIds, limit)
    fun accessibleIds(): Set<String> = database.accessibleIds()
    fun accessibleVectorIds(): Set<String> = database.accessibleVectorIds()
    fun keyframeEmbeddingPendingItems(producerVersion: String, limit: Int): List<VideoKeyframeRecord> =
        database.keyframeEmbeddingPendingItems(producerVersion, limit)
    fun keyframeEmbeddingPendingItemsForIds(
        producerVersion: String,
        mediaIds: Set<String>,
        limit: Int,
    ): List<VideoKeyframeRecord> = database.keyframeEmbeddingPendingItemsForIds(producerVersion, mediaIds, limit)
    fun completeKeyframeEmbedding(id: String, producerVersion: String) = database.completeKeyframeEmbedding(id, producerVersion)
    fun failKeyframeEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean): StageStatus =
        database.failKeyframeEmbedding(id, producerVersion, message, permanent)
    fun markEmbedding(id: String, producerVersion: String, owner: String = "repository-direct"): Boolean =
        database.markEmbedding(id, producerVersion, owner)
    fun completeEmbedding(id: String, producerVersion: String) = database.completeEmbedding(id, producerVersion)
    fun failEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean): StageStatus =
        database.failEmbedding(id, producerVersion, message, permanent)
    fun recoverInterruptedJobs(
        pipeline: IndexingPipeline,
        reclaimOrphanedLeases: Boolean = false,
    ) = database.recoverInterruptedJobs(pipeline, reclaimOrphanedLeases)
    fun renewIndexingLeases(pipeline: IndexingPipeline, owner: String) =
        database.renewIndexingLeases(pipeline, owner)
    fun releaseIndexingLeases(pipeline: IndexingPipeline, owner: String) =
        database.releaseIndexingLeases(pipeline, owner)
    fun markIndexing(
        id: String,
        owner: String = "repository-direct",
    ): Boolean = database.markIndexing(id, owner)
    fun completeIndex(
        id: String,
        labels: List<String>,
        description: String,
        ocrText: String,
        faceCount: Int,
        previewPath: String?,
        blocks: List<OcrBlockRecord>,
        entities: List<OcrEntityRecord>,
        ocrAttempted: Boolean,
        ocrProducerVersion: String?,
        visualFeatures: VisualFeatures,
        keyframes: List<VideoKeyframeRecord>,
    ) = database.completeIndex(
        id, labels, description, ocrText, faceCount, previewPath, blocks, entities,
        ocrAttempted, ocrProducerVersion, visualFeatures, keyframes,
    )
    fun failIndex(id: String, message: String, permanent: Boolean): StageStatus =
        database.failIndex(id, message, permanent)
    fun nextMediaRetryAt(): Long? = database.nextMediaRetryAt()
    fun nextEmbeddingRetryAt(): Long? = database.nextEmbeddingRetryAt()
    fun nextPeopleRetryAt(): Long? = database.nextPeopleRetryAt()
    fun rebuildEvents() {
        database.rebuildEvents()
        SemanticEnrichmentScheduler.schedule(appContext)
    }
    fun requestSemanticEnrichment(): SemanticEnrichmentPlan =
        SemanticEnrichmentCoordinator(database).rebuildPlan(
            userRequested = true,
            modelVersion = services.modelPackManager.status().packVersion,
        ).also {
            SemanticEnrichmentScheduler.schedule(appContext, userRequested = true)
        }
    fun semanticMemoryProgress(): SemanticMemoryProgress {
        val queued = database.queueLegacySemanticCaptionJobs()
        if (queued > 0 && indexingJobControlsStore.load().semanticMemoryEnabled) {
            SemanticEnrichmentScheduler.schedule(
                appContext,
                userRequested = database.hasUserRequestedPendingSemanticEnrichmentJobs(),
            )
        }
        return database.semanticMemoryProgress(services.modelPackManager.status().packVersion)
    }

    private fun queuePersonalSemanticMemory(
        userRequested: Boolean = false,
        mediaIds: Set<String>? = null,
    ): Int {
        val model = services.modelPackManager.status()
        if (!model.installed || !model.multimodal) return 0
        val queued = database.queueEligiblePersonalSemanticMemoryJobs(model.packVersion, userRequested, mediaIds)
        if (
            indexingJobControlsStore.load().semanticMemoryEnabled &&
            database.hasPendingSemanticEnrichmentJobs()
        ) {
            SemanticEnrichmentScheduler.schedule(
                appContext,
                userRequested = userRequested || database.hasUserRequestedPendingSemanticEnrichmentJobs(),
            )
        }
        return queued
    }
    fun semanticMemoryMedia(): List<SemanticMemoryMedia> {
        val accessibleItems = database.allItems().associateBy(GalleryItem::id)
        val factsByMedia = database.allSemanticFacts().groupBy(SemanticFactRecord::evidenceMediaId)
        val captionsByMedia = database.allSemanticCaptions().groupBy(SemanticCaptionRecord::evidenceMediaId)
        val chunksByMedia = database.allSemanticCaptionChunks().groupBy(SemanticCaptionChunkRecord::evidenceMediaId)
        val personFactsByMedia = database.allPersonVisualFacts().groupBy(PersonVisualFactRecord::mediaId)
        return (factsByMedia.keys + captionsByMedia.keys + chunksByMedia.keys + personFactsByMedia.keys)
            .mapNotNull { mediaId ->
                val item = accessibleItems[mediaId] ?: return@mapNotNull null
                val storedFacts = factsByMedia[mediaId].orEmpty()
                val (protectedFacts, visibleFacts) = storedFacts.partition {
                    SensitiveContentClassifier.isSensitive("${it.predicate} ${it.value}")
                }
                SemanticMemoryMedia(
                    item = item,
                    facts = visibleFacts,
                    protectedFactCount = protectedFacts.size,
                    captions = captionsByMedia[mediaId].orEmpty().filterNot { SensitiveContentClassifier.isSensitive(it.text) },
                    captionChunks = chunksByMedia[mediaId].orEmpty()
                        .filterNot { SensitiveContentClassifier.isSensitive(it.exactText) },
                    personVisualFacts = personFactsByMedia[mediaId].orEmpty(),
                )
            }
            .sortedWith(
                compareByDescending<SemanticMemoryMedia> {
                    it.item.capturedAt ?: it.item.modifiedAt ?: 0L
                }.thenBy { it.item.id },
            )
    }
    fun events(): List<EventRecord> = database.events()
    fun eventInspections(): List<EventInspection> {
        val accessibleItems = database.allItems().associateBy(GalleryItem::id)
        val mediaIdsByEvent = database.eventMembership().entries.groupBy(
            keySelector = { it.value },
            valueTransform = { it.key },
        )
        return database.events().map { event ->
            EventInspection(
                event = event,
                media = mediaIdsByEvent[event.id].orEmpty()
                    .mapNotNull(accessibleItems::get)
                    .sortedWith(
                        compareByDescending<GalleryItem> {
                            it.capturedAt ?: it.modifiedAt ?: 0L
                        }.thenBy { it.id },
                    ),
            )
        }
    }
    fun saveEventCorrection(
        operation: EventCorrectionOperation,
        mediaIds: Set<String>,
        title: String? = null,
        locationName: String? = null,
    ): EventCorrectionRecord = database.saveEventCorrection(operation, mediaIds, title, locationName)
    fun conversationState(sessionId: String = GalleryDatabase.PRIMARY_QUERY_SESSION): ConversationSearchState =
        database.conversationState(sessionId)

    private fun scheduleEmbeddingsIfAvailable() {
        if (services.retrievalModelPackManager.status().installed) EmbeddingIndexScheduler.schedule(appContext)
    }

    suspend fun beginInteractiveQuery() {
        IndexingResourceCoordinator.beginInteractiveQuery()
        withContext(Dispatchers.IO) {
            try {
                SemanticEnrichmentScheduler.cancelAndWait(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
            try {
                CaptionEmbeddingScheduler.cancelAndWait(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
            try {
                EmbeddingIndexScheduler.cancelAndWait(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
            }
        }
    }

    fun endInteractiveQuery() {
        IndexingResourceCoordinator.endInteractiveQuery()
        EmbeddingIndexScheduler.schedule(appContext)
        CaptionEmbeddingScheduler.schedule(appContext)
        SemanticEnrichmentScheduler.schedule(
            appContext,
            userRequested = database.hasUserRequestedPendingSemanticEnrichmentJobs(),
        )
    }

    suspend fun search(query: String, activeResultIds: Set<String>? = null): SearchOutcome =
        searchProgressive(query, activeResultIds).filterIsInstance<QueryProgress.Completed>().single().outcome

    suspend fun searchInSession(
        query: String,
        sessionId: String = GalleryDatabase.PRIMARY_QUERY_SESSION,
        initialScopeIds: Set<String>? = null,
    ): SearchOutcome = searchProgressive(query, activeResultIds = initialScopeIds, sessionId = sessionId)
        .filterIsInstance<QueryProgress.Completed>().single().outcome

    fun searchProgressive(
        query: String,
        activeResultIds: Set<String>? = null,
        sessionId: String? = null,
    ): Flow<QueryProgress> = flow {
        val started = SystemClock.elapsedRealtime()
        val conversation = sessionId?.let(database::conversationState)
        val hasActiveConversation = FollowUpRefinementPolicy.hasActiveResultSet(conversation)
        val scopedIds = if (hasActiveConversation) conversation?.activeResultIds else activeResultIds
        emit(QueryProgress.Understanding)
        val compiledPlan = if (hasActiveConversation && conversation != null) {
            planner.compileFollowUp(
                query,
                FollowUpPlanningContext(conversation, sessionId?.let(sessionPlans::get)),
            )
        } else {
            planner.compile(query, scopedIds)
        }
        val isFollowUp = hasActiveConversation && conversation != null &&
            compiledPlan.baseResultIds == conversation.activeResultIds
        val parentResultSetId = if (isFollowUp) conversation?.activeResultSetId else null
        val (planPatch, patchedPlan) = if (conversation != null && isFollowUp) {
            planPatchResolver.createAndApply(
                compiledPlan,
                conversation,
                sessionId?.let(sessionPlans::get),
            ).let { it.first to it.second }
        } else {
            null to compiledPlan
        }
        val sanitizedPatchedPlan = patchedPlan.copy(
            peopleClauses = PeopleClauseSanitizer.sanitize(patchedPlan.peopleClauses),
        )
        val peopleMergedPlan = sanitizedPatchedPlan.copy(
            peopleClauses = PeopleClauseMergePolicy.merge(
                plannerClauses = sanitizedPatchedPlan.peopleClauses,
                detectedClauses = PeopleQueryReferenceDetector.detect(query),
                reviewedGroups = database.resolveReviewedPersonGroups(query),
                resolveReviewedIds = database::resolveReviewedPersonIds,
            ),
        )
        val personConditionCanonicalizedPlan = PersonConditionCanonicalizationPolicy.apply(
            query = query,
            plan = peopleMergedPlan,
            resolveReviewedIds = database::resolveReviewedPersonIds,
        )
        val plan = PeopleRetrievalConstraintPolicy.apply(
            personConditionCanonicalizedPlan,
            database::resolveReviewedPersonIds,
        )
        sessionId?.let { sessionPlans[it] = plan }
        emit(QueryProgress.PlanReady(plan))
        val peopleStatus = database.peopleIndexStatus()
        val identityReadiness = mutableMapOf<String, Boolean>()
        val peopleUnavailable = PeopleQueryGate.unavailableReason(plan, peopleStatus) { requested ->
            identityReadiness.getOrPut(requested) {
                database.identityReadyReviewedPersonIds(listOf(requested)).isNotEmpty()
            }
        }
        if (peopleUnavailable != null) {
            val eligibleImageCount = database.allItems().count { it.kind == MediaKind.IMAGE }
            val peopleReport = PeopleUnavailableCoveragePolicy.report(eligibleImageCount)
            emit(QueryProgress.InitialResults(plan, emptyList()))
            val outcome = finalizeOutcome(sessionId, parentResultSetId, SearchOutcome(
                plan = plan,
                hits = emptyList(),
                answer = SearchAnswer(
                    headline = "People search is unavailable",
                    detail = peopleUnavailable,
                    evidenceIds = emptyList(),
                    exactness = ResultExactness.PARTIAL_INDEX,
                    indexedEligibleCount = peopleReport.indexedCount,
                    totalEligibleCount = peopleReport.eligibleCount,
                    warnings = listOf(peopleUnavailable),
                    channelReports = listOf(peopleReport),
                ),
                elapsedMs = max(1, SystemClock.elapsedRealtime() - started),
                planPatch = planPatch,
                channelReports = listOf(peopleReport),
            ))
            emit(QueryProgress.Completed(outcome))
            return@flow
        }
        val baseAllowed = resolveExecutionScope(plan.baseResultIds, activeResultIds)
        val peopleScope = PeopleClauseResolver.resolve(plan.peopleClauses) { personId ->
            database.mediaIdsForReviewedPeople(listOf(personId))
        }
        val requiredAllowed = when {
            baseAllowed == null -> peopleScope.requiredIds
            peopleScope.requiredIds == null -> baseAllowed
            else -> baseAllowed intersect peopleScope.requiredIds
        }
        val databaseItems = database.allItems()
        val allowed = if (peopleScope.excludedIds.isEmpty()) {
            requiredAllowed
        } else {
            (requiredAllowed ?: databaseItems.mapTo(mutableSetOf(), GalleryItem::id)) - peopleScope.excludedIds
        }
        val hasExplicitComparisonScopes = plan.intent == QueryIntent.COMPARE && plan.comparisonScopes.size >= 2
        val terms = RetrievalConceptExpansion.evidenceTerms(
            RetrievalTerms.forExecution(
                plan.terms,
                reviewedPeopleFilterApplied = plan.peopleClauses.isNotEmpty(),
            ),
        )
        val prePeopleItems = databaseItems.filter { item ->
            val inScope = when (plan.mediaScope) {
                MediaScope.ALL -> true
                MediaScope.IMAGES -> item.kind == MediaKind.IMAGE
                MediaScope.VIDEOS -> item.kind == MediaKind.VIDEO
                MediaScope.DOCUMENTS -> item.kind == MediaKind.PDF || item.ocrText.isNotBlank() || item.looksLikeDocument()
            }
            inScope && DeterministicScreenshotMediaPolicy.allows(plan, item) &&
                (baseAllowed == null || item.id in baseAllowed) &&
                GalleryFilterEvaluator.matches(item, plan.filter) &&
                item.matchesRequiredPlace(if (hasExplicitComparisonScopes) null else plan.place) &&
                item.matchesRequiredMerchant(plan.ocrClause?.merchant)
        }
        val allItems = prePeopleItems.filter {
            (allowed == null || it.id in allowed) &&
                !DeterministicNegativeClausePolicy.excludes(it, plan.semanticClauses)
        }
        val eligibleIds = allItems.mapTo(mutableSetOf(), GalleryItem::id)
        val deterministicAggregationHits = if (
            plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX) &&
            plan.semanticClauses.isEmpty()
        ) {
            val field = OcrFactAllowlist.resolve(plan.aggregation?.field) ?: OcrFactAllowlist.fields.first()
            val entities = database.ocrEntitiesForMediaIds(eligibleIds, field.type)
            allItems.mapNotNull { item ->
                entities[item.id]?.let { entity ->
                    SearchHit(
                        item = item,
                        score = 0.0,
                        evidence = listOf(
                            EvidenceRecord(
                                id = "${item.id}:${field.sourceField}:${StableDerivedId.sha256(entity.normalizedValue)}",
                                mediaId = item.id,
                                sourceField = field.sourceField,
                                text = entity.rawText,
                                confidence = entity.confidence,
                                producerVersion = entity.producerVersion,
                                region = listOf(entity.left, entity.top, entity.right, entity.bottom),
                            ),
                        ),
                    )
                }
            }
        } else {
            emptyList()
        }
        val deterministicDocumentHits = if (
            plan.intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA) ||
            plan.intent == QueryIntent.LIST && plan.ocrClause?.requestedField != null
        ) {
            OcrFactAllowlist.resolve(plan.ocrClause?.requestedField)?.let { field ->
                val entities = database.ocrEntitiesForMediaIds(eligibleIds, field.type)
                allItems.mapNotNull { item ->
                    entities[item.id]?.let { entity ->
                        SearchHit(
                            item = item,
                            score = 0.0,
                            evidence = listOf(
                                EvidenceRecord(
                                    id = "${item.id}:${field.sourceField}:${StableDerivedId.sha256(entity.normalizedValue)}",
                                    mediaId = item.id,
                                    sourceField = field.sourceField,
                                    text = entity.rawText,
                                    confidence = entity.confidence,
                                    producerVersion = entity.producerVersion,
                                    region = listOf(entity.left, entity.top, entity.right, entity.bottom),
                                ),
                            ),
                        )
                    }
                }
            }.orEmpty()
        } else {
            emptyList()
        }
        val lexicalSearch = database.fullTextMatches(terms)
        val fullTextIds = lexicalSearch.ids
        val lexicalRanked = allItems
            .asSequence()
            .mapNotNull { item -> score(item, terms, item.id in fullTextIds) }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.item.title })
            .toList()
        val semanticQueries = SemanticQueryVariants.from(plan)
        val requiredPersonChunkClusters = PersonCaptionConstraintPolicy.requiredClusterIds(plan)
        val captionSearch = database.searchSemanticCaptions(
            semanticQueries + terms,
            eligibleIds,
            requiredPersonChunkClusters,
        )
        val captionRanked = captionSearch.hits
        val captionProducer = captionVectors.producerVersion()
        val eligibleCaptionChunks = if (captionProducer == null) {
            emptyList()
        } else {
            database.eligibleCaptionChunksForSearch(eligibleIds, requiredPersonChunkClusters, captionProducer)
        }
        val searchableCaptionChunkIds = captionProducer?.let {
            CaptionVectorCoveragePolicy.searchableChunkIds(eligibleCaptionChunks, it)
        }.orEmpty()
        val captionedMediaCount = eligibleCaptionChunks.asSequence()
            .map(SemanticCaptionChunkRecord::mediaId)
            .distinct()
            .count()
        val captionVectorSearch = captionVectors.searchVariants(
            semanticQueries,
            eligibleCaptionChunks.mapTo(mutableSetOf(), SemanticCaptionChunkRecord::id),
            searchableCaptionChunkIds,
            eligibleIds.size,
            plan.limit.coerceIn(20, 100),
            captionedMediaCount,
        )
        val captionEmbeddingRanked = database.resolveCaptionVectorHits(
            captionVectorSearch.hits,
            eligibleIds,
            requiredPersonChunkClusters,
        )
        val eligibleVectorIds = database.vectorIdsForMedia(eligibleIds)
        val exactPredicateScan = if (
            SemanticPredicateScanPolicy.requested(plan) &&
            semanticQueries.isNotEmpty() &&
            eligibleIds.isNotEmpty()
        ) {
            semanticVectors.producerVersion()?.let { modelVersion ->
                val indexedMediaIds = database.mediaIdsWithVectorCoverage(
                    eligibleIds,
                    semanticVectors.indexedIds(),
                )
                val scanId = database.createOrResumeSemanticPredicateScan(
                    query = SemanticPredicateScanPolicy.queryText(plan),
                    modelVersion = modelVersion,
                    eligibleMediaIds = eligibleIds,
                    indexedMediaIds = indexedMediaIds,
                )
                val record = SemanticPredicateScanRunner(database, semanticVectors).run(scanId) { progress ->
                    emit(QueryProgress.SemanticScan(progress.searchedCount, progress.eligibleCount))
                }
                record?.also {
                    if (!it.completeCoverage) SemanticPredicateScanScheduler.schedule(appContext, it.id)
                }
            }
        } else {
            null
        }
        val semanticVectorReport = exactPredicateScan?.let { scan ->
            RetrievalChannelReport(
                channel = RetrievalChannel.SEMANTIC,
                status = when {
                    scan.status == SemanticPredicateScanStatus.FAILED -> ChannelStatus.FAILED
                    scan.status == SemanticPredicateScanStatus.COMPLETE && scan.indexedCount >= scan.eligibleCount -> ChannelStatus.SUCCESS
                    else -> ChannelStatus.PARTIAL
                },
                eligibleCount = scan.eligibleCount,
                indexedCount = scan.indexedCount,
                searchedCount = scan.searchedCount,
                hits = database.semanticPredicateScanHits(scan.id),
                modelVersion = scan.modelVersion,
                errorCode = scan.error,
            )
        } ?: SemanticChannelReportFusion.fuse(
            semanticQueries.map { semanticQuery ->
                semanticVectors.searchTextReport(
                    query = semanticQuery,
                    topK = plan.limit.coerceIn(20, 100),
                    eligibleCount = eligibleIds.size,
                    allowedIds = eligibleVectorIds,
                )
            },
        )
        val rawSemanticRanked = semanticVectorReport.hits
        val semanticKeyframes = database.videoKeyframesByIds(rawSemanticRanked.mapTo(mutableSetOf()) { it.mediaId })
        val resolvedSemanticHits = resolveSemanticVideoHits(rawSemanticRanked, semanticKeyframes)
        val semanticRanked = resolvedSemanticHits.map { it.hit }
        val captionById = captionRanked.associateBy(CaptionSearchHit::mediaId)
        val captionEmbeddingById = captionEmbeddingRanked.associateBy(CaptionSearchHit::mediaId)
        val bestSemanticKeyframeByMedia = resolvedSemanticHits.mapNotNull { resolved ->
            resolved.keyframe?.let { it.mediaId to it }
        }.toMap()
        val eventRanked = if (
            plan.intent == QueryIntent.LIST && plan.grouping == Grouping.EVENT && terms.isEmpty() && semanticQueries.isEmpty()
        ) {
            database.listEvents(eligibleIds)
        } else {
            database.searchEvents(terms, eligibleIds)
        }
        val rawEventMediaRank = eventRanked.flatMap { it.mediaIds }.distinct()
        val eventByMedia = eventRanked.flatMap { hit -> hit.mediaIds.map { it to hit.event } }.toMap()
        val lexicalById = lexicalRanked.associateBy { it.item.id }
        val semanticById = semanticRanked.associateBy { it.mediaId }
        val itemPredicateTerms = EventExpansionPolicy.itemPredicateTerms(plan)
        val itemPredicateQueries = EventExpansionPolicy.itemPredicateQueries(plan)
        val predicateLexicalIds = if (itemPredicateTerms.isEmpty()) {
            emptySet()
        } else {
            database.fullTextMatches(itemPredicateTerms).ids
        }
        val directCaptionIds = captionRanked
            .asSequence()
            .filter { it.directEvidence }
            .mapTo(mutableSetOf(), CaptionSearchHit::mediaId)
        val directCaptionEmbeddingIds = captionEmbeddingRanked
            .asSequence()
            .filter { it.directEvidence }
            .mapTo(mutableSetOf(), CaptionSearchHit::mediaId)
        val predicateSemanticReport = if (
            itemPredicateQueries.isEmpty() ||
            (plan.place == null && plan.comparisonScopes.isEmpty())
        ) {
            null
        } else {
            semanticVectors.searchTextReport(
                query = itemPredicateQueries.joinToString(" "),
                topK = plan.limit.coerceIn(20, 100),
                eligibleCount = eligibleIds.size,
                allowedIds = eligibleVectorIds,
            )
        }
        val predicateSemanticIds = predicateSemanticReport?.hits
            ?.mapTo(mutableSetOf(), VectorHit::mediaId)
            ?: semanticById.keys
        val itemPredicateIds = EventExpansionPolicy.itemPredicateIds(
            predicateTerms = itemPredicateTerms,
            lexicalIds = predicateLexicalIds,
            semanticIds = predicateSemanticIds,
            captionIds = directCaptionIds,
            captionEmbeddingIds = directCaptionEmbeddingIds,
        )
        val allowContextualEventExpansion =
            plan.intent == QueryIntent.EVENT_SUMMARY || plan.grouping == Grouping.EVENT ||
                (terms.isEmpty() && semanticQueries.isEmpty())
        val eventMediaRank = EventExpansionPolicy.mediaIdsForSearch(
            rawEventMediaIds = rawEventMediaRank,
            itemPredicateIds = itemPredicateIds,
            allowContextualExpansion = allowContextualEventExpansion,
        )
        val eventCoverageRequired = plan.intent == QueryIntent.EVENT_SUMMARY ||
            plan.grouping == Grouping.EVENT || rawEventMediaRank.isNotEmpty()
        val eventStageCoverage = if (eventCoverageRequired) {
            database.indexStageCoverage(eligibleIds, IndexStage.EVENTS)
        } else {
            IndexStageCoverage(eligibleCount = 0)
        }
        val eventStatus = EventChannelCoveragePolicy.status(eventCoverageRequired, eventStageCoverage)
        val itemById = allItems.associateBy { it.id }
        val deterministicScopeHit: (GalleryItem) -> SearchHit = { item ->
            val event = eventByMedia[item.id]
            SearchHit(
                item = item,
                score = 0.0,
                evidence = buildList {
                    add(
                        EvidenceRecord(
                            id = "${item.id}:deterministic_scope",
                            mediaId = item.id,
                            sourceField = "deterministic_scope",
                            text = listOf(item.title, item.location, item.album)
                                .filter(String::isNotBlank)
                                .distinct()
                                .joinToString(" | "),
                            confidence = 1f,
                            producerVersion = "gallery-deterministic-v1",
                        ),
                    )
                    event?.let {
                        add(
                            EvidenceRecord(
                                id = "${item.id}:event:${it.id}",
                                mediaId = item.id,
                                sourceField = "event",
                                text = "${it.title} (${formatEventRange(it.startTime, it.endTime)})",
                                confidence = it.confidence,
                                producerVersion = it.producerVersion,
                            ),
                        )
                    }
                },
            )
        }
        val deterministicCapabilityHits = when {
            isDeterministicMetadataCount(plan, terms, semanticQueries, false) ->
                allItems.map(deterministicScopeHit)
            plan.intent == QueryIntent.LIST && terms.isEmpty() && semanticQueries.isEmpty() ->
                allItems.map(deterministicScopeHit)
            plan.intent == QueryIntent.LIST && plan.grouping == Grouping.EVENT &&
                eventStatus == ChannelStatus.SUCCESS && eventMediaRank.isNotEmpty() ->
                eventMediaRank.mapNotNull { itemById[it] }.map(deterministicScopeHit)
            hasExplicitComparisonScopes ->
                plan.comparisonScopes.flatMap { scope ->
                    allItems.filter { item -> item.matchesComparisonScope(scope, eventByMedia[item.id]) }
                        .map(deterministicScopeHit)
                }
            plan.intent in setOf(QueryIntent.EVENT_SUMMARY, QueryIntent.TIMELINE, QueryIntent.COMPARE) &&
                eventStatus == ChannelStatus.SUCCESS && eventMediaRank.isNotEmpty() ->
                eventMediaRank.mapNotNull { itemById[it] }.map(deterministicScopeHit)
            else -> emptyList()
        }
        val deterministicAnswerHits = deterministicCapabilityHits.ifEmpty {
            deterministicAggregationHits.ifEmpty { deterministicDocumentHits }
        }
        val peopleCoverage = if (plan.peopleClauses.isEmpty()) {
            PeopleCoverage()
        } else {
            database.peopleCoverage(
                allItems.filter { it.kind == MediaKind.IMAGE }.mapTo(mutableSetOf(), GalleryItem::id),
            )
        }
        val peopleChannelReport = RetrievalChannelReport<SearchHit>(
            RetrievalChannel.PEOPLE,
            when {
                plan.peopleClauses.isEmpty() -> ChannelStatus.NOT_REQUIRED
                peopleCoverage.isComplete -> ChannelStatus.SUCCESS
                else -> ChannelStatus.PARTIAL
            },
            peopleCoverage.eligibleCount,
            peopleCoverage.indexedCount,
            peopleCoverage.indexedCount,
            emptyList(),
            modelVersion = services.faceEngines.activeDescriptor()?.producerVersion,
            errorCode = if (plan.peopleClauses.isNotEmpty() && !peopleCoverage.isComplete) {
                "FACE_COVERAGE_PARTIAL"
            } else {
                null
            },
        )
        val ocrRequired = plan.ocrClause != null || plan.intent in setOf(
            QueryIntent.ANSWER_FACT,
            QueryIntent.DOCUMENT_QA,
            QueryIntent.SUM,
            QueryIntent.MIN_MAX,
        )
        val ocrStageCoverage = if (ocrRequired) {
            database.indexStageCoverage(eligibleIds, IndexStage.OCR)
        } else {
            IndexStageCoverage(eligibleCount = 0)
        }
        val ocrModelAvailable = services.ocrEngines.activeDescriptor() != null
        val ocrStatus = OcrChannelCoveragePolicy.status(ocrRequired, ocrStageCoverage, ocrModelAvailable)
        val ocrChannelReport = RetrievalChannelReport<SearchHit>(
            channel = RetrievalChannel.OCR,
            status = ocrStatus,
            eligibleCount = ocrStageCoverage.eligibleCount,
            indexedCount = ocrStageCoverage.coveredCount,
            searchedCount = if (ocrRequired) ocrStageCoverage.coveredCount else 0,
            hits = emptyList(),
            modelVersion = services.ocrEngines.activeDescriptor()?.producerVersion,
            errorCode = OcrChannelCoveragePolicy.errorCode(ocrStatus),
        )
        val refinementIds = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = plan.baseResultIds != null,
            semanticIds = semanticRanked.map { it.mediaId },
            lexicalIds = lexicalById.keys,
            eventIds = eventMediaRank.toSet(),
        )
        val fused = HybridRankFusion.fuse(
            listOf(
                RankedChannel(1.0, lexicalRanked.map { it.item.id }),
                RankedChannel(0.85, semanticRanked.map { it.mediaId }),
                RankedChannel(0.80, captionRanked.map { it.mediaId }),
                RankedChannel(0.75, captionEmbeddingRanked.map { it.mediaId }),
                RankedChannel(0.95, eventMediaRank),
            ),
        ).let { ranked -> refinementIds?.let { eligible -> ranked.filter { it.first in eligible } } ?: ranked }
        val fusedHits = fused.mapNotNull { (id, score) ->
            val lexical = lexicalById[id]
            val semantic = semanticById[id]
            val caption = captionById[id]
            val captionEmbedding = captionEmbeddingById[id]
            val item = lexical?.item ?: itemById[id] ?: return@mapNotNull null
            val semanticEvidence = semantic?.let {
                val keyframe = bestSemanticKeyframeByMedia[item.id]
                EvidenceRecord(
                    id = "${item.id}:image_text_embedding:${StableDerivedId.sha256(plan.originalQuery)}",
                    mediaId = item.id,
                    sourceField = "image_text_embedding",
                    text = if (keyframe == null) "Local image-text similarity" else "Local video-frame similarity at ${formatTimestamp(keyframe.timestampMs)}",
                    confidence = ((it.score + 1f) / 2f).coerceIn(0f, 1f),
                    producerVersion = semanticVectors.producerVersion() ?: "unknown-retrieval-pack",
                    timestampMs = keyframe?.timestampMs,
                )
            }
            val eventEvidence = eventByMedia[id]?.let { event ->
                EvidenceRecord(
                    id = "${item.id}:event:${event.id}",
                    mediaId = item.id,
                    sourceField = "event",
                    text = "${event.title} (${formatEventRange(event.startTime, event.endTime)})",
                    confidence = event.confidence,
                    producerVersion = event.producerVersion,
                    scope = SemanticFactScope.EVENT,
                    scopeId = event.id.toString(),
                    evidenceMediaId = item.id,
                    applicability = "EVENT_MEMBERSHIP",
                )
            }
            val captionEvidence = caption?.let {
                EvidenceRecord(
                    id = "${item.id}:semantic_caption:${it.caption.id}",
                    mediaId = item.id,
                    sourceField = if (it.directEvidence) "semantic_caption" else "semantic_caption_candidate_expansion",
                    text = it.caption.text,
                    confidence = (it.caption.confidence * if (it.directEvidence) 1f else 0.72f).coerceIn(0f, 1f),
                    producerVersion = it.caption.modelVersion,
                    scope = it.chunk?.scope ?: it.caption.scope,
                    scopeId = it.chunk?.scopeId ?: it.caption.subjectId,
                    evidenceMediaId = it.chunk?.evidenceMediaId ?: it.caption.evidenceMediaId,
                    clusterId = it.chunk?.clusterId,
                    applicability = it.chunk?.applicability ?: it.caption.applicability,
                )
            }
            val captionEmbeddingEvidence = captionEmbedding?.let {
                val chunk = it.chunk ?: return@let null
                EvidenceRecord(
                    id = "${item.id}:semantic_caption_embedding:${chunk.id}",
                    mediaId = item.id,
                    sourceField = if (it.directEvidence) "semantic_caption_embedding" else "semantic_caption_embedding_context",
                    text = chunk.exactText,
                    confidence = (chunk.confidence * if (it.directEvidence) 1f else 0.72f).coerceIn(0f, 1f),
                    producerVersion = chunk.embeddingModelVersion ?: captionVectorSearch.modelVersion ?: "unknown-caption-vector",
                    scope = chunk.scope,
                    scopeId = chunk.scopeId,
                    evidenceMediaId = chunk.evidenceMediaId,
                    clusterId = chunk.clusterId,
                    applicability = chunk.applicability,
                )
            }
            SearchHit(
                item,
                score,
                lexical.orEmptyEvidence() + listOfNotNull(
                    semanticEvidence,
                    captionEvidence,
                    captionEmbeddingEvidence,
                    eventEvidence,
                ),
            )
        }
        val fusedHitsById = fusedHits.associateBy { it.item.id }
        val channelHit: (String, Double, RetrievalChannel) -> SearchHit? = { mediaId, score, channel ->
            fusedHitsById[mediaId]?.let { hit ->
                RetrievalChannelEvidence.project(hit, channel)?.copy(score = score)
            }
        }
        val readyEligibleCount = allItems.count { it.id in eligibleIds && it.indexState == IndexState.READY }
        val lexicalChannelReport = RetrievalChannelReport(
            RetrievalChannel.LEXICAL,
            lexicalSearch.status,
            if (lexicalSearch.status == ChannelStatus.NOT_REQUIRED) 0 else eligibleIds.size,
            if (lexicalSearch.status == ChannelStatus.NOT_REQUIRED) 0 else readyEligibleCount,
            if (lexicalSearch.status == ChannelStatus.NOT_REQUIRED) 0 else eligibleIds.size,
            lexicalRanked,
            modelVersion = "sqlite-fts+metadata-v2",
            errorCode = lexicalSearch.errorCode,
        )
        val semanticChannelReport = RetrievalChannelReport<SearchHit>(
            channel = semanticVectorReport.channel,
            status = semanticVectorReport.status,
            eligibleCount = semanticVectorReport.eligibleCount,
            indexedCount = semanticVectorReport.indexedCount,
            searchedCount = semanticVectorReport.searchedCount,
            hits = semanticRanked.mapNotNull { vectorHit ->
                channelHit(vectorHit.mediaId, vectorHit.score.toDouble(), RetrievalChannel.SEMANTIC)
            },
            modelVersion = semanticVectorReport.modelVersion,
            errorCode = semanticVectorReport.errorCode,
        )
        val eventChannelReport = RetrievalChannelReport(
            RetrievalChannel.EVENT,
            eventStatus,
            eventStageCoverage.eligibleCount,
            eventStageCoverage.coveredCount,
            eventRanked.size,
            eventMediaRank.mapNotNull { id -> channelHit(id, 1.0, RetrievalChannel.EVENT) },
            modelVersion = EventCompiler.PRODUCER_VERSION,
            errorCode = when (eventStatus) {
                ChannelStatus.PARTIAL -> "EVENT_COVERAGE_PARTIAL"
                ChannelStatus.UNAVAILABLE -> "EVENT_INDEX_UNAVAILABLE"
                else -> null
            },
        )
        val eventPredicateSemanticChannelReport = predicateSemanticReport?.let { report ->
            val projected = EventPredicateSemanticChannelPolicy.project(report)
            RetrievalChannelReport(
                channel = projected.channel,
                status = projected.status,
                eligibleCount = projected.eligibleCount,
                indexedCount = projected.indexedCount,
                searchedCount = projected.searchedCount,
                hits = projected.hits.mapNotNull { vectorHit ->
                    channelHit(vectorHit.mediaId, vectorHit.score.toDouble(), projected.channel)
                },
                modelVersion = projected.modelVersion,
                errorCode = projected.errorCode,
            )
        }
        val captionCoverage = database.semanticCaptionEvidenceCount(eligibleIds)
        val captionStatus = when {
            captionSearch.status == ChannelStatus.NOT_REQUIRED -> ChannelStatus.NOT_REQUIRED
            captionSearch.status != ChannelStatus.SUCCESS -> captionSearch.status
            captionCoverage < eligibleIds.size -> ChannelStatus.PARTIAL
            else -> ChannelStatus.SUCCESS
        }
        val captionChannelReport = RetrievalChannelReport(
            RetrievalChannel.CAPTION,
            captionStatus,
            if (captionStatus == ChannelStatus.NOT_REQUIRED) 0 else eligibleIds.size,
            if (captionStatus == ChannelStatus.NOT_REQUIRED) 0 else captionCoverage,
            if (captionStatus == ChannelStatus.NOT_REQUIRED) 0 else captionCoverage,
            captionRanked.mapNotNull { match ->
                channelHit(match.mediaId, match.score, RetrievalChannel.CAPTION)
            },
            modelVersion = captionRanked.firstOrNull()?.caption?.modelVersion,
            errorCode = captionSearch.errorCode
                ?: if (captionCoverage < eligibleIds.size && captionStatus != ChannelStatus.NOT_REQUIRED) {
                    "CAPTION_COVERAGE_PARTIAL"
                } else {
                    null
                },
        )
        val captionChunksByMedia = eligibleCaptionChunks.groupBy(SemanticCaptionChunkRecord::mediaId)
        val captionEmbeddingSearchedMediaCount = captionChunksByMedia.count { (_, chunks) ->
            chunks.any { it.id in searchableCaptionChunkIds }
        }
        val captionEmbeddingIndexedMediaCount = captionChunksByMedia.count { (_, chunks) ->
            chunks.isNotEmpty() && chunks.all { it.id in searchableCaptionChunkIds }
        }
        val captionEmbeddingChannelReport = RetrievalChannelReport(
            RetrievalChannel.CAPTION_EMBEDDING,
            captionVectorSearch.status,
            eligibleIds.size,
            captionEmbeddingIndexedMediaCount,
            captionEmbeddingSearchedMediaCount,
            captionEmbeddingRanked.mapNotNull { match ->
                channelHit(match.mediaId, match.score, RetrievalChannel.CAPTION_EMBEDDING)
            },
            modelVersion = captionVectorSearch.modelVersion,
            errorCode = captionVectorSearch.errorCode,
        )
        val ranked = when (plan.sort) {
            SortSpec.CAPTURE_TIME_DESC -> fusedHits.sortedWith(compareByDescending<SearchHit> { it.item.capturedAt ?: Long.MIN_VALUE }.thenByDescending { it.score })
            SortSpec.CAPTURE_TIME_ASC -> fusedHits.sortedWith(compareBy<SearchHit> { it.item.capturedAt ?: Long.MAX_VALUE }.thenByDescending { it.score })
            SortSpec.QUALITY -> fusedHits.sortedWith(compareByDescending<SearchHit> { it.item.qualityScore ?: 0f }.thenByDescending { it.score })
            else -> fusedHits
        }
        val collapsed = if (plan.intent == QueryIntent.COUNT) ranked else DuplicateCollapse.collapse(ranked)
        val diverse = if (
            plan.intent == QueryIntent.FIND_MEDIA && (plan.grouping == Grouping.EVENT || plan.terms.size <= 3)
        ) {
            EventDiversity.rerank(collapsed, database.eventMembership())
        } else {
            collapsed
        }
        val factIntents = setOf(
            QueryIntent.ANSWER_FACT,
            QueryIntent.DOCUMENT_QA,
            QueryIntent.LIST,
            QueryIntent.SUM,
            QueryIntent.MIN_MAX,
            QueryIntent.COMPARE,
        )
        val deterministicFacts = if (plan.intent in factIntents) {
            diverse.map { addDeterministicFactEvidence(it, plan) }
        } else {
            diverse
        }
        val cachedSemanticFacts = database.semanticFacts(deterministicFacts.map { it.item.id })
            .filter {
                it.scope == SemanticFactScope.MEDIA &&
                    it.applicability !in SemanticProvenanceApplicability.NON_CONFIRMING
            }
            .groupBy(SemanticFactRecord::subjectId)
        val enriched = deterministicFacts.map { hit ->
            val cached = cachedSemanticFacts[hit.item.id].orEmpty().map { fact ->
                EvidenceRecord(
                    id = "semantic:${fact.subjectId}:${fact.predicate}:${fact.value}".take(240),
                    mediaId = hit.item.id,
                    sourceField = "semantic_fact",
                    text = "${fact.predicate}: ${fact.value}",
                    confidence = fact.confidence,
                    producerVersion = fact.modelVersion,
                    region = fact.region,
                    scope = fact.scope,
                    scopeId = fact.subjectId,
                    evidenceMediaId = fact.evidenceMediaId,
                    applicability = fact.applicability,
                )
            }
            hit.copy(evidence = (hit.evidence + cached).distinctBy(EvidenceRecord::id))
        }
        val initialHits = enriched.take(plan.limit.coerceIn(1, 100))
        emit(QueryProgress.InitialResults(plan, initialHits))
        if (VisualVerificationPolicy.requiresVerification(plan) && initialHits.isNotEmpty()) {
            emit(QueryProgress.Verifying(initialHits.take(LiteRtGemmaVisualVerifier.MAX_CANDIDATES).size))
        }
        val verification = visualVerifier.verifyWhenNeeded(plan, enriched)
        val verifiedEvidence = verification.evidence.groupBy { it.mediaId }
        val verified = if (verification.applied) {
            enriched.asSequence()
                .filter { it.item.id in verification.acceptedIds }
                .map { hit -> hit.copy(evidence = hit.evidence + verifiedEvidence[hit.item.id].orEmpty()) }
                .toList()
        } else {
            enriched
        }
        val hits = verified.take(plan.limit.coerceIn(1, 100))
        val deterministicMetadataCount = isDeterministicMetadataCount(plan, terms, semanticQueries, verification.applied)
        val visualChannelReport = RetrievalChannelReport(
            RetrievalChannel.VISUAL_VERIFICATION,
            when {
                !verification.applied -> ChannelStatus.NOT_REQUIRED
                verification.trace?.usedGemma == false &&
                    verification.trace?.fallbackReason?.contains("No verified", true) == true -> ChannelStatus.UNAVAILABLE
                verification.failures.isNotEmpty() && verification.evaluations.isNotEmpty() -> ChannelStatus.PARTIAL
                verification.failures.isNotEmpty() -> ChannelStatus.FAILED
                else -> ChannelStatus.SUCCESS
            },
            initialHits.size,
            initialHits.size,
            verification.evaluations.size,
            hits,
            modelVersion = verification.trace?.modelRevision,
            errorCode = verification.trace?.fallbackReason?.take(120),
        )
        val channelReports = listOfNotNull(
            lexicalChannelReport,
            semanticChannelReport,
            captionChannelReport,
            captionEmbeddingChannelReport,
            eventChannelReport,
            eventPredicateSemanticChannelReport,
            ocrChannelReport,
            peopleChannelReport,
            visualChannelReport,
        )

        val exactPredicateMatchCount = exactPredicateScan?.takeIf { it.completeCoverage }?.let { scan ->
            SemanticPredicateScanResultPolicy.completeMatchCount(
                scan,
                database.semanticPredicateScanHits(scan.id),
            )
        }
        val matchCount = when {
            verification.applied -> verified.size
            deterministicMetadataCount -> deterministicAnswerHits.size
            exactPredicateMatchCount != null -> exactPredicateMatchCount
            else -> ranked.size
        }
        val requiresAuthentication = requiresAuthenticationForAnswer(plan, hits, deterministicAnswerHits)
        val deterministicAnswer = buildAnswer(
            plan,
            hits,
            allItems,
            eligibleIds,
            matchCount,
            verification,
            channelReports,
            deterministicAnswerHits,
            completePredicateScan = exactPredicateScan?.completeCoverage == true,
            deterministicMetadataCount = deterministicMetadataCount,
            sensitiveContentAuthorized = requiresAuthentication,
        )
        val sensitiveAnswerToken = if (requiresAuthentication) {
            sensitiveAnswers.put(deterministicAnswer.copy(channelReports = channelReports))
        } else {
            null
        }
        val answer = if (requiresAuthentication) {
            SensitiveEvidencePolicy.lock(deterministicAnswer)
        } else if (shouldComposeGroundedAnswer(plan, hits, verification)) {
            emit(QueryProgress.ComposingAnswer)
            groundedAnswerComposer.compose(
                GroundedAnswerInput(
                    plan,
                    GroundedAnswerEvidenceHits.merge(hits, deterministicAnswerHits),
                    deterministicAnswer,
                ),
            ).answer
        } else {
            deterministicAnswer
        }.copy(channelReports = channelReports)
        val outcomeHits = GroundedAnswerEvidenceHits.closeCitations(
            primary = hits,
            supplemental = deterministicAnswerHits,
            evidenceIds = (
                answer.evidenceIds +
                    if (requiresAuthentication) deterministicAnswer.evidenceIds else emptyList()
                ).distinct(),
        )
        val outcome = finalizeOutcome(sessionId, parentResultSetId, SearchOutcome(
            plan = plan,
            hits = outcomeHits,
            answer = answer,
            elapsedMs = max(1, SystemClock.elapsedRealtime() - started),
            planPatch = planPatch,
            channelReports = channelReports,
            sensitiveAnswerToken = sensitiveAnswerToken,
        ))
        emit(QueryProgress.Completed(outcome))
    }

    private fun finalizeOutcome(
        sessionId: String?,
        parentResultSetId: String?,
        outcome: SearchOutcome,
    ): SearchOutcome {
        val persisted = if (sessionId == null) outcome else database.persistResultSet(sessionId, outcome, parentResultSetId)
        database.recordQuery(persisted, sessionId)
        return persisted
    }

    private fun score(item: GalleryItem, terms: List<String>, fullTextMatch: Boolean): SearchHit? {
        if (terms.isEmpty()) {
            return SearchHit(item, 1.0, listOf(evidence(item, "metadata", item.title, 1f)))
        }

        val title = item.title.lowercase(Locale.ROOT)
        val location = item.location.lowercase(Locale.ROOT)
        val description = item.description.lowercase(Locale.ROOT)
        val ocrText = item.ocrText.lowercase(Locale.ROOT)
        val tags = item.tags.map { it.lowercase(Locale.ROOT) }
        var score = 0.0
        val evidence = mutableListOf<EvidenceRecord>()

        terms.forEach { term ->
            val matchingKeyframe = if (item.kind == MediaKind.VIDEO) {
                database.videoKeyframes(item.id).firstOrNull { frame ->
                    frame.labels.any { term in it.lowercase(Locale.ROOT) || it.lowercase(Locale.ROOT) in term } ||
                        term in frame.ocrText.lowercase(Locale.ROOT)
                }
            } else null
            if (matchingKeyframe != null) {
                score += 12.0
                evidence += EvidenceRecord(
                    id = "${item.id}:video_keyframe:${matchingKeyframe.id}:$term",
                    mediaId = item.id,
                    sourceField = "video_keyframe",
                    text = "${matchingKeyframe.labels.joinToString(", ").ifBlank { matchingKeyframe.ocrText }} at ${formatTimestamp(matchingKeyframe.timestampMs)}",
                    confidence = .92f,
                    producerVersion = matchingKeyframe.producerVersion,
                    timestampMs = matchingKeyframe.timestampMs,
                )
            }
            when {
                tags.any { it == term } -> {
                    score += 8.0
                    evidence += evidence(item, "semantic_tag", term, 1f)
                }
                tags.any { term in it || it in term } -> {
                    score += 5.0
                    evidence += evidence(item, "semantic_tag", tags.first { term in it || it in term }, .95f)
                }
            }
            if (term in location) {
                score += 7.0
                evidence += evidence(item, "location", item.location, 1f)
            }
            if (term in title) {
                score += 4.0
                evidence += evidence(item, "title", item.title, 1f)
            }
            if (term in description) {
                score += 2.0
                evidence += evidence(item, "description", item.description, .9f)
            }
            if (term in ocrText) {
                score += 10.0
                val matchingBlock = database.ocrBlocks(item.id).firstOrNull { term in it.text.lowercase(Locale.ROOT) }
                val matchingEntity = database.ocrEntities(item.id).firstOrNull { entity ->
                    term in entity.rawText.lowercase(Locale.ROOT) || term in entity.normalizedValue.lowercase(Locale.ROOT)
                }
                evidence += EvidenceRecord(
                id = "${item.id}:ocr:${StableDerivedId.sha256(term)}",
                    mediaId = item.id,
                    sourceField = ocrEvidenceSourceField(matchingEntity?.type),
                    text = matchingEntity?.rawText ?: matchingBlock?.text ?: term,
                    confidence = matchingEntity?.confidence ?: matchingBlock?.confidence ?: .8f,
                    producerVersion = matchingEntity?.producerVersion ?: "mlkit-text-v2",
                    region = matchingEntity?.let { listOf(it.left, it.top, it.right, it.bottom) }
                        ?: matchingBlock?.let { listOf(it.left, it.top, it.right, it.bottom) },
                    timestampMs = matchingBlock?.timestampMs,
                    pageIndex = matchingBlock?.pageIndex,
                )
            }
        }

        if (fullTextMatch) score += 3.0

        return if (score > 0) SearchHit(item, score, evidence.distinctBy { it.id }) else null
    }

    private fun evidence(
        item: GalleryItem,
        source: String,
        value: String,
        confidence: Float,
    ) = EvidenceRecord(
        id = "${item.id}:$source:${StableDerivedId.sha256(value.lowercase(Locale.ROOT))}",
        mediaId = item.id,
        sourceField = source,
        text = value,
        confidence = confidence,
    )

    private fun buildAnswer(
        plan: GalleryQueryPlan,
        hits: List<SearchHit>,
        allItems: List<GalleryItem>,
        eligibleIds: Set<String>,
        matchCount: Int,
        verification: VerificationResult,
        channelReports: List<RetrievalChannelReport<SearchHit>>,
        deterministicAnswerHits: List<SearchHit>,
        completePredicateScan: Boolean = false,
        deterministicMetadataCount: Boolean = false,
        sensitiveContentAuthorized: Boolean = false,
    ): SearchAnswer {
        val totalItems = eligibleIds.size
        val readyItems = allItems.count { it.id in eligibleIds && it.indexState == IndexState.READY }
        val semanticReport = channelReports.first { it.channel == RetrievalChannel.SEMANTIC }
        val peopleReport = channelReports.first { it.channel == RetrievalChannel.PEOPLE }
        val ocrReport = channelReports.first { it.channel == RetrievalChannel.OCR }
        val peopleCoverageComplete = peopleReport.status !in setOf(
            ChannelStatus.PARTIAL,
            ChannelStatus.UNAVAILABLE,
            ChannelStatus.FAILED,
        )
        val ocrCoverageComplete = ocrReport.status !in setOf(
            ChannelStatus.PARTIAL,
            ChannelStatus.UNAVAILABLE,
            ChannelStatus.FAILED,
        )
        val eventCoverageComplete = channelReports.firstOrNull { it.channel == RetrievalChannel.EVENT }?.status == ChannelStatus.SUCCESS
        val usedSemanticRetrieval = semanticReport.status != ChannelStatus.NOT_REQUIRED
        val deterministicResultSetFilter = plan.baseResultIds != null && plan.terms.isEmpty() &&
            plan.semanticClauses.isEmpty() && plan.filter != FilterExpression.True && !verification.applied
        val deterministicAggregation = plan.intent in setOf(QueryIntent.COUNT, QueryIntent.SUM, QueryIntent.MIN_MAX) &&
            plan.aggregation != null && plan.semanticClauses.isEmpty() && !verification.applied &&
            (deterministicMetadataCount || deterministicAnswerHits.isNotEmpty() || plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX))
        val deterministicList = plan.intent == QueryIntent.LIST && !verification.applied && (
            plan.terms.isEmpty() && plan.semanticClauses.isEmpty() ||
                plan.grouping == Grouping.EVENT && eventCoverageComplete && deterministicAnswerHits.isNotEmpty() ||
                plan.ocrClause != null && ocrCoverageComplete && deterministicAnswerHits.isNotEmpty()
            )
        val deterministicComparison = plan.intent == QueryIntent.COMPARE &&
            plan.comparisonScopes.size >= 2 && !verification.applied
        val requestedDocumentField = OcrFactAllowlist.resolve(plan.ocrClause?.requestedField)
        val deterministicDocumentFact = plan.intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA) &&
            requestedDocumentField != null &&
            ocrCoverageComplete &&
            DocumentAnswerSelector.select(
                deterministicAnswerHits,
                setOf(requestedDocumentField.sourceField),
                plan.sort,
            )?.fact != null &&
            !verification.applied
        val deterministicDocumentConstraint = plan.intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA) &&
            plan.ocrClause?.merchant?.isNotBlank() == true &&
            plan.terms.isEmpty() &&
            plan.semanticClauses.isEmpty() &&
            ocrCoverageComplete &&
            !verification.applied
        val exactness = RetrievalExactnessPolicy.resolve(
            allEligibleIndexed = readyItems == totalItems && peopleCoverageComplete && ocrCoverageComplete,
            deterministicOperation = deterministicAggregation || deterministicResultSetFilter || deterministicDocumentFact ||
                deterministicDocumentConstraint || deterministicList || deterministicComparison,
            semanticReport = semanticReport,
            verificationApplied = verification.applied,
            completePredicateScan = completePredicateScan,
        )
        val warnings = buildList {
            if (verification.failures.isNotEmpty()) {
                add("Visual verification had ${verification.failures.size} bounded failure(s); no failed candidate was accepted.")
            }
            verification.trace?.fallbackReason?.let { add("Visual verification unavailable: $it") }
            channelReports.filter { it.status in setOf(ChannelStatus.UNAVAILABLE, ChannelStatus.FAILED, ChannelStatus.PARTIAL) }
                .forEach { report ->
                    add(
                        "${report.channel.name.lowercase().replace('_', ' ')} channel ${report.status.name.lowercase()}: " +
                            "searched ${report.searchedCount} of ${report.eligibleCount} eligible items.",
                    )
                }
        }.distinct()
        val evidenceIds = hits.flatMap { it.evidence }.map { it.id }.distinct().take(12)
        if (hits.isEmpty() && !shouldExecuteCapabilityWithoutMediaHits(plan.intent, verification.applied)) {
            return SearchAnswer(
                headline = "No supported matches found",
                detail = if (verification.applied) {
                    "No bounded candidate was proven to satisfy every required visual condition. Failed or unverified candidates are never returned as matches."
                } else if (completePredicateScan) {
                    "An exhaustive semantic scan evaluated ${semanticReport.searchedCount} of " +
                        "${semanticReport.eligibleCount} eligible local items and found no supported matches."
                } else if (usedSemanticRetrieval) {
                    RetrievalCoverageWording.boundedSemanticNoResult(semanticReport)
                } else {
                    "All $totalItems eligible local items were checked. " +
                        if (readyItems < totalItems) "Some items are still indexing." else "Try a place, object, OCR word, or scene."
                },
                evidenceIds = emptyList(),
                exactness = exactness,
                indexedEligibleCount = readyItems,
                totalEligibleCount = totalItems,
                warnings = warnings,
                channelReports = channelReports,
            )
        }

        return CapabilityAnswerExecutor.execute(
            CapabilityAnswerContext(
                plan = plan,
                hits = hits,
                matchCount = matchCount,
                exactness = exactness,
                indexedEligibleCount = readyItems,
                totalEligibleCount = totalItems,
                warnings = warnings,
                channelReports = channelReports,
                eventsByMedia = database.eventsForMedia((hits + deterministicAnswerHits).map { it.item.id }),
                deterministicHits = deterministicAnswerHits,
                comparisonScopes = plan.comparisonScopes,
                peopleByMedia = database.indexedPeopleForMediaIds(
                    (hits + deterministicAnswerHits).mapTo(mutableSetOf()) { it.item.id },
                ),
                eventCoverageComplete = eventCoverageComplete,
                sensitiveContentAuthorized = sensitiveContentAuthorized,
            ),
        )

    }

    private fun SearchHit?.orEmptyEvidence(): List<EvidenceRecord> = this?.evidence.orEmpty()

    private fun formatTimestamp(timestampMs: Long): String {
        val totalSeconds = timestampMs.coerceAtLeast(0L) / 1_000L
        return "%d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun formatEventRange(startTime: Long, endTime: Long): String {
        val formatter = java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
            .withZone(java.time.ZoneId.systemDefault())
        return listOf(startTime, endTime)
            .map { formatter.format(java.time.Instant.ofEpochMilli(it)) }
            .joinToString(" - ")
    }

    private fun shouldComposeGroundedAnswer(
        plan: GalleryQueryPlan,
        hits: List<SearchHit>,
        verification: VerificationResult,
    ): Boolean = GroundedAnswerPolicy.shouldCompose(
        plan = plan,
        hasHits = hits.isNotEmpty(),
        modelInstalled = services.modelPackManager.status().installed,
        verificationApplied = verification.applied,
    )

    private fun GalleryItem.looksLikeDocument(): Boolean {
        val text = (filename + " " + title + " " + tags.joinToString(" ")).lowercase(Locale.ROOT)
        return listOf("receipt", "invoice", "document", "ticket", "boarding", "menu", "screenshot", "wifi", "confirmation")
            .any(text::contains)
    }

    private fun GalleryItem.matchesRequiredMerchant(merchant: String?): Boolean {
        val required = merchant?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return true
        val merchantEntities = database.ocrEntities(id, OcrEntityType.MERCHANT)
            .flatMap { listOf(it.rawText, it.normalizedValue) }
        val identityText = "$filename $title ${tags.joinToString(" ")}"
        return matchesMerchantIdentity(required, merchantEntities, identityText)
    }

    private fun GalleryItem.matchesRequiredPlace(place: String?): Boolean {
        val required = place?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) ?: return true
        return listOf(location, album, title, filename).plus(tags)
            .any { required in it.lowercase(Locale.ROOT) }
    }

    private fun GalleryItem.matchesComparisonScope(scope: String, event: EventRecord?): Boolean {
        val needle = scope.trim().lowercase(Locale.ROOT).takeIf(String::isNotBlank) ?: return false
        return listOf(
            location,
            album,
            title,
            filename,
            description,
            event?.title.orEmpty(),
            event?.locationName.orEmpty(),
            event?.searchText.orEmpty(),
        ).plus(tags).any { needle in it.lowercase(Locale.ROOT) }
    }

    private fun addDeterministicFactEvidence(hit: SearchHit, plan: GalleryQueryPlan): SearchHit {
        val requested = OcrFactAllowlist.resolve(plan.ocrClause?.requestedField ?: plan.aggregation?.field)
        val fields = when {
            requested != null -> listOf(requested)
            plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX) -> listOf(OcrFactAllowlist.fields.first())
            plan.intent in setOf(QueryIntent.ANSWER_FACT, QueryIntent.DOCUMENT_QA) -> emptyList()
            else -> OcrFactAllowlist.fields
        }
        val evidence = fields.mapNotNull { field ->
            val selected = database.ocrEntities(hit.item.id, field.type).firstOrNull() ?: return@mapNotNull null
            EvidenceRecord(
                id = "${hit.item.id}:${field.sourceField}:${StableDerivedId.sha256(selected.normalizedValue)}",
                mediaId = hit.item.id,
                sourceField = field.sourceField,
                text = selected.rawText,
                confidence = selected.confidence,
                producerVersion = selected.producerVersion,
                region = listOf(selected.left, selected.top, selected.right, selected.bottom),
            )
        }
        return if (evidence.isEmpty()) hit else hit.copy(evidence = evidence + hit.evidence)
    }
}

internal fun resolveExecutionScope(planResultIds: Set<String>?, explicitScopeIds: Set<String>?): Set<String>? =
    planResultIds ?: explicitScopeIds

internal fun ocrEvidenceSourceField(entityType: OcrEntityType?): String =
    entityType?.let { type -> OcrFactAllowlist.fields.firstOrNull { it.type == type }?.sourceField }
        ?: "ocr_text"

internal fun matchesMerchantIdentity(
    required: String,
    merchantEntities: List<String>,
    documentIdentityText: String,
): Boolean {
    val normalized = required.trim().lowercase(Locale.ROOT)
    if (normalized.isEmpty()) return true
    return merchantEntities.any { normalized in it.lowercase(Locale.ROOT) } ||
        normalized in documentIdentityText.lowercase(Locale.ROOT)
}

internal data class ResolvedSemanticHit(
    val hit: VectorHit,
    val keyframe: VideoKeyframeRecord?,
    val sourceVectorId: String,
)

internal fun resolveSemanticVideoHits(
    rawHits: List<VectorHit>,
    keyframesById: Map<String, VideoKeyframeRecord>,
): List<ResolvedSemanticHit> {
    val order = compareByDescending<ResolvedSemanticHit> { it.hit.score }.thenBy { it.sourceVectorId }
    return rawHits.map { raw ->
        val keyframe = keyframesById[raw.mediaId]
        ResolvedSemanticHit(
            hit = raw.copy(mediaId = keyframe?.mediaId ?: raw.mediaId),
            keyframe = keyframe,
            sourceVectorId = raw.mediaId,
        )
    }.groupBy { it.hit.mediaId }
        .mapNotNull { (_, candidates) -> candidates.minWithOrNull(order) }
        .sortedWith(order)
}
