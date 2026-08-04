package io.github.anup42.askalbum

/**
 * Model-independent debug variants use the deterministic engines from the debug source set.
 * Reflection keeps those test-only classes out of production and release compilation.
 */
internal fun fixtureBackendsEnabled(): Boolean = BuildConfig.DEBUG && BuildConfig.MODEL_INDEPENDENT

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
}

class ProductionGalleryPlanCompiler(
    private val planner: LiteRtLmQueryPlanner,
) : GalleryPlanCompiler {
    override suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
        planner.compile(query, activeResultIds)
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
