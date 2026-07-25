package com.askphotos.android

import android.graphics.BitmapFactory
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.MediaController
import android.widget.VideoView
import android.net.Uri
import android.os.Build
import android.util.Size
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.fragment.app.FragmentActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
private val Canvas = Color(0xFFF5F6F8)
private val Ink = Color(0xFF14201D)
private val Mist = Color(0xFFE9E9EE)
private val OneUiBlue = Color(0xFF4A63D8)

@Composable
private fun AskPhotosTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) {
            darkColorScheme(
                primary = Color(0xFFB9C3FF),
                onPrimary = Color(0xFF142568),
                primaryContainer = Color(0xFF2D438D),
                background = Color(0xFF0B0B0C),
                surface = Color(0xFF17171A),
                surfaceVariant = Color(0xFF29292E),
            )
        } else {
            lightColorScheme(
                primary = OneUiBlue,
                onPrimary = Color.White,
                primaryContainer = Color(0xFFDDE2FF),
                onPrimaryContainer = Color(0xFF142568),
                background = Canvas,
                onBackground = Color(0xFF19191D),
                surface = Color.White,
                onSurface = Color(0xFF19191D),
                surfaceVariant = Mist,
                onSurfaceVariant = Color(0xFF5E5E66),
                outline = Color(0xFFC6C6CE),
            )
        },
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
    val ocrModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importOcrModel(uri)
    }
    val faceModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.importFaceModel(uri)
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
        bottomBar = {
            if (state.destination != AppDestination.ONBOARDING) {
                AppNavigation(
                    selected = state.destination,
                    onSelect = viewModel::navigate,
                )
            }
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
                    onContinue = { viewModel.navigate(AppDestination.GALLERY) },
                )
                state.destination == AppDestination.ASK -> AskScreen(state, viewModel, evidenceGate::open)
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
                    onSearch = { viewModel.navigate(AppDestination.ASK) },
                    operationMessage = state.operationMessage,
                )
                state.destination == AppDestination.ALBUMS -> AlbumsScreen(
                    items = state.items,
                    onSelect = evidenceGate::open,
                    onPickMedia = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                )
                state.destination == AppDestination.MENU -> GalleryMenuScreen(
                    items = state.items,
                    onPhotos = { viewModel.navigate(AppDestination.GALLERY) },
                    onAlbums = { viewModel.navigate(AppDestination.ALBUMS) },
                    onDocuments = { viewModel.ask("Show documents") },
                    onVideos = { viewModel.ask("Show videos") },
                    onPeople = { viewModel.navigate(AppDestination.PEOPLE) },
                    onPrivacy = { viewModel.navigate(AppDestination.PRIVACY) },
                    onPlaces = { viewModel.ask("Show photos grouped by place") },
                    onSettings = { viewModel.navigate(AppDestination.INDEX_MANAGER) },
                )
                state.destination == AppDestination.INDEX_MANAGER -> IndexManagerScreen(
                    index = state.index,
                    peopleIndex = state.peopleIndex,
                    modelPack = state.modelPack,
                    modelDownload = state.modelDownload,
                    retrievalPack = state.retrievalPack,
                    retrievalProvision = state.retrievalProvision,
                    ocrModel = state.ocrModel,
                    ocrModelDownload = state.ocrModelDownload,
                    faceModel = state.faceModel,
                    faceModelDownload = state.faceModelDownload,
                    operationMessage = state.operationMessage,
                    indexingActive = state.indexingActive,
                    onRetry = viewModel::retryIndexing,
                    onImportModel = { modelPicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) },
                    onSelectModelTier = viewModel::selectModelTier,
                    onDownloadModel = viewModel::downloadSelectedModel,
                    onCancelModelDownload = viewModel::cancelModelDownload,
                    onImportRetrievalModel = {
                        retrievalModelPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onInstallEmbeddedRetrievalModel = viewModel::installEmbeddedRetrievalModel,
                    onDownloadOcrModel = viewModel::downloadOcrModel,
                    onCancelOcrModelDownload = viewModel::cancelOcrModelDownload,
                    onImportOcrModel = {
                        ocrModelPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    onDownloadFaceModel = viewModel::downloadFaceModel,
                    onCancelFaceModelDownload = viewModel::cancelFaceModelDownload,
                    onImportFaceModel = {
                        faceModelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )
                state.destination == AppDestination.PEOPLE -> PeopleScreen(
                    peopleIndex = state.peopleIndex,
                    clusters = state.peopleReviewClusters,
                    operationMessage = state.operationMessage,
                    onReviewCluster = { id, label, relationship, aliases ->
                        viewModel.saveReviewedPersonCluster(id, label, relationship, aliases)
                    },
                    onRemoveLabel = viewModel::removePersonLabel,
                    onSetHidden = viewModel::setPersonClusterHidden,
                    onMerge = viewModel::mergePersonClusters,
                    onMoveFace = viewModel::moveFaceToCluster,
                )
                else -> PrivacyScreen(
                    peopleIndex = state.peopleIndex,
                    operationMessage = state.operationMessage,
                    onEnablePeople = viewModel::enablePeopleIndexing,
                    onResetPeople = viewModel::resetPeopleIndex,
                    onReviewOnboarding = { viewModel.navigate(AppDestination.ONBOARDING) },
                )
            }
        }
    }
    state.selectedEvidence?.let { hit ->
        EvidenceDialog(
            hit = hit,
            onDismiss = viewModel::dismissEvidence,
            onAsk = { item ->
                viewModel.dismissEvidence()
                viewModel.updateQuery("Tell me about ${item.title}")
                viewModel.navigate(AppDestination.ASK)
            },
        )
    }
}

@Composable
private fun AppNavigation(selected: AppDestination, onSelect: (AppDestination) -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(34.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        NavigationBar(
            modifier = Modifier.height(68.dp),
            containerColor = Color.Transparent,
            tonalElevation = 0.dp,
        ) {
            GalleryNavigationItem(
                selected = selected == AppDestination.GALLERY,
                label = "Photos",
                icon = R.drawable.ic_gallery_photos,
                onClick = { onSelect(AppDestination.GALLERY) },
            )
            GalleryNavigationItem(
                selected = selected == AppDestination.ALBUMS,
                label = "Albums",
                icon = R.drawable.ic_gallery_albums,
                onClick = { onSelect(AppDestination.ALBUMS) },
            )
            GalleryNavigationItem(
                selected = selected == AppDestination.ASK || selected == AppDestination.RESULTS,
                label = "Ask",
                icon = R.drawable.ic_gallery_ask,
                onClick = { onSelect(AppDestination.ASK) },
            )
            GalleryNavigationItem(
                selected = selected == AppDestination.MENU || selected == AppDestination.INDEX_MANAGER || selected == AppDestination.PRIVACY ||
                    selected == AppDestination.PEOPLE,
                label = "Menu",
                icon = R.drawable.ic_gallery_menu,
                onClick = { onSelect(AppDestination.MENU) },
            )
        }
    }
}

@Composable
private fun RowScope.GalleryNavigationItem(
    selected: Boolean,
    label: String,
    icon: Int,
    onClick: () -> Unit,
) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(23.dp)) },
        label = { Text(label, fontSize = 11.sp) },
    )
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

private val suggestions = CapabilityRegistry.suggestedQueries

@Composable
private fun LegacyAskScreen(state: GalleryUiState, viewModel: GalleryViewModel, onEvidence: (SearchHit) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Ask results" },
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
                state.conversation.activeResultSetId?.let {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color = Mist,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().testTag("active-result-set").semantics {
                            contentDescription = "Active local result set"
                        },
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            val count = state.conversation.activeResultIds.size
                            Text(
                                if (count > 0) "Follow-up scope: $count saved ${if (count == 1) "result" else "results"}" else "The last result set was empty",
                                fontWeight = FontWeight.Bold,
                                color = Forest,
                            )
                            Text(
                                if (count > 0) {
                                    "Use “Only…”, “With…”, “What about…”, or “Which is best?” to refine these items without searching unrelated media."
                                } else {
                                    "Ask a new full question; an empty result set cannot be refined."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
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
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = viewModel::cancelQuery,
                            modifier = Modifier.testTag("cancel-query").semantics { contentDescription = "Cancel query" },
                        ) { Text("Cancel") }
                    }
                }
            }
        }

        if (state.progressiveHits.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Early candidates", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Forest)
                    Text(
                        "These local matches may be reordered or removed after verification.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
            items(state.progressiveHits.take(8), key = { "progress-${it.item.id}" }) { hit ->
                ResultTile(hit, onClick = { onEvidence(hit) })
            }
        }

        state.operationMessage?.takeIf { state.executionStatus == null }?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(color = Lime, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(14.dp), color = Forest, fontWeight = FontWeight.SemiBold)
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
private fun LegacyResultsScreen(
    outcome: SearchOutcome?,
    onEvidence: (SearchHit) -> Unit,
    onAsk: () -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Ask results" },
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
            item(span = { GridItemSpan(maxLineSpan) }) { AnswerCard(outcome, onAsk) }
            items(outcome.hits, key = { it.item.id }) { hit ->
                ResultTile(hit, onClick = { onEvidence(hit) })
            }
        }
    }
}

@Composable
private fun LegacyAnswerCard(outcome: SearchOutcome, onRefine: () -> Unit) {
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
            if (outcome.resultSetId != null && outcome.hits.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onRefine,
                    modifier = Modifier.testTag("refine-results"),
                    colors = ButtonDefaults.buttonColors(containerColor = Lime, contentColor = Forest),
                ) { Text("Refine these results") }
            }
            if (outcome.answer.claims.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                outcome.answer.claims.forEach { claim ->
                    Text("• ${claim.text}", color = Color.White, fontSize = 13.sp, lineHeight = 18.sp)
                    Text(
                        "Evidence ${claim.evidenceIds.joinToString()} • ${(claim.confidence * 100).toInt()}%",
                        color = Color(0xFFB9D6C8),
                        fontSize = 10.sp,
                    )
                }
            }
            outcome.answer.warnings.forEach { warning ->
                Spacer(Modifier.height(8.dp))
                Text(warning, color = Color(0xFFFFD7A8), fontSize = 11.sp, lineHeight = 15.sp)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactPill(outcome.answer.exactness.name.replace('_', ' '))
                FactPill("${outcome.elapsedMs} ms")
                FactPill(if (outcome.answer.requiresAuthentication) "AUTH REQUIRED" else "${outcome.answer.evidenceIds.size} evidence")
            }
            if (outcome.plan.baseResultIds != null) {
                Spacer(Modifier.height(10.dp))
                Text("↳ Refined within the previous result set", color = Lime, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LegacyFactPill(text: String) {
    Surface(color = Color(0xFF2A574B), shape = CircleShape) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegacyResultTile(hit: SearchHit, onClick: () -> Unit) {
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
                hit.evidence.mapNotNull { it.timestampMs }.minOrNull()?.let { timestamp ->
                    Text("Match at ${formatPlaybackTime(timestamp)}", color = Forest, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
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
private fun LegacyGalleryScreen(
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
    peopleIndex: PeopleIndexStatus,
    modelPack: ModelPackStatus,
    modelDownload: GemmaDownloadProgress,
    retrievalPack: RetrievalPackStatus,
    retrievalProvision: RetrievalProvisionProgress,
    ocrModel: OcrModelStatus,
    ocrModelDownload: OcrModelDownloadProgress,
    faceModel: FaceModelStatus,
    faceModelDownload: FaceModelDownloadProgress,
    operationMessage: String?,
    indexingActive: Boolean,
    onRetry: () -> Unit,
    onImportModel: () -> Unit,
    onSelectModelTier: (GemmaModelTier) -> Unit,
    onDownloadModel: () -> Unit,
    onCancelModelDownload: () -> Unit,
    onImportRetrievalModel: () -> Unit,
    onInstallEmbeddedRetrievalModel: () -> Unit,
    onDownloadOcrModel: () -> Unit,
    onCancelOcrModelDownload: () -> Unit,
    onImportOcrModel: () -> Unit,
    onDownloadFaceModel: () -> Unit,
    onCancelFaceModelDownload: () -> Unit,
    onImportFaceModel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(18.dp, 8.dp, 18.dp, 32.dp),
    ) {
        Text("On-device AI", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Agentic Gallery ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            modifier = Modifier.testTag("app-version"),
        )
        Spacer(Modifier.height(20.dp))
        IndexMetric("Media discovered", index.discovered, index.discovered, "Asset manifest")
        IndexMetric("Metadata ready", index.metadataReady, index.discovered, "demo-metadata-v1", inProgress = indexingActive && index.metadataReady < index.discovered)
        IndexMetric("Semantic facts ready", index.semanticFactsReady, index.discovered, "demo-sidecar-v1", inProgress = indexingActive && index.semanticFactsReady < index.discovered)
        IndexMetric(
            "OCR ready or skipped",
            index.ocrReady,
            index.discovered,
            ocrModel.producerVersion ?: "mlkit-text-latin-v2 fallback",
            inProgress = indexingActive && index.ocrReady < index.discovered,
        )
        IndexMetric("Visual labels ready", index.visualLabelsReady, index.discovered, "mlkit-image-label-v1 bundled", inProgress = indexingActive && index.visualLabelsReady < index.discovered)
        IndexMetric("Video keyframes", index.videoKeyframesReady, index.videoKeyframesReady, VideoKeyframePolicy.PRODUCER_VERSION)
        IndexMetric(
            "Face indexing",
            index.facesScanned,
            index.faceEligible,
            when {
                !peopleIndex.enabled -> "Off - enable People indexing in Privacy"
                faceModel.installed -> faceModel.producerVersion ?: FaceModelCatalog.sface.producerVersion
                else -> "mlkit-face-detection-v1; SFace not installed"
            },
            enabled = peopleIndex.enabled,
            inProgress = indexingActive && peopleIndex.pendingMediaCount > 0,
        )
        IndexMetric("Events", index.events, index.events, "deterministic day grouping")
        val pendingCount = index.pending + peopleIndex.pendingMediaCount
        if (pendingCount > 0 || index.failed > 0) {
            if (indexingActive) {
                Surface(
                    modifier = Modifier.fillMaxWidth().testTag("indexing-in-progress"),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(30.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Indexing in progress", fontWeight = FontWeight.SemiBold)
                            Text("$pendingCount remaining", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().testTag("resume-indexing")) { Text("Resume indexing") }
            }
            Spacer(Modifier.height(10.dp))
        }
        operationMessage?.let {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
        }
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(22.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Runtime policy", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(9.dp))
                Text("Offline hybrid local runtime", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(7.dp))
                Text("OCR, image labeling, face detection, PDF rendering, and bounded video keyframes run locally. PaddleOCR, Gemma, SigLIP2, and SFace use separate verified model packs with deterministic fallbacks where available.", color = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.height(12.dp))
                Text("Local database: ${formatBytes(index.storageBytes)}", color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text("Multilingual OCR engine", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text(if (ocrModel.installed) "${ocrModel.name} ${ocrModel.version}" else "ML Kit Latin fallback is active")
                Text(
                    "${ocrModel.license} - ${ocrModel.languages} - ${formatBytes(ocrModel.sizeBytes)} - verified SHA-256 files",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                Text(
                    "The OCR worker selects an engine through a provider registry, so another OCR implementation can be registered without changing indexing code.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                ocrModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                if (!ocrModel.installed && BuildConfig.ALLOW_MODEL_DOWNLOAD) {
                    Spacer(Modifier.height(10.dp))
                    when (ocrModelDownload.state) {
                        GemmaDownloadState.QUEUED, GemmaDownloadState.DOWNLOADING, GemmaDownloadState.VERIFYING -> {
                            SettingsCircularProgress(ocrModelDownload.fraction)
                            Text(
                                if (ocrModelDownload.state == GemmaDownloadState.VERIFYING) "Verifying SHA-256 and activating..." else "${formatBytes(ocrModelDownload.bytesDownloaded)} of ${formatBytes(ocrModelDownload.totalBytes)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                            TextButton(onClick = onCancelOcrModelDownload) { Text("Cancel download") }
                        }
                        else -> Button(
                            onClick = onDownloadOcrModel,
                            modifier = Modifier.fillMaxWidth().testTag("download-paddleocr"),
                        ) { Text("Download PaddleOCR multilingual") }
                    }
                } else if (!ocrModel.installed) {
                    Spacer(Modifier.height(8.dp))
                    Text("Offline demo build: import the verified PaddleOCR ZIP locally.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(onClick = onImportOcrModel) {
                    Text(if (ocrModel.installed) "Replace verified OCR pack" else "Import PaddleOCR ZIP")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text("Gemma model pack", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text(if (modelPack.installed) modelPack.name else "Selected model is not installed — deterministic planning remains active")
                Text(modelPack.runtimeVersion, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GemmaModelCatalog.all.forEach { spec ->
                        FilterChip(
                            modifier = Modifier.testTag("gemma-tier-${spec.tier.name}"),
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
                            SettingsCircularProgress(modelDownload.fraction)
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
                                modifier = Modifier.fillMaxWidth().testTag("download-gemma-${selectedSpec.tier.name}"),
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
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text("Face identity model", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(7.dp))
                Text(if (faceModel.installed) "${faceModel.name} ${faceModel.version}" else "Not installed — face boxes only")
                Text(
                    "Apache-2.0 • 128 dimensions • ${formatBytes(faceModel.sizeBytes)} • pinned SHA-256 ${faceModel.sha256.take(12)}…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                faceModel.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                if (!faceModel.installed && faceModelDownload.state in setOf(
                        GemmaDownloadState.QUEUED,
                        GemmaDownloadState.DOWNLOADING,
                        GemmaDownloadState.VERIFYING,
                    )
                ) {
                    Spacer(Modifier.height(10.dp))
                    SettingsCircularProgress(faceModelDownload.fraction)
                    Text(
                        if (faceModelDownload.state == GemmaDownloadState.VERIFYING) {
                            "Verifying embedded SFace and activating…"
                        } else {
                            "Installing embedded SFace… ${formatBytes(faceModelDownload.bytesDownloaded)} of ${formatBytes(faceModelDownload.totalBytes)}"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                } else if (!faceModel.installed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The embedded SFace model is installed automatically into app-private storage.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    faceModelDownload.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                    if (BuildConfig.ALLOW_MODEL_DOWNLOAD) {
                        Button(
                            onClick = onDownloadFaceModel,
                            modifier = Modifier.fillMaxWidth().testTag("download-sface"),
                        ) { Text("Retry from verified source") }
                    }
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(onClick = onImportFaceModel) {
                    Text(if (faceModel.installed) "Replace pinned ONNX" else "Import pinned ONNX")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
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
                if (!retrievalPack.installed) {
                    Spacer(Modifier.height(10.dp))
                    when (retrievalProvision.state) {
                        GemmaDownloadState.QUEUED, GemmaDownloadState.DOWNLOADING, GemmaDownloadState.VERIFYING -> {
                            SettingsCircularProgress(retrievalProvision.fraction)
                            Text(
                                if (retrievalProvision.state == GemmaDownloadState.VERIFYING) {
                                    "Verifying embedded archive and every model artifact..."
                                } else {
                                    "Installing ${formatBytes(retrievalProvision.bytesCopied)} of ${formatBytes(retrievalProvision.totalBytes)} from the APK"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        else -> {
                            Button(
                                onClick = onInstallEmbeddedRetrievalModel,
                                modifier = Modifier.fillMaxWidth().testTag("install-embedded-siglip2"),
                            ) { Text("Install embedded SigLIP2") }
                            retrievalProvision.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 11.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(11.dp))
                OutlinedButton(onClick = onImportRetrievalModel) {
                    Text(if (retrievalPack.installed) "Replace signed pack" else "Import .agretrieval pack")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp)) {
                Text("Privacy posture", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "✓ App-private gallery memory\n" +
                        (if (BuildConfig.ALLOW_MODEL_DOWNLOAD) "✓ Network used only for selected model downloads\n" else "✓ No Internet permission\n") +
                        "✓ No cloud inference\n✓ System Photo Picker and partial access\n✓ Evidence source shown per result\n✓ Face identity search remains opt-in; SFace embeddings stay app-private",
                    lineHeight = 25.sp,
                )
            }
        }
    }
}

@Composable
private fun SettingsCircularProgress(fraction: Float) {
    val safeFraction = fraction.coerceIn(0f, 1f)
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(48.dp).testTag("settings-card-progress"), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { safeFraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 4.dp)
            Text("${(safeFraction * 100).toInt()}%", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IndexMetric(
    label: String,
    value: Int,
    total: Int,
    version: String,
    enabled: Boolean = true,
    inProgress: Boolean = false,
) {
    val fraction = if (total <= 0) 0f else (value.toFloat() / total).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.Bold)
                    Text(version, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Spacer(Modifier.width(12.dp))
                when {
                    !enabled -> Text("Off", color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
                    inProgress -> Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxSize(), strokeWidth = 4.dp)
                        Text("${(fraction * 100).toInt()}%", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    else -> Text("$value / $total", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun PrivacyScreen(
    peopleIndex: PeopleIndexStatus,
    operationMessage: String?,
    onEnablePeople: () -> Unit,
    onResetPeople: () -> Unit,
    onReviewOnboarding: () -> Unit,
) {
    var confirmation by remember { mutableStateOf<PeopleConfirmation?>(null) }
    Column(
        Modifier.fillMaxSize().semantics { contentDescription = "Privacy screen" }
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp, 10.dp, 20.dp, 32.dp),
    ) {
        Text("Privacy", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("Gallery media, derived indexes, queries, and answers remain on this phone.")
        Spacer(Modifier.height(18.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
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
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    if (peopleIndex.enabled) "People indexing enabled" else "People indexing off",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
                Spacer(Modifier.height(8.dp))
                if (peopleIndex.enabled) {
                    Text(
                        "Face detection runs only on this phone. ${peopleIndex.faceInstanceCount} face boxes are stored in app-private memory; " +
                            "${peopleIndex.reviewedClusterCount} identity clusters have been reviewed.",
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${if (peopleIndex.identityReadyFaceCount > 0) "SFace embeddings and local clusters are active." else "Identity embeddings will start after the verified SFace pack is installed."} The app does not infer sensitive traits.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { confirmation = PeopleConfirmation.RESET },
                        modifier = Modifier.fillMaxWidth().testTag("reset-people-index"),
                    ) { Text("Turn off and delete people data") }
                } else {
                    Text("No personal face boxes, identity embeddings, clusters, labels, or aliases are retained.")
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { confirmation = PeopleConfirmation.ENABLE },
                        modifier = Modifier.fillMaxWidth().testTag("enable-people-index"),
                    ) { Text("Enable people indexing") }
                }
            }
        }
        operationMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        OutlinedButton(onClick = onReviewOnboarding) { Text("Review onboarding") }
    }

    confirmation?.let { action ->
        val enabling = action == PeopleConfirmation.ENABLE
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(if (enabling) "Enable private face detection?" else "Delete all people data?") },
            text = {
                Text(
                    if (enabling) {
                        "The app will detect face regions in consented images and keep derived data only in app-private storage. You can erase it at any time. Identity search remains unavailable until you review clusters created by a compatible local model."
                    } else {
                        "This stops people indexing and permanently deletes every stored face box, identity embedding reference, cluster, label, and alias. Your original gallery media is not changed."
                    },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmation = null
                        if (enabling) onEnablePeople() else onResetPeople()
                    },
                    modifier = Modifier.testTag(if (enabling) "confirm-enable-people" else "confirm-reset-people"),
                ) { Text(if (enabling) "Enable on device" else "Delete people data") }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }
}

private enum class PeopleConfirmation { ENABLE, RESET }

@Composable
private fun PeopleScreen(
    peopleIndex: PeopleIndexStatus,
    clusters: List<PersonClusterReviewItem>,
    operationMessage: String?,
    onReviewCluster: (String, String, String?, List<String>) -> Unit,
    onRemoveLabel: (String) -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
    onMerge: (String, String) -> Unit,
    onMoveFace: (String, String?) -> Unit,
) {
    var editingCluster by remember { mutableStateOf<PersonClusterReviewItem?>(null) }
    val toReview = clusters.filter { !it.reviewed && !it.hidden }
    val named = clusters.filter { it.reviewed && !it.hidden }
    val hidden = clusters.filter(PersonClusterReviewItem::hidden)
    androidx.compose.foundation.lazy.LazyColumn(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp, 10.dp, 20.dp, 32.dp),
    ) {
        item(key = "people-header") {
            Text("People", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Review and correct local face clusters. Original photos are never changed.")
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("People identity status", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("${toReview.size} to review • ${named.size} named • ${hidden.size} hidden")
                    Spacer(Modifier.height(6.dp))
                    if (!peopleIndex.enabled) {
                        Text("People indexing is currently off. Enable it in Privacy first.")
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!peopleIndex.enabled) {
            item(key = "people-disabled") {
                Text("No review is possible while people indexing is disabled.")
            }
        } else if (clusters.isEmpty()) {
            item(key = "people-processing") {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Face clusters are still being processed.", fontWeight = FontWeight.SemiBold)
                        Text("Indexed clusters will appear here without restarting face indexing.")
                    }
                }
            }
        } else {
            if (toReview.isNotEmpty()) {
                item(key = "to-review-heading") {
                    Text("To review", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                }
                items(
                    count = toReview.size,
                    key = { index -> "review-${toReview[index].id}" },
                ) { index ->
                    val cluster = toReview[index]
                    PersonClusterCard(cluster, "Tag this person", { editingCluster = cluster }, onSetHidden)
                }
            }
            if (named.isNotEmpty()) {
                item(key = "named-heading") {
                    Spacer(Modifier.height(14.dp))
                    Text("Named people", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                }
                items(
                    count = named.size,
                    key = { index -> "named-${named[index].id}" },
                ) { index ->
                    val cluster = named[index]
                    PersonClusterCard(cluster, "Edit person", { editingCluster = cluster }, onSetHidden)
                }
            }
            if (hidden.isNotEmpty()) {
                item(key = "hidden-heading") {
                    Spacer(Modifier.height(14.dp))
                    Text("Hidden", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                }
                items(
                    count = hidden.size,
                    key = { index -> "hidden-${hidden[index].id}" },
                ) { index ->
                    val cluster = hidden[index]
                    PersonClusterCard(cluster, "Edit", { editingCluster = cluster }, onSetHidden)
                }
            }
        }
        operationMessage?.let { message ->
            item(key = "people-operation") {
                Spacer(Modifier.height(12.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(message, Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }

    editingCluster?.let { cluster ->
        var label by remember(cluster.id) { mutableStateOf(cluster.label.orEmpty()) }
        var relationship by remember(cluster.id) { mutableStateOf(cluster.relationship.orEmpty()) }
        var aliases by remember(cluster.id) { mutableStateOf(cluster.aliases.joinToString(", ")) }
        var mergeTarget by remember(cluster.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingCluster = null },
            title = { Text(if (cluster.reviewed) "Edit ${cluster.label}" else "Tag this person") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    cluster.representativeFace?.let {
                        FaceCropImage(it, Modifier.size(104.dp).clip(RoundedCornerShape(20.dp)))
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("Names, relationships, and aliases stay only in the app-private people index.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = relationship,
                        onValueChange = { relationship = it },
                        label = { Text("Relationship (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Me, mother, father, brother, sister, partner, child, friend, other/custom", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { relationship = "Me"; if (label.isBlank()) label = "Me" }) { Text("Mark as Me") }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text("Aliases, comma separated") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onReviewCluster(cluster.id, label, relationship.ifBlank { null }, parseAliases(aliases)) ; editingCluster = null }),
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = mergeTarget,
                        onValueChange = { mergeTarget = it },
                        label = { Text("Target cluster ID for merge/move") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (mergeTarget.isNotBlank()) {
                        TextButton(onClick = { onMerge(mergeTarget.trim(), cluster.id); editingCluster = null }) {
                            Text("Merge this cluster into target")
                        }
                    }
                    if (cluster.supportingFaces.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("Correct faces", fontWeight = FontWeight.SemiBold)
                        cluster.supportingFaces.forEach { face ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                FaceCropImage(face, Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)))
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(face.item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                                    TextButton(onClick = { onMoveFace(face.id, null); editingCluster = null }) { Text("Not this person") }
                                    if (mergeTarget.isNotBlank()) {
                                        TextButton(onClick = { onMoveFace(face.id, mergeTarget.trim()); editingCluster = null }) {
                                            Text("Move to target")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (cluster.reviewed) {
                        TextButton(onClick = { onRemoveLabel(cluster.id); editingCluster = null }) { Text("Remove local label") }
                    }
                    TextButton(onClick = { onSetHidden(cluster.id, !cluster.hidden); editingCluster = null }) {
                        Text(if (cluster.hidden) "Unhide cluster" else "Hide cluster")
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = label.isNotBlank(),
                    onClick = {
                        onReviewCluster(cluster.id, label, relationship.ifBlank { null }, parseAliases(aliases))
                        editingCluster = null
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingCluster = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PersonClusterCard(
    cluster: PersonClusterReviewItem,
    actionLabel: String,
    onEdit: () -> Unit,
    onSetHidden: (String, Boolean) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                cluster.representativeFace?.let {
                    FaceCropImage(it, Modifier.size(82.dp).clip(RoundedCornerShape(18.dp)))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(cluster.label ?: "Unreviewed person", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${cluster.faceCount} faces • ${if (cluster.reviewed) "reviewed" else "to review"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    cluster.relationship?.let { Text(it, fontSize = 12.sp) }
                    if (cluster.aliases.isNotEmpty()) Text(cluster.aliases.joinToString(", "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (cluster.supportingFaces.size > 1) {
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    cluster.supportingFaces.drop(1).take(3).forEach { face ->
                        FaceCropImage(face, Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)))
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onEdit) { Text(actionLabel) }
                TextButton(onClick = { onSetHidden(cluster.id, !cluster.hidden) }) { Text(if (cluster.hidden) "Unhide" else "Hide") }
            }
        }
    }
}

@Composable
private fun FaceCropImage(face: PersonFaceReviewItem, modifier: Modifier) {
    val context = LocalContext.current
    val crop by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = face.id,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            decodeFaceThumbnail(context.applicationContext, face)
        }
    }
    if (crop != null) {
        Image(requireNotNull(crop), face.item.description, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Face", fontSize = 10.sp)
        }
    }
}

private fun decodeFaceThumbnail(
    context: android.content.Context,
    face: PersonFaceReviewItem,
): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    fun openSource(): java.io.InputStream? = when {
        face.item.previewPath != null -> java.io.File(face.item.previewPath).inputStream()
        face.item.assetPath != null -> context.assets.open(face.item.assetPath)
        face.item.contentUri != null -> context.contentResolver.openInputStream(Uri.parse(face.item.contentUri))
        else -> null
    }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openSource()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@runCatching null
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val sample = FaceCropSamplingPolicy.sampleSize(bounds.outWidth, bounds.outHeight)
    val source = openSource()?.use {
        BitmapFactory.decodeStream(
            it,
            null,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            },
        )
    } ?: return@runCatching null
    val faceWidth = (face.right - face.left).coerceAtLeast(.01f)
    val faceHeight = (face.bottom - face.top).coerceAtLeast(.01f)
    val left = ((face.left - faceWidth * .25f).coerceIn(0f, 1f) * source.width)
        .toInt()
        .coerceIn(0, source.width - 1)
    val top = ((face.top - faceHeight * .3f).coerceIn(0f, 1f) * source.height)
        .toInt()
        .coerceIn(0, source.height - 1)
    val right = ((face.right + faceWidth * .25f).coerceIn(0f, 1f) * source.width)
        .toInt()
        .coerceIn(left + 1, source.width)
    val bottom = ((face.bottom + faceHeight * .35f).coerceIn(0f, 1f) * source.height)
        .toInt()
        .coerceIn(top + 1, source.height)
    val cropped = android.graphics.Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    if (cropped !== source) source.recycle()
    val maxEdge = maxOf(cropped.width, cropped.height)
    val output = if (maxEdge <= FaceCropSamplingPolicy.OUTPUT_MAX_EDGE) {
        cropped
    } else {
        val scale = FaceCropSamplingPolicy.OUTPUT_MAX_EDGE.toFloat() / maxEdge
        val scaled = android.graphics.Bitmap.createScaledBitmap(
            cropped,
            (cropped.width * scale).toInt().coerceAtLeast(1),
            (cropped.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== cropped) cropped.recycle()
        scaled
    }
    output.asImageBitmap()
}.getOrNull()

internal object FaceCropSamplingPolicy {
    const val SOURCE_MAX_EDGE = 1024
    const val OUTPUT_MAX_EDGE = 256

    fun sampleSize(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (maxOf(width / sample, height / sample) > SOURCE_MAX_EDGE) sample *= 2
        return sample
    }
}

private fun parseAliases(rawAliases: String): List<String> = rawAliases
    .split(",")
    .map { it.trim() }
    .filter { it.isNotBlank() }
    .distinct()
    .take(16)

@Composable
private fun OnboardingScreen(onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().semantics { contentDescription = "Onboarding screen" }
            .safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp, 18.dp, 24.dp, 32.dp),
    ) {
        Text(
            "Your gallery stays yours",
            fontSize = 32.sp,
            lineHeight = 36.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text("Choose media with Android's system pickers. Indexing and question answering run locally, and people search remains off until you explicitly enable it.")
        Spacer(Modifier.height(20.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(20.dp)) {
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
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue to Photos") }
    }
}

@Composable
internal fun LegacyEvidenceDialog(hit: SearchHit, onDismiss: () -> Unit) {
    val playbackTimestamp = hit.evidence.mapNotNull { it.timestampMs }.minOrNull()
    var playing by remember(hit.item.id, playbackTimestamp) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Why this answer?", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().fillMaxHeight(.72f).verticalScroll(rememberScrollState())) {
                if (playing && hit.item.kind == MediaKind.VIDEO && hit.item.contentUri != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)).testTag("video-playback"),
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                val controls = MediaController(viewContext)
                                controls.setAnchorView(this)
                                setMediaController(controls)
                                setVideoURI(Uri.parse(hit.item.contentUri))
                                setOnPreparedListener {
                                    seekTo((playbackTimestamp ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                                    start()
                                }
                            }
                        },
                        onRelease = VideoView::stopPlayback,
                    )
                } else {
                    AssetImage(hit.item, Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(16.dp)))
                }
                Spacer(Modifier.height(13.dp))
                Text(hit.item.title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(hit.item.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("${hit.item.kind.name} • ${hit.item.source.name.replace('_', ' ')} • ${hit.item.indexState.name.replace('_', ' ')}", fontSize = 11.sp, color = Forest, fontWeight = FontWeight.SemiBold)
                if (hit.item.kind == MediaKind.VIDEO && hit.item.contentUri != null && playbackTimestamp != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { playing = true },
                        modifier = Modifier.testTag("play-video-at-match"),
                    ) { Text("Play from ${formatPlaybackTime(playbackTimestamp)}") }
                }
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
                        evidence.pageIndex?.let { Text("PDF page ${it + 1}", fontSize = 11.sp, color = Forest) }
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
private fun AssetImage(
    item: GalleryItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
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
            contentScale = contentScale,
        )
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("Image unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AskScreen(state: GalleryUiState, viewModel: GalleryViewModel, onEvidence: (SearchHit) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Ask results" },
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(start = 14.dp, top = 48.dp, end = 14.dp, bottom = 14.dp)) {
                Text("Ask", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(14.dp))
                state.conversation.activeResultSetId?.let {
                    val count = state.conversation.activeResultIds.size
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.fillMaxWidth().testTag("active-result-set"),
                    ) {
                        Text(
                            if (count > 0) "Refining $count ${if (count == 1) "result" else "results"}" else "Previous search had no matches",
                            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::updateQuery,
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Gallery question" },
                    placeholder = { Text("Search photos or ask a question") },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.ask() }),
                    trailingIcon = {
                        IconButton(
                            onClick = { viewModel.ask() },
                            enabled = state.query.isNotBlank() && state.executionStatus == null,
                            modifier = Modifier.testTag("submit-question").semantics { contentDescription = "Submit question" },
                        ) {
                            Icon(painterResource(R.drawable.ic_gallery_ask), contentDescription = null)
                        }
                    },
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("People", "Places", "Documents").forEach { category ->
                        SuggestionChip(
                            onClick = { viewModel.ask("Show $category") },
                            label = { Text(category, maxLines = 1) },
                        )
                    }
                }
            }
        }
        state.executionStatus?.let { status ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(status, modifier = Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        TextButton(onClick = viewModel::cancelQuery, modifier = Modifier.testTag("cancel-query")) { Text("Cancel") }
                    }
                }
            }
        }
        if (state.progressiveHits.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Possible matches", Modifier.padding(14.dp, 14.dp, 14.dp, 8.dp), fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            }
            items(state.progressiveHits.take(24), key = { "progress-${it.item.id}" }) { hit ->
                ResultTile(hit) { onEvidence(hit) }
            }
        }
        state.operationMessage?.takeIf { state.executionStatus == null }?.let { message ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(14.dp),
                ) { Text(message, Modifier.padding(12.dp), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable
private fun ResultsScreen(outcome: SearchOutcome?, onEvidence: (SearchHit) -> Unit, onAsk: () -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Ask results" },
        contentPadding = PaddingValues(start = 2.dp, top = 46.dp, end = 2.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (outcome == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(20.dp)) {
                    Text("No results yet", fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onAsk) { Text("Ask") }
                }
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) { AnswerCard(outcome, onAsk) }
            items(outcome.hits, key = { it.item.id }) { hit -> ResultTile(hit) { onEvidence(hit) } }
        }
    }
}

@Composable
private fun AnswerCard(outcome: SearchOutcome, onRefine: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp).semantics { contentDescription = "Answer ${outcome.answer.headline}" },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(outcome.answer.headline, fontSize = 21.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold)
            if (outcome.answer.detail.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(outcome.answer.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 20.sp)
            }
            if (outcome.resultSetId != null && outcome.hits.isNotEmpty()) {
                TextButton(onClick = onRefine, modifier = Modifier.testTag("refine-results")) { Text("Refine") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FactPill(outcome.answer.exactness.name.replace('_', ' '))
                FactPill(if (outcome.answer.requiresAuthentication) "Unlock required" else "${outcome.answer.evidenceIds.size} sources")
            }
            val reports = outcome.channelReports.filter { it.status != ChannelStatus.NOT_REQUIRED }
            if (reports.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Retrieval coverage", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                reports.forEach { report ->
                    Text(
                        "${report.channel.name.replace('_', ' ').lowercase()}: ${report.status.name.lowercase()} - " +
                            "${report.searchedCount}/${report.eligibleCount} searched",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun FactPill(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape) {
        Text(text.lowercase(), Modifier.padding(horizontal = 9.dp, vertical = 5.dp), fontSize = 10.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ResultTile(hit: SearchHit, onClick: () -> Unit) {
    MediaThumbnail(hit.item, hit.duplicateIds.size, selected = false, onClick = onClick, onLongClick = onClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    item: GalleryItem,
    duplicateCount: Int = 0,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { contentDescription = item.description },
    ) {
        AssetImage(item, Modifier.fillMaxSize())
        item.durationMs?.takeIf { item.kind == MediaKind.VIDEO }?.let { duration ->
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                color = Color.Black.copy(alpha = .68f),
                shape = RoundedCornerShape(5.dp),
            ) { Text(formatPlaybackTime(duration), Modifier.padding(horizontal = 5.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp) }
        }
        if (duplicateCount > 0) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                color = Color.Black.copy(alpha = .68f),
                shape = CircleShape,
            ) { Text("+$duplicateCount", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = Color.White, fontSize = 10.sp) }
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .3f)))
            Surface(
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
            ) { Text("✓", Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold) }
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
    onSearch: () -> Unit,
    operationMessage: String?,
) {
    var importMenu by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    val context = LocalContext.current
    val groups = remember(items) {
        items.sortedWith(compareByDescending<GalleryItem> { it.capturedAt ?: it.modifiedAt ?: 0L }.thenBy { it.id })
            .groupBy { galleryDayLabel(it.capturedAt ?: it.modifiedAt) }
            .toList()
    }
    BackHandler(selectedIds.isNotEmpty()) { selectedIds = emptySet() }
    Box(Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Gallery screen; ${items.count { it.source != MediaSource.DEMO_ASSET }} imported items" }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = if (selectedIds.isEmpty()) 24.dp else 96.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(start = 14.dp, top = 42.dp, end = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedIds.isEmpty()) {
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = onSearch) { Icon(painterResource(R.drawable.ic_gallery_search), "Search") }
                            Box {
                                IconButton(onClick = { importMenu = true }) { Icon(painterResource(R.drawable.ic_gallery_more), "More") }
                                DropdownMenu(expanded = importMenu, onDismissRequest = { importMenu = false }) {
                                    DropdownMenuItem(text = { Text("Pick photos and videos") }, onClick = { importMenu = false; onPickMedia() })
                                    DropdownMenuItem(text = { Text("Import accessible gallery") }, onClick = { importMenu = false; onFullGallery() })
                                    DropdownMenuItem(text = { Text("Add documents") }, onClick = { importMenu = false; onPickDocuments() })
                                }
                            }
                        } else {
                            Text("${selectedIds.size} selected", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selectedIds = items.mapTo(mutableSetOf()) { it.id } }) { Text("All") }
                            TextButton(onClick = { selectedIds = emptySet() }) { Text("Cancel") }
                        }
                    }
                    operationMessage?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
            groups.forEach { (label, groupItems) ->
                item(key = "day-$label", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                        groupItems.firstOrNull()?.location?.takeIf { it.isNotBlank() }?.let {
                            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                items(groupItems, key = { it.id }) { item ->
                    val selected = item.id in selectedIds
                    MediaThumbnail(
                        item = item,
                        selected = selected,
                        onClick = {
                            if (selectedIds.isNotEmpty()) {
                                selectedIds = if (selected) selectedIds - item.id else selectedIds + item.id
                            } else {
                                onSelect(item.asMetadataHit())
                            }
                        },
                        onLongClick = { selectedIds = selectedIds + item.id },
                    )
                }
            }
        }
        if (selectedIds.isNotEmpty()) {
            SelectionToolbar(
                count = selectedIds.size,
                modifier = Modifier.align(Alignment.BottomCenter),
                onShare = { shareItems(context, items.filter { it.id in selectedIds }) },
                onAsk = { selectedIds = emptySet(); onSearch() },
                onClear = { selectedIds = emptySet() },
            )
        }
    }
}

@Composable
private fun SelectionToolbar(count: Int, modifier: Modifier = Modifier, onShare: () -> Unit, onAsk: () -> Unit, onClear: () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(14.dp),
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("$count", Modifier.padding(horizontal = 10.dp), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onAsk) { Text("Ask") }
            TextButton(onClick = onShare) { Text("Share") }
            TextButton(onClick = onClear) { Text("Clear") }
        }
    }
}

@Composable
private fun AlbumsScreen(items: List<GalleryItem>, onSelect: (SearchHit) -> Unit, onPickMedia: () -> Unit) {
    var activeAlbum by remember { mutableStateOf<String?>(null) }
    val albums = remember(items) {
        items.groupBy { item -> item.album.ifBlank { item.location.ifBlank { "Other" } } }
            .toList().sortedByDescending { (_, media) -> media.maxOfOrNull { it.capturedAt ?: it.modifiedAt ?: 0L } ?: 0L }
    }
    BackHandler(activeAlbum != null) { activeAlbum = null }
    val visibleItems = activeAlbum?.let { name -> albums.firstOrNull { it.first == name }?.second }.orEmpty()
    LazyVerticalGrid(
        columns = GridCells.Fixed(if (activeAlbum == null) 2 else 4),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Albums screen" },
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(if (activeAlbum == null) 12.dp else 2.dp),
        verticalArrangement = Arrangement.spacedBy(if (activeAlbum == null) 14.dp else 2.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(start = 6.dp, top = 48.dp, end = 2.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (activeAlbum == null) {
                    Text("Albums", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onPickMedia) { Text("+", fontSize = 28.sp, fontWeight = FontWeight.Light) }
                } else {
                    TextButton(onClick = { activeAlbum = null }) { Text("Back") }
                    Text(activeAlbum ?: "", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (activeAlbum == null) {
            items(albums, key = { it.first }) { (name, albumItems) ->
                Column(Modifier.clickable { activeAlbum = name }) {
                    AssetImage(albumItems.first(), Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)))
                    Text(name, Modifier.padding(top = 7.dp), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${albumItems.size}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        } else {
            items(visibleItems, key = { it.id }) { item ->
                MediaThumbnail(item, selected = false, onClick = { onSelect(item.asMetadataHit()) }, onLongClick = { onSelect(item.asMetadataHit()) })
            }
        }
    }
}

private data class GalleryMenuAction(val label: String, val icon: Int, val count: Int? = null, val onClick: () -> Unit)

@Composable
private fun GalleryMenuScreen(
    items: List<GalleryItem>,
    onPhotos: () -> Unit,
    onAlbums: () -> Unit,
    onDocuments: () -> Unit,
    onVideos: () -> Unit,
    onPeople: () -> Unit,
    onPrivacy: () -> Unit,
    onPlaces: () -> Unit,
    onSettings: () -> Unit,
) {
    val actions = listOf(
        GalleryMenuAction("Videos", R.drawable.ic_gallery_video, items.count { it.kind == MediaKind.VIDEO }, onVideos),
        GalleryMenuAction("Recent", R.drawable.ic_gallery_photos, items.size, onPhotos),
        GalleryMenuAction("Albums", R.drawable.ic_gallery_albums, null, onAlbums),
        GalleryMenuAction("Documents", R.drawable.ic_gallery_search, items.count { it.kind == MediaKind.PDF }, onDocuments),
        GalleryMenuAction("People", R.drawable.ic_gallery_people, null, onPeople),
        GalleryMenuAction("Places", R.drawable.ic_gallery_search, items.map { it.location }.filter { it.isNotBlank() }.distinct().size, onPlaces),
        GalleryMenuAction("Privacy", R.drawable.ic_gallery_privacy, null, onPrivacy),
        GalleryMenuAction("Settings", R.drawable.ic_gallery_settings, null, onSettings),
    )
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = "Gallery menu" },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Menu", Modifier.padding(top = 50.dp, bottom = 22.dp), fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        }
        items(actions, key = { it.label }) { action ->
            Column(
                modifier = Modifier.clickable(onClick = action.onClick).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                    Icon(painterResource(action.icon), action.label, Modifier.padding(17.dp).size(26.dp), tint = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(7.dp))
                Text(action.label, fontSize = 12.sp, maxLines = 1)
                action.count?.let { Text("$it", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp) }
            }
        }
    }
}

@Composable
internal fun EvidenceDialog(hit: SearchHit, onDismiss: () -> Unit, onAsk: ((GalleryItem) -> Unit)? = null) {
    val context = LocalContext.current
    val playbackTimestamp = hit.evidence.mapNotNull { it.timestampMs }.minOrNull()
    var playing by remember(hit.item.id, playbackTimestamp) { mutableStateOf(false) }
    var detailsVisible by remember(hit.item.id) { mutableStateOf(false) }
    var favorite by remember(hit.item.id) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                if (playing && hit.item.kind == MediaKind.VIDEO && hit.item.contentUri != null) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize().testTag("video-playback"),
                        factory = { viewContext ->
                            VideoView(viewContext).apply {
                                val controls = MediaController(viewContext)
                                controls.setAnchorView(this)
                                setMediaController(controls)
                                setVideoURI(Uri.parse(hit.item.contentUri))
                                setOnPreparedListener {
                                    seekTo((playbackTimestamp ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                                    start()
                                }
                            }
                        },
                        onRelease = VideoView::stopPlayback,
                    )
                } else {
                    AssetImage(hit.item, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
                Row(
                    Modifier.fillMaxWidth().safeDrawingPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f)) {
                        TextButton(onClick = onDismiss) { Text("Back", color = Color.White) }
                    }
                    Spacer(Modifier.weight(1f))
                    Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f)) {
                        IconButton(onClick = { detailsVisible = !detailsVisible }) {
                            Icon(painterResource(R.drawable.ic_gallery_info), "Details and evidence", tint = Color.White)
                        }
                    }
                }
                if (!detailsVisible) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                        shape = RoundedCornerShape(30.dp),
                        color = Color.Black.copy(alpha = .68f),
                    ) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ViewerAction(if (favorite) "Favorited" else "Favorite", R.drawable.ic_gallery_photos) { favorite = !favorite }
                            onAsk?.let { ask -> ViewerAction("Ask", R.drawable.ic_gallery_ask) { ask(hit.item) } }
                            ViewerAction("Share", R.drawable.ic_gallery_share) { shareItems(context, listOf(hit.item)) }
                            if (hit.item.kind == MediaKind.VIDEO && hit.item.contentUri != null) {
                                ViewerAction("Play", R.drawable.ic_gallery_video) { playing = true }
                            }
                            ViewerAction("Details", R.drawable.ic_gallery_info) { detailsVisible = true }
                        }
                    }
                } else {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.58f),
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp, 18.dp, 20.dp, 34.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(hit.item.title, modifier = Modifier.weight(1f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                                TextButton(onClick = { detailsVisible = false }) { Text("Close") }
                            }
                            Text(hit.item.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            hit.evidence.forEachIndexed { index, evidence ->
                                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                Text(evidence.sourceField.replace('_', ' '), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(evidence.text)
                                Text("${(evidence.confidence * 100).toInt()}% confidence", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                            }
                            if (hit.evidence.isEmpty()) Text("Supported by this item's local metadata record.")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerAction(label: String, icon: Int, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(painterResource(icon), label, Modifier.size(22.dp), tint = Color.White)
        Text(label, color = Color.White, fontSize = 10.sp)
    }
}

private fun GalleryItem.asMetadataHit(): SearchHit = SearchHit(
    item = this,
    score = 1.0,
    evidence = listOf(EvidenceRecord("$id:metadata", id, "metadata", title, 1f)),
)

private fun galleryDayLabel(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0L) return "Undated"
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
    val today = java.time.LocalDate.now(zone)
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
}

private fun shareItems(context: android.content.Context, items: List<GalleryItem>) {
    if (items.isEmpty()) return
    val uris = ArrayList(items.mapNotNull { it.contentUri }.map(Uri::parse))
    val intent = if (uris.size > 1) {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = items.first().mimeType
            if (uris.isNotEmpty()) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putExtra(Intent.EXTRA_TEXT, items.first().sourceUrl.ifBlank { items.first().title })
        }
    }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, "Share from Gallery")) }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / 1024f / 1024f)} MB"
}

private fun formatPlaybackTime(timestampMs: Long): String {
    val totalSeconds = timestampMs.coerceAtLeast(0L) / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
