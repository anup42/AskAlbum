package io.github.anup42.askalbum

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreCorpusEvaluationAcceptanceTest {
    @Test
    fun q01ThroughQ13ProduceStructuredEvidenceBackedEvaluation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val runId = InstrumentationRegistry.getArguments().getString("galleryRunId")
        assumeTrue("galleryRunId was not supplied", !runId.isNullOrBlank())
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val repository = application.repository
        withContext(Dispatchers.IO) { repository.initialize() }
        val pack = requireNotNull(application.services.retrievalModelPackManager.current())
        assertEquals("ba1f3b0-q8-core05", pack.manifest.packVersion)
        assertEquals(0.05f, pack.manifest.minimumSimilarity, 0f)

        val safeRunId = requireNotNull(runId)
        val seedResult = JSONObject(File(application.filesDir, "test-seed/$safeRunId/seed-result.json").readText())
        val seededUris = seedResult.getJSONArray("createdUris").let { array ->
            (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
        }
        val seededIds = repository.allItems().filter { it.contentUri in seededUris }.mapTo(mutableSetOf()) { it.id }
        assertEquals("Core evaluator must resolve every recorded seed URI", seedResult.getInt("createdCount"), seededIds.size)
        waitForIndex(repository, application, seededUris)
        val records = mutableListOf<CaseRecord>()
        val session = "core_eval_${safeRunId.take(40)}_${System.currentTimeMillis()}"
        var q01: SearchOutcome? = null
        var q02: SearchOutcome? = null

        records.evaluate("Q01") {
            repository.searchInSession("Show photos from my Singapore trip.", session, seededIds).also { outcome ->
                q01 = outcome
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                val rank = requireRank(outcome, listOf("singapore_marina_bay_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "event" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("singapore_marina_bay_01"))
        }
        records.evaluate("Q02") {
            val parent = requireNotNull(q01) { "Q01 did not produce a parent result set" }
            repository.searchInSession("Only Marina Bay.", session).also { outcome ->
                q02 = outcome
                assertEquals(parent.resultSetId, outcome.baseResultSetId)
                assertTrue(outcome.hits.all { hit -> parent.hits.any { it.item.id == hit.item.id } })
                val rank = requireRank(outcome, listOf("singapore_marina_bay_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("singapore_marina_bay_01"))
        }
        records.evaluate("Q03") {
            val parent = requireNotNull(q02) { "Q02 did not produce a parent result set" }
            repository.searchInSession("What about last year?", session).also { outcome ->
                assertEquals("Q03 did not retain Q02 as its parent", parent.resultSetId, outcome.baseResultSetId)
                assertTrue("Q03 fabricated ${outcome.hits.size} out-of-period hits", outcome.hits.isEmpty())
                assertTrue(
                    "Q03 emitted unsupported evidence or claims",
                    outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty(),
                )
                assertEquals("Q03 result-set filter was not exact", ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q04") {
            repository.search("What was the total on my latest Swiggy receipt?", seededIds).also { outcome ->
                assertEquals(QueryIntent.ANSWER_FACT, outcome.plan.intent)
                assertEquals(SensitiveEvidencePolicy.LOCKED_HEADLINE.uppercase(), outcome.answer.headline.uppercase())
                assertTrue(outcome.answer.requiresAuthentication)
                assertTrue(outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty())
                val receipt = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_swiggy_receipt.png" })
                assertTrue(receipt.evidence.any { it.sourceField == "document_total" })
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics(listOf("synthetic_swiggy_receipt"))
        }
        records.evaluate("Q05") {
            repository.search("How many photos did I take in 2024?", seededIds).also { outcome ->
                assertEquals(QueryIntent.COUNT, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                assertTrue(outcome.plan.semanticClauses.isEmpty() && outcome.plan.terms.isEmpty())
                assertEquals("67 matching items", outcome.answer.headline)
                assertEquals(ResultExactness.EXACT, outcome.answer.exactness)
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q06") {
            repository.search("Show beach sunsets.", seededIds).also { outcome ->
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                val rank = requireRank(outcome, listOf("goa_beach_01", "legacy_demo-beach"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("goa_beach_01", "legacy_demo-beach"))
        }

        val store = application.services.galleryDatabase
        val peopleFixtures = repository.allItems()
            .filter { it.id in seededIds && it.filename.startsWith("singapore_marina_bay_01_v") }
            .sortedBy(GalleryItem::filename)
            .take(3)
        assertEquals("People acceptance requires three run-scoped fixture images", 3, peopleFixtures.size)
        val together = peopleFixtures[0]
        val meOnly = peopleFixtures[1]
        val swapped = peopleFixtures[2]
        store.resetPeopleIndex()
        try {
            seedReviewedPeopleFixtures(store, together.id, meOnly.id, swapped.id)
            records.evaluate("Q07") {
                repository.search(
                    "Show photos with me and my brother.",
                    setOf(together.id, meOnly.id),
                ).also { outcome ->
                    val peopleReport = outcome.channelReports.single { it.channel == RetrievalChannel.PEOPLE }
                    assertEquals(setOf(ME_CLUSTER, BROTHER_CLUSTER), requiredPeople(store, outcome.plan))
                    assertEquals(listOf(together.id), outcome.hits.map { it.item.id })
                    assertEquals(ChannelStatus.SUCCESS, peopleReport.status)
                    assertEquals(peopleReport.eligibleCount, peopleReport.indexedCount)
                    assertEvidenceClosure(outcome)
                }.metrics(listOf(together.filename.substringBeforeLast('.')))
            }

            val appearancePlan = GalleryQueryPlan(
                originalQuery = APPEARANCE_QUERY,
                intent = QueryIntent.FIND_MEDIA,
                mediaScope = MediaScope.IMAGES,
                peopleClauses = listOf(PersonClause(ME_CLUSTER), PersonClause(BROTHER_CLUSTER)),
                semanticClauses = listOf(
                    SemanticClause(
                        text = "wearing a yellow hat",
                        hardness = ConstraintStrength.HARD,
                        subject = SemanticSubject.PERSON,
                        relationToPerson = ME_CLUSTER,
                    ),
                    SemanticClause(
                        text = "wearing a blue suit",
                        hardness = ConstraintStrength.HARD,
                        subject = SemanticSubject.PERSON,
                        relationToPerson = BROTHER_CLUSTER,
                    ),
                ),
                terms = listOf("singapore"),
                verification = VerificationPolicy.REQUIRED,
                limit = 10,
            )
            val cachedVerifier = LiteRtGemmaVisualVerifier(
                context = application,
                modelPacks = application.services.modelPackManager,
                sessions = application.services.gemmaSessions,
                database = store,
            )
            val peopleRepository = GalleryRepository(
                context = application,
                database = store,
                planner = FixedPlanCompiler(appearancePlan),
                visualVerifier = cachedVerifier,
            )
            records.evaluate("Q08") {
                peopleRepository.search(APPEARANCE_QUERY, setOf(together.id, swapped.id)).also { outcome ->
                    val visualReport = outcome.channelReports.single { it.channel == RetrievalChannel.VISUAL_VERIFICATION }
                    assertEquals(listOf(together.id), outcome.hits.map { it.item.id })
                    assertEquals(ChannelStatus.SUCCESS, visualReport.status)
                    assertEquals(2, visualReport.searchedCount)
                    assertEquals(
                        setOf(ME_CLUSTER, BROTHER_CLUSTER),
                        outcome.hits.single().evidence
                            .filter { it.sourceField == "visual_verification" }
                            .mapNotNull(EvidenceRecord::clusterId)
                            .toSet(),
                    )
                    assertTrue(
                        "Swapped fixture lacks opposite-person truth",
                        store.personVisualFactsForMedia(swapped.id).count {
                            it.verdict == PersonVisualVerdict.VERIFIED_TRUE
                        } == 2,
                    )
                    assertEvidenceClosure(outcome)
                }.metrics(listOf(together.filename.substringBeforeLast('.')))
            }
        } finally {
            store.resetPeopleIndex()
        }

        records.evaluate("Q09") {
            repository.search("Pichle saal Goa wali photos dikhao.", seededIds).also { outcome ->
                assertEquals(QueryIntent.FIND_MEDIA, outcome.plan.intent)
                assertEquals(MediaScope.IMAGES, outcome.plan.mediaScope)
                val rank = requireRank(outcome, listOf("goa_beach_01"), 10)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "event" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("goa_beach_01"))
        }
        records.evaluate("Q10") {
            repository.search("Show a receipt from a merchant that does not exist.", seededIds).also { outcome ->
                assertEquals(QueryIntent.DOCUMENT_QA, outcome.plan.intent)
                assertTrue(outcome.hits.isEmpty())
                assertTrue(outcome.answer.evidenceIds.isEmpty() && outcome.answer.claims.isEmpty())
                assertEvidenceClosure(outcome)
            }.metrics()
        }
        records.evaluate("Q11") {
            repository.search("Find the yellow bicycle in my video.", seededIds).also { outcome ->
                assertEquals(MediaScope.VIDEOS, outcome.plan.mediaScope)
                val video = requireNotNull(outcome.hits.firstOrNull { it.item.filename == "synthetic_video_screen_timeline.mp4" })
                val evidence = requireNotNull(video.evidence.firstOrNull { it.sourceField == "video_keyframe" })
                assertTrue(requireNotNull(evidence.timestampMs) in 5_000L..13_000L)
                assertEvidenceClosure(outcome)
            }.metrics(listOf("synthetic_video_screen_timeline"))
        }
        records.evaluate("Q12") {
            repository.search("Show photos of my dog.", seededIds).also { outcome ->
                val rank = requireRank(outcome, listOf("domesticated_dog_01"), 5)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("domesticated_dog_01"))
        }
        records.evaluate("Q13") {
            repository.search("Show children playing football outdoors.", seededIds).also { outcome ->
                val rank = requireRank(outcome, listOf("children_football_01"), 5)
                assertTrue(outcome.hits[rank - 1].evidence.any { it.sourceField == "image_text_embedding" })
                assertEvidenceClosure(outcome)
            }.metrics(listOf("children_football_01"))
        }

        val artifact = JSONObject().apply {
            put("schemaVersion", 1)
            put("galleryRunId", requireNotNull(runId))
            put("retrievalPack", pack.manifest.packVersion)
            put("minimumSimilarity", pack.manifest.minimumSimilarity.toDouble())
            put("indexedItems", repository.allItems().count { it.indexState == IndexState.READY })
            put("passed", records.count { it.status == "PASS" })
            put("failed", records.count { it.status == "FAIL" })
            put("skipped", records.count { it.status == "SKIP" })
            put("cases", JSONArray(records.map(CaseRecord::json)))
        }
        val outputRoot = File(application.filesDir, "evaluation").also { require(it.mkdirs() || it.isDirectory) }
        File(outputRoot, "core-q01-q13.json").writeText(artifact.toString(2), Charsets.UTF_8)
        instrumentation.sendStatus(2, Bundle().apply {
            putString(
                "core_evaluation_summary",
                "CORE_Q01_Q13 passed=${artifact.getInt("passed")} failed=${artifact.getInt("failed")} " +
                    "skipped=${artifact.getInt("skipped")} threshold=${pack.manifest.minimumSimilarity}",
            )
        })
        val failed = records.filter { it.status == "FAIL" }
        assertTrue(failed.joinToString { "${it.id}: ${it.detail}" }, failed.isEmpty())
    }

    private fun waitForIndex(
        repository: GalleryRepository,
        application: AskAlbumApplication,
        expectedUris: Set<String>,
    ) {
        IndexScheduler.schedule(application)
        EmbeddingIndexScheduler.schedule(application)
        val deadline = SystemClock.elapsedRealtime() + INDEX_TIMEOUT_MS
        val requiredStages = setOf(
            IndexStage.DISCOVERY,
            IndexStage.METADATA,
            IndexStage.THUMBNAIL,
            IndexStage.VIDEO_KEYFRAMES,
            IndexStage.EMBEDDING,
            IndexStage.OCR,
            IndexStage.EVENTS,
        )
        fun inFlight(coverage: ScopedIndexCoverage): Int = coverage.stageStatuses
            .filterKeys { it in requiredStages }
            .values
            .sumOf { counts -> (counts[StageStatus.PENDING] ?: 0) + (counts[StageStatus.RUNNING] ?: 0) }

        var coverage = repository.indexCoverageForContentUris(expectedUris)
        while (
            (coverage.mediaCount < expectedUris.size ||
                (coverage.indexStates[IndexState.PENDING] ?: 0) > 0 ||
                (coverage.indexStates[IndexState.INDEXING] ?: 0) > 0 ||
                inFlight(coverage) > 0) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(500)
            coverage = repository.indexCoverageForContentUris(expectedUris)
        }
        assertEquals("Core media rows did not finish", expectedUris.size, coverage.mediaCount)
        assertEquals(0, coverage.indexStates[IndexState.PENDING] ?: 0)
        assertEquals(0, coverage.indexStates[IndexState.INDEXING] ?: 0)
        requiredStages.forEach { stage ->
            val counts = coverage.stageStatuses.getValue(stage)
            assertEquals("$stage still has pending work", 0, counts[StageStatus.PENDING] ?: 0)
            assertEquals("$stage still has running work", 0, counts[StageStatus.RUNNING] ?: 0)
            assertEquals("$stage has retryable failures", 0, counts[StageStatus.FAILED_RETRYABLE] ?: 0)
            assertEquals("$stage has exhausted failures", 0, counts[StageStatus.FAILED_EXHAUSTED] ?: 0)
            assertEquals("$stage has permanent failures", 0, counts[StageStatus.FAILED_PERMANENT] ?: 0)
        }
    }

    private suspend fun MutableList<CaseRecord>.evaluate(id: String, block: suspend () -> CaseMetrics) {
        val started = SystemClock.elapsedRealtime()
        val result = runCatching { block() }
        val elapsed = SystemClock.elapsedRealtime() - started
        this += result.fold(
            onSuccess = { metrics ->
                CaseRecord(id, "PASS", elapsed, metrics.hitCount, metrics.expectedRank, metrics.exactness, metrics.detail)
            },
            onFailure = { error ->
                CaseRecord(id, "FAIL", elapsed, 0, null, null, error.message.orEmpty().take(500))
            },
        )
    }

    private fun SearchOutcome.metrics(prefixes: List<String> = emptyList()): CaseMetrics = CaseMetrics(
        hitCount = hits.size,
        expectedRank = prefixes.takeIf(List<String>::isNotEmpty)?.let { rankByPrefix(this, it) },
        exactness = answer.exactness.name,
        detail = "intent=${plan.intent}; evidence=${answer.evidenceIds.size}",
    )

    private fun requireRank(outcome: SearchOutcome, prefixes: List<String>, maxRank: Int): Int {
        val rank = rankByPrefix(outcome, prefixes)
        assertNotNull("Expected ${prefixes.joinToString()} in top $maxRank; got ${outcome.hits.take(maxRank).map { it.item.filename }}", rank)
        assertTrue("Expected result rank $rank exceeded top-$maxRank", requireNotNull(rank) <= maxRank)
        return rank
    }

    private fun rankByPrefix(outcome: SearchOutcome, prefixes: List<String>): Int? = outcome.hits
        .indexOfFirst { hit -> prefixes.any { prefix -> hit.item.filename.startsWith(prefix) } }
        .takeIf { it >= 0 }
        ?.plus(1)

    private fun assertEvidenceClosure(outcome: SearchOutcome) {
        val evidence = outcome.hits.flatMap(SearchHit::evidence).associateBy(EvidenceRecord::id)
        assertTrue("Answer cites unknown evidence", outcome.answer.evidenceIds.all(evidence::containsKey))
        assertTrue("Claim cites unknown evidence", outcome.answer.claims.flatMap(GroundedClaim::evidenceIds).all(evidence::containsKey))
    }

    private fun seedReviewedPeopleFixtures(
        store: GalleryDatabase,
        togetherId: String,
        meOnlyId: String,
        swappedId: String,
    ) {
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster(ME_CLUSTER)
        store.ensureAutomaticPersonCluster(BROTHER_CLUSTER)
        store.completeEmbeddedFaces(
            togetherId,
            listOf(face(.08f, .10f, .28f, .48f, 0), face(.56f, .11f, .77f, .50f, 1)),
            listOf(ME_CLUSTER, BROTHER_CLUSTER),
            FACE_PRODUCER,
        )
        store.completeEmbeddedFaces(
            meOnlyId,
            listOf(face(.20f, .12f, .43f, .52f, 0)),
            listOf(ME_CLUSTER),
            FACE_PRODUCER,
        )
        store.completeEmbeddedFaces(
            swappedId,
            listOf(face(.08f, .10f, .28f, .48f, 0), face(.56f, .11f, .77f, .50f, 1)),
            listOf(ME_CLUSTER, BROTHER_CLUSTER),
            FACE_PRODUCER,
        )
        store.saveReviewedPersonCluster(ME_CLUSTER, "Me", "Me", listOf("myself", "main"))
        store.saveReviewedPersonCluster(BROTHER_CLUSTER, "Brother", "brother", listOf("bhaiya", "\u092d\u0948\u092f\u093e"))

        saveFixtureVerdict(store, togetherId, ME_CLUSTER, "P1 is wearing a yellow hat", ME_REGION, PersonVisualVerdict.VERIFIED_TRUE)
        saveFixtureVerdict(store, togetherId, BROTHER_CLUSTER, "P2 is wearing a blue suit", BROTHER_REGION, PersonVisualVerdict.VERIFIED_TRUE)
        saveFixtureVerdict(store, swappedId, ME_CLUSTER, "P1 is wearing a yellow hat", ME_REGION, PersonVisualVerdict.VERIFIED_FALSE)
        saveFixtureVerdict(store, swappedId, BROTHER_CLUSTER, "P2 is wearing a blue suit", BROTHER_REGION, PersonVisualVerdict.VERIFIED_FALSE)
        saveFixtureVerdict(store, swappedId, ME_CLUSTER, "P1 is wearing a blue suit", ME_REGION, PersonVisualVerdict.VERIFIED_TRUE)
        saveFixtureVerdict(store, swappedId, BROTHER_CLUSTER, "P2 is wearing a yellow hat", BROTHER_REGION, PersonVisualVerdict.VERIFIED_TRUE)
    }

    private fun saveFixtureVerdict(
        store: GalleryDatabase,
        mediaId: String,
        clusterId: String,
        predicate: String,
        region: List<Float>,
        verdict: PersonVisualVerdict,
    ) = store.saveVerifiedPersonAttributeFact(
        mediaId = mediaId,
        clusterId = clusterId,
        predicate = predicate,
        value = verdict.name,
        confidence = .99f,
        region = region,
        modelVersion = FIXTURE_VERIFIER_VERSION,
        verdict = verdict,
    )

    private fun face(left: Float, top: Float, right: Float, bottom: Float, index: Int) = FaceInstance(
        bounds = listOf(left, top, right, bottom),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[index] = 1f },
        quality = .99f,
    )

    private fun requiredPeople(store: GalleryDatabase, plan: GalleryQueryPlan): Set<String> =
        PeopleClauseResolver.requiredGroups(plan.peopleClauses).flatMapTo(linkedSetOf()) { group ->
            group.flatMap { clause ->
                store.resolveReviewedPersonIds(clause.personId).ifEmpty { setOf(clause.personId) }
            }
        }

    private class FixedPlanCompiler(private val plan: GalleryQueryPlan) : GalleryPlanCompiler {
        override suspend fun compile(query: String, activeResultIds: Set<String>?): GalleryQueryPlan =
            plan.copy(originalQuery = query, baseResultIds = activeResultIds)
    }

    private data class CaseMetrics(
        val hitCount: Int,
        val expectedRank: Int?,
        val exactness: String,
        val detail: String,
    )

    private data class CaseRecord(
        val id: String,
        val status: String,
        val latencyMs: Long,
        val hitCount: Int,
        val expectedRank: Int?,
        val exactness: String?,
        val detail: String,
    ) {
        fun json(): JSONObject = JSONObject().apply {
            put("id", id)
            put("status", status)
            put("latencyMs", latencyMs)
            put("hitCount", hitCount)
            if (expectedRank == null) put("expectedRank", JSONObject.NULL) else put("expectedRank", expectedRank)
            if (exactness == null) put("exactness", JSONObject.NULL) else put("exactness", exactness)
            put("detail", detail)
        }
    }

    private companion object {
        const val INDEX_TIMEOUT_MS = 10 * 60_000L
        const val ME_CLUSTER = "person_core_me"
        const val BROTHER_CLUSTER = "person_core_brother"
        const val FACE_PRODUCER = "fixture-core-people-v1"
        const val FIXTURE_VERIFIER_VERSION = "gemma-4-e2b-fixture-cache-v1"
        const val APPEARANCE_QUERY =
            "Show photos where I am wearing a yellow hat and my brother is wearing a blue suit."
        val ME_REGION = listOf(.08f, .10f, .28f, .48f)
        val BROTHER_REGION = listOf(.56f, .11f, .77f, .50f)
    }
}
