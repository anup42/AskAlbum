package com.askphotos.android

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AppDestination { ONBOARDING, GALLERY, ASK, RESULTS, INDEX_MANAGER, PRIVACY }

data class GalleryUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val query: String = "",
    val executionStatus: String? = null,
    val outcome: SearchOutcome? = null,
    val items: List<GalleryItem> = emptyList(),
    val index: IndexSummary = IndexSummary(),
    val selectedEvidence: SearchHit? = null,
    val destination: AppDestination = AppDestination.ASK,
    val operationMessage: String? = null,
    val modelPack: ModelPackStatus = ModelPackStatus(installed = false),
    val modelDownload: GemmaDownloadProgress = GemmaDownloadProgress(),
    val retrievalPack: RetrievalPackStatus = RetrievalPackStatus(installed = false),
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {
    private val askPhotosApplication = application as AskPhotosApplication
    private val repository = askPhotosApplication.repository
    private val modelPacks = askPhotosApplication.modelPackManager
    private val modelDownloader = askPhotosApplication.services.modelDownloader
    private val retrievalPacks = askPhotosApplication.services.retrievalModelPackManager
    var state by mutableStateOf(GalleryUiState())
        private set

    init {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val summary = repository.initialize()
                    summary to repository.allItems()
                }
            }.onSuccess { (summary, items) ->
                state = state.copy(
                    loading = false,
                    index = summary,
                    items = items,
                    modelPack = modelPacks.status(),
                    modelDownload = modelDownloader.progress(modelPacks.selectedTier()),
                    retrievalPack = retrievalPacks.status(),
                )
                monitorIndexing()
                monitorModelDownload()
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
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) { modelPacks.selectTier(tier) }
            val progress = withContext(Dispatchers.IO) { modelDownloader.progress(tier) }
            state = state.copy(modelPack = status, modelDownload = progress, operationMessage = null)
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

    fun clearOperationMessage() {
        state = state.copy(operationMessage = null)
    }

    fun ask(query: String = state.query) {
        if (query.isBlank() || state.executionStatus != null) return
        state = state.copy(query = query, executionStatus = "Compiling a safe local plan…", selectedEvidence = null)
        viewModelScope.launch {
            delay(120)
            state = state.copy(executionStatus = "Scanning indexed evidence on this phone…")
            val previousIds = state.outcome?.hits?.map { it.item.id }?.toSet()
            val result = runCatching {
                withContext(Dispatchers.Default) { repository.search(query, previousIds) }
            }
            result.onSuccess { outcome ->
                state = state.copy(outcome = outcome, executionStatus = null, destination = AppDestination.RESULTS)
            }.onFailure { error ->
                state = state.copy(executionStatus = null, error = error.message ?: "Search failed")
            }
        }
    }

    fun showEvidence(hit: SearchHit) {
        state = state.copy(selectedEvidence = hit)
    }

    fun dismissEvidence() {
        state = state.copy(selectedEvidence = null)
    }

    private suspend fun reload(message: String? = state.operationMessage) {
        val (summary, items) = withContext(Dispatchers.IO) { repository.indexSummary() to repository.allItems() }
        state = state.copy(index = summary, items = items, operationMessage = message)
    }

    private fun monitorIndexing() {
        viewModelScope.launch {
            repeat(90) {
                delay(1_000)
                reload()
                if (state.index.pending == 0) return@launch
            }
        }
    }

    private fun monitorModelDownload() {
        viewModelScope.launch {
            repeat(7_200) {
                val tier = state.modelPack.selectedTier
                val progress = withContext(Dispatchers.IO) { modelDownloader.progress(tier) }
                val modelStatus = if (progress.state == GemmaDownloadState.INSTALLED) {
                    withContext(Dispatchers.IO) { modelPacks.status() }
                } else state.modelPack
                state = state.copy(modelPack = modelStatus, modelDownload = progress)
                if (progress.state !in setOf(GemmaDownloadState.QUEUED, GemmaDownloadState.DOWNLOADING, GemmaDownloadState.VERIFYING)) return@launch
                delay(1_000)
            }
        }
    }
}
