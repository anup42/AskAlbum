package io.github.anup42.askalbum

/**
 * Stable seams for production model packs. Implementations must never send image data off-device.
 * The bundled MVP uses [QueryCompiler] and curated sample facts, so it makes no false model claim.
 */
interface GenerativeEngine {
    suspend fun compilePlan(input: PlannerInput): GalleryQueryPlan
    suspend fun verify(input: VerificationInput): VerificationResult
    suspend fun composeAnswer(input: GroundedAnswerInput): SearchAnswer
}

interface ImageTextEmbeddingEngine {
    suspend fun embedImage(image: ModelImage): FloatArray
    suspend fun embedText(text: String): FloatArray
}

interface OcrEngine : AutoCloseable {
    suspend fun recognize(image: ModelImage): OcrDocument
    override fun close() = Unit
}

interface FaceEngine : AutoCloseable {
    suspend fun detectAndEmbed(image: ModelImage): List<FaceInstance>
    override fun close() = Unit
}

interface VectorIndex {
    suspend fun upsert(mediaId: String, vector: FloatArray)
    suspend fun delete(mediaId: String)
    suspend fun search(query: FloatArray, topK: Int, allowedIds: Set<String>? = null): List<VectorHit>
}

interface QueryPlanner {
    suspend fun compileAndValidate(input: PlannerInput): GalleryQueryPlan
}

interface QueryExecutor {
    suspend fun retrieve(plan: GalleryQueryPlan): List<SearchHit>
}

interface CandidateVerifier {
    suspend fun verifyWhenNeeded(plan: GalleryQueryPlan, candidates: List<SearchHit>): VerificationResult
}

interface EvidenceRepository {
    suspend fun records(ids: Set<String>): List<EvidenceRecord>
}

enum class ModelCapability { GENERATIVE, IMAGE_EMBEDDING, TEXT_EMBEDDING, OCR, FACES }

interface InferenceResourceManager {
    suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T
}

data class PlannerInput(
    val query: String,
    val activeResultIds: Set<String>?,
    val knownPeopleAliases: Map<String, String> = emptyMap(),
    val availableCapabilities: Set<ModelCapability> = emptySet(),
)
data class VerificationInput(val plan: GalleryQueryPlan, val candidates: List<GalleryItem>)

data class VerificationConditionSpec(
    val id: String,
    val text: String,
    val polarity: Polarity,
    val hardness: ConstraintStrength,
    val subject: SemanticSubject,
    val relationToPerson: String?,
)

data class VerificationConditionEvaluation(
    val id: String,
    val satisfied: Boolean,
    val confidence: Float,
    val verdict: PersonVisualVerdict = if (satisfied) PersonVisualVerdict.VERIFIED_TRUE else PersonVisualVerdict.VERIFIED_FALSE,
)

data class CandidateVerification(
    val mediaId: String,
    val conditions: List<VerificationConditionEvaluation>,
    val overallMatch: Boolean,
)

enum class VerificationInferenceBackend { GPU, CPU, NOT_RUN }

data class VerificationExecutionTrace(
    val usedGemma: Boolean,
    val backend: VerificationInferenceBackend,
    val modelTier: GemmaModelTier? = null,
    val modelRevision: String? = null,
    val requestedCandidates: Int = 0,
    val verifiedCandidates: Int = 0,
    val generationCalls: Int = 0,
    val repairedCandidates: Int = 0,
    val engineLoadMs: Long = 0,
    val generationMs: Long = 0,
    val engineCloseMs: Long = 0,
    val elapsedMs: Long = 0,
    val fallbackReason: String? = null,
)

data class VerificationFailure(val mediaId: String?, val reason: String)

data class VerificationResult(
    val acceptedIds: Set<String>,
    val evidence: List<EvidenceRecord>,
    val applied: Boolean = false,
    val evaluations: List<CandidateVerification> = emptyList(),
    val failures: List<VerificationFailure> = emptyList(),
    val trace: VerificationExecutionTrace? = null,
)
data class GroundedAnswerInput(
    val plan: GalleryQueryPlan,
    val hits: List<SearchHit>,
    val deterministicAnswer: SearchAnswer? = null,
)
data class ModelImage(
    val rgbBytes: ByteArray,
    val width: Int,
    val height: Int,
    val fixtureText: String? = null,
)
data class OcrDocument(val blocks: List<OcrBlock>, val language: String? = null)
data class OcrBlock(val text: String, val confidence: Float, val bounds: List<Float>, val script: String? = null)
data class FaceInstance(val bounds: List<Float>, val embedding: FloatArray, val quality: Float)
data class VectorHit(val mediaId: String, val score: Float)
