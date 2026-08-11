package io.github.anup42.askalbum

import java.util.Locale
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultilingualRetrievalBenchmarkTest {
    private val cases by lazy(::loadCases)
    private val corpus by lazy(::fixtureCorpus)
    private val encoder = FixtureTextEncoder()

    @Test
    fun publicFixtureCoversLanguagesChannelsParaphrasesAndTruthfulExactness() {
        assertEquals(21, cases.size)
        assertEquals(7, cases.map(BenchmarkCase::caseId).distinct().size)
        cases.groupBy(BenchmarkCase::caseId).forEach { (caseId, equivalents) ->
            assertEquals(caseId, setOf("en", "hi", "hinglish"), equivalents.mapTo(mutableSetOf(), BenchmarkCase::language))
            assertEquals(caseId, 1, equivalents.map(BenchmarkCase::canonicalQuery).distinct().size)
            assertEquals(caseId, 1, equivalents.map(BenchmarkCase::expectedId).distinct().size)
        }
        assertEquals(
            setOf(
                "metadata",
                "ocr",
                "caption_fts",
                "caption_embedding",
                "image_vector",
                "people",
                "person_fact",
                "event",
                "negative_clause",
            ),
            cases.flatMapTo(mutableSetOf(), BenchmarkCase::expectedChannels),
        )
        assertTrue(cases.all { it.exactness == ResultExactness.ESTIMATED_FROM_RETRIEVAL })
        val canonicalText = cases.joinToString(" ", transform = BenchmarkCase::canonicalQuery).lowercase(Locale.ROOT)
        listOf("automobile", "car", "sofa", "couch", "footwear", "shoes", "handbag", "purse").forEach {
            assertTrue("Missing paraphrase $it", it in canonicalText)
        }
    }

    @Test
    fun fixtureCiRetrievalMeetsDeterministicRecallAndMrrAcrossAllLanguages() = runBlocking {
        val measurements = cases.map { benchmarkCase ->
            val result = execute(benchmarkCase)
            assertEquals(benchmarkCase.query, result.plan.originalQuery)
            assertTrue("Original query missing for ${benchmarkCase.caseId}/${benchmarkCase.language}", result.variants.contains(benchmarkCase.query))
            assertTrue("Canonical query missing for ${benchmarkCase.caseId}/${benchmarkCase.language}", result.variants.contains(benchmarkCase.canonicalQuery))
            benchmarkCase.expectedChannels.forEach { channel ->
                assertTrue(
                    "$channel did not retain ${benchmarkCase.expectedId} for ${benchmarkCase.caseId}/${benchmarkCase.language}",
                    result.channelRanks[channel].orEmpty().contains(benchmarkCase.expectedId),
                )
            }
            val rank = result.confirmedIds.indexOf(benchmarkCase.expectedId) + 1
            assertTrue(
                "${benchmarkCase.caseId}/${benchmarkCase.language} missed ${benchmarkCase.expectedId}; " +
                    "candidates=${result.candidateIds} confirmed=${result.confirmedIds}",
                rank in 1..TOP_K,
            )
            Measurement(benchmarkCase.caseId, benchmarkCase.language, rank)
        }

        val recallAtK = measurements.count { it.rank <= TOP_K }.toDouble() / measurements.size
        val mrr = measurements.map { 1.0 / it.rank }.average()
        assertEquals(1.0, recallAtK, 0.0)
        assertTrue("Fixture MRR was $mrr", mrr >= MINIMUM_MRR)
        measurements.groupBy(Measurement::caseId).forEach { (caseId, equivalents) ->
            val spread = equivalents.maxOf(Measurement::rank) - equivalents.minOf(Measurement::rank)
            assertTrue("$caseId language rank spread was $spread", spread <= MAXIMUM_LANGUAGE_RANK_SPREAD)
        }
    }

    @Test
    fun hardFiltersConstrainEveryChannelBeforeTopK() = runBlocking {
        cases.forEach { benchmarkCase ->
            val result = execute(benchmarkCase)
            result.channelRanks.forEach { (channel, ids) ->
                assertTrue(
                    "$channel escaped the eligible set for ${benchmarkCase.caseId}/${benchmarkCase.language}: $ids",
                    ids.all(result.eligibleIds::contains),
                )
            }
        }

        val vectorIndex = ReferenceVectorIndex(2)
        repeat(101) { vectorIndex.upsert("wrong-year-$it", floatArrayOf(1f, 0f)) }
        vectorIndex.upsert("red-automobile-2024", floatArrayOf(.8f, .6f))
        val query = floatArrayOf(1f, 0f)
        assertFalse(vectorIndex.search(query, 100).any { it.mediaId == "red-automobile-2024" })
        assertEquals(
            listOf("red-automobile-2024"),
            vectorIndex.search(query, 100, setOf("red-automobile-2024")).map(VectorHit::mediaId),
        )

        val negative = execute(cases.first { it.caseId == "sunset-no-screenshot" })
        assertFalse(negative.eligibleIds.contains("beach-sunset-screenshot"))
        assertTrue(negative.channelRanks.values.flatten().none { it == "beach-sunset-screenshot" })
    }

    @Test
    fun personFactsRemainBoundToTheRequestedReviewedCluster() = runBlocking {
        listOf("wife-footwear", "wife-handbag").forEach { caseId ->
            val benchmarkCase = cases.first { it.caseId == caseId && it.language == "en" }
            val result = execute(benchmarkCase)
            val wrongPersonId = when (caseId) {
                "wife-footwear" -> "me-white-shoes"
                else -> "me-black-handbag"
            }
            assertEquals(setOf(WIFE_CLUSTER), PersonCaptionConstraintPolicy.requiredClusterIds(result.plan))
            assertTrue(result.channelRanks.getValue("person_fact").contains(benchmarkCase.expectedId))
            assertFalse(result.channelRanks.getValue("person_fact").contains(wrongPersonId))
            assertTrue(benchmarkCase.expectedId in result.confirmedIds)
        }
        val footwear = execute(cases.first { it.caseId == "wife-footwear" && it.language == "en" })
        assertTrue("The broad image channel should expose the swapped-person candidate", "me-white-shoes" in footwear.candidateIds)
        assertFalse("Me-bound white footwear crossed into Wife confirmation", "me-white-shoes" in footwear.confirmedIds)
        val handbag = execute(cases.first { it.caseId == "wife-handbag" && it.language == "en" })
        assertTrue("The broad image channel should expose the swapped-person candidate", "me-black-handbag" in handbag.candidateIds)
        assertFalse("Me-bound handbag crossed into Wife confirmation", "me-black-handbag" in handbag.confirmedIds)
    }

    @Test
    fun missingAndPartialModelChannelsRemainTypedWhileOtherEvidenceCanRetrieve() = runBlocking {
        var unavailableSearchExecuted = false
        val unavailable = SemanticChannelReporter.execute(
            "automobile",
            null,
            3,
            setOf("red-automobile-2024", "living-room-sofa", "swiggy-receipt"),
            10,
            indexedIds = { error("No model pack must not read the vector index") },
            search = { _, _, _ -> unavailableSearchExecuted = true; emptyList() },
        )
        assertEquals(ChannelStatus.UNAVAILABLE, unavailable.status)
        assertFalse(unavailableSearchExecuted)
        assertTrue(unavailable.hits.isEmpty())

        val partial = SemanticChannelReporter.execute(
            "automobile",
            "fixture-text@1",
            3,
            setOf("red-automobile-2024", "living-room-sofa"),
            10,
            indexedIds = { setOf("red-automobile-2024") },
            search = { _, _, _ -> listOf(VectorHit("red-automobile-2024", .9f)) },
        )
        assertEquals(ChannelStatus.PARTIAL, partial.status)
        assertEquals(3, partial.eligibleCount)
        assertEquals(1, partial.indexedCount)
        assertEquals("VECTOR_COVERAGE_PARTIAL", partial.errorCode)

        assertEquals(
            ChannelStatus.PARTIAL,
            CaptionVectorCoveragePolicy.status(
                queryRequired = true,
                eligibleMediaCount = 3,
                captionedMediaCount = 1,
                eligibleChunkCount = 2,
                indexedChunkCount = 2,
            ),
        )
        val fallback = HybridRankFusion.fuse(
            listOf(
                RankedChannel(1.0, listOf("swiggy-receipt")),
                RankedChannel(.85, unavailable.hits.map(VectorHit::mediaId)),
            ),
        )
        assertEquals("swiggy-receipt", fallback.first().first)
        assertEquals(ChannelStatus.UNAVAILABLE, unavailable.status)
    }

    private suspend fun execute(benchmarkCase: BenchmarkCase): BenchmarkResult {
        val plan = planFor(benchmarkCase)
        val variants = CaptionLexicalQueryBuilder.variants(SemanticQueryVariants.from(plan))
        val eligible = eligibleMedia(plan, benchmarkCase)
        val eligibleIds = eligible.mapTo(linkedSetOf(), FixtureMedia::id)
        val channelRanks = linkedMapOf<String, MutableList<String>>()
        val fusionChannels = mutableListOf<RankedChannel>()

        fun addChannel(name: String, weight: Double, ids: List<String>) {
            if (ids.isEmpty()) return
            val aggregate = channelRanks.getOrPut(name, ::mutableListOf)
            ids.forEach { id -> if (id !in aggregate) aggregate += id }
            fusionChannels += RankedChannel(weight, ids)
        }

        variants.forEach { variant ->
            addChannel("metadata", 1.0, lexicalRank(variant, eligible, FixtureMedia::metadataText))
            addChannel("ocr", 1.0, lexicalRank(variant, eligible, FixtureMedia::ocrText))
            val personScopedCaption: (FixtureMedia) -> String = { media ->
                benchmarkCase.requiredClusterId?.let { clusterId ->
                    media.personFacts[clusterId]
                        ?.takeIf { it.polarity == Polarity.POSITIVE }
                        ?.text
                        .orEmpty()
                } ?: media.captionText
            }
            addChannel("caption_fts", .80, lexicalRank(variant, eligible, personScopedCaption))
            addChannel("caption_embedding", .75, vectorRank(variant, eligibleIds, personScopedCaption))
            addChannel("image_vector", .85, vectorRank(variant, eligibleIds, FixtureMedia::imageText))
            addChannel("event", .95, lexicalRank(variant, eligible, FixtureMedia::eventText))
        }
        benchmarkCase.requiredClusterId?.let { clusterId ->
            channelRanks["people"] = eligible.mapTo(mutableListOf(), FixtureMedia::id)
            channelRanks["person_fact"] = vectorRank(
                benchmarkCase.canonicalQuery,
                eligibleIds,
            ) { media ->
                media.personFacts[clusterId]
                    ?.takeIf { it.polarity == Polarity.POSITIVE }
                    ?.text
                    .orEmpty()
            }.toMutableList()
        }
        if (benchmarkCase.excludeScreenshots) {
            channelRanks["negative_clause"] = eligible.mapTo(mutableListOf(), FixtureMedia::id)
        }

        val candidateIds = HybridRankFusion.fuse(fusionChannels).map(Pair<String, Double>::first).take(TOP_K)
        val confirmedIds = candidateIds.filter { id ->
            matchesPersonConstraint(benchmarkCase, corpus.first { it.id == id })
        }
        return BenchmarkResult(
            plan = plan,
            variants = variants,
            eligibleIds = eligibleIds,
            channelRanks = channelRanks.mapValues { it.value.toList() },
            candidateIds = candidateIds,
            confirmedIds = confirmedIds,
        )
    }

    private fun planFor(benchmarkCase: BenchmarkCase): GalleryQueryPlan {
        val positiveClause = benchmarkCase.requiredClusterId?.let { clusterId ->
            SemanticClause(
                text = benchmarkCase.query,
                canonicalText = benchmarkCase.canonicalQuery,
                hardness = ConstraintStrength.HARD,
                subject = SemanticSubject.PERSON,
                relationToPerson = clusterId,
            )
        } ?: SemanticClause(benchmarkCase.query, benchmarkCase.canonicalQuery)
        val clauses = buildList {
            add(positiveClause)
            if (benchmarkCase.excludeScreenshots) {
                add(SemanticClause("screenshots", polarity = Polarity.NEGATIVE, hardness = ConstraintStrength.HARD))
            }
        }
        return GalleryQueryPlan(
            originalQuery = benchmarkCase.query,
            intent = QueryIntent.FIND_MEDIA,
            mediaScope = benchmarkCase.mediaScope,
            peopleClauses = benchmarkCase.requiredClusterId?.let { listOf(PersonClause(it)) }.orEmpty(),
            semanticClauses = clauses,
            limit = TOP_K,
        )
    }

    private fun eligibleMedia(plan: GalleryQueryPlan, benchmarkCase: BenchmarkCase): List<FixtureMedia> {
        val peopleScope = PeopleClauseResolver.resolve(plan.peopleClauses) { clusterId ->
            corpus.filter { clusterId in it.reviewedPeople }.mapTo(mutableSetOf(), FixtureMedia::id)
        }
        return corpus.filter { media ->
            val mediaScopeMatches = when (plan.mediaScope) {
                MediaScope.ALL -> true
                MediaScope.IMAGES -> media.kind == MediaKind.IMAGE
                MediaScope.VIDEOS -> media.kind == MediaKind.VIDEO
                MediaScope.DOCUMENTS -> media.kind == MediaKind.PDF || media.ocrText.isNotBlank()
            }
            mediaScopeMatches &&
                (benchmarkCase.hardYear == null || media.year == benchmarkCase.hardYear) &&
                (peopleScope.requiredIds == null || media.id in peopleScope.requiredIds) &&
                media.id !in peopleScope.excludedIds &&
                !DeterministicNegativeClausePolicy.excludes(media.galleryItem(), plan.semanticClauses)
        }
    }

    private fun lexicalRank(
        query: String,
        eligible: List<FixtureMedia>,
        text: (FixtureMedia) -> String,
    ): List<String> {
        val queryTokens = searchableTokens(query)
        return eligible.mapNotNull { media ->
            val overlap = queryTokens.intersect(searchableTokens(text(media))).size
            overlap.takeIf { it > 0 }?.let { media.id to it }
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .map(Pair<String, Int>::first)
            .take(TOP_K)
    }

    private suspend fun vectorRank(
        query: String,
        eligibleIds: Set<String>,
        text: (FixtureMedia) -> String,
    ): List<String> {
        val queryVector = encoder.encode(query)
        if (queryVector.all { it == 0f }) return emptyList()
        val index = ReferenceVectorIndex(encoder.dimension)
        corpus.forEach { media ->
            val vector = encoder.encode(text(media))
            if (vector.any { it != 0f }) index.upsert(media.id, vector)
        }
        return index.search(queryVector, TOP_K, eligibleIds).map(VectorHit::mediaId)
    }

    private fun matchesPersonConstraint(benchmarkCase: BenchmarkCase, media: FixtureMedia): Boolean {
        val clusterId = benchmarkCase.requiredClusterId ?: return true
        val fact = media.personFacts[clusterId] ?: return false
        return fact.polarity == Polarity.POSITIVE &&
            encoder.similarity(benchmarkCase.canonicalQuery, fact.text) >= PERSON_MATCH_THRESHOLD
    }

    private fun searchableTokens(text: String): Set<String> = TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT))
        .map(MatchResult::value)
        .filter { it.length > 1 && it !in STOP_WORDS }
        .toSet()

    private fun loadCases(): List<BenchmarkCase> {
        val stream = requireNotNull(javaClass.getResourceAsStream("/multilingual_retrieval_benchmark.tsv")) {
            "multilingual_retrieval_benchmark.tsv is missing from test resources"
        }
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).mapIndexed { index, line ->
                val fields = line.split('\t')
                require(fields.size == 11) { "Benchmark row ${index + 2} has ${fields.size} fields" }
                BenchmarkCase(
                    caseId = fields[0],
                    language = fields[1],
                    query = fields[2],
                    canonicalQuery = fields[3],
                    expectedId = fields[4],
                    hardYear = fields[5].takeUnless { it == "-" }?.toInt(),
                    mediaScope = MediaScope.valueOf(fields[6]),
                    requiredClusterId = fields[7].takeUnless { it == "-" },
                    excludeScreenshots = fields[8].toBooleanStrict(),
                    expectedChannels = fields[9].split(';').toSet(),
                    exactness = ResultExactness.valueOf(fields[10]),
                )
            }.toList()
        }
    }

    private fun fixtureCorpus(): List<FixtureMedia> = buildList {
        add(
            FixtureMedia(
                "goa-family-2025",
                2025,
                metadataText = "Goa 2025 family trip",
                captionText = "A family is walking beside the beach during a Goa trip",
                imageText = "family beach travel in Goa",
                eventText = "Goa family trip 2025",
            ),
        )
        add(
            FixtureMedia(
                "goa-family-2024",
                2024,
                metadataText = "Goa 2024 family trip",
                captionText = "A family is walking beside the beach during a Goa trip",
                imageText = "family beach travel in Goa",
                eventText = "Goa family trip 2024",
            ),
        )
        add(
            FixtureMedia(
                "red-automobile-2024",
                2024,
                metadataText = "road photo captured in 2024",
                captionText = "A red car is parked beside a road",
                imageText = "red vehicle car outdoors",
            ),
        )
        repeat(101) { index ->
            add(
                FixtureMedia(
                    "wrong-year-automobile-$index",
                    2023,
                    metadataText = "vehicle photo captured in 2023",
                    captionText = "An automobile fills the frame",
                    imageText = "automobile vehicle",
                ),
            )
        }
        add(
            FixtureMedia(
                "living-room-sofa",
                2024,
                metadataText = "living room interior 2024",
                captionText = "A large couch sits near the living room window",
                imageText = "sofa couch indoor furniture",
            ),
        )
        add(
            FixtureMedia(
                "wife-white-shoes",
                2024,
                reviewedPeople = setOf(WIFE_CLUSTER, ME_CLUSTER),
                metadataText = "family portrait 2024",
                captionText = "Two reviewed people are standing together",
                imageText = "two people with white shoes visible",
                personFacts = mapOf(
                    WIFE_CLUSTER to FixturePersonFact("wearing white shoes footwear"),
                    ME_CLUSTER to FixturePersonFact("wearing red clothing"),
                ),
            ),
        )
        add(
            FixtureMedia(
                "me-white-shoes",
                2024,
                reviewedPeople = setOf(WIFE_CLUSTER, ME_CLUSTER),
                metadataText = "family portrait 2024",
                captionText = "Two reviewed people are standing together",
                imageText = "two people with white shoes visible",
                personFacts = mapOf(
                    WIFE_CLUSTER to FixturePersonFact("wearing red clothing"),
                    ME_CLUSTER to FixturePersonFact("wearing white shoes footwear"),
                ),
            ),
        )
        add(
            FixtureMedia(
                "wife-black-handbag",
                2024,
                reviewedPeople = setOf(WIFE_CLUSTER, ME_CLUSTER),
                metadataText = "family portrait 2024",
                captionText = "Two reviewed people are walking outdoors",
                imageText = "two people and a black handbag purse",
                personFacts = mapOf(
                    WIFE_CLUSTER to FixturePersonFact("carrying black handbag purse"),
                    ME_CLUSTER to FixturePersonFact("carrying bag", Polarity.NEGATIVE),
                ),
            ),
        )
        add(
            FixtureMedia(
                "me-black-handbag",
                2024,
                reviewedPeople = setOf(WIFE_CLUSTER, ME_CLUSTER),
                metadataText = "family portrait 2024",
                captionText = "Two reviewed people are walking outdoors",
                imageText = "two people and a black handbag purse",
                personFacts = mapOf(
                    WIFE_CLUSTER to FixturePersonFact("carrying bag", Polarity.NEGATIVE),
                    ME_CLUSTER to FixturePersonFact("carrying black handbag purse"),
                ),
            ),
        )
        add(
            FixtureMedia(
                "swiggy-receipt",
                2025,
                metadataText = "latest Swiggy receipt screenshot",
                ocrText = "Swiggy receipt total amount INR 1248",
                captionText = "A food delivery receipt screenshot",
            ),
        )
        add(
            FixtureMedia(
                "beach-sunset-photo",
                2024,
                metadataText = "beach vacation photo 2024",
                captionText = "Orange sunset light over a beach and ocean",
                imageText = "beach sunset seaside dusk",
            ),
        )
        add(
            FixtureMedia(
                "beach-sunset-screenshot",
                2024,
                isScreenshot = true,
                metadataText = "beach sunset screen capture 2024",
                captionText = "A screenshot containing a beach sunset",
                imageText = "beach sunset seaside dusk",
            ),
        )
    }

    private data class BenchmarkCase(
        val caseId: String,
        val language: String,
        val query: String,
        val canonicalQuery: String,
        val expectedId: String,
        val hardYear: Int?,
        val mediaScope: MediaScope,
        val requiredClusterId: String?,
        val excludeScreenshots: Boolean,
        val expectedChannels: Set<String>,
        val exactness: ResultExactness,
    )

    private data class FixtureMedia(
        val id: String,
        val year: Int,
        val kind: MediaKind = MediaKind.IMAGE,
        val isScreenshot: Boolean = false,
        val reviewedPeople: Set<String> = emptySet(),
        val metadataText: String = "",
        val ocrText: String = "",
        val captionText: String = "",
        val imageText: String = "",
        val eventText: String = "",
        val personFacts: Map<String, FixturePersonFact> = emptyMap(),
    ) {
        fun galleryItem() = GalleryItem(
            id = id,
            filename = if (isScreenshot) "Screenshot_$id.png" else "$id.jpg",
            title = id,
            creator = null,
            location = "",
            latitude = null,
            longitude = null,
            tags = if (isScreenshot) listOf("screen capture") else emptyList(),
            description = captionText,
            license = "",
            sourceUrl = "",
            assetPath = null,
        )
    }

    private data class BenchmarkResult(
        val plan: GalleryQueryPlan,
        val variants: List<String>,
        val eligibleIds: Set<String>,
        val channelRanks: Map<String, List<String>>,
        val candidateIds: List<String>,
        val confirmedIds: List<String>,
    )

    private data class Measurement(val caseId: String, val language: String, val rank: Int)

    private data class FixturePersonFact(
        val text: String,
        val polarity: Polarity = Polarity.POSITIVE,
    )

    private class FixtureTextEncoder {
        private val concepts = listOf(
            setOf("goa"),
            setOf("family"),
            setOf("trip", "travel"),
            setOf("automobile", "car", "vehicle"),
            setOf("sofa", "couch"),
            setOf("living", "room"),
            setOf("footwear", "shoe", "shoes"),
            setOf("white"),
            setOf("handbag", "purse", "bag"),
            setOf("black"),
            setOf("swiggy"),
            setOf("receipt"),
            setOf("total", "amount"),
            setOf("beach", "seaside"),
            setOf("sunset", "dusk"),
        )

        val dimension: Int = concepts.size

        fun encode(text: String): FloatArray {
            val tokens = TOKEN_REGEX.findAll(text.lowercase(Locale.ROOT)).map(MatchResult::value).toSet()
            return FloatArray(dimension) { index -> if (concepts[index].any(tokens::contains)) 1f else 0f }
        }

        fun similarity(left: String, right: String): Double {
            val leftVector = encode(left)
            val rightVector = encode(right)
            val dot = leftVector.indices.sumOf { leftVector[it].toDouble() * rightVector[it] }
            val leftNorm = sqrt(leftVector.sumOf { it.toDouble() * it })
            val rightNorm = sqrt(rightVector.sumOf { it.toDouble() * it })
            return if (leftNorm == 0.0 || rightNorm == 0.0) 0.0 else dot / (leftNorm * rightNorm)
        }
    }

    private companion object {
        const val TOP_K = 5
        const val MINIMUM_MRR = .80
        const val MAXIMUM_LANGUAGE_RANK_SPREAD = 1
        const val PERSON_MATCH_THRESHOLD = .70
        const val WIFE_CLUSTER = "wife-cluster"
        const val ME_CLUSTER = "me-cluster"
        val TOKEN_REGEX = Regex("[\\p{L}\\p{N}]+")
        val STOP_WORDS = setOf(
            "a", "an", "and", "are", "from", "in", "is", "my", "of", "on", "photo", "photos",
            "picture", "pictures", "show", "the", "to", "was", "where", "with",
        )
    }
}
