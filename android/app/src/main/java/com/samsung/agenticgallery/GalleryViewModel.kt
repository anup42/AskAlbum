package com.samsung.agenticgallery

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

enum class AppDestination { ONBOARDING, GALLERY, ALBUMS, ASK, RESULTS, MENU, INDEX_MANAGER, SEMANTIC_MEMORY, EVENTS_INDEX, PRIVACY, PEOPLE }

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
    val selectedEvidenceMetadata: IndexedMediaMetadata? = null,
    val selectedEvidenceMetadataLoading: Boolean = false,
    val selectedEvidenceMetadataError: String? = null,
    val selectedEvidenceMetadataUnlocked: Boolean = false,
    val destination: AppDestination = AppDestination.GALLERY,
    val indexingActive: Boolean = false,
    val indexingRunCriteria: IndexingRunCriteria = IndexingRunCriteria(),
    val indexingAdmission: BackgroundWorkAdmission = BackgroundWorkAdmission(true, 0, null),
    val indexingJobControls: IndexingJobControls = IndexingJobControls(),
    val indexingPipelines: Map<IndexingJob, IndexingPipelineSnapshot> = emptyMap(),
    val operationMessage: String? = null,
    val semanticMemory: SemanticMemoryProgress = SemanticMemoryProgress(),
    val semanticMemoryPlanning: Boolean = false,
    val semanticMemoryMedia: List<SemanticMemoryMedia> = emptyList(),
    val semanticMemoryMediaLoading: Boolean = false,
    val semanticMemoryMediaError: String? = null,
    val eventInspections: List<EventInspection> = emptyList(),
    val eventInspectionsLoading: Boolean = false,
    val eventInspectionsError: String? = null,
    val modelPack: ModelPackStatus = ModelPackStatus(installed = false),
    val modelDownload: GemmaDownloadProgress = GemmaDownloadProgress(),
    val retrievalPack: RetrievalPackStatus = RetrievalPackStatus(installed = false),
    val retrievalProvision: RetrievalProvisionProgress = RetrievalProvisionProgress(),
    val ocrModel: OcrModelStatus = OcrModelStatus(),
    val ocrModelDownload: OcrModelDownloadProgress = OcrModelDownloadProgress(),
    val faceModel: FaceModelStatus = FaceModelStatus(),
    val faceModelDownload: FaceModelDownloadProgress = FaceModelDownloadProgress(),
    val peopleIndex: PeopleIndexStatus = PeopleIndexStatus(),
    val peopleReviewClusters: List<PersonClusterReviewItem> = emptyList(),
    val selectedPeopleClusterId: String? = null,
    val selectedPeopleClusterFaces: List<PersonFaceReviewItem> = emptyList(),
    val peopleClusterFaceOffset: Int = 0,
    val peopleClusterFacesLoading: Boolean = false,
    val conversation: ConversationSearchState = ConversationSearchState(GalleryDatabase.PRIMARY_QUERY_SESSION),
)

private data class GalleryInitialization(
    val summary: IndexSummary,
    val items: List<GalleryItem>,
    val peopleIndex: PeopleIndexStatus,
    val conversation: ConversationSearchState,
    val semanticMemory: SemanticMemoryProgress,
    val indexingRunCriteria: IndexingRunCriteria,
    val indexingAdmission: BackgroundWorkAdmission,
    val indexingJobControls: IndexingJobControls,
)

private data class ModelInitialization(
    val modelPack: ModelPackStatus,
    val modelDownload: GemmaDownloadProgress,
    val retrievalPack: RetrievalPackStatus,
    val retrievalProvision: RetrievalProvisionProgress,
    val ocrModel: OcrModelStatus,
    val ocrModelDownload: OcrModelDownloadProgress,
    val faceModel: FaceModelStatus,
    val faceModelDownload: FaceModelDownloadProgress,
)

internal fun automaticGemmaCandidates(status: ModelPackStatus): List<GemmaModelTier> {
    if (status.installed) return emptyList()
    val recommended = status.deviceAssessment?.recommendedTier ?: GemmaModelTier.E2B
    return listOf(recommended, GemmaModelTier.E2B).distinct()
}
class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val askPhotosApplication = application as AgenticGalleryApplication
    private val repository = askPhotosApplication.repository
    private val modelPacks = askPhotosApplication.modelPackManager
    private val modelDownloader = askPhotosApplication.services.modelDownloader
    private val retrievalPacks = askPhotosApplication.services.retrievalModelPackManager
    private val retrievalProvisioner = askPhotosApplication.services.embeddedRetrievalModelProvisioner
    private val ocrModelPacks = askPhotosApplication.services.ocrModelPackManager
    private val ocrModelDownloader = askPhotosApplication.services.ocrModelDownloader
    private val faceModelPacks = askPhotosApplication.services.faceModelPackManager
    private val faceModelDownloader = askPhotosApplication.services.faceModelDownloader
    private val faceVectorStore = askPhotosApplication.services.faceVectorStore
    private val reviewedIdentityExpander = ReviewedIdentityClusterExpander(askPhotosApplication)
    private val destinationHistory = ArrayDeque<AppDestination>()
    private val embeddedFaceModelProvisioner = askPhotosApplication.services.embeddedFaceModelProvisioner
    private var modelMonitorJob: Job? = null
    private var retrievalProvisionMonitorJob: Job? = null
    private var ocrModelMonitorJob: Job? = null
    private var faceModelMonitorJob: Job? = null
    private var queryJob: Job? = null
    private var indexMonitorJob: Job? = null
    private var semanticMemoryMonitorJob: Job? = null
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
                        repository.semanticMemoryProgress(),
                        repository.indexingRunCriteria(),
                        repository.indexingAdmission(),
                        repository.indexingJobControls(),
                    )
                }
            }.onSuccess { initial ->
                state = state.copy(
                    loading = false,
                    index = initial.summary,
                    items = initial.items,
                    peopleIndex = initial.peopleIndex,
                    conversation = initial.conversation,
                    semanticMemory = initial.semanticMemory,
                    indexingRunCriteria = initial.indexingRunCriteria,
                    indexingAdmission = initial.indexingAdmission,
                    indexingJobControls = initial.indexingJobControls,
                )
                if (initial.peopleIndex.enabled) loadPeopleReviewClusters()
                monitorIndexing()
                loadModelInitialization()
            }.onFailure { error ->
                state = state.copy(loading = false, error = error.message ?: "Could not open local gallery memory")
            }
        }
    }

    private fun loadModelInitialization() {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val modelPack = modelPacks.status()
                    ModelInitialization(
                        modelPack = modelPack,
                        modelDownload = modelDownloader.progress(modelPack.tier ?: modelPack.selectedTier),
                        retrievalPack = retrievalPacks.status(),
                        retrievalProvision = retrievalProvisioner.progress(),
                        ocrModel = ocrModelPacks.status(),
                        ocrModelDownload = ocrModelDownloader.progress(),
                        faceModel = faceModelPacks.status(),
                        faceModelDownload = currentFaceModelProgress(),
                    )
                }
            }.onSuccess { models ->
                state = state.copy(
                    modelPack = models.modelPack,
                    modelDownload = models.modelDownload,
                    retrievalPack = models.retrievalPack,
                    retrievalProvision = models.retrievalProvision,
                    ocrModel = models.ocrModel,
                    ocrModelDownload = models.ocrModelDownload,
                    faceModel = models.faceModel,
                    faceModelDownload = models.faceModelDownload,
                )
                monitorModelDownload()
                monitorRetrievalProvision()
                monitorOcrModelDownload()
                monitorFaceModelDownload()
            }
        }
    }

    fun updateQuery(value: String) {
        state = state.copy(query = value)
    }

    fun navigate(destination: AppDestination) {
        if (destination == state.destination) return
        destinationHistory.addLast(state.destination)
        setDestination(destination)
    }

    fun selectPrimaryDestination(destination: AppDestination) {
        destinationHistory.clear()
        setDestination(destination)
    }

    fun canNavigateBack(): Boolean =
        state.selectedPeopleClusterId != null ||
            destinationHistory.isNotEmpty() ||
            state.destination in setOf(
                AppDestination.RESULTS,
                AppDestination.PEOPLE,
                AppDestination.PRIVACY,
                AppDestination.INDEX_MANAGER,
                AppDestination.SEMANTIC_MEMORY,
                AppDestination.EVENTS_INDEX,
            )

    fun navigateBack() {
        if (state.selectedPeopleClusterId != null) {
            closePersonCluster()
            return
        }
        val previous = destinationHistory.removeLastOrNull() ?: when (state.destination) {
            AppDestination.RESULTS -> AppDestination.ASK
            AppDestination.SEMANTIC_MEMORY -> AppDestination.INDEX_MANAGER
            AppDestination.EVENTS_INDEX -> AppDestination.INDEX_MANAGER
            AppDestination.PEOPLE, AppDestination.PRIVACY, AppDestination.INDEX_MANAGER -> AppDestination.MENU
            else -> null
        }
        previous?.let(::setDestination)
    }

    private fun setDestination(destination: AppDestination) {
        state = if (destination == AppDestination.PEOPLE) {
            state.copy(destination = destination)
        } else {
            state.copy(
                destination = destination,
                selectedPeopleClusterId = null,
                selectedPeopleClusterFaces = emptyList(),
                peopleClusterFaceOffset = 0,
                peopleClusterFacesLoading = false,
            )
        }
        if (destination == AppDestination.PEOPLE) {
            loadPeopleReviewClusters()
        }
        if (destination == AppDestination.SEMANTIC_MEMORY) {
            loadSemanticMemoryMedia()
        }
        if (destination == AppDestination.EVENTS_INDEX) {
            loadEventInspections()
        }
        if (destination == AppDestination.INDEX_MANAGER || destination == AppDestination.SEMANTIC_MEMORY) {
            monitorSemanticMemory()
        } else {
            semanticMemoryMonitorJob?.cancel()
        }
    }

    private fun loadSemanticMemoryMedia() {
        state = state.copy(
            semanticMemoryMediaLoading = true,
            semanticMemoryMediaError = null,
        )
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.semanticMemoryMedia() } }
                .onSuccess { media ->
                    state = state.copy(
                        semanticMemoryMedia = media,
                        semanticMemoryMediaLoading = false,
                    )
                }
                .onFailure { error ->
                    state = state.copy(
                        semanticMemoryMediaLoading = false,
                        semanticMemoryMediaError = error.message ?: "Could not load cached Gemma facts",
                    )
                }
        }
    }

    private fun loadEventInspections() {
        state = state.copy(
            eventInspectionsLoading = true,
            eventInspectionsError = null,
        )
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.eventInspections() } }
                .onSuccess { events ->
                    state = state.copy(
                        eventInspections = events,
                        eventInspectionsLoading = false,
                    )
                }
                .onFailure { error ->
                    state = state.copy(
                        eventInspectionsLoading = false,
                        eventInspectionsError = error.message ?: "Could not load stored events",
                    )
                }
        }
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
        if (state.indexingActive) return
        state = state.copy(indexingActive = true, operationMessage = "Restarting local indexing...")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.recoverInterruptedJobs()
                    IndexScheduler.restart(getApplication())
                    if (retrievalPacks.status().installed) EmbeddingIndexScheduler.restart(getApplication())
                    if (repository.peopleIndexStatus().enabled) PeopleIndexScheduler.restart(getApplication())
                    repository.indexingAdmission()
                }
            }.onSuccess { admission ->
                state = state.copy(
                    indexingAdmission = admission,
                    indexingActive = admission.allowed,
                    operationMessage = if (admission.allowed) {
                        "Indexing in progress"
                    } else {
                        "Indexing paused: ${admission.reason}"
                    },
                )
                monitorIndexing()
            }.onFailure { error ->
                state = state.copy(
                    indexingActive = false,
                    operationMessage = error.message ?: "Indexing could not be restarted",
                )
            }
        }
    }

    fun saveIndexingRunCriteria(criteria: IndexingRunCriteria) {
        state = state.copy(operationMessage = "Applying indexing stop and resume criteria...")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val saved = repository.saveIndexingRunCriteria(criteria)
                    repository.recoverInterruptedJobs()
                    IndexScheduler.restart(getApplication())
                    if (retrievalPacks.status().installed) EmbeddingIndexScheduler.restart(getApplication())
                    if (repository.peopleIndexStatus().enabled) PeopleIndexScheduler.restart(getApplication())
                    Triple(saved, repository.indexingAdmission(), repository.indexSummary())
                }
            }.onSuccess { (saved, admission, summary) ->
                state = state.copy(
                    indexingRunCriteria = saved,
                    indexingAdmission = admission,
                    index = summary,
                    indexingActive = admission.allowed && hasRunnableIndexing(
                        summary,
                        state.peopleIndex,
                        state.indexingJobControls,
                        state.retrievalPack.installed,
                    ),
                    operationMessage = if (admission.allowed) {
                        "Indexing criteria saved. Pending indexing can run now."
                    } else {
                        "Indexing criteria saved. Paused: ${admission.reason}"
                    },
                )
                monitorIndexing()
            }.onFailure { error ->
                state = state.copy(operationMessage = error.message ?: "Could not save indexing criteria")
            }
        }
    }

    fun setIndexingJobEnabled(job: IndexingJob, enabled: Boolean) {
        if (job == IndexingJob.PEOPLE && enabled && !state.peopleIndex.enabled) {
            state = state.copy(operationMessage = "Enable face indexing in People before starting the People worker")
            return
        }
        if (job == IndexingJob.EMBEDDINGS && enabled && !state.retrievalPack.installed) {
            state = state.copy(operationMessage = "The verified retrieval model is required before starting embeddings")
            return
        }
        if (job == IndexingJob.SEMANTIC_MEMORY && enabled && (!state.modelPack.installed || !state.modelPack.multimodal)) {
            state = state.copy(operationMessage = "A verified multimodal Gemma model is required before starting semantic memory")
            return
        }
        state = state.copy(operationMessage = "${if (enabled) "Starting" else "Stopping"} ${indexingJobLabel(job)}...")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val controls = repository.setIndexingJobEnabled(job, enabled)
                    when (job) {
                        IndexingJob.MEDIA_ANALYSIS -> {
                            if (enabled) InitialImportService.startIndexing(getApplication())
                            else IndexScheduler.cancelAndWait(getApplication())
                        }
                        IndexingJob.EMBEDDINGS -> {
                            if (enabled) InitialImportService.startIndexing(getApplication())
                            else {
                                EmbeddingIndexScheduler.cancelAndWait(getApplication())
                                CaptionEmbeddingScheduler.cancelAndWait(getApplication())
                            }
                        }
                        IndexingJob.PEOPLE -> {
                            if (enabled) PeopleIndexScheduler.schedule(getApplication())
                            else PeopleIndexScheduler.cancelAndWait(getApplication())
                        }
                        IndexingJob.SEMANTIC_MEMORY -> {
                            if (enabled) SemanticEnrichmentScheduler.schedule(getApplication(), userRequested = true)
                            else SemanticEnrichmentScheduler.cancelAndWait(getApplication())
                        }
                    }
                    repository.recoverInterruptedJobs()
                    Triple(controls, repository.indexSummary(), repository.semanticMemoryProgress())
                }
            }.onSuccess { (controls, summary, semanticMemory) ->
                state = state.copy(
                    indexingJobControls = controls,
                    index = summary,
                    semanticMemory = semanticMemory,
                    indexingActive = state.indexingAdmission.allowed &&
                        hasRunnableIndexing(summary, state.peopleIndex, controls, state.retrievalPack.installed),
                    operationMessage = "${indexingJobLabel(job)} ${if (enabled) "started" else "stopped"}. Completed index data was kept.",
                )
                monitorIndexing()
            }.onFailure { error ->
                state = state.copy(operationMessage = error.message ?: "Could not change indexing job")
            }
        }
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

    fun requestSemanticEnrichment() {
        val model = state.modelPack
        if (!model.installed || !model.multimodal) {
            state = state.copy(
                operationMessage = "Gemma is still being prepared automatically for semantic memory",
            )
            return
        }
        if (state.semanticMemoryPlanning || state.semanticMemory.runningJobs > 0) return
        state = state.copy(
            semanticMemoryPlanning = true,
            operationMessage = "Selecting representative media for Gemma semantic memory...",
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val plan = repository.requestSemanticEnrichment()
                    Triple(plan.jobs.size, repository.indexSummary(), repository.semanticMemoryProgress())
                }
            }.onSuccess { (jobCount, summary, progress) ->
                state = state.copy(
                    index = summary,
                    semanticMemory = progress,
                    semanticMemoryPlanning = false,
                    operationMessage = if (jobCount > 0) {
                        "Queued $jobCount representative analyses. Existing gallery and people indexes are unchanged."
                    } else {
                        "No eligible representative media needs semantic enrichment"
                    },
                )
                if (progress.hasActiveWork) monitorSemanticMemory()
            }.onFailure { error ->
                state = state.copy(
                    semanticMemoryPlanning = false,
                    operationMessage = error.message ?: "Semantic memory could not be scheduled",
                )
            }
        }
    }

    fun importRetrievalPack(uri: Uri?) {
        if (uri == null) return
        state = state.copy(operationMessage = "Verifying the signed retrieval pack…")
        viewModelScope.launch {
            runCatching { retrievalPacks.import(uri) }
                .onSuccess { status ->
                    EmbeddingIndexScheduler.schedule(getApplication())
                    CaptionEmbeddingScheduler.schedule(getApplication())
                    state = state.copy(
                        retrievalPack = status,
                        operationMessage = "${status.packId} ${status.packVersion} is ready",
                    )
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Retrieval-pack import failed") }
        }
    }

    fun installEmbeddedRetrievalModel() {
        state = state.copy(operationMessage = "Preparing the embedded SigLIP2 Base model...")
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { retrievalProvisioner.enqueueIfNeeded() } }
                .onSuccess {
                    state = state.copy(
                        retrievalProvision = RetrievalProvisionProgress(state = GemmaDownloadState.QUEUED),
                        operationMessage = "Embedded SigLIP2 installation queued in app-private storage",
                    )
                    monitorRetrievalProvision()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Embedded SigLIP2 installation could not start") }
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
                        faceModelDownload = currentFaceModelProgress(),
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
                    if (state.destination == AppDestination.PEOPLE) loadPeopleReviewClusters()
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
                        peopleReviewClusters = emptyList(),
                    )
                    reload()
                }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "People index reset failed") }
        }
    }

    fun loadPeopleReviewClusters() {
        if (!state.peopleIndex.enabled) {
            state = state.copy(peopleReviewClusters = emptyList())
            return
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.personClusterSummaries(includeHidden = true) } }
                .onSuccess { clusters -> state = state.copy(peopleReviewClusters = clusters) }
                .onFailure { error -> state = state.copy(operationMessage = error.message ?: "Could not load face review clusters") }
        }
    }

    fun saveReviewedPersonCluster(
        id: String,
        label: String,
        relationship: String?,
        aliases: List<String>,
        includeInPersonalSemanticMemory: Boolean,
    ) {
        if (label.isBlank()) return
        val safeLabel = label.trim()
        val safeRelationship = relationship?.trim()?.takeIf(String::isNotBlank)
        val safeAliases = aliases.map(String::trim).filter(String::isNotBlank).distinct()
        val previousClusters = state.peopleReviewClusters
        state = state.copy(
            peopleReviewClusters = PeopleClusterStateReducer.review(
                clusters = previousClusters,
                id = id,
                label = safeLabel,
                relationship = safeRelationship,
                aliases = safeAliases,
                includeInPersonalSemanticMemory = includeInPersonalSemanticMemory,
            ),
            operationMessage = "Saving identity for $safeLabel...",
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.saveReviewedPersonCluster(
                        id = id,
                        label = safeLabel,
                        relationship = safeRelationship,
                        aliases = safeAliases,
                        includeInPersonalSemanticMemory = includeInPersonalSemanticMemory,
                    )
                }
            }.onSuccess { status ->
                state = state.copy(
                    peopleIndex = status,
                    operationMessage = "Saved identity for $safeLabel",
                )
            }.onFailure { error ->
                state = state.copy(
                    peopleReviewClusters = previousClusters,
                    operationMessage = error.message ?: "Person cluster review could not be saved",
                )
            }
        }
    }

    fun openPersonCluster(id: String) {
        if (state.peopleReviewClusters.none { it.id == id }) return
        state = state.copy(
            selectedPeopleClusterId = id,
            selectedPeopleClusterFaces = emptyList(),
            peopleClusterFaceOffset = 0,
            peopleClusterFacesLoading = false,
        )
        loadMorePersonClusterFaces()
    }

    fun closePersonCluster() {
        state = state.copy(
            selectedPeopleClusterId = null,
            selectedPeopleClusterFaces = emptyList(),
            peopleClusterFaceOffset = 0,
            peopleClusterFacesLoading = false,
        )
    }

    fun loadMorePersonClusterFaces() {
        val clusterId = state.selectedPeopleClusterId ?: return
        if (state.peopleClusterFacesLoading) return
        val cluster = state.peopleReviewClusters.firstOrNull { it.id == clusterId } ?: return
        val offset = state.peopleClusterFaceOffset
        if (offset >= cluster.faceCount) return
        state = state.copy(peopleClusterFacesLoading = true)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.personFacesForCluster(clusterId, PEOPLE_FACE_PAGE_SIZE, offset)
                }
            }.onSuccess { page ->
                if (state.selectedPeopleClusterId != clusterId) return@onSuccess
                state = state.copy(
                    selectedPeopleClusterFaces = (state.selectedPeopleClusterFaces + page).distinctBy(PersonFaceReviewItem::id),
                    peopleClusterFaceOffset = offset + PEOPLE_FACE_PAGE_SIZE,
                    peopleClusterFacesLoading = false,
                )
            }.onFailure { error ->
                if (state.selectedPeopleClusterId == clusterId) {
                    state = state.copy(
                        peopleClusterFacesLoading = false,
                        operationMessage = error.message ?: "Could not load this person's photos",
                    )
                }
            }
        }
    }

    fun setPersonClusterRepresentative(faceId: String) {
        val clusterId = state.selectedPeopleClusterId ?: return
        val face = state.selectedPeopleClusterFaces.firstOrNull { it.id == faceId } ?: return
        val previousClusters = state.peopleReviewClusters
        state = state.copy(
            peopleReviewClusters = PeopleClusterStateReducer.setRepresentative(previousClusters, clusterId, face),
            operationMessage = "Updating representative photo...",
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.setPersonClusterRepresentative(clusterId, faceId) }
            }.onSuccess {
                state = state.copy(operationMessage = "Representative photo updated")
            }.onFailure { error ->
                state = state.copy(
                    peopleReviewClusters = previousClusters,
                    operationMessage = error.message ?: "Representative photo could not be updated",
                )
            }
        }
    }

    fun improveSelectedPersonCluster() {
        val clusterId = state.selectedPeopleClusterId ?: return
        val cluster = state.peopleReviewClusters.firstOrNull { it.id == clusterId } ?: return
        if (!cluster.reviewed) {
            state = state.copy(operationMessage = "Name this person before improving automatic matches")
            return
        }
        val representativeFaceId = cluster.representativeFaceId
            ?: cluster.representativeFace?.id
            ?: state.selectedPeopleClusterFaces.firstOrNull()?.id
        if (representativeFaceId == null) {
            state = state.copy(operationMessage = "Set a representative photo before improving matches")
            return
        }
        state = state.copy(peopleClusterFacesLoading = true, operationMessage = "Checking faces against the representative...")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val memberships = repository.faceClusterMemberships(clusterId)
                    val representativeFace = repository.personFace(representativeFaceId)
                        ?: error("Representative face is unavailable")
                    reviewedIdentityExpander.ensureEmbedding(representativeFace)
                    val similarities = faceVectorStore.similarities(
                        representativeFaceId,
                        memberships.map(FaceClusterMembership::faceId),
                    )
                    val decision = FaceClusterRefinementPolicy.decide(memberships, representativeFaceId, similarities)
                    val moved = repository.refineReviewedPersonCluster(
                        clusterId,
                        representativeFaceId,
                        decision.rejectedFaceIds,
                    )
                    decision to moved
                }
            }.onSuccess { (decision, moved) ->
                val representative = cluster.representativeFace
                    ?: state.selectedPeopleClusterFaces.firstOrNull { it.id == representativeFaceId }
                state = state.copy(
                    peopleReviewClusters = if (representative == null) {
                        state.peopleReviewClusters
                    } else {
                        PeopleClusterStateReducer.setRepresentative(
                            state.peopleReviewClusters.map { item ->
                                if (item.id == clusterId) item.copy(faceCount = (item.faceCount - moved).coerceAtLeast(1)) else item
                            },
                            clusterId,
                            representative,
                        )
                    },
                    selectedPeopleClusterFaces = state.selectedPeopleClusterFaces.filter { it.id in decision.keptFaceIds },
                    peopleClusterFaceOffset = 0,
                    peopleClusterFacesLoading = false,
                    operationMessage = if (moved == 0) {
                        "No low-confidence automatic matches were found"
                    } else {
                        "Moved $moved low-confidence automatic matches out of ${cluster.label ?: "this person"}"
                    },
                )
                loadPeopleReviewClusters()
            }.onFailure { error ->
                state = state.copy(
                    peopleClusterFacesLoading = false,
                    operationMessage = error.message ?: "Could not improve this cluster",
                )
            }
        }
    }

    fun excludeFaceFromSelectedCluster(faceId: String) {
        val clusterId = state.selectedPeopleClusterId ?: return
        val previousClusters = state.peopleReviewClusters
        val previousFaces = state.selectedPeopleClusterFaces
        if (previousFaces.none { it.id == faceId }) return
        state = state.copy(
            peopleReviewClusters = PeopleClusterStateReducer.excludeFace(previousClusters, clusterId, faceId),
            selectedPeopleClusterFaces = previousFaces.filterNot { it.id == faceId },
            operationMessage = "Excluding photo from this person...",
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { repository.excludeFaceFromCluster(faceId) }
            }.onSuccess {
                state = state.copy(operationMessage = "Photo excluded from this person")
            }.onFailure { error ->
                state = state.copy(
                    peopleReviewClusters = previousClusters,
                    selectedPeopleClusterFaces = previousFaces,
                    operationMessage = error.message ?: "Photo could not be excluded",
                )
            }
        }
    }

    fun removePersonLabel(id: String) {
        updatePeople("Removing the local label...") { repository.removePersonLabel(id) to "Person label removed" }
    }

    fun setPersonClusterHidden(id: String, hidden: Boolean) {
        updatePeople(if (hidden) "Hiding cluster..." else "Restoring cluster...") {
            repository.setPersonClusterHidden(id, hidden) to if (hidden) "Cluster hidden" else "Cluster restored"
        }
    }

    fun mergePersonClusters(targetId: String, sourceId: String) {
        if (targetId.isBlank() || targetId == sourceId) return
        updatePeople("Merging face clusters...") {
            repository.mergePersonClusters(targetId.trim(), sourceId) to "Clusters merged"
        }
    }

    fun moveFaceToCluster(faceId: String, targetId: String?) {
        state = state.copy(operationMessage = "Correcting face assignment...")
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val target = repository.moveFaceToCluster(faceId, targetId?.trim()?.takeIf(String::isNotBlank))
                    target to repository.personClusterSummaries(includeHidden = true)
                }
            }.onSuccess { (target, clusters) ->
                state = state.copy(peopleReviewClusters = clusters, operationMessage = "Face moved to $target")
            }.onFailure { error ->
                state = state.copy(operationMessage = error.message ?: "Face assignment could not be changed")
            }
        }
    }

    private fun updatePeople(pendingMessage: String, operation: suspend () -> Pair<PeopleIndexStatus, String>) {
        state = state.copy(operationMessage = pendingMessage)
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val (status, message) = operation()
                    Triple(status, repository.personClusterSummaries(includeHidden = true), message)
                }
            }.onSuccess { (status, clusters, message) ->
                state = state.copy(peopleIndex = status, peopleReviewClusters = clusters, operationMessage = message)
            }.onFailure { error ->
                state = state.copy(operationMessage = error.message ?: "People data could not be updated")
            }
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
        state = state.copy(
            selectedEvidence = hit,
            selectedEvidenceMetadata = null,
            selectedEvidenceMetadataLoading = false,
            selectedEvidenceMetadataError = null,
            selectedEvidenceMetadataUnlocked = false,
        )
    }

    fun dismissEvidence() {
        state = state.copy(
            selectedEvidence = null,
            selectedEvidenceMetadata = null,
            selectedEvidenceMetadataLoading = false,
            selectedEvidenceMetadataError = null,
            selectedEvidenceMetadataUnlocked = false,
        )
    }

    fun loadSelectedEvidenceMetadata(includeSensitiveContent: Boolean = false) {
        val selected = state.selectedEvidence ?: return
        if (state.selectedEvidenceMetadataLoading) return
        if (state.selectedEvidenceMetadata != null &&
            (!includeSensitiveContent || state.selectedEvidenceMetadataUnlocked)
        ) {
            return
        }
        val mediaId = selected.item.id
        state = state.copy(
            selectedEvidenceMetadataLoading = true,
            selectedEvidenceMetadataError = null,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.indexedMetadata(mediaId, includeSensitiveContent)
                }
            }.onSuccess { metadata ->
                if (state.selectedEvidence?.item?.id == mediaId) {
                    state = state.copy(
                        selectedEvidenceMetadata = metadata,
                        selectedEvidenceMetadataLoading = false,
                        selectedEvidenceMetadataUnlocked = includeSensitiveContent,
                    )
                }
            }.onFailure { error ->
                if (state.selectedEvidence?.item?.id == mediaId) {
                    state = state.copy(
                        selectedEvidenceMetadataLoading = false,
                        selectedEvidenceMetadataError = error.message ?: "Indexed metadata could not be loaded",
                    )
                }
            }
        }
    }

    fun unlockSelectedEvidenceMetadata(hit: SearchHit) {
        if (state.selectedEvidence?.item?.id != hit.item.id) return
        loadSelectedEvidenceMetadata(includeSensitiveContent = true)
    }

    private suspend fun reload(message: String? = state.operationMessage) {
        val refreshed = withContext(Dispatchers.IO) {
            GalleryRefresh(
                repository.indexSummary(),
                repository.allItems(),
                repository.peopleIndexStatus(),
                repository.indexingAdmission(),
            )
        }
        state = state.copy(
            index = refreshed.summary,
            items = refreshed.items,
            peopleIndex = refreshed.peopleIndex,
            indexingAdmission = refreshed.admission,
            operationMessage = message,
        )
        if (state.destination == AppDestination.PEOPLE && refreshed.peopleIndex.enabled) {
            loadPeopleReviewClusters()
        } else if (!refreshed.peopleIndex.enabled) {
            state = state.copy(peopleReviewClusters = emptyList())
        }
    }

    private suspend fun reloadIndexStatus(message: String? = state.operationMessage) {
        val refreshed = withContext(Dispatchers.IO) {
            Triple(
                repository.indexSummary(),
                repository.peopleIndexStatus(),
                repository.indexingAdmission(),
            )
        }
        state = state.copy(
            index = refreshed.first,
            peopleIndex = refreshed.second,
            indexingAdmission = refreshed.third,
            operationMessage = message,
        )
    }

    private fun monitorIndexing() {
        indexMonitorJob?.cancel()
        state = state.copy(indexingActive = state.indexingAdmission.allowed)
        indexMonitorJob = viewModelScope.launch {
            var pollCount = 0
            while (true) {
                delay(2_500)
                reloadIndexStatus()
                val pipelines = withContext(Dispatchers.IO) {
                    IndexingRuntimeStatusReader(getApplication()).read(
                        state.index,
                        state.peopleIndex,
                        state.semanticMemory,
                        state.indexingJobControls,
                        state.indexingAdmission,
                    )
                }
                state = state.copy(indexingPipelines = pipelines)
                val hasPending = pipelines.values.any {
                    it.state !in setOf(
                        IndexingPipelineState.COMPLETE,
                        IndexingPipelineState.DEGRADED,
                        IndexingPipelineState.STOPPED_BY_USER,
                    )
                }
                if (!hasPending) {
                    reload()
                    state = state.copy(indexingActive = false)
                    return@launch
                }
                val running = pipelines.values.any { it.state == IndexingPipelineState.RUNNING }
                state = if (running) {
                    state.copy(
                        indexingActive = true,
                        operationMessage = state.operationMessage?.takeUnless { it.startsWith("Indexing paused:") }
                            ?: "Indexing in progress",
                    )
                } else {
                    val waiting = pipelines.values.firstOrNull {
                        it.state == IndexingPipelineState.BACKOFF ||
                            it.state == IndexingPipelineState.WAITING_CONSTRAINTS ||
                            it.state == IndexingPipelineState.FAILED
                    }
                    state.copy(
                        indexingActive = false,
                        operationMessage = waiting?.message ?: state.operationMessage,
                    )
                }
                pollCount += 1
                if (pollCount % 12 == 0) {
                    withContext(Dispatchers.IO) {
                        IndexingSupervisor.reconcile(
                            getApplication(),
                            state.index,
                            state.peopleIndex,
                            state.semanticMemory,
                            state.indexingJobControls,
                            state.retrievalPack.installed,
                        )
                    }
                    val items = withContext(Dispatchers.IO) { repository.allItems() }
                    state = state.copy(items = items)
                }
            }
        }
    }

    private data class GalleryRefresh(
        val summary: IndexSummary,
        val items: List<GalleryItem>,
        val peopleIndex: PeopleIndexStatus,
        val admission: BackgroundWorkAdmission,
    )

    private fun hasRunnableIndexing(
        summary: IndexSummary,
        peopleIndex: PeopleIndexStatus,
        controls: IndexingJobControls,
        retrievalPackInstalled: Boolean,
    ): Boolean =
        (summary.pending > 0 && controls.mediaAnalysisEnabled) ||
            (peopleIndex.pendingMediaCount > 0 && controls.peopleEnabled) ||
            (
                retrievalPackInstalled &&
                    controls.embeddingsEnabled &&
                    summary.siglipVectorsReady < summary.discovered
            )

    private fun indexingJobLabel(job: IndexingJob): String = when (job) {
        IndexingJob.MEDIA_ANALYSIS -> "Media analysis"
        IndexingJob.EMBEDDINGS -> "SigLIP2 vectors"
        IndexingJob.PEOPLE -> "People indexing"
        IndexingJob.SEMANTIC_MEMORY -> "Gemma semantic memory"
    }

    private fun monitorSemanticMemory() {
        semanticMemoryMonitorJob?.cancel()
        semanticMemoryMonitorJob = viewModelScope.launch {
            while (state.destination == AppDestination.INDEX_MANAGER) {
                val progress = withContext(Dispatchers.IO) { repository.semanticMemoryProgress() }
                state = state.copy(semanticMemory = progress)
                if (!progress.hasActiveWork) return@launch
                delay(if (progress.runningJobs > 0) 750 else 2_000)
            }
        }
    }

    init {
        automaticallyProvisionGemma()
    }

    private fun automaticallyProvisionGemma() {
        if (!BuildConfig.ALLOW_MODEL_DOWNLOAD) return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { provisionGemmaModel() } }
                .onSuccess { (status, progress) ->
                    val message = when (progress.state) {
                        GemmaDownloadState.QUEUED,
                        GemmaDownloadState.DOWNLOADING,
                        GemmaDownloadState.VERIFYING,
                        -> "${GemmaModelCatalog.require(progress.tier).displayName} is downloading automatically for all Gemma features"
                        else -> null
                    }
                    state = state.copy(
                        modelPack = status,
                        modelDownload = progress,
                        operationMessage = state.operationMessage ?: message,
                    )
                    if (progress.state in ACTIVE_DOWNLOAD_STATES) monitorModelDownload()
                }
                .onFailure { error ->
                    state = state.copy(operationMessage = error.message ?: "The automatic Gemma model download could not start")
                }
        }
    }

    private fun provisionGemmaModel(): Pair<ModelPackStatus, GemmaDownloadProgress> {
        val current = modelPacks.status()
        if (current.installed) {
            val tier = current.tier ?: current.selectedTier
            return current to modelDownloader.progress(tier)
        }
        var lastFailure: Throwable? = null
        automaticGemmaCandidates(current).forEach { tier ->
            if (modelPacks.isInstalled(tier)) {
                return modelPacks.selectTier(tier) to modelDownloader.progress(tier)
            }
            val existing = modelDownloader.progress(tier)
            if (existing.state in ACTIVE_DOWNLOAD_STATES) {
                return modelPacks.selectTier(tier) to existing
            }
            runCatching { modelDownloader.enqueue(tier) }
                .onSuccess {
                    return modelPacks.selectTier(tier) to GemmaDownloadProgress(
                        tier = tier,
                        state = GemmaDownloadState.QUEUED,
                        totalBytes = GemmaModelCatalog.require(tier).sizeBytes,
                    )
                }
                .onFailure { lastFailure = it }
        }
        throw lastFailure ?: IllegalStateException("No compatible Gemma model is available for this device")
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

    private fun monitorRetrievalProvision() {
        retrievalProvisionMonitorJob?.cancel()
        retrievalProvisionMonitorJob = viewModelScope.launch {
            repeat(7_200) {
                val progress = withContext(Dispatchers.IO) { retrievalProvisioner.progress() }
                val wasInstalled = state.retrievalPack.installed
                val status = if (progress.state == GemmaDownloadState.INSTALLED) {
                    withContext(Dispatchers.IO) { retrievalPacks.status() }
                } else state.retrievalPack
                if (!wasInstalled && status.installed) {
                    EmbeddingIndexScheduler.schedule(getApplication())
                    monitorIndexing()
                }
                state = state.copy(retrievalPack = status, retrievalProvision = progress)
                if (progress.state !in ACTIVE_DOWNLOAD_STATES) return@launch
                delay(1_000)
            }
        }
    }

    private fun monitorFaceModelDownload() {
        faceModelMonitorJob?.cancel()
        faceModelMonitorJob = viewModelScope.launch {
            repeat(7_200) {
                val (progress, status) = withContext(Dispatchers.IO) {
                    currentFaceModelProgress() to faceModelPacks.status()
                }
                val wasInstalled = state.faceModel.installed
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

    private fun currentFaceModelProgress(): FaceModelDownloadProgress {
        val network = faceModelDownloader.progress()
        val embedded = embeddedFaceModelProvisioner.progress()
        return when {
            network.state in ACTIVE_DOWNLOAD_STATES -> network
            embedded.state != GemmaDownloadState.IDLE -> embedded
            else -> network
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
        const val PEOPLE_FACE_PAGE_SIZE = 60
        val ACTIVE_DOWNLOAD_STATES = setOf(
            GemmaDownloadState.QUEUED,
            GemmaDownloadState.DOWNLOADING,
            GemmaDownloadState.VERIFYING,
        )
    }
}

internal object PeopleClusterStateReducer {
    fun review(
        clusters: List<PersonClusterReviewItem>,
        id: String,
        label: String,
        relationship: String?,
        aliases: List<String>,
        includeInPersonalSemanticMemory: Boolean = PersonalSemanticMemoryPolicy.defaultEnabled(relationship),
    ): List<PersonClusterReviewItem> = clusters.map { cluster ->
        when {
            cluster.id == id -> cluster.copy(
                label = label,
                relationship = relationship,
                aliases = aliases,
                reviewed = true,
                hidden = false,
                includeInPersonalSemanticMemory = includeInPersonalSemanticMemory,
            )
            relationship.equals("me", ignoreCase = true) && cluster.relationship.equals("me", ignoreCase = true) ->
                cluster.copy(relationship = null)
            else -> cluster
        }
    }

    fun setRepresentative(
        clusters: List<PersonClusterReviewItem>,
        clusterId: String,
        face: PersonFaceReviewItem,
    ): List<PersonClusterReviewItem> = clusters.map { cluster ->
        if (cluster.id != clusterId) {
            cluster
        } else {
            cluster.copy(
                representativeFaceId = face.id,
                representativeFace = face,
                supportingFaces = (listOf(face) + cluster.supportingFaces).distinctBy(PersonFaceReviewItem::id).take(4),
            )
        }
    }

    fun excludeFace(
        clusters: List<PersonClusterReviewItem>,
        clusterId: String,
        faceId: String,
    ): List<PersonClusterReviewItem> = clusters.map { cluster ->
        if (cluster.id != clusterId) {
            cluster
        } else {
            val remainingFaces = cluster.supportingFaces.filterNot { it.id == faceId }
            val representativeWasExcluded = cluster.representativeFaceId == faceId || cluster.representativeFace?.id == faceId
            cluster.copy(
                faceCount = (cluster.faceCount - 1).coerceAtLeast(0),
                representativeFaceId = if (representativeWasExcluded) null else cluster.representativeFaceId,
                representativeFace = if (representativeWasExcluded) remainingFaces.firstOrNull() else cluster.representativeFace,
                supportingFaces = remainingFaces,
            )
        }
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
