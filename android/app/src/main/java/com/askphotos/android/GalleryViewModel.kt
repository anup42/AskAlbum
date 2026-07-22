package com.askphotos.android

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination { ONBOARDING, GALLERY, ASK, RESULTS, INDEX_MANAGER, PRIVACY }

enum class QueryExecutionStage { UNDERSTANDING, SEARCHING, INITIAL_RESULTS, VERIFYING, COMPOSING }

data class GalleryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val executionStatus: String? = null,
    val executionStage: QueryExecutionStage? = null,
    val progressivePlan: GalleryQueryPlan? = null,
    val progressiveHits: List<SearchHit> = emptyList(),
    val outcome: SearchOutcome? = null,
    val items: List<GalleryItem> = emptyList(),
    val index: IndexSummary = IndexSummary(),
    val selectedEvidence: SearchHit? = null,
    val destination: AppDestination = AppDestination.ASK,
    val operationMessage: String? = null,
    val modelPack: ModelPackStatus = ModelPackStatus(installed = false),
    val modelDownload: GemmaDownloadProgress = GemmaDownloadProgress(),
    val retrievalPack: RetrievalPackStatus = RetrievalPackStatus(installed = false),
    val ocrModel: OcrModelStatus = OcrModelStatus(),
    val ocrModelDownload: OcrModelDownloadProgress = OcrModelDownloadProgress(),
    val faceModel: FaceModelStatus = FaceModelStatus(),
    val faceModelDownload: FaceModelDownloadProgress = FaceModelDownloadProgress(),
    val peopleIndex: PeopleIndexStatus = PeopleIndexStatus(),
    val conversation: ConversationSearchState = ConversationSearchState(GalleryDatabase.PRIMARY_QUERY_SESSION),
)

private data class GalleryInitialization(
    val summary: IndexSummary,
    val items: List<GalleryItem>,
    val peopleIndex: PeopleIndexStatus,
    val conversation: ConversationSearchState,
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val askPhotosApplication = application as AskPhotosApplication
    private val repository = askPhotosApplication.repository
    private val modelPacks = askPhotosApplication.modelPackManager
    private val modelDownloader = askPhotosApplication.services.modelDownloader
    private val retrievalPacks = askPhotosApplication.services.retrievalModelPackManager
    private val ocrModelPacks = askPhotosApplication.services.ocrModelPackManager
    private val ocrModelDownloader = askPhotosApplication.services.ocrModelDownloader
    private val faceModelPacks = askPhotosApplication.services.faceModelPackManager
    private val faceModelDownloader = askPhotosApplication.services.faceModelDownloader
    private var modelMonitorJob: Job? = null
    private var ocrModelMonitorJob: Job? = null
    private var faceModelMonitorJob: Job? = null
    private var queryJob: Job? = null
    private var queryGeneration = 0L
    var state by mutableStateOf(GalleryUiState())
        private set

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val summary = repository.initialize()
                    GalleryInitialization(
                        summary,
                        repository.allItems(),
                        repository.peopleIndexStatus(),
                        repository.conversationState(),
                    )
                }
            }.onSuccess { initial ->
                state = state.copy(
                    loading = false,
                    index = initial.summary,
                    items = initial.items,
                    modelPack = modelPacks.status(),
                    modelDownload = modelDownloader.progress(modelPacks.selectedTier()),
                    retrievalPack = retrievalPacks.status(),
                    ocrModel = ocrModelPacks.status(),
                    ocrModelDownload = ocrModelDownloader.progress(),
                    faceModel = faceModelPacks.status(),
                    faceModelDownload = faceModelDownloader.progress(),
                    peopleIndex = initial.peopleIndex,
                    conversation = initial.conversation,
                )
                monitorIndexing()
                monitorModelDownload()
                monitorOcrModelDownload()
                monitorFaceModelDownload()
            }.onFailure { error ->
                state = state.copy(loading = false, error = error.message ?: "Could not open local gallery memory")
            }
        }
    }

    fun updateQuery(value: String) {
        state = state.copy(query = value)
    }

    fun navigate(destination: AppDestination) {
        state = state.copy(destination = destination)
    }

    fun importUris(uris: List<Uri>, source: MediaSource) {
        if (uris.isEmpty()) return
        state = state.copy(operationMessage = "Importing ${uris.size} selected items…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.importUris(uris, source) } }
                .onSuccess { count ->
                    reload("$count items queued for private on-device indexing")
                    monitorIndexing()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Import failed") }
        }
    }

    fun scanAccessibleGallery() {
        InitialImportService.start(getApplication())
        state = state.copy(operationMessage = "Private gallery import started")
        monitorIndexing()
    }

    fun scheduleIncrementalScan() {
        MediaScanScheduler.schedule(getApplication())
        monitorIndexing()
    }

    fun retryIndexing() {
        IndexScheduler.schedule(getApplication())
        state = state.copy(operationMessage = "Indexing resumed")
        monitorIndexing()
    }

    fun importModelPack(uri: Uri?) {
        if (uri == null) return
        state = state.copy(operationMessage = "Verifying the signed Gemma pack and device profile…")
        viewModelScope.launch {
            runCatching { modelPacks.import(uri) }
                .onSuccess { status -> state = state.copy(modelPack = status, operationMessage = "${status.name} is ready") }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Model import failed") }
        }
    }

    fun selectModelTier(tier: GemmaModelTier) {
        modelMonitorJob?.cancel()
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { modelPacks.selectTier(tier) }
            state = state.copy(
                modelPack = status,
                modelDownload = GemmaDownloadProgress(
                    tier = tier,
                    totalBytes = GemmaModelCatalog.require(tier).sizeBytes,
                ),
                operationMessage = null,
            )
            val progress = withContext(Dispatchers.IO) { modelDownloader.progress(tier) }
            if (state.modelPack.selectedTier == tier) {
                state = state.copy(modelDownload = progress)
                if (progress.state in ACTIVE_DOWNLOAD_STATES) monitorModelDownload()
            }
        }
    }

    fun downloadSelectedModel() {
        val tier = state.modelPack.selectedTier
        state = state.copy(operationMessage = "Preparing ${GemmaModelCatalog.require(tier).displayName} download…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { modelDownloader.enqueue(tier) } }
                .onSuccess {
                    state = state.copy(
                        modelDownload = GemmaDownloadProgress(
                            tier = tier,
                            state = GemmaDownloadState.QUEUED,
                            totalBytes = GemmaModelCatalog.require(tier).sizeBytes,
                        ),
                        operationMessage = "Download queued; the model stays in app-private storage",
                    )
                    monitorModelDownload()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Model download could not start") }
        }
    }

    fun cancelModelDownload() {
        val tier = state.modelPack.selectedTier
        modelDownloader.cancel(tier)
        state = state.copy(operationMessage = "${GemmaModelCatalog.require(tier).displayName} download cancelled")
    }

    fun importRetrievalPack(uri: Uri?) {
        if (uri == null) return
        state = state.copy(operationMessage = "Verifying the signed retrieval pack…")
        viewModelScope.launch {
            runCatching { retrievalPacks.import(uri) }
                .onSuccess { status ->
                    EmbeddingIndexScheduler.schedule(getApplication())
                    state = state.copy(
                        retrievalPack = status,
                        operationMessage = "${status.packId} ${status.packVersion} is ready",
                    )
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Retrieval-pack import failed") }
        }
    }

    fun importFaceModel(uri: Uri?) {
        if (uri == null) return
        state = state.copy(operationMessage = "Verifying the pinned OpenCV SFace model…")
        viewModelScope.launch {
            runCatching { faceModelPacks.import(uri) }
                .onSuccess { status ->
                    val people = withContext(Dispatchers.IO) { repository.onFaceModelInstalled() }
                    state = state.copy(
                        faceModel = status,
                        faceModelDownload = faceModelDownloader.progress(),
                        peopleIndex = people,
                        operationMessage = "OpenCV SFace is ready; local 128-dimensional face indexing is queued",
                    )
                    monitorIndexing()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "SFace import failed") }
        }
    }

    fun importOcrModel(uri: Uri?) {
        if (uri == null) return
        state = state.copy(operationMessage = "Verifying the pinned PaddleOCR multilingual pack...")
        viewModelScope.launch {
            runCatching { ocrModelPacks.importArchive(uri) }
                .onSuccess { status ->
                    val changed = withContext(Dispatchers.IO) { repository.onOcrModelInstalled() }
                    state = state.copy(
                        ocrModel = status,
                        ocrModelDownload = ocrModelDownloader.progress(),
                        operationMessage = "PaddleOCR is ready; $changed previously indexed items were queued for multilingual OCR",
                    )
                    monitorIndexing()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "PaddleOCR import failed") }
        }
    }

    fun downloadOcrModel() {
        state = state.copy(operationMessage = "Preparing the PaddleOCR multilingual download...")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { ocrModelDownloader.enqueue() } }
                .onSuccess {
                    state = state.copy(
                        ocrModelDownload = OcrModelDownloadProgress(state = GemmaDownloadState.QUEUED),
                        operationMessage = "PaddleOCR download queued; all files will remain in app-private storage",
                    )
                    monitorOcrModelDownload()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "PaddleOCR download could not start") }
        }
    }

    fun cancelOcrModelDownload() {
        ocrModelDownloader.cancel()
        state = state.copy(operationMessage = "PaddleOCR download cancelled")
    }

    fun downloadFaceModel() {
        state = state.copy(operationMessage = "Preparing the OpenCV SFace download…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { faceModelDownloader.enqueue() } }
                .onSuccess {
                    state = state.copy(
                        faceModelDownload = FaceModelDownloadProgress(state = GemmaDownloadState.QUEUED),
                        operationMessage = "SFace download queued; the model will stay in app-private storage",
                    )
                    monitorFaceModelDownload()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "SFace download could not start") }
        }
    }

    fun cancelFaceModelDownload() {
        faceModelDownloader.cancel()
        state = state.copy(operationMessage = "SFace download cancelled")
    }

    fun clearOperationMessage() {
        state = state.copy(operationMessage = null)
    }

    fun enablePeopleIndexing() {
        if (state.peopleIndex.enabled) return
        state = state.copy(operationMessage = "Enabling private on-device face detection…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.enablePeopleIndexing() } }
                .onSuccess { status ->
                    state = state.copy(
                        peopleIndex = status,
                        operationMessage = "People indexing enabled; identity search remains unavailable until you review local clusters",
                    )
                    monitorIndexing()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "People indexing could not be enabled") }
        }
    }

    fun resetPeopleIndex() {
        state = state.copy(operationMessage = "Stopping people indexing and deleting derived face data…")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.resetPeopleIndex() } }
                .onSuccess { status ->
                    state = state.copy(
                        peopleIndex = status,
                        operationMessage = "People index reset; all face boxes, clusters, labels, and aliases were deleted",
                    )
                    reload()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "People index reset failed") }
        }
    }

    fun ask(query: String = state.query) {
        if (query.isBlank() || queryJob?.isActive == true) return
        val generation = ++queryGeneration
        state = state.copy(
            query = query,
            executionStatus = "Understanding your question…",
            executionStage = QueryExecutionStage.UNDERSTANDING,
            progressivePlan = null,
            progressiveHits = emptyList(),
            selectedEvidence = null,
            operationMessage = null,
            error = null,
        )
        queryJob = viewModelScope.launch {
            try {
                repository.searchProgressive(query, sessionId = GalleryDatabase.PRIMARY_QUERY_SESSION)
                    .flowOn(Dispatchers.Default)
                    .collect { progress ->
                        if (generation == queryGeneration) state = QueryProgressUiReducer.reduce(state, progress)
                    }
            } catch (cancelled: CancellationException) {
                if (generation == queryGeneration) {
                    state = state.copy(
                        executionStatus = null,
                        executionStage = null,
                        progressivePlan = null,
                        progressiveHits = emptyList(),
                        operationMessage = "Query cancelled; no partial answer was saved",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                if (generation == queryGeneration) {
                    state = state.copy(
                        executionStatus = null,
                        executionStage = null,
                        progressivePlan = null,
                        progressiveHits = emptyList(),
                        error = error.message ?: "Search failed",
                    )
                }
            } finally {
                if (generation == queryGeneration) queryJob = null
            }
        }
    }

    fun cancelQuery() {
        val active = queryJob?.takeIf { it.isActive } ?: return
        queryGeneration++
        queryJob = null
        state = state.copy(
            executionStatus = null,
            executionStage = null,
            progressivePlan = null,
            progressiveHits = emptyList(),
            operationMessage = "Query cancelled; no partial answer was saved",
        )
        active.cancel(CancellationException("Cancelled by the user"))
    }

    fun showEvidence(hit: SearchHit) {
        state = state.copy(selectedEvidence = hit)
    }

    fun dismissEvidence() {
        state = state.copy(selectedEvidence = null)
    }

    private suspend fun reload(message: String? = state.operationMessage) {
        val (summary, items, peopleIndex) = withContext(Dispatchers.IO) {
            Triple(repository.indexSummary(), repository.allItems(), repository.peopleIndexStatus())
        }
        state = state.copy(index = summary, items = items, peopleIndex = peopleIndex, operationMessage = message)
    }

    private fun monitorIndexing() {
        viewModelScope.launch {
            repeat(90) {
                delay(1_000)
                reload()
                if (state.index.pending == 0 && state.peopleIndex.pendingMediaCount == 0) return@launch
            }
        }
    }

    private fun monitorModelDownload() {
        modelMonitorJob?.cancel()
        modelMonitorJob = viewModelScope.launch {
            repeat(7_200) {
                val tier = state.modelPack.selectedTier
                val progress = withContext(Dispatchers.IO) { modelDownloader.progress(tier) }
                val modelStatus = if (progress.state == GemmaDownloadState.INSTALLED) {
                    withContext(Dispatchers.IO) { modelPacks.status() }
                } else state.modelPack
                state = state.copy(modelPack = modelStatus, modelDownload = progress)
                if (progress.state !in ACTIVE_DOWNLOAD_STATES) return@launch
                delay(1_000)
            }
        }
    }

    private fun monitorFaceModelDownload() {
        faceModelMonitorJob?.cancel()
        faceModelMonitorJob = viewModelScope.launch {
            repeat(7_200) {
                val progress = withContext(Dispatchers.IO) { faceModelDownloader.progress() }
                val wasInstalled = state.faceModel.installed
                val status = if (progress.state == GemmaDownloadState.INSTALLED) {
                    withContext(Dispatchers.IO) { faceModelPacks.status() }
                } else state.faceModel
                var people = state.peopleIndex
                if (!wasInstalled && status.installed) {
                    people = withContext(Dispatchers.IO) { repository.onFaceModelInstalled() }
                    monitorIndexing()
                }
                state = state.copy(faceModel = status, faceModelDownload = progress, peopleIndex = people)
                if (progress.state !in ACTIVE_DOWNLOAD_STATES) return@launch
                delay(1_000)
            }
        }
    }

    private fun monitorOcrModelDownload() {
        ocrModelMonitorJob?.cancel()
        ocrModelMonitorJob = viewModelScope.launch {
            repeat(7_200) {
                val progress = withContext(Dispatchers.IO) { ocrModelDownloader.progress() }
                val wasInstalled = state.ocrModel.installed
                val status = if (progress.state == GemmaDownloadState.INSTALLED) {
                    withContext(Dispatchers.IO) { ocrModelPacks.status() }
                } else state.ocrModel
                if (!wasInstalled && status.installed) {
                    withContext(Dispatchers.IO) { repository.onOcrModelInstalled() }
                    monitorIndexing()
                }
                state = state.copy(ocrModel = status, ocrModelDownload = progress)
                if (progress.state !in ACTIVE_DOWNLOAD_STATES) return@launch
                delay(1_000)
            }
        }
    }

    private companion object {
        val ACTIVE_DOWNLOAD_STATES = setOf(
            GemmaDownloadState.QUEUED,
            GemmaDownloadState.DOWNLOADING,
            GemmaDownloadState.VERIFYING,
        )
    }
}

internal object QueryProgressUiReducer {
    fun reduce(state: GalleryUiState, progress: QueryProgress): GalleryUiState = when (progress) {
        QueryProgress.Understanding -> state.copy(
            executionStatus = "Understanding your question…",
            executionStage = QueryExecutionStage.UNDERSTANDING,
        )
        is QueryProgress.PlanReady -> state.copy(
            executionStatus = "Searching indexed evidence on this phone…",
            executionStage = QueryExecutionStage.SEARCHING,
            progressivePlan = progress.plan,
        )
        is QueryProgress.InitialResults -> state.copy(
            executionStatus = "Found ${progress.hits.size} possible ${if (progress.hits.size == 1) "match" else "matches"}",
            executionStage = QueryExecutionStage.INITIAL_RESULTS,
            progressivePlan = progress.plan,
            progressiveHits = progress.hits,
        )
        is QueryProgress.Verifying -> state.copy(
            executionStatus = "Checking ${progress.candidateCount} likely ${if (progress.candidateCount == 1) "match" else "matches"} with Gemma…",
            executionStage = QueryExecutionStage.VERIFYING,
        )
        QueryProgress.ComposingAnswer -> state.copy(
            executionStatus = "Composing an evidence-grounded answer…",
            executionStage = QueryExecutionStage.COMPOSING,
        )
        is QueryProgress.Completed -> state.copy(
            outcome = progress.outcome,
            conversation = state.conversation.copy(
                activeResultSetId = progress.outcome.resultSetId,
                activeResultIds = progress.outcome.hits.map { it.item.id }.toSet(),
                lastQuery = progress.outcome.plan.originalQuery,
                referencedPeople = progress.outcome.plan.peopleClauses.map { it.personId }.toSet(),
                currentTimeScope = progress.outcome.plan.filter.firstTimeRange(),
                currentPlaceScope = setOfNotNull(progress.outcome.plan.place),
                grouping = progress.outcome.plan.grouping,
                lastEvidenceIds = progress.outcome.answer.evidenceIds,
            ),
            executionStatus = null,
            executionStage = null,
            progressivePlan = null,
            progressiveHits = emptyList(),
            destination = AppDestination.RESULTS,
        )
    }

    private fun FilterExpression.firstTimeRange(): FilterExpression.TimeRange? = when (this) {
        is FilterExpression.TimeRange -> this
        is FilterExpression.And -> clauses.firstNotNullOfOrNull { it.firstTimeRange() }
        else -> null
    }
}
