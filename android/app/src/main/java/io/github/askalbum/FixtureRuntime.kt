package io.github.anup42.askalbum

import android.content.Context
import java.io.File
import java.util.Locale

/**
 * Model-independent debug variants use the deterministic engines from the debug source set.
 * Reflection keeps those test-only classes out of production and release compilation.
 */
internal fun fixtureBackendsEnabled(): Boolean = BuildConfig.DEBUG && BuildConfig.MODEL_INDEPENDENT

internal fun fixtureRetrievalPackEnabled(): Boolean =
    fixtureBackendsEnabled() && BuildConfig.DISTRIBUTION == "fixtureCi"

/**
 * Metadata-only retrieval contract for the model-independent connected fixture.
 * It is never used by consumer/release builds and contains no model artifact.
 */
internal object FixtureRetrievalPack {
    const val PACK_ID = "siglip2-base-p16-224-q8"
    const val PACK_VERSION = "ba1f3b0-q8-core05"
    const val SOURCE_REVISION = "022b6f71160ffb0169ca4709e2d7e25be659598a"
    private const val FIXTURE_SHA256 = "6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d"

    fun install(context: Context): InstalledRetrievalPack = InstalledRetrievalPack(
        directory = File(context.filesDir, "models/retrieval/fixture"),
        manifest = RetrievalPackManifest(
            schemaVersion = 1,
            packId = PACK_ID,
            packVersion = PACK_VERSION,
            sourceModel = "google/siglip2-base-patch16-224",
            sourceRevision = SOURCE_REVISION,
            sourceLicense = "apache-2.0",
            artifactRepository = null,
            artifactRevision = null,
            runtime = RETRIEVAL_RUNTIME_LITERT,
            runtimeVersion = LITERT_VERSION,
            embeddingDimension = 128,
            normalized = true,
            minimumSimilarity = 0.05f,
            imageSize = 224,
            imageLayout = "NCHW",
            resizeMethod = "BICUBIC",
            imageMean = floatArrayOf(0.5f, 0.5f, 0.5f),
            imageStd = floatArrayOf(0.5f, 0.5f, 0.5f),
            textLength = 64,
            lowercaseText = true,
            padTokenId = 0,
            eosTokenId = 1,
            textInputType = "INT32",
            signatureAlgorithm = "SHA256withRSA",
            signingKeySha256 = "0".repeat(64),
            files = listOf(
                RetrievalPackFile(ROLE_IMAGE_ENCODER, "fixture-image.tflite", 1, FIXTURE_SHA256),
                RetrievalPackFile(ROLE_TEXT_ENCODER, "fixture-text.tflite", 1, FIXTURE_SHA256),
                RetrievalPackFile(ROLE_TOKENIZER, "fixture-tokenizer", 1, FIXTURE_SHA256),
                RetrievalPackFile(ROLE_LICENSE, "fixture-license", 1, FIXTURE_SHA256),
            ),
        ),
    )
}

/** Corpus annotations used only by model-independent fixture builds. */
internal fun fixtureDocumentText(filename: String, pageIndex: Int = 0): String? {
    if (!fixtureBackendsEnabled()) return null
    return when (filename) {
        "synthetic_swiggy_receipt.png" ->
            "SYNTHETIC TEST RECEIPT\nSWIGGY TEST KITCHEN\n18 JUL 2026\nOrder TEST-1842\n" +
                "Subtotal INR 1,180\nTax INR 118\nDiscount INR 50\nGRAND TOTAL INR 1,248\nAmount Paid INR 1,248"
        "synthetic_restaurant_receipt.png" ->
            "SYNTHETIC TEST RECEIPT\nFICUS CAFE\n12 MAR 2024\nSubtotal INR 900\nTax INR 90\n" +
                "Discount INR 40\nGRAND TOTAL INR 950"
        "synthetic_wifi_card.png" ->
            "SYNTHETIC WI-FI TEST CARD\nNetwork: GalleryDemo\nPassword: mango-tree-2048\nThis credential is fictitious."
        "synthetic_boarding_pass.png" ->
            "SYNTHETIC BOARDING PASS\nPassenger: TEST TRAVELLER\nFlight: AG 204\nDEL -> SIN\n" +
                "Date: 12 MAR 2024\nDeparture: 08:10\nGate: T04"
        "synthetic_hotel_confirmation.png" ->
            "SYNTHETIC HOTEL CONFIRMATION\nMarina Test Hotel, Singapore\nCheck-in: 12 MAR 2024\n" +
                "Check-out: 18 MAR 2024\nBooking: TEST-SG-1203"
        "synthetic_menu_english.png" ->
            "SYNTHETIC TEST MENU\nCoconut Curry INR 320\nGarden Rice INR 180\nLime Soda INR 90"
        "synthetic_menu_transliterated.png" ->
            "SYNTHETIC TEST MENU\nMasala chai INR 80\nAloo tikki INR 140\nNimbu pani INR 70"
        "synthetic_menu_hindi.png" ->
            "SYNTHETIC TEST MENU\nMasala chai INR 80\nAloo tikki INR 140\nNimbu pani INR 70"
        "synthetic_calendar.png" ->
            "SYNTHETIC CALENDAR\nSingapore trip\n12-18 March 2024\nMarina Bay on 13 March"
        "synthetic_two_page_document.pdf" -> when (pageIndex) {
            0 -> "SYNTHETIC TWO-PAGE PDF\nPage 1\nProject: AskAlbum\nReference: PDF-TEST-204"
            1 -> "SYNTHETIC TWO-PAGE PDF\nPage 2\nKnown fact: evidence stays on device\nDate: 21 JUL 2026"
            else -> null
        }
        else -> null
    }
}

internal fun fixtureVisualLabels(filename: String, timestampMs: Long?): List<String> {
    if (!fixtureBackendsEnabled()) return emptyList()
    val normalized = filename.lowercase(Locale.ROOT)
    return when {
        normalized == "synthetic_video_screen_timeline.mp4" && timestampMs in 5_000L..13_000L ->
            listOf("yellow bicycle", "bicycle", "yellow")
        normalized.contains("synthetic_swiggy") -> listOf("receipt", "swiggy")
        normalized.contains("domesticated_dog") -> listOf("dog", "pet")
        normalized.contains("children_football") -> listOf("children", "football", "outdoors")
        normalized.contains("goa_beach") || normalized.contains("legacy_demo-beach") ->
            listOf("beach", "sunset")
        normalized.contains("singapore_marina_bay") -> listOf("singapore", "marina bay", "skyline")
        else -> emptyList()
    }
}

internal fun fixtureEmbeddingConcept(value: String): String? {
    if (!fixtureBackendsEnabled()) return null
    val normalized = value.lowercase(Locale.ROOT)
    return when {
        normalized.contains("yellow") && normalized.contains("bicycle") ||
            normalized.contains("synthetic_video_screen_timeline") -> "yellow bicycle"
        normalized.contains("beach") && (normalized.contains("sunset") || normalized.contains("goa")) ||
            normalized.contains("legacy_demo-beach") -> "beach sunset"
        normalized.contains("dog") || normalized.contains("pet") -> "dog pet"
        normalized.contains("children") && normalized.contains("football") ||
            normalized.contains("children_football") -> "children football outdoors"
        normalized.contains("marina") || normalized.contains("singapore") -> "marina bay"
        else -> null
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T : Any> newFixtureEngine(className: String): T =
    Class.forName("io.github.anup42.askalbum.$className")
        .getDeclaredConstructor()
        .newInstance() as T

class FixtureOcrEngineProvider : ModelEngineProvider<OcrEngine> {
    override val descriptor = ModelEngineDescriptor(
        id = "fixture-ocr",
        displayName = "Deterministic fixture OCR",
        producerVersion = "fixture-ocr-v1",
        license = "Test fixture",
        capabilities = setOf(ModelCapability.OCR),
    )

    override fun isAvailable(): Boolean = true

    override suspend fun create(): OcrEngine = newFixtureEngine("FakeOcrEngine")
}

class FixtureFaceEngineProvider : ModelEngineProvider<FaceEngine> {
    override val descriptor = ModelEngineDescriptor(
        id = "fixture-face",
        displayName = "Deterministic fixture face engine",
        producerVersion = "fixture-face-v1",
        license = "Test fixture",
        capabilities = setOf(ModelCapability.FACES),
    )

    override fun isAvailable(): Boolean = true

    override suspend fun create(): FaceEngine = newFixtureEngine("FakeFaceEngine")
}

interface GalleryPlanCompiler {
    suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan

    suspend fun compileFollowUp(
        query: String,
        context: FollowUpPlanningContext,
    ): GalleryQueryPlan = compile(query, context.state.activeResultIds)
}

data class FollowUpPlanningContext(
    val state: ConversationSearchState,
    val previousPlan: GalleryQueryPlan?,
)

class ProductionGalleryPlanCompiler(
    private val planner: LiteRtLmQueryPlanner,
) : GalleryPlanCompiler {
    override suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
        planner.compile(query, activeResultIds)

    override suspend fun compileFollowUp(
        query: String,
        context: FollowUpPlanningContext,
    ): GalleryQueryPlan = planner.compileFollowUp(query, context)
}

class FixtureGalleryPlanCompiler : GalleryPlanCompiler {
    private val engine by lazy { newFixtureEngine<GenerativeEngine>("FakeGenerativeEngine") }

    override suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
        engine.compilePlan(PlannerInput(query, activeResultIds))
}

class FixtureCandidateVerifier : CandidateVerifier {
    private val engine by lazy { newFixtureEngine<GenerativeEngine>("FakeGenerativeEngine") }

    override suspend fun verifyWhenNeeded(
        plan: GalleryQueryPlan,
        candidates: List<SearchHit>,
    ): VerificationResult = engine.verify(
        VerificationInput(plan, candidates.map(SearchHit::item)),
    )
}

interface GalleryAnswerComposer {
    suspend fun compose(input: GroundedAnswerInput): GroundedAnswerCompositionResult
}

class ProductionGalleryAnswerComposer(
    private val composer: LiteRtGemmaGroundedAnswerComposer,
) : GalleryAnswerComposer {
    override suspend fun compose(input: GroundedAnswerInput): GroundedAnswerCompositionResult =
        composer.compose(input)
}

class FixtureGalleryAnswerComposer : GalleryAnswerComposer {
    override suspend fun compose(input: GroundedAnswerInput): GroundedAnswerCompositionResult {
        val baseline = requireNotNull(input.deterministicAnswer) {
            "A deterministic baseline answer is required"
        }
        return GroundedAnswerCompositionResult(
            answer = baseline,
            trace = GroundedAnswerCompositionTrace(
                usedGemma = false,
                backend = PlannerInferenceBackend.DETERMINISTIC,
                evidenceCount = input.hits.sumOf { it.evidence.size },
                fallbackReason = "Fixture backend selected",
            ),
        )
    }
}
