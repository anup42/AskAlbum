package io.github.anup42.askalbum

/** Deterministic debug backends. They contain no network or model-pack dependency. */
class FakeGenerativeEngine(
    private val compiler: QueryCompiler = QueryCompiler(),
    private val validator: GroundedClaimValidator = GroundedClaimValidator(),
) : GenerativeEngine {
    override suspend fun compilePlan(input: PlannerInput): GalleryQueryPlan =
        compiler.compile(input.query, input.activeResultIds)

    override suspend fun verify(input: VerificationInput): VerificationResult = VerificationResult(
        acceptedIds = input.candidates.mapTo(linkedSetOf()) { it.id },
        evidence = emptyList(),
    )

    override suspend fun composeAnswer(input: GroundedAnswerInput): SearchAnswer {
        val evidence = input.hits.flatMap { it.evidence }
        val claim = GroundedClaim(
            text = "Found ${input.hits.size} fixture matches",
            evidenceIds = evidence.map { it.id }.distinct(),
            confidence = 1f,
        )
        val answer = SearchAnswer(
            headline = claim.text,
            detail = "Computed by the deterministic debug backend.",
            evidenceIds = claim.evidenceIds,
            exactness = ResultExactness.COMPLETE_MODEL_SCAN,
            indexedEligibleCount = input.hits.size,
            totalEligibleCount = input.hits.size,
            claims = listOf(claim),
        )
        return validator.validate(answer, evidence)
    }
}

class FakeEmbeddingEngine(private val dimension: Int = 32) : ImageTextEmbeddingEngine {
    override suspend fun embedImage(image: ModelImage): FloatArray =
        embed(image.rgbBytes.fold(1) { acc, byte -> 31 * acc + byte })

    override suspend fun embedText(text: String): FloatArray = embed(text.lowercase().hashCode())

    private fun embed(seed: Int): FloatArray {
        var value = seed.toLong() and 0xffffffffL
        val vector = FloatArray(dimension) {
            value = (1_664_525L * value + 1_013_904_223L) and 0xffffffffL
            ((value.toDouble() / 0xffffffffL) * 2.0 - 1.0).toFloat()
        }
        return normalizeVector(vector, dimension)
    }
}

class FakeOcrEngine : OcrEngine {
    override suspend fun recognize(image: ModelImage): OcrDocument = OcrDocument(
        blocks = image.fixtureText?.let { listOf(OcrBlock(it, 1f, listOf(0f, 0f, 1f, 1f))) }.orEmpty(),
        language = "fixture",
    )
}

class FakeFaceEngine : FaceEngine {
    override suspend fun detectAndEmbed(image: ModelImage): List<FaceInstance> = emptyList()
}
