package com.askphotos.android

import android.graphics.BitmapFactory
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Size
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    private val galleryViewModel: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AskPhotosTheme {
                AskPhotosApp(galleryViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
            else -> ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (permission) galleryViewModel.scheduleIncrementalScan()
    }
}

private val Forest = Color(0xFF173F35)
private val Lime = Color(0xFFD9FF6F)
private val Canvas = Color(0xFFF7F9F4)
private val Ink = Color(0xFF14201D)
private val Mist = Color(0xFFE8EEE9)

@Composable
private fun AskPhotosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Forest,
            onPrimary = Color.White,
            primaryContainer = Lime,
            onPrimaryContainer = Forest,
            background = Canvas,
            onBackground = Ink,
            surface = Color.White,
            onSurface = Ink,
            surfaceVariant = Mist,
            onSurfaceVariant = Color(0xFF53615D),
            outline = Color(0xFFB7C3BC),
        ),
        content = content,
    )
}

@Composable
private fun AskPhotosApp(viewModel: GalleryViewModel) {
    val state = viewModel.state
    val context = LocalContext.current
    val evidenceGate = remember(context) {
        SensitiveEvidenceGate(context as FragmentActivity, viewModel::showEvidence)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(100)) { uris ->
        viewModel.importUris(uris, MediaSource.PHOTO_PICKER)
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.importUris(uris, MediaSource.SAF_DOCUMENT)
    }
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importModelPack(uri)
    }
    val retrievalModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importRetrievalPack(uri)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.scanAccessibleGallery()
    }
    val requestFullGallery = {
        val permissions = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val alreadyGranted = permissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (alreadyGranted) viewModel.scanAccessibleGallery() else permissionLauncher.launch(permissions)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppHeader() },
        bottomBar = {
            AppNavigation(
                selected = state.destination,
                onSelect = viewModel::navigate,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> LoadingState()
                state.error != null -> ErrorState(state.error)
                state.destination == AppDestination.ONBOARDING -> OnboardingScreen(
                    onContinue = { viewModel.navigate(AppDestination.ASK) },
                )
                state.destination == AppDestination.ASK -> AskScreen(state, viewModel)
                state.destination == AppDestination.RESULTS -> ResultsScreen(
                    outcome = state.outcome,
                    onEvidence = evidenceGate::open,
                    onAsk = { viewModel.navigate(AppDestination.ASK) },
                )
                state.destination == AppDestination.GALLERY -> GalleryScreen(
                    items = state.items,
                    onSelect = evidenceGate::open,
                    onPickMedia = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    onPickDocuments = { documentPicker.launch(arrayOf("image/*", "video/*", "application/pdf")) },
                    onFullGallery = requestFullGallery,
                    operationMessage = state.operationMessage,
                )
                state.destination == AppDestination.INDEX_MANAGER -> IndexManagerScreen(
                    index = state.index,
                    modelPack = state.modelPack,
                    modelDownload = state.modelDownload,
                    retrievalPack = state.retrievalPack,
                    operationMessage = state.operationMessage,
                    onRetry = viewModel::retryIndexing,
                    onImportModel = { modelPicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                    onSelectModelTier = viewModel::selectModelTier,
                    onDownloadModel = viewModel::downloadSelectedModel,
                    onCancelModelDownload = viewModel::cancelModelDownload,
                    onImportRetrievalModel = {
                        retrievalModelPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                )
                else -> PrivacyScreen(
                    onReviewOnboarding = { viewModel.navigate(AppDestination.ONBOARDING) },
                )
            }
        }
    }
    state.selectedEvidence?.let { EvidenceDialog(it, viewModel::dismissEvidence) }
}

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Forest),
            contentAlignment = Alignment.Center,
        ) {
            Text("A", color = Lime, fontWeight = FontWeight.Black, fontSize = 21.sp)
        }
        Spacer(Modifier.width(11.dp))
        Column {
            Text("AskPhotos", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text("Private gallery intelligence", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Spacer(Modifier.weight(1f))
        Surface(color = Mist, shape = CircleShape) {
            Text("ON-DEVICE", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = Forest, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun AppNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar(modifier = Modifier.navigationBarsPadding(), containerColor = Color.White) {
        NavigationBarItem(
            selected = selected == AppDestination.ASK,
            onClick = { onSelect(AppDestination.ASK) },
            icon = { Text("A") },
            label = { Text("Ask") },
        )
        NavigationBarItem(
            selected = selected == AppDestination.RESULTS,
            onClick = { onSelect(AppDestination.RESULTS) },
            icon = { Text("R") },
            label = { Text("Results") },
        )
        NavigationBarItem(
            selected = selected == AppDestination.GALLERY,
            onClick = { onSelect(AppDestination.GALLERY) },
            icon = { Text("G") },
            label = { Text("Gallery") },
        )
        NavigationBarItem(
            selected = selected == AppDestination.INDEX_MANAGER,
            onClick = { onSelect(AppDestination.INDEX_MANAGER) },
            icon = { Text("I") },
            label = { Text("Index") },
        )
        NavigationBarItem(
            selected = selected == AppDestination.PRIVACY || selected == AppDestination.ONBOARDING,
            onClick = { onSelect(AppDestination.PRIVACY) },
            icon = { Text("P") },
            label = { Text("Privacy") },
        )
    }
}

@Composable
private fun LoadingState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("Compiling the local sample gallery…")
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text("Gallery memory could not be opened", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

private val suggestions = listOf(
    "Show Amsterdam photos",
    "How many beach photos?",
    "Find colorful flowers",
    "Show bicycles",
)

@Composable
private fun AskScreen(state: GalleryUiState, viewModel: GalleryViewModel) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Ask results" },
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Ask your gallery", fontSize = 32.sp, lineHeight = 35.sp, fontWeight = FontWeight.Black, color = Forest)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Search local memories, refine the result, and inspect every supporting fact.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Gallery question" },
                    placeholder = { Text("e.g. Show Amsterdam photos") },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.ask() }),
                    trailingIcon = {
                        Button(
                            onClick = { viewModel.ask() },
                            enabled = state.query.isNotBlank() && state.executionStatus == null,
                            modifier = Modifier.testTag("submit-question").semantics { contentDescription = "Submit question" },
                            contentPadding = PaddingValues(horizontal = 14.dp),
                        ) { Text("Ask") }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    suggestions.take(2).forEach { suggestion ->
                        SuggestionChip(onClick = { viewModel.ask(suggestion) }, label = { Text(suggestion.replace(" photos", ""), maxLines = 1) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    suggestions.drop(2).forEach { suggestion ->
                        SuggestionChip(onClick = { viewModel.ask(suggestion) }, label = { Text(suggestion, maxLines = 1) })
                    }
                }
            }
        }

        state.executionStatus?.let { status ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(colors = CardDefaults.cardColors(containerColor = Mist)) {
                    Column(Modifier.padding(16.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Forest)
                        Spacer(Modifier.height(10.dp))
                        Text(status, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        if (state.executionStatus == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrivacyCard(state.index.discovered)
            }
        }
    }
}

@Composable
private fun ResultsScreen(
    outcome: SearchOutcome?,
    onEvidence: (SearchHit) -> Unit,
    onAsk: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "Ask results" },
        contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (outcome == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Results", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Forest)
                    Spacer(Modifier.height(8.dp))
                    Text("Ask a gallery question to create a local evidence-backed result set.")
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onAsk) { Text("Ask your gallery") }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) { AnswerCard(outcome) }
            items(outcome.hits, key = { it.item.id }) { hit ->
                ResultTile(hit, onClick = { onEvidence(hit) })
            }
        }
    }
}

@Composable
private fun AnswerCard(outcome: SearchOutcome) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Answer ${outcome.answer.headline}" },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Forest),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text("GROUNDED ANSWER", color = Lime, fontSize = 11.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(8.dp))
            Text(outcome.answer.headline, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(7.dp))
            Text(outcome.answer.detail, color = Color(0xFFDCE8E2), lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactPill(outcome.answer.exactness.name.replace('_', ' '))
                FactPill("${outcome.elapsedMs} ms")
                FactPill("${outcome.answer.evidenceIds.size} evidence")
            }
            if (outcome.plan.baseResultIds != null) {
                Spacer(Modifier.height(10.dp))
                Text("↳ Refined within the previous result set", color = Lime, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun FactPill(text: String) {
    Surface(color = Color(0xFF2A574B), shape = CircleShape) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResultTile(hit: SearchHit, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column {
            AssetImage(hit.item, Modifier.fillMaxWidth().height(138.dp))
            Column(Modifier.padding(11.dp)) {
                Text(hit.item.title, fontWeight = FontWeight.Bold, maxLines = 2, lineHeight = 18.sp)
                Spacer(Modifier.height(3.dp))
                Text(hit.item.location, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
                if (hit.duplicateIds.isNotEmpty()) {
                    Spacer(Modifier.height(5.dp))
                    Text("+${hit.duplicateIds.size} similar", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(7.dp))
                Text("Why this answer?", color = Forest, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun PrivacyCard(count: Int) {
    Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp)) {
            Text("A private memory, already useful", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("$count CC0 sample photos are indexed in app-private SQLite. Queries, images, evidence, and answers stay on this device.")
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF20A36A)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (BuildConfig.ALLOW_MODEL_DOWNLOAD) "Network only for model downloads" else "No Internet permission",
                    color = Forest,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun GalleryScreen(
    items: List<GalleryItem>,
    onSelect: (SearchHit) -> Unit,
    onPickMedia: () -> Unit,
    onPickDocuments: () -> Unit,
    onFullGallery: () -> Unit,
    operationMessage: String?,
) {
    val importedCount = items.count { it.source != MediaSource.DEMO_ASSET }
    LazyVerticalGrid(
        modifier = Modifier.semantics { contentDescription = "Gallery screen; $importedCount imported items" },
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("Local library", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Forest)
                Text("${items.size} consented local items", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onPickMedia) { Text("Pick photos") }
                    OutlinedButton(onClick = onFullGallery) { Text("Full gallery") }
                }
                Spacer(Modifier.height(7.dp))
                OutlinedButton(onClick = onPickDocuments) { Text("Add PDFs or files") }
                operationMessage?.let {
                    Spacer(Modifier.height(9.dp))
                    Surface(color = Lime, shape = RoundedCornerShape(12.dp)) {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = Forest, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        items(items, key = { it.id }) { item ->
            val hit = remember(item) {
                SearchHit(item, 1.0, listOf(EvidenceRecord("${item.id}:metadata", item.id, "metadata", item.title, 1f)))
            }
            ResultTile(hit) { onSelect(hit) }
        }
    }
}

@Composable
private fun IndexManagerScreen(
    index: IndexSummary,
    modelPack: ModelPackStatus,
    modelDownload: GemmaDownloadProgress,
    retrievalPack: RetrievalPackStatus,
    operationMessage: String?,
    onRetry: () -> Unit,
    onImportModel: () -> Unit,
    onSelectModelTier: (GemmaModelTier) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportRetrievalModel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp, 8.dp, 18.dp, 32.dp),
    ) {
        Text("Gallery memory", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Forest)
        Text("Progressive, versioned, and stored only on this phone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        IndexMetric("Media discovered", index.discovered, index.discovered, "Asset manifest")
        IndexMetric("Metadata ready", index.metadataReady, index.discovered, "demo-metadata-v1")
        IndexMetric("Semantic facts ready", index.semanticFactsReady, index.discovered, "demo-sidecar-v1")
        IndexMetric("OCR ready or skipped", index.ocrReady, index.discovered, "demo skipped / ML Kit bundled")
        IndexMetric("Visual labels ready", index.visualLabelsReady, index.discovered, "mlkit-image-label-v1 bundled")
        IndexMetric("Face stage resolved", index.facesScanned, index.discovered, "demo skipped / face count only")
        IndexMetric("Pending", index.pending, index.discovered, "WorkManager resumable queue")
        IndexMetric("Events", index.events, index.events, "deterministic day grouping")
        IndexMetric("Failed", index.failed, index.discovered, "No retries pending")
        if (index.pending > 0 || index.failed > 0) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Resume indexing") }
            Spacer(Modifier.height(10.dp))
        }
        operationMessage?.let {
            Surface(color = Lime, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), color = Forest, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Forest), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Runtime policy", color = Lime, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text("Offline hybrid local runtime", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("Bundled OCR, image labeling, face detection, PDF rendering, and video thumbnails run locally. Gemma, SigLIP2, and face identity embeddings still require separate compatible model packs.", color = Color(0xFFDCE8E2))
                Spacer(Modifier.height(12.dp))
                Text("Local database: ${formatBytes(index.storageBytes)}", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp)) {
                Text("Gemma model pack", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text(if (modelPack.installed) modelPack.name else "Selected model is not installed — deterministic planning remains active")
                Text(modelPack.runtimeVersion, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GemmaModelCatalog.all.forEach { spec ->
                        FilterChip(
                            selected = modelPack.selectedTier == spec.tier,
                            onClick = { onSelectModelTier(spec.tier) },
                            label = { Text(spec.tier.name) },
                        )
                    }
                }
                val selectedSpec = GemmaModelCatalog.require(modelPack.selectedTier)
                Text(
                    "${selectedSpec.displayName} • ${formatBytes(selectedSpec.sizeBytes)} • ${selectedSpec.deviceClassRamGb} GB-class device",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                if (modelPack.installed) {
                    Text("${modelPack.packId} ${modelPack.packVersion} • ${modelPack.tier} • ${formatBytes(modelPack.sizeBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    Text("Signed model SHA-256 ${modelPack.sha256?.take(12)}…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                } else {
                    Text("Device recommendation: ${modelPack.deviceAssessment?.recommendedTier ?: GemmaModelTier.E2B}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    modelPack.deviceAssessment?.reason?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp) }
                }
                modelPack.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                if (modelPack.downloadAllowed && !modelPack.installed) {
                    Spacer(Modifier.height(10.dp))
                    when (modelDownload.state) {
                        GemmaDownloadState.QUEUED, GemmaDownloadState.DOWNLOADING, GemmaDownloadState.VERIFYING -> {
                            LinearProgressIndicator(
                                progress = { modelDownload.fraction },
                                modifier = Modifier.fillMaxWidth(),
                                color = Forest,
                                trackColor = Mist,
                            )
                            Text(
                                if (modelDownload.state == GemmaDownloadState.VERIFYING) "Verifying SHA-256 and activating…" else "${formatBytes(modelDownload.bytesDownloaded)} of ${formatBytes(modelDownload.totalBytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            TextButton(onClick = onCancelModelDownload) { Text("Cancel download") }
                        }
                        else -> {
                            Button(
                                onClick = onDownloadModel,
                                enabled = modelPack.deviceAssessment?.supported == true,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Download ${selectedSpec.displayName}") }
                            modelDownload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                        }
                    }
                } else if (!modelPack.downloadAllowed) {
                    Spacer(Modifier.height(8.dp))
                    Text("Offline demo build: network model downloads are disabled.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(onClick = onImportModel) { Text(if (modelPack.installed) "Replace signed pack" else "Import .agemma pack") }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp)) {
                Text("SigLIP2 retrieval pack", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text(
                    if (retrievalPack.installed) {
                        "${retrievalPack.packId} ${retrievalPack.packVersion}"
                    } else {
                        retrievalPack.error ?: "Not installed — fixture semantics remain active"
                    },
                )
                if (retrievalPack.installed) {
                    Text(
                        "${formatBytes(retrievalPack.installedBytes)} • ${retrievalPack.embeddingDimension} dimensions • pinned ${retrievalPack.sourceRevision?.take(12)}…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(onClick = onImportRetrievalModel) {
                    Text(if (retrievalPack.installed) "Replace signed pack" else "Import .agretrieval pack")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(20.dp)) {
                Text("Privacy posture", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "✓ App-private gallery memory\n" +
                        (if (BuildConfig.ALLOW_MODEL_DOWNLOAD) "✓ Network used only for selected model downloads\n" else "✓ No Internet permission\n") +
                        "✓ No cloud inference\n✓ System Photo Picker and partial access\n✓ Evidence source shown per result\n✓ Face identity search remains opt-in and unavailable until its model pack is installed",
                    lineHeight = 25.sp,
                )
            }
        }
    }
}

@Composable
private fun IndexMetric(label: String, value: Int, total: Int, version: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(label, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("$value / $total", color = Forest, fontWeight = FontWeight.Black, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { if (total == 0) 0f else value.toFloat() / total },
                modifier = Modifier.fillMaxWidth(),
                color = Forest,
                trackColor = Mist,
            )
            Spacer(Modifier.height(6.dp))
            Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun PrivacyScreen(onReviewOnboarding: () -> Unit) {
    Column(
        Modifier.fillMaxSize().semantics { contentDescription = "Privacy screen" }
            .verticalScroll(rememberScrollState()).padding(20.dp, 10.dp, 20.dp, 32.dp),
    ) {
        Text("Privacy", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Forest)
        Spacer(Modifier.height(8.dp))
        Text("Gallery media, derived indexes, queries, and answers remain on this phone.")
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Current protections", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (BuildConfig.ALLOW_MODEL_DOWNLOAD) "Network restricted to user-started model downloads" else "No Internet permission") +
                        "\nApp-private index storage\nNo cloud inference\nSystem media consent surfaces\nSensitive evidence authentication\nPeople identity indexing disabled by default",
                    lineHeight = 25.sp,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onReviewOnboarding) { Text("Review onboarding") }
    }
}

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().semantics { contentDescription = "Onboarding screen" }
            .verticalScroll(rememberScrollState()).padding(24.dp, 18.dp, 24.dp, 32.dp),
    ) {
        Text(
            "Your gallery stays yours",
            fontSize = 32.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.Black,
            color = Forest,
        )
        Spacer(Modifier.height(10.dp))
        Text("Choose media with Android's system pickers. Indexing and question answering run locally, and people search remains off until you explicitly enable it.")
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Mist), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Progressive capabilities", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "1. Metadata and albums\n2. Visual retrieval\n3. OCR and document facts\n4. Optional people and event memory",
                    lineHeight = 25.sp,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue to Ask") }
    }
}

@Composable
private fun EvidenceDialog(hit: SearchHit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Why this answer?", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.72f).verticalScroll(rememberScrollState())) {
                AssetImage(hit.item, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)))
                Spacer(Modifier.height(13.dp))
                Text(hit.item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(hit.item.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${hit.item.kind.name} • ${hit.item.source.name.replace('_', ' ')} • ${hit.item.indexState.name.replace('_', ' ')}", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                Text("EVIDENCE", color = Forest, fontSize = 11.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.height(7.dp))
                if (hit.evidence.isEmpty()) {
                    Text("This library view is supported by the item metadata record.")
                } else {
                    hit.evidence.forEachIndexed { index, evidence ->
                        if (index > 0) HorizontalDivider(Modifier.padding(vertical = 9.dp))
                        Text(evidence.sourceField.replace('_', ' ').uppercase(), fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(evidence.text, fontWeight = FontWeight.SemiBold)
                        Text("Confidence ${(evidence.confidence * 100).toInt()}% • ${evidence.producerVersion}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        evidence.region?.let { region ->
                            Text("Region ${region.joinToString(prefix = "[", postfix = "]") { "%.2f".format(it) }}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text("Description", fontWeight = FontWeight.Bold)
                Text(hit.item.description)
                Spacer(Modifier.height(12.dp))
                Text("${hit.item.license} • ${hit.item.creator ?: "Creator not listed"}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        shape = RoundedCornerShape(26.dp),
    )
}

@Composable
private fun AssetImage(item: GalleryItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bitmap = remember(item.assetPath, item.contentUri, item.previewPath) {
        runCatching {
            when {
                item.previewPath != null -> BitmapFactory.decodeFile(item.previewPath)
                item.assetPath != null -> context.assets.open(item.assetPath).use(BitmapFactory::decodeStream)
                item.contentUri != null -> {
                    val uri = Uri.parse(item.contentUri)
                    runCatching { context.contentResolver.loadThumbnail(uri, Size(720, 720), null) }.getOrNull()
                        ?: context.contentResolver.openInputStream(uri).use(BitmapFactory::decodeStream)
                }
                else -> null
            }?.asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = item.description,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(modifier.background(Mist), contentAlignment = Alignment.Center) { Text("Image unavailable") }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
}
