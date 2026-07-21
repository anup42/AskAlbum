package com.askphotos.android

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.single
import kotlin.math.max

class GalleryRepository(context: Context) {
    private val appContext = context.applicationContext
    private val database = GalleryDatabase(context.applicationContext)
    private val services = (context.applicationContext as AskPhotosApplication).services
    private val planner = LiteRtLmQueryPlanner(services.modelPackManager, services.inferenceResources)
    private val semanticVectors = services.semanticVectorStore
    private val visualVerifier = services.visualVerifier
    private val groundedAnswerComposer = services.groundedAnswerComposer
    private val importer = MediaImporter(context.applicationContext)
    private val planPatchResolver = ResultSetPlanPatchResolver()

    fun initialize(): IndexSummary {
        database.recoverInterruptedJobs()
        database.seedDemoIfEmpty()
        database.ensureStageRows()
        if (database.pendingItems(1).isNotEmpty()) IndexScheduler.schedule(appContext)
        if (services.retrievalModelPackManager.status().installed) EmbeddingIndexScheduler.schedule(appContext)
        if (database.peopleIndexStatus().enabled) PeopleIndexScheduler.schedule(appContext)
        return database.summary()
    }

    fun allItems(): List<GalleryItem> = database.allItems()

    fun indexSummary(): IndexSummary = database.summary()

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
    fun peopleIndexStatus(): PeopleIndexStatus = database.peopleIndexStatus()

    fun enablePeopleIndexing(): PeopleIndexStatus = database
        .enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        .also { PeopleIndexScheduler.schedule(appContext) }

    fun resetPeopleIndex(): PeopleIndexStatus {
        PeopleIndexScheduler.cancelAndWait(appContext)
        return database.resetPeopleIndex()
    }

    fun saveReviewedPersonCluster(id: String, label: String, relationship: String?, aliases: List<String>): PeopleIndexStatus =
        database.saveReviewedPersonCluster(id, label, relationship, aliases)

    fun pendingItems(limit: Int): List<GalleryItem> = database.pendingItems(limit)
    fun facePendingItems(limit: Int): List<GalleryItem> = database.facePendingItems(limit)
    fun markFaces(mediaId: String) = database.markFaces(mediaId)
    fun completeFaces(mediaId: String, detections: List<FaceDetectionRecord>, producerVersion: String) =
        database.completeFaces(mediaId, detections, producerVersion)
    fun failFaces(mediaId: String, message: String, permanent: Boolean) = database.failFaces(mediaId, message, permanent)
    fun embeddingPendingItems(producerVersion: String, limit: Int): List<GalleryItem> =
        database.embeddingPendingItems(producerVersion, limit)
    fun accessibleIds(): Set<String> = database.accessibleIds()
    fun markEmbedding(id: String, producerVersion: String) = database.markEmbedding(id, producerVersion)
    fun completeEmbedding(id: String, producerVersion: String) = database.completeEmbedding(id, producerVersion)
    fun failEmbedding(id: String, producerVersion: String, message: String, permanent: Boolean) =
        database.failEmbedding(id, producerVersion, message, permanent)
    fun recoverInterruptedJobs() = database.recoverInterruptedJobs()
    fun markIndexing(id: String) = database.markIndexing(id)
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
        visualFeatures: VisualFeatures,
    ) = database.completeIndex(id, labels, description, ocrText, faceCount, previewPath, blocks, entities, ocrAttempted, visualFeatures)
    fun failIndex(id: String, message: String, permanent: Boolean) = database.failIndex(id, message, permanent)
    fun rebuildEvents() = database.rebuildEvents()
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
    ): SearchOutcome = searchProgressive(query, sessionId = sessionId)
        .filterIsInstance<QueryProgress.Completed>().single().outcome

    fun searchProgressive(
        query: String,
        activeResultIds: Set<String>? = null,
        sessionId: String? = null,
    ): Flow<QueryProgress> = flow {
        val started = SystemClock.elapsedRealtime()
        val conversation = sessionId?.let(database::conversationState)
        val isFollowUp = FollowUpLanguage.isFollowUp(query)
        val scopedIds = if (conversation != null && isFollowUp) conversation.activeResultIds else activeResultIds
        val parentResultSetId = if (conversation != null && isFollowUp) conversation.activeResultSetId else null
        emit(QueryProgress.Understanding)
        val compiledPlan = planner.compile(query, scopedIds)
        val (planPatch, plan) = if (conversation != null && isFollowUp) {
            planPatchResolver.createAndApply(compiledPlan, conversation).let { it.first to it.second }
        } else {
            null to compiledPlan
        }
        emit(QueryProgress.PlanReady(plan))
        val peopleStatus = database.peopleIndexStatus()
        val peopleUnavailable = PeopleQueryGate.unavailableReason(plan, peopleStatus)
        if (peopleUnavailable != null) {
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
                ),
                elapsedMs = max(1, SystemClock.elapsedRealtime() - started),
                planPatch = planPatch,
            ))
            emit(QueryProgress.Completed(outcome))
            return@flow
        }
        val allowed = plan.baseResultIds
        val terms = plan.terms
        val allItems = database.allItems().filter { item ->
            val inScope = when (plan.mediaScope) {
                MediaScope.ALL -> true
                MediaScope.IMAGES -> item.kind == MediaKind.IMAGE
                MediaScope.VIDEOS -> item.kind == MediaKind.VIDEO
                MediaScope.DOCUMENTS -> item.kind == MediaKind.PDF || item.ocrText.isNotBlank() || item.looksLikeDocument()
            }
            inScope && GalleryFilterEvaluator.matches(item, plan.filter) && item.matchesRequiredMerchant(plan.ocrClause?.merchant)
        }
        val fullTextIds = database.fullTextMatches(terms)
        val lexicalRanked = allItems
            .asSequence()
            .filter { allowed == null || it.id in allowed }
            .mapNotNull { item -> score(item, terms, item.id in fullTextIds) }
            .sortedWith(compareByDescending<SearchHit> { it.score }.thenBy { it.item.title })
            .toList()
        val semanticRanked = runCatching {
            semanticVectors.searchText(plan.originalQuery, topK = plan.limit.coerceIn(20, 100), allowedIds = allowed)
        }.getOrDefault(emptyList())
        val lexicalById = lexicalRanked.associateBy { it.item.id }
        val semanticById = semanticRanked.associateBy { it.mediaId }
        val itemById = allItems.associateBy { it.id }
        val fused = HybridRankFusion.fuse(
            listOf(
                RankedChannel(1.0, lexicalRanked.map { it.item.id }),
                RankedChannel(0.85, semanticRanked.map { it.mediaId }),
            ),
        )
        val fusedHits = fused.mapNotNull { (id, score) ->
            val lexical = lexicalById[id]
            val semantic = semanticById[id]
            val item = lexical?.item ?: itemById[id] ?: return@mapNotNull null
            val semanticEvidence = semantic?.let {
                EvidenceRecord(
                    id = "${item.id}:image_text_embedding:${plan.originalQuery.hashCode().toUInt()}",
                    mediaId = item.id,
                    sourceField = "image_text_embedding",
                    text = "Local image-text similarity",
                    confidence = ((it.score + 1f) / 2f).coerceIn(0f, 1f),
                    producerVersion = semanticVectors.producerVersion() ?: "unknown-retrieval-pack",
                )
            }
            SearchHit(item, score, lexical.orEmptyEvidence() + listOfNotNull(semanticEvidence))
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
        val enriched = if (plan.intent == QueryIntent.ANSWER_FACT) diverse.map(::addDeterministicFactEvidence) else diverse
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

        val matchCount = if (verification.applied) verified.size else ranked.size
        val deterministicAnswer = buildAnswer(plan, hits, allItems, matchCount, verification)
        val answer = if (shouldComposeGroundedAnswer(plan, hits, verification)) {
            emit(QueryProgress.ComposingAnswer)
            groundedAnswerComposer.compose(GroundedAnswerInput(plan, hits, deterministicAnswer)).answer
        } else {
            deterministicAnswer
        }
        val outcome = finalizeOutcome(sessionId, parentResultSetId, SearchOutcome(
            plan = plan,
            hits = hits,
            answer = answer,
            elapsedMs = max(1, SystemClock.elapsedRealtime() - started),
            planPatch = planPatch,
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
    ): SearchAnswer {
        val totalItems = allItems.size
        val readyItems = allItems.count { it.indexState == IndexState.READY }
        val usedSemanticRetrieval = hits.any { hit -> hit.evidence.any { it.sourceField == "image_text_embedding" } }
        val exactness = when {
            readyItems < totalItems -> ResultExactness.PARTIAL_INDEX
            usedSemanticRetrieval || verification.applied -> ResultExactness.ESTIMATED_FROM_RETRIEVAL
            else -> ResultExactness.COMPLETE_MODEL_SCAN
        }
        val warnings = buildList {
            if (verification.failures.isNotEmpty()) {
                add("Visual verification had ${verification.failures.size} bounded failure(s); no failed candidate was accepted.")
            }
            verification.trace?.fallbackReason?.let { add("Visual verification unavailable: $it") }
        }.distinct()
        val evidenceIds = hits.flatMap { it.evidence }.map { it.id }.distinct().take(12)
        if (hits.isEmpty()) {
            return SearchAnswer(
                headline = "No supported matches found",
                detail = if (verification.applied) {
                    "No bounded candidate was proven to satisfy every required visual condition. Failed or unverified candidates are never returned as matches."
                } else {
                    "All ${if (plan.baseResultIds == null) totalItems else plan.baseResultIds.size} eligible local items were checked. ${if (readyItems < totalItems) "Some items are still indexing." else "Try a place, object, OCR word, or scene."}"
                },
                evidenceIds = emptyList(),
                exactness = exactness,
                indexedEligibleCount = readyItems,
                totalEligibleCount = totalItems,
                warnings = warnings,
            )
        }

        val scope = if (plan.baseResultIds == null) "the local gallery" else "your previous result set"
        return when (plan.intent) {
            QueryIntent.COUNT -> SearchAnswer(
                headline = "$matchCount matching ${if (matchCount == 1) "item" else "items"}",
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
            )
            QueryIntent.EVENT_SUMMARY -> {
                val locations = hits.groupingBy { it.item.location }.eachCount().entries
                    .sortedByDescending { it.value }.take(3).joinToString { it.key }
                SearchAnswer(
                    headline = "Found ${hits.size} related ${if (hits.size == 1) "memory" else "memories"}",
                    detail = "The strongest evidence points to $locations.",
                    evidenceIds = evidenceIds,
                    exactness = exactness,
                    indexedEligibleCount = readyItems,
                    totalEligibleCount = totalItems,
                    warnings = warnings,
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
            )
        }
    }

    private fun SearchHit?.orEmptyEvidence(): List<EvidenceRecord> = this?.evidence.orEmpty()

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
        val indexedText = buildString {
            append(title).append(' ').append(description).append(' ').append(ocrText).append(' ')
            append(tags.joinToString(" "))
        }.lowercase(Locale.ROOT)
        return required in indexedText
    }

    private fun addDeterministicFactEvidence(hit: SearchHit): SearchHit {
        val selected = database.ocrEntities(hit.item.id, OcrEntityType.RECEIPT_TOTAL).firstOrNull() ?: return hit
        val evidence = EvidenceRecord(
            id = "${hit.item.id}:document_total:${selected.normalizedValue.hashCode().toUInt()}",
            mediaId = hit.item.id,
            sourceField = "document_total",
            text = selected.rawText,
            confidence = selected.confidence,
            producerVersion = selected.producerVersion,
            region = listOf(selected.left, selected.top, selected.right, selected.bottom),
        )
        return hit.copy(evidence = listOf(evidence) + hit.evidence)
    }
}
