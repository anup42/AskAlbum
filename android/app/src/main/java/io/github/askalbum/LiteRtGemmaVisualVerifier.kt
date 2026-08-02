package io.github.anup42.askalbum

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Bounded one-image-at-a-time Gemma verification. It never accepts a candidate after an inference failure. */
class LiteRtGemmaVisualVerifier(
    context: Context,
    private val modelPacks: ModelPackManager,
    private val sessions: GemmaSessionManager,
    private val database: GalleryDatabase,
    private val imageLoader: GalleryImageLoader = GalleryImageLoader(context.applicationContext),
    private val compiler: BoundedGemmaVerificationCompiler = BoundedGemmaVerificationCompiler(),
) : CandidateVerifier {
    override suspend fun verifyWhenNeeded(plan: GalleryQueryPlan, candidates: List<SearchHit>): VerificationResult {
        if (!VisualVerificationPolicy.requiresVerification(plan)) {
            return VerificationResult(candidates.mapTo(linkedSetOf()) { it.item.id }, emptyList())
        }
        val started = SystemClock.elapsedRealtime()
        val bounded = candidates.filter { it.item.kind in setOf(MediaKind.IMAGE, MediaKind.VIDEO) }.take(MAX_CANDIDATES)
        val conditions = VisualVerificationPolicy.conditions(plan)
        if (bounded.isEmpty() || conditions.isEmpty()) {
            return failedBeforeInference(started, bounded.size, "No bounded media conditions were available")
        }
        val status = modelPacks.status()
        val path = status.path
        if (path == null || !status.installed || !status.multimodal) {
            return failedBeforeInference(started, bounded.size, "No verified multimodal Gemma pack is active")
        }
        if (status.deviceAssessment?.supported == false) {
            return failedBeforeInference(started, bounded.size, status.deviceAssessment.reason)
        }

        return try {
            sessions.withEngine(path, multimodal = true) { initialized ->
                withContext(Dispatchers.IO) {
                    require(File(path).isFile) { "Verified Gemma artifact is unavailable" }
                    val accepted = linkedSetOf<String>()
                    val evidence = mutableListOf<EvidenceRecord>()
                    val evaluations = mutableListOf<CandidateVerification>()
                    val failures = mutableListOf<VerificationFailure>()
                    var generationCalls = 0
                    var repairedCandidates = 0
                    var generationMs = 0L
                    bounded.forEach { hit ->
                            runCatching {
                                val requiredGroups = PeopleClauseResolver.requiredGroups(plan.peopleClauses)
                                val requiredPeople = requiredGroups.flatten().map(PersonClause::personId).toSet()
                                val bindings = database.reviewedFaceBindings(hit.item.id, requiredPeople)
                                if (requiredPeople.isNotEmpty()) {
                                    val grouped = bindings.groupBy(PersonVerificationBinding::clusterId)
                                    val everyRequestedIdentityBound = requiredGroups.all { alternatives ->
                                        alternatives.any { clause ->
                                            bindings.any { binding ->
                                                binding.clusterId == clause.personId ||
                                                    binding.identityTerms.any { it.equals(clause.personId, ignoreCase = true) }
                                            }
                                        }
                                    }
                                    require(everyRequestedIdentityBound && grouped.values.all { it.size == 1 }) {
                                        "Required reviewed identities could not be bound unambiguously to visible faces"
                                    }
                                }
                                val boundConditions = PersonVerificationPromptBinding.bind(conditions, bindings)
                                val loaded = imageLoader.loadForVerification(hit, database.videoKeyframes(hit.item.id))
                                val bytes = PersonVerificationImageComposer.compose(loaded.bytes, bindings)
                                val generationStarted = SystemClock.elapsedRealtime()
                                val decoded = compiler.compile(boundConditions, prompt(plan, boundConditions, bindings)) { prompt ->
                                    initialized.engine.generateVision(bytes, prompt, seed = 23)
                                }
                                generationMs += SystemClock.elapsedRealtime() - generationStarted
                                generationCalls += decoded.generationCalls
                                if (decoded.generationCalls > 1) repairedCandidates++
                                val evaluationsById = decoded.payload.conditions.associateBy(VerificationConditionEvaluation::id)
                                val overallMatch = boundConditions
                                    .filter { it.hardness == ConstraintStrength.HARD }
                                    .all { spec ->
                                        evaluationsById[spec.id]?.let { evaluation ->
                                            SemanticPolarityNormalizer.conditionMatched(spec, evaluation)
                                        } == true
                                    }
                                val candidate = CandidateVerification(hit.item.id, decoded.payload.conditions, overallMatch)
                                evaluations += candidate
                                if (candidate.overallMatch) accepted += hit.item.id
                                decoded.payload.conditions.filter { evaluation ->
                                    val spec = boundConditions.single { it.id == evaluation.id }
                                    SemanticPolarityNormalizer.conditionMatched(spec, evaluation)
                                }.forEach { evaluation ->
                                    val spec = boundConditions.single { it.id == evaluation.id }
                                    evidence += EvidenceRecord(
                                        id = "${hit.item.id}:visual_verification:${spec.id}",
                                        mediaId = hit.item.id,
                                        sourceField = "visual_verification",
                                        text = if (spec.polarity == Polarity.NEGATIVE) {
                                            "No visible ${spec.text}"
                                        } else {
                                            spec.text
                                        },
                                        confidence = evaluation.confidence,
                                        producerVersion = producerVersion(status),
                                        timestampMs = loaded.timestampMs,
                                    )
                                    val binding = spec.relationToPerson?.let { clusterId ->
                                        bindings.singleOrNull { it.clusterId == clusterId }
                                    }
                                    if (binding != null) {
                                        database.saveVerifiedPersonAttributeFact(
                                            mediaId = hit.item.id,
                                            clusterId = binding.clusterId,
                                            predicate = spec.text,
                                            value = evaluation.verdict.name,
                                            confidence = evaluation.confidence,
                                            region = listOf(binding.left, binding.top, binding.right, binding.bottom),
                                            modelVersion = producerVersion(status),
                                        )
                                    }
                                }
                            }.onFailure { error ->
                                if (error is CancellationException) throw error
                                failures += VerificationFailure(hit.item.id, sanitize(error))
                            }
                    }
                    VerificationResult(
                        acceptedIds = accepted,
                        evidence = evidence,
                        applied = true,
                        evaluations = evaluations,
                        failures = failures,
                        trace = VerificationExecutionTrace(
                            usedGemma = true,
                            backend = if (initialized.engine.backend == PlannerInferenceBackend.GPU) {
                                VerificationInferenceBackend.GPU
                            } else {
                                VerificationInferenceBackend.CPU
                            },
                            modelTier = status.tier,
                            modelRevision = status.packVersion,
                            requestedCandidates = bounded.size,
                            verifiedCandidates = evaluations.size,
                            generationCalls = generationCalls,
                            repairedCandidates = repairedCandidates,
                            engineLoadMs = initialized.loadMs,
                            generationMs = generationMs,
                            engineCloseMs = 0L,
                            elapsedMs = SystemClock.elapsedRealtime() - started,
                        ),
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failedBeforeInference(started, bounded.size, sanitize(error))
        }
    }

    private fun prompt(
        plan: GalleryQueryPlan,
        conditions: List<VerificationConditionSpec>,
        bindings: List<PersonVerificationBinding>,
    ): String {
        val array = JSONArray().apply {
            conditions.forEach { condition ->
                put(JSONObject().apply {
                    put("id", condition.id)
                    put("text", condition.text)
                    put("polarity", condition.polarity.name)
                    put("hardness", condition.hardness.name)
                    put("subject", condition.subject.name)
                    condition.relationToPerson?.let { clusterId ->
                        put("relationToPerson", bindings.firstOrNull { it.clusterId == clusterId }?.stableLabel ?: clusterId)
                    }
                })
            }
        }
        val mapping = JSONObject().apply {
            bindings.forEach { binding -> put(binding.stableLabel, "reviewed-cluster:${binding.clusterId}") }
        }
        return """
            Inspect the supplied contact sheet. Its top panel is the full image with labelled face boxes; lower panels are expanded upper/full-body crops.
            Return exactly one JSON object and no markdown or explanation.
            Every condition text is a positive visual predicate. Set satisfied=true only when that predicate is visibly present.
            Kotlin applies polarity after your response: a NEGATIVE condition matches only when its positive predicate is not visible. Never invert polarity yourself.
            Person labels are deterministic and must not be reassigned. Bind every person-specific condition only to the matching P-label.
            Person mapping: $mapping
            For synthetic cards or diagrams, visible labels and illustrated clothing are valid image evidence.
            Query context: ${JSONObject.quote(plan.originalQuery)}
            Conditions: $array
            Required shape: {"conditions":[{"id":"c1","verdict":"VERIFIED_TRUE","confidence":0.95}],"overallMatch":true}
            verdict must be VERIFIED_TRUE only when the predicate is visibly attached to the requested P-label.
            Use VERIFIED_FALSE only when the relevant body region is visible and contradicts the predicate.
            Use NOT_VISIBLE when the required head, torso, legs, feet, or hands are cropped, occluded, or too unclear.
            Use AMBIGUOUS when multiple bodies or objects could be associated with the labelled face.
            Include every supplied ID exactly once. confidence must be from 0 to 1.
            overallMatch is advisory; Kotlin deterministically applies HARD constraints and polarity.
            Never emit media IDs, paths, URIs, boxes, tools, or additional fields.
        """.trimIndent()
    }

    private suspend fun generate(engine: Engine, imageBytes: ByteArray, prompt: String): String {
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 23),
            extraContext = mapOf("enable_thinking" to false),
        )
        return engine.generateTextCancellable(
            config,
            Contents.of(
                com.google.ai.edge.litertlm.Content.ImageBytes(imageBytes),
                com.google.ai.edge.litertlm.Content.Text(prompt),
            ),
            mapOf("enable_thinking" to false),
        )
    }

    private fun createEngine(path: String): InitializedVerificationEngine {
        val started = SystemClock.elapsedRealtime()
        val gpu = runCatching {
            initializeEngine(
                EngineConfig(
                    modelPath = path,
                    backend = Backend.GPU(),
                    visionBackend = Backend.GPU(),
                    maxNumTokens = 4096,
                    maxNumImages = 1,
                ),
            ).let { InitializedVerificationEngine(it, VerificationInferenceBackend.GPU, SystemClock.elapsedRealtime() - started) }
        }
        return gpu.getOrElse { gpuFailure ->
            runCatching {
                initializeEngine(
                    EngineConfig(
                        modelPath = path,
                        backend = Backend.CPU(),
                        visionBackend = Backend.CPU(),
                        maxNumTokens = 4096,
                        maxNumImages = 1,
                    ),
                ).let { InitializedVerificationEngine(it, VerificationInferenceBackend.CPU, SystemClock.elapsedRealtime() - started) }
            }.getOrElse { cpuFailure ->
                throw GemmaModelLoadFailure("Gemma visual verification failed on GPU and CPU", cpuFailure.also { it.addSuppressed(gpuFailure) })
            }
        }
    }

    private fun initializeEngine(config: EngineConfig): Engine {
        val engine = Engine(config)
        return try {
            engine.initialize()
            engine
        } catch (error: Throwable) {
            runCatching { engine.close() }
            throw error
        }
    }

    private fun failedBeforeInference(started: Long, count: Int, reason: String): VerificationResult {
        val safe = reason.take(240)
        return VerificationResult(
            acceptedIds = emptySet(),
            evidence = emptyList(),
            applied = true,
            failures = listOf(VerificationFailure(null, safe)),
            trace = VerificationExecutionTrace(
                usedGemma = false,
                backend = VerificationInferenceBackend.NOT_RUN,
                requestedCandidates = count,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                fallbackReason = safe,
            ),
        )
    }

    private fun sanitize(error: Throwable): String = when (error) {
        is SecurityException -> "Gallery image access was denied"
        is java.io.FileNotFoundException -> "Gallery image is unavailable"
        is IllegalArgumentException -> "Visual verification input was rejected"
        is GemmaModelLoadFailure -> "The on-device multimodal model could not be loaded"
        else -> "On-device visual verification failed (${error::class.java.simpleName.take(80)})"
    }

    private fun producerVersion(status: ModelPackStatus): String =
        "gemma-4-${status.tier?.name?.lowercase() ?: "unknown"}-${status.packVersion ?: "unknown"}"

    private data class InitializedVerificationEngine(
        val engine: Engine,
        val backend: VerificationInferenceBackend,
        val loadMs: Long,
    )

    companion object {
        const val MAX_CANDIDATES = 8
    }
}

internal object VisualVerificationPolicy {
    private const val MAX_CONDITIONS = 16
    private val hardVisualTerms = setOf("only", "wearing", "behind", "in front", "taller", "shorter", "same person")

    fun requiresVerification(plan: GalleryQueryPlan): Boolean = when (plan.verification) {
        VerificationPolicy.NEVER -> false
        VerificationPolicy.REQUIRED -> true
        VerificationPolicy.AUTO -> plan.semanticClauses.any { clause ->
            clause.hardness == ConstraintStrength.HARD ||
                clause.polarity == Polarity.NEGATIVE ||
                clause.subject == SemanticSubject.PERSON ||
                clause.relationToPerson != null ||
                hardVisualTerms.any { it in clause.text.lowercase() }
        }
    }

    fun conditions(plan: GalleryQueryPlan): List<VerificationConditionSpec> {
        val clauses = plan.semanticClauses.ifEmpty {
            listOf(SemanticClause(plan.originalQuery, hardness = ConstraintStrength.HARD))
        }
        return clauses.map(SemanticPolarityNormalizer::normalize).take(MAX_CONDITIONS).mapIndexed { index, clause ->
            VerificationConditionSpec(
                id = "c${index + 1}",
                text = clause.text,
                polarity = clause.polarity,
                hardness = clause.hardness,
                subject = clause.subject,
                relationToPerson = clause.relationToPerson,
            )
        }
    }
}
