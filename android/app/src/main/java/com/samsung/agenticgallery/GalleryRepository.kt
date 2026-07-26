package com.samsung.agenticgallery

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlin.math.max
import java.util.concurrent.ConcurrentHashMap

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val services = (context.applicationContext as AgenticGalleryApplication).services
    private val database = services.galleryDatabase
    private val planner = LiteRtLmQueryPlanner(services.modelPackManager, services.gemmaSessions)
    private val semanticVectors = services.semanticVectorStore
    private val visualVerifier = services.visualVerifier
    private val groundedAnswerComposer = services.groundedAnswerComposer
    private val importer = MediaImporter(context.applicationContext)
    private val indexingRunCriteriaStore = IndexingRunCriteriaStore(appContext)
    private val indexingJobControlsStore = IndexingJobControlsStore(appContext)
    private val planPatchResolver = ResultSetPlanPatchResolver()
    private val sessionPlans = ConcurrentHashMap<String, GalleryQueryPlan>()

    fun initialize(): IndexSummary {
        val restartWorkersAfterUpdate = consumeWorkerUpdateRestart()
        database.recoverInterruptedJobs()
        val legacyCaptionJobs = database.queueLegacySemanticCaptionJobs()
        if (legacyCaptionJobs > 0 && indexingJobControlsStore.load().semanticMemoryEnabled) {
            SemanticEnrichmentScheduler.schedule(appContext)
        }
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        services.ocrModelPackManager.current()?.let { database.requestOcrReindex(it.spec.producerVersion) }
        if (database.pendingItems(1).isNotEmpty()) {
            if (restartWorkersAfterUpdate) IndexScheduler.restart(appContext) else IndexScheduler.schedule(appContext)
        }
        if (services.retrievalModelPackManager.status().installed) {
            if (restartWorkersAfterUpdate) {
                EmbeddingIndexScheduler.restart(appContext)
            } else {
                EmbeddingIndexScheduler.schedule(appContext)
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

    fun removeImportedUris(uris: Collection<Uri>, reason: String = "source_removed"): MediaRemovalResult {
        IndexScheduler.cancelAndWait(appContext)
        val result = database.removeImportedByUris(uris.map(Uri::toString), reason)
        if (database.pendingItems(1).isNotEmpty()) IndexScheduler.schedule(appContext)
        scheduleEmbeddingsIfAvailable()
        return result
    }

    fun tombstoneCount(): Int = database.tombstoneCount()
    fun stageRecords(mediaId: String): List<MediaIndexStageRecord> = database.stageRecords(mediaId)
    fun videoKeyframes(mediaId: String): List<VideoKeyframeRecord> = database.videoKeyframes(mediaId)
    fun indexedMetadata(mediaId: String, includeSensitiveContent: Boolean = false): IndexedMediaMetadata {
        val item = requireNotNull(database.itemById(mediaId)) { "Media item is no longer available" }
        val blocks = database.ocrBlocks(mediaId)
        val entities = database.ocrEntities(mediaId)
        val keyframes = database.videoKeyframes(mediaId)
        val facts = database.semanticFactsForMedia(mediaId)
        val captions = database.semanticCaptionsForMedia(mediaId)
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
            personVisualFacts = personVisualFacts,
        )
    }
    fun peopleIndexStatus(): PeopleIndexStatus = database.peopleIndexStatus()

    fun enablePeopleIndexing(): PeopleIndexStatus = database
        .enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        .also { PeopleIndexScheduler.schedule(appContext) }

    fun resetPeopleIndex(): PeopleIndexStatus {
        PeopleIndexScheduler.cancelAndWait(appContext)
        kotlinx.coroutines.runBlocking { services.faceVectorStore.clear() }
        return database.resetPeopleIndex()
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

    fun saveReviewedPersonCluster(id: String, label: String, relationship: String?, aliases: List<String>): PeopleIndexStatus =
        database.saveReviewedPersonCluster(id, label, relationship, aliases)

    fun personClustersPendingReview(): List<PersonClusterReviewItem> = database.personClustersPendingReview()
    fun personClusterSummaries(includeHidden: Boolean = true): List<PersonClusterReviewItem> =
        database.personClusterSummaries(includeHidden)
    fun personFacesForCluster(id: String, limit: Int, offset: Int): List<PersonFaceReviewItem> =
        database.personFacesForCluster(id, limit, offset)
    fun setPersonClusterRepresentative(id: String, faceId: String) =
        database.setPersonClusterRepresentative(id, faceId)
    fun excludeFaceFromCluster(faceId: String): String = database.excludeFaceFromCluster(faceId)
    fun removePersonLabel(id: String): PeopleIndexStatus = database.removePersonLabel(id)
    fun setPersonClusterHidden(id: String, hidden: Boolean): PeopleIndexStatus = database.setPersonClusterHidden(id, hidden)
    fun mergePersonClusters(targetId: String, sourceId: String): PeopleIndexStatus =
        database.mergePersonClusters(targetId, sourceId)
    fun moveFaceToCluster(faceId: String, targetId: String? = null): String = database.moveFaceToCluster(faceId, targetId)

    fun pendingItems(limit: Int): List<GalleryItem> = database.pendingItems(limit)
    fun pendingItemsForIds(mediaIds: Set<String>, limit: Int): List<GalleryItem> = database.pendingItemsForIds(mediaIds, limit)
    fun requestGalleryReindex(mediaIds: Set<String>) = database.requestGalleryReindex(mediaIds)
    fun ocrBlocks(mediaId: String): List<OcrBlockRecord> = database.ocrBlocks(mediaId)
    fun facePendingItems(limit: Int): List<GalleryItem> = database.facePendingItems(limit)
    fun markFaces(mediaId: String) = database.markFaces(mediaId)
    fun completeFaces(mediaId: String, detections: List<FaceDetectionRecord>, producerVersion: String) =
        database.completeFaces(mediaId, detections, producerVersion)
    fun completeEmbeddedFaces(mediaId: String, faces: List<FaceInstance>, clusterIds: List<String>, producerVersion: String) =
        database.completeEmbeddedFaces(mediaId, faces, clusterIds, producerVersion)
    fun faceIdsForMedia(mediaId: String): List<String> = database.faceIdsForMedia(mediaId)
    fun allEmbeddedFaceIds(): Set<String> = database.allEmbeddedFaceIds()
    fun clusterIdForFace(faceId: String): String? = database.clusterIdForFace(faceId)
    fun faceClusterReferences(faceIds: List<String>): Map<String, FaceClusterReference> =
        database.faceClusterReferences(faceIds)
    fun faceClusterMemberships(clusterId: String): List<FaceClusterMembership> =
        database.faceClusterMemberships(clusterId)
    fun refineReviewedPersonCluster(clusterId: String, representativeFaceId: String, rejectedFaceIds: Set<String>): Int =
        database.refineReviewedPersonCluster(clusterId, representativeFaceId, rejectedFaceIds)
    fun ensureAutomaticPersonCluster(id: String) = database.ensureAutomaticPersonCluster(id)
    fun failFaces(mediaId: String, message: String, permanent: Boolean) = database.failFaces(mediaId, message, permanent)
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
    fun markEmbedding(id: String, producerVersion: String, owner: String = "repository-direct"): Boolean =
        database.markEmbedding(id, producerVersion, owner)
    fun completeEmbedding(id: String, producerVersion: String) = database.completeEmbedding(id, producerVersion)
    fun failEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean): StageStatus =
        database.failEmbedding(id, producerVersion, message, permanent)
    fun recoverInterruptedJobs() = database.recoverInterruptedJobs()
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
    fun rebuildEvents() {
        database.rebuildEvents()
        SemanticEnrichmentScheduler.schedule(appContext)
    }
    fun requestSemanticEnrichment(): SemanticEnrichmentPlan =
        SemanticEnrichmentCoordinator(database).rebuildPlan(userRequested = true).also {
            SemanticEnrichmentScheduler.schedule(appContext, userRequested = true)
        }
    fun semanticMemoryProgress(): SemanticMemoryProgress {
        val queued = database.queueLegacySemanticCaptionJobs()
        if (queued > 0 && indexingJobControlsStore.load().semanticMemoryEnabled) {
            SemanticEnrichmentScheduler.schedule(appContext)
        }
        return database.semanticMemoryProgress()
    }
    fun semanticMemoryMedia(): List<SemanticMemoryMedia> {
        val accessibleItems = database.allItems().associateBy(GalleryItem::id)
        val factsByMedia = database.allSemanticFacts().groupBy(SemanticFactRecord::evidenceMediaId)
        val captionsByMedia = database.allSemanticCaptions().groupBy(SemanticCaptionRecord::evidenceMediaId)
        return (factsByMedia.keys + captionsByMedia.keys)
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
                    personVisualFacts = database.personVisualFactsForMedia(mediaId),
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
        val isFollowUp = FollowUpRefinementPolicy.isContextualFollowUp(query, conversation)
        val scopedIds = if (conversation != null && isFollowUp) conversation.activeResultIds else activeResultIds
        val parentResultSetId = if (conversation != null && isFollowUp) conversation.activeResultSetId else null
        emit(QueryProgress.Understanding)
        val compiledPlan = planner.compile(query, scopedIds)
        val (planPatch, patchedPlan) = if (conversation != null && isFollowUp) {
            planPatchResolver.createAndApply(
                compiledPlan,
                conversation,
                sessionId?.let(sessionPlans::get),
            ).let { it.first to it.second }
        } else {
            null to compiledPlan
        }
        val resolvedPersonGroups = database.resolveReviewedPersonGroups(query)
        val plan = if (resolvedPersonGroups.isEmpty()) {
            patchedPlan
        } else {
            patchedPlan.copy(
                peopleClauses = (
                    patchedPlan.peopleClauses + resolvedPersonGroups.flatMap { group ->
                        group.personIds.map { personId ->
                            PersonClause(personId = personId, alternativeGroup = group.alternativeGroup)
                        }
                    }
                )
                    .distinctBy { it.personId to it.mustBePresent },
            )
        }
        sessionId?.let { sessionPlans[it] = plan }
        emit(QueryProgress.PlanReady(plan))
        val peopleStatus = database.peopleIndexStatus()
        val peopleUnavailable = PeopleQueryGate.unavailableReason(plan, peopleStatus)
        if (peopleUnavailable != null) {
            val peopleReport = RetrievalChannelReport<SearchHit>(
                RetrievalChannel.PEOPLE,
                ChannelStatus.UNAVAILABLE,
                database.allItems().count { it.kind == MediaKind.IMAGE },
                peopleStatus.identityReadyFaceCount,
                0,
                emptyList(),
                errorCode = "REVIEWED_IDENTITY_UNAVAILABLE",
            )
            emit(QueryProgress.InitialResults(plan, emptyList()))
            val outcome = finalizeOutcome(sessionId, parentResultSetId, SearchOutcome(
                plan = plan,
                hits = emptyList(),
                answer = SearchAnswer(
                    headline = "People search is unavailable",
                    detail = peopleUnavailable,
                    evidenceIds = emptyList(),
                    exactness = ResultExactness.PARTIAL_INDEX,
                    indexedEligibleCount = peopleStatus.faceInstanceCount,
                    totalEligibleCount = database.allItems().count { it.kind == MediaKind.IMAGE },
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
        val terms = RetrievalConceptExpansion.evidenceTerms(
            RetrievalTerms.forExecution(
                plan.terms,
                reviewedPeopleFilterApplied = plan.peopleClauses.isNotEmpty(),
            ),
        )
        val allItems = databaseItems.filter { item ->
            val inScope = when (plan.mediaScope) {
                MediaScope.ALL -> true
                MediaScope.IMAGES -> item.kind == MediaKind.IMAGE
                MediaScope.VIDEOS -> item.kind == MediaKind.VIDEO
                MediaScope.DOCUMENTS -> item.kind == MediaKind.PDF || item.ocrText.isNotBlank() || item.looksLikeDocument()
            }
            inScope && (allowed == null || item.id in allowed) &&
                GalleryFilterEvaluator.matches(item, plan.filter) &&
                item.matchesRequiredPlace(plan.place) &&
                item.matchesRequiredMerchant(plan.ocrClause?.merchant)
        }
        val eligibleIds = allItems.mapTo(mutableSetOf(), GalleryItem::id)
        val fullTextIds = database.fullTextMatches(terms)
        val lexicalRanked = allItems
            .asSequence()
            .mapNotNull { item -> score(item, terms, item.id in fullTextIds) }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.item.title })
            .toList()
        val semanticQueries = SemanticQueryVariants.from(plan)
        val captionRanked = database.searchSemanticCaptions(semanticQueries + terms, eligibleIds)
        val eligibleVectorIds = database.vectorIdsForMedia(eligibleIds)
        val semanticVectorReport = SemanticChannelReportFusion.fuse(
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
        val bestSemanticKeyframeByMedia = resolvedSemanticHits.mapNotNull { resolved ->
            resolved.keyframe?.let { it.mediaId to it }
        }.toMap()
        val eventRanked = database.searchEvents(terms, eligibleIds)
        val eventMediaRank = eventRanked.flatMap { it.mediaIds }.distinct()
        val eventByMedia = eventRanked.flatMap { hit -> hit.mediaIds.map { it to hit.event } }.toMap()
        val lexicalById = lexicalRanked.associateBy { it.item.id }
        val semanticById = semanticRanked.associateBy { it.mediaId }
        val itemById = allItems.associateBy { it.id }
        val resolvedSemanticBySourceId = resolvedSemanticHits.associateBy(ResolvedSemanticHit::sourceVectorId)
        val semanticChannelReport = semanticVectorReport.mapHits { vectorHit ->
            val resolved = resolvedSemanticBySourceId[vectorHit.mediaId] ?: return@mapHits null
            val item = itemById[resolved.hit.mediaId] ?: return@mapHits null
            SearchHit(item, resolved.hit.score.toDouble(), emptyList())
        }
        val readyEligibleCount = allItems.count { it.indexState == IndexState.READY }
        val lexicalChannelReport = RetrievalChannelReport(
            RetrievalChannel.LEXICAL,
            ChannelStatus.SUCCESS,
            allItems.size,
            readyEligibleCount,
            allItems.size,
            lexicalRanked,
            modelVersion = "sqlite-fts+metadata-v1",
        )
        val eventChannelReport = RetrievalChannelReport(
            RetrievalChannel.EVENT,
            if (terms.isEmpty() && plan.intent != QueryIntent.EVENT_SUMMARY) ChannelStatus.NOT_REQUIRED else ChannelStatus.SUCCESS,
            allItems.size,
            database.events().size,
            eventRanked.size,
            eventMediaRank.mapNotNull { id -> itemById[id]?.let { SearchHit(it, 1.0, emptyList()) } },
            modelVersion = EventCompiler.PRODUCER_VERSION,
        )
        val captionCoverage = database.semanticCaptionEvidenceCount()
        val captionChannelReport = RetrievalChannelReport(
            RetrievalChannel.CAPTION,
            if (captionCoverage == 0) ChannelStatus.PARTIAL else ChannelStatus.SUCCESS,
            allItems.size,
            captionCoverage,
            captionRanked.size,
            captionRanked.mapNotNull { match ->
                itemById[match.mediaId]?.let { SearchHit(it, match.score, emptyList()) }
            },
            modelVersion = captionRanked.firstOrNull()?.caption?.modelVersion,
            errorCode = if (captionCoverage == 0) "NO_CACHED_CAPTIONS" else null,
        )
        val peopleChannelReport = RetrievalChannelReport<SearchHit>(
            RetrievalChannel.PEOPLE,
            if (plan.peopleClauses.isEmpty()) ChannelStatus.NOT_REQUIRED else ChannelStatus.SUCCESS,
            databaseItems.count { it.kind == MediaKind.IMAGE },
            peopleStatus.identityReadyFaceCount,
            if (plan.peopleClauses.isEmpty()) 0 else allItems.size,
            emptyList(),
            modelVersion = services.faceEngines.activeDescriptor()?.producerVersion,
        )
        val ocrChannelReport = RetrievalChannelReport<SearchHit>(
            RetrievalChannel.OCR,
            if (plan.ocrClause == null) ChannelStatus.NOT_REQUIRED else if (readyEligibleCount < allItems.size) ChannelStatus.PARTIAL else ChannelStatus.SUCCESS,
            allItems.size,
            readyEligibleCount,
            if (plan.ocrClause == null) 0 else allItems.size,
            emptyList(),
            modelVersion = services.ocrEngines.activeDescriptor()?.producerVersion,
        )
        val refinementIds = FollowUpRefinementPolicy.corroboratedSemanticIds(
            scoped = plan.baseResultIds != null,
            semanticIds = semanticRanked.map { it.mediaId },
            lexicalIds = lexicalById.keys,
            eventIds = eventByMedia.keys,
        )
        val fused = HybridRankFusion.fuse(
            listOf(
                RankedChannel(1.0, lexicalRanked.map { it.item.id }),
                RankedChannel(0.85, semanticRanked.map { it.mediaId }),
                RankedChannel(0.80, captionRanked.map { it.mediaId }),
                RankedChannel(0.95, eventMediaRank),
            ),
        ).let { ranked -> refinementIds?.let { eligible -> ranked.filter { it.first in eligible } } ?: ranked }
        val fusedHits = fused.mapNotNull { (id, score) ->
            val lexical = lexicalById[id]
            val semantic = semanticById[id]
            val caption = captionById[id]
            val item = lexical?.item ?: itemById[id] ?: return@mapNotNull null
            val semanticEvidence = semantic?.let {
                val keyframe = bestSemanticKeyframeByMedia[item.id]
                EvidenceRecord(
                    id = "${item.id}:image_text_embedding:${plan.originalQuery.hashCode().toUInt()}",
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
                    text = "${event.title} (${event.startTime}..${event.endTime})",
                    confidence = event.confidence,
                    producerVersion = event.producerVersion,
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
                )
            }
            SearchHit(item, score, lexical.orEmptyEvidence() + listOfNotNull(semanticEvidence, captionEvidence, eventEvidence))
        }
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
        val channelReports = listOf(
            lexicalChannelReport,
            semanticChannelReport,
            captionChannelReport,
            eventChannelReport,
            ocrChannelReport,
            peopleChannelReport,
            visualChannelReport,
        )

        val matchCount = if (verification.applied) verified.size else ranked.size
        val deterministicAnswer = buildAnswer(plan, hits, allItems, matchCount, verification, channelReports)
        val requiresAuthentication = hits.any(SensitiveEvidencePolicy::requiresAuthentication)
        val answer = if (requiresAuthentication) {
            SensitiveEvidencePolicy.lock(deterministicAnswer)
        } else if (shouldComposeGroundedAnswer(plan, hits, verification)) {
            emit(QueryProgress.ComposingAnswer)
            groundedAnswerComposer.compose(GroundedAnswerInput(plan, hits, deterministicAnswer)).answer
        } else {
            deterministicAnswer
        }.copy(channelReports = channelReports)
        val outcome = finalizeOutcome(sessionId, parentResultSetId, SearchOutcome(
            plan = plan,
            hits = hits,
            answer = answer,
            elapsedMs = max(1, SystemClock.elapsedRealtime() - started),
            planPatch = planPatch,
            channelReports = channelReports,
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
                evidence += EvidenceRecord(
                    id = "${item.id}:ocr:${term.hashCode().toUInt()}",
                    mediaId = item.id,
                    sourceField = "ocr_text",
                    text = matchingBlock?.text ?: term,
                    confidence = matchingBlock?.confidence ?: .8f,
                    producerVersion = "mlkit-text-v2",
                    region = matchingBlock?.let { listOf(it.left, it.top, it.right, it.bottom) },
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
        id = "${item.id}:$source:${value.lowercase(Locale.ROOT).hashCode().toUInt()}",
        mediaId = item.id,
        sourceField = source,
        text = value,
        confidence = confidence,
    )

    private fun buildAnswer(
        plan: GalleryQueryPlan,
        hits: List<SearchHit>,
        allItems: List<GalleryItem>,
        matchCount: Int,
        verification: VerificationResult,
        channelReports: List<RetrievalChannelReport<SearchHit>>,
    ): SearchAnswer {
        val totalItems = allItems.size
        val readyItems = allItems.count { it.indexState == IndexState.READY }
        val semanticReport = channelReports.first { it.channel == RetrievalChannel.SEMANTIC }
        val usedSemanticRetrieval = semanticReport.status != ChannelStatus.NOT_REQUIRED
        val deterministicResultSetFilter = plan.baseResultIds != null && plan.terms.isEmpty() &&
            plan.semanticClauses.isEmpty() && plan.filter != FilterExpression.True && !verification.applied
        val deterministicAggregation = plan.intent in setOf(QueryIntent.COUNT, QueryIntent.SUM, QueryIntent.MIN_MAX) &&
            plan.aggregation != null && plan.semanticClauses.isEmpty() && !usedSemanticRetrieval && !verification.applied
        val exactness = RetrievalExactnessPolicy.resolve(
            allEligibleIndexed = readyItems == totalItems,
            deterministicOperation = deterministicAggregation || deterministicResultSetFilter,
            semanticReport = semanticReport,
            verificationApplied = verification.applied,
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
        if (hits.isEmpty()) {
            return SearchAnswer(
                headline = "No supported matches found",
                detail = if (verification.applied) {
                    "No bounded candidate was proven to satisfy every required visual condition. Failed or unverified candidates are never returned as matches."
                } else if (usedSemanticRetrieval) {
                    "The semantic channel was ${semanticReport.status.name.lowercase()} and searched " +
                        "${semanticReport.searchedCount} of ${semanticReport.eligibleCount} eligible local items. " +
                        "This is not a complete gallery predicate scan."
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
                eventsByMedia = database.eventsForMedia(hits.map { it.item.id }),
            ),
        )

        @Suppress("UNREACHABLE_CODE")
        val scope = if (plan.baseResultIds == null) "the local gallery" else "your previous result set"
        return when (plan.intent) {
            QueryIntent.COUNT -> SearchAnswer(
                headline = RetrievalAnswerWording.countHeadline(matchCount, usedSemanticRetrieval),
                detail = if (usedSemanticRetrieval) {
                    "This count comes from thresholded semantic retrieval over $scope; run a complete predicate scan when an exact visual count is required."
                } else {
                    "This is a complete scan of $scope using indexed metadata and deterministic local facts."
                },
                evidenceIds = evidenceIds,
                exactness = exactness,
                indexedEligibleCount = readyItems,
                totalEligibleCount = totalItems,
                warnings = warnings,
                channelReports = channelReports,
            )
            QueryIntent.EVENT_SUMMARY -> {
                val event = database.eventsForMedia(hits.map { it.item.id }).values
                    .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
                val locations = hits.map { it.item.location }.filter(String::isNotBlank).groupingBy { it }.eachCount().entries
                    .sortedByDescending { it.value }.take(3).joinToString { it.key }
                SearchAnswer(
                    headline = event?.let { "${it.title}: ${hits.size} related ${if (hits.size == 1) "memory" else "memories"}" }
                        ?: "Found ${hits.size} related ${if (hits.size == 1) "memory" else "memories"}",
                    detail = event?.let { "This local event runs from ${it.startTime} to ${it.endTime}${it.locationName?.let { name -> " near $name" }.orEmpty()}." }
                        ?: "The strongest evidence points to ${locations.ifBlank { "the indexed event" }}.",
                    evidenceIds = evidenceIds,
                    exactness = exactness,
                    indexedEligibleCount = readyItems,
                    totalEligibleCount = totalItems,
                    warnings = warnings,
                    channelReports = channelReports,
                )
            }
            QueryIntent.ANSWER_FACT -> {
                // Hits are already sorted by the plan (for example newest first). Never skip a newer
                // failed/ambiguous document and silently answer from an older one.
                val selection = DocumentAnswerSelector.select(hits)
                val fact = selection?.fact
                SearchAnswer(
                    headline = fact?.text ?: "I found the document, but not a reliable final total",
                    detail = if (fact != null) "The value comes from a locally recognized Total, Grand Total, or Amount Paid line." else "Open the evidence to inspect the local OCR. The app will not invent a number.",
                    evidenceIds = fact?.let { listOf(it.id) } ?: evidenceIds,
                    exactness = if (fact != null && selection.document.item.indexState == IndexState.READY) ResultExactness.EXACT else exactness,
                    indexedEligibleCount = readyItems,
                    totalEligibleCount = totalItems,
                    warnings = warnings,
                    channelReports = channelReports,
                )
            }
            else -> SearchAnswer(
                headline = "Found ${hits.size} ${if (hits.size == 1) "match" else "matches"}",
                detail = "Ranked from $scope. Open Why this answer? to inspect the exact sidecar fields used.",
                evidenceIds = evidenceIds,
                exactness = exactness,
                indexedEligibleCount = readyItems,
                totalEligibleCount = totalItems,
                warnings = warnings,
                channelReports = channelReports,
            )
        }
    }

    private fun SearchHit?.orEmptyEvidence(): List<EvidenceRecord> = this?.evidence.orEmpty()

    private fun formatTimestamp(timestampMs: Long): String {
        val totalSeconds = timestampMs.coerceAtLeast(0L) / 1_000L
        return "%d:%02d".format(Locale.ROOT, totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun shouldComposeGroundedAnswer(
        plan: GalleryQueryPlan,
        hits: List<SearchHit>,
        verification: VerificationResult,
    ): Boolean = hits.isNotEmpty() && services.modelPackManager.status().installed && (
        verification.applied || plan.intent in setOf(
            QueryIntent.COMPARE,
            QueryIntent.TIMELINE,
            QueryIntent.EVENT_SUMMARY,
        )
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

    private fun addDeterministicFactEvidence(hit: SearchHit, plan: GalleryQueryPlan): SearchHit {
        val requested = OcrFactAllowlist.resolve(plan.ocrClause?.requestedField ?: plan.aggregation?.field)
        val fields = when {
            requested != null -> listOf(requested)
            plan.intent in setOf(QueryIntent.SUM, QueryIntent.MIN_MAX) -> listOf(OcrFactAllowlist.fields.first())
            else -> OcrFactAllowlist.fields
        }
        val evidence = fields.mapNotNull { field ->
            val selected = database.ocrEntities(hit.item.id, field.type).firstOrNull() ?: return@mapNotNull null
            EvidenceRecord(
                id = "${hit.item.id}:${field.sourceField}:${selected.normalizedValue.hashCode().toUInt()}",
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
