package io.github.anup42.askalbum

import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealGemmaPeopleResolutionAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var database: GalleryDatabase? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        database?.close()
        database = null
        context.deleteDatabase(TEST_DATABASE)
    }

    @Test
    fun englishHindiAndHinglishResolveReviewedPeopleBeforeHardEligibility() = runBlocking {
        assumeFalse("Real Gemma acceptance requires the consumer variant", BuildConfig.MODEL_INDEPENDENT)
        val application = context.applicationContext as AskAlbumApplication
        val model = application.services.modelPackManager.status()
        assumeTrue("A verified E2B pack is required", model.installed && model.tier == GemmaModelTier.E2B)

        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster(ME_CLUSTER)
        store.ensureAutomaticPersonCluster(WIFE_CLUSTER)
        val images = store.allItems().filter { item ->
            item.kind == MediaKind.IMAGE &&
                (item.previewPath != null || item.assetPath != null || item.contentUri != null)
        }.take(2)
        assertEquals("The isolated corpus needs two image candidates", 2, images.size)
        val together = images[0]
        val meOnly = images[1]
        store.completeEmbeddedFaces(
            mediaId = together.id,
            faces = listOf(
                face(.08f, .10f, .28f, .48f, 0),
                face(.56f, .11f, .77f, .50f, 1),
            ),
            clusterIds = listOf(ME_CLUSTER, WIFE_CLUSTER),
            producerVersion = FACE_PRODUCER,
        )
        store.completeEmbeddedFaces(
            mediaId = meOnly.id,
            faces = listOf(face(.20f, .12f, .43f, .52f, 0)),
            clusterIds = listOf(ME_CLUSTER),
            producerVersion = FACE_PRODUCER,
        )
        store.saveReviewedPersonCluster(
            ME_CLUSTER,
            label = "Me",
            relationship = "Me",
            aliases = listOf("myself", "मैं", "mere"),
        )
        store.saveReviewedPersonCluster(
            WIFE_CLUSTER,
            label = "Anita",
            relationship = "partner",
            aliases = listOf("wife", "पत्नी", "biwi"),
        )
        assertEquals(setOf(ME_CLUSTER), store.resolveReviewedPersonIds(ME_CLUSTER))
        assertEquals(setOf(WIFE_CLUSTER), store.resolveReviewedPersonIds(WIFE_CLUSTER))

        val verifier = RecordingCandidateVerifier()
        val repository = GalleryRepository(
            context = context,
            database = store,
            planner = application.services.queryPlanCompiler,
            visualVerifier = verifier,
        )
        val initializationBefore = application.services.gemmaSessions.initializationCount
        val queries = listOf(
            "Show photos with me and my wife",
            "मैं और मेरी पत्नी वाली फोटो दिखाओ",
            "Meri biwi aur mere saath wali photos dikhao",
        )

        queries.forEachIndexed { index, query ->
            val outcome = withTimeout(3 * 60_000L) {
                repository.search(query, setOf(together.id, meOnly.id))
            }
            val peopleReport = outcome.channelReports.single { it.channel == RetrievalChannel.PEOPLE }
            val candidateBatch = verifier.candidateBatches.getOrNull(index)
            val diagnostic = "query=$query clauses=${outcome.plan.peopleClauses} terms=${outcome.plan.terms} " +
                "semantic=${outcome.plan.semanticClauses} people=${peopleReport.status}/" +
                "${peopleReport.indexedCount}/${peopleReport.eligibleCount} error=${peopleReport.errorCode} " +
                "headline=${outcome.answer.headline} detail=${outcome.answer.detail} hits=${outcome.hits.map { it.item.id }}"
            instrumentation.sendStatus(
                2,
                Bundle().apply { putString("real_gemma_people_case_$index", diagnostic.take(3_500)) },
            )
            assertTrue("No candidate reached verification: $diagnostic", candidateBatch != null)
            val resolvedRequiredPeople = PeopleClauseResolver.requiredGroups(outcome.plan.peopleClauses)
                .flatMap { group ->
                    group.flatMap { clause ->
                        store.resolveReviewedPersonIds(clause.personId).ifEmpty {
                            setOf(clause.personId).filterTo(linkedSetOf()) {
                                it == ME_CLUSTER || it == WIFE_CLUSTER
                            }
                        }
                    }
                }
                .toSet()
            assertEquals("$query did not resolve both reviewed identities", setOf(ME_CLUSTER, WIFE_CLUSTER), resolvedRequiredPeople)
            assertEquals("$query reached verification with the wrong eligible set", setOf(together.id), candidateBatch)
            assertEquals("$query returned media outside the hard People intersection", listOf(together.id), outcome.hits.map { it.item.id })
            assertEquals(ChannelStatus.SUCCESS, peopleReport.status)
            assertTrue("$query did not report complete reviewed-People coverage", peopleReport.indexedCount == peopleReport.eligibleCount)
        }

        val initializationAfter = application.services.gemmaSessions.initializationCount
        assertEquals("Multilingual People planning initialized Gemma more than once", 1, initializationAfter - initializationBefore)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "real_gemma_people_resolution_trace",
                    "REAL_GEMMA_PEOPLE_RESOLUTION queries=${queries.size} candidates=${verifier.candidateBatches.size} " +
                        "returned=${together.id} excluded=${meOnly.id} initDelta=${initializationAfter - initializationBefore}",
                )
            },
        )
    }

    private fun face(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        embeddingIndex: Int,
    ) = FaceInstance(
        bounds = listOf(left, top, right, bottom),
        embedding = FloatArray(FaceModelCatalog.sface.embeddingDimension).also { it[embeddingIndex] = 1f },
        quality = .95f,
    )

    private class RecordingCandidateVerifier : CandidateVerifier {
        val candidateBatches = mutableListOf<Set<String>>()

        override suspend fun verifyWhenNeeded(
            plan: GalleryQueryPlan,
            candidates: List<SearchHit>,
        ): VerificationResult {
            candidateBatches += candidates.mapTo(linkedSetOf()) { it.item.id }
            return VerificationResult(
                acceptedIds = candidates.mapTo(linkedSetOf()) { it.item.id },
                evidence = emptyList(),
            )
        }
    }

    private companion object {
        const val TEST_DATABASE = "real-gemma-people-resolution-acceptance.db"
        const val ME_CLUSTER = "person_me_multilingual_acceptance"
        const val WIFE_CLUSTER = "person_wife_multilingual_acceptance"
        const val FACE_PRODUCER = "fixture-face-real-people-resolution-v1"
    }
}
