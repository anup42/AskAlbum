package io.github.anup42.askalbum

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProductionPersonVerifierDeviceTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
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
        File(context.filesDir, FIXTURE_PACK_RELATIVE_PATH).delete()
        File(context.filesDir, "verification-fixture").delete()
    }

    @Test
    fun swappedClothingCannotSatisfyTheWrongReviewedIdentity() = runBlocking {
        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        store.seedDemoIfEmpty()
        store.ensureStageRows()
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        store.ensureAutomaticPersonCluster(ME_CLUSTER)
        store.ensureAutomaticPersonCluster(WIFE_CLUSTER)
        val item = store.allItems().first { candidate ->
            candidate.kind == MediaKind.IMAGE &&
                (candidate.previewPath != null || candidate.assetPath != null || candidate.contentUri != null)
        }
        store.completeEmbeddedFaces(
            mediaId = item.id,
            faces = listOf(
                face(.08f, .10f, .28f, .48f, 0),
                face(.56f, .11f, .77f, .50f, 1),
            ),
            clusterIds = listOf(ME_CLUSTER, WIFE_CLUSTER),
            producerVersion = "fixture-face-production-verifier",
        )
        store.saveReviewedPersonCluster(ME_CLUSTER, "Me", "Me", listOf("main"))
        store.saveReviewedPersonCluster(WIFE_CLUSTER, "Wife", "partner", listOf("spouse"))

        val plan = GalleryQueryPlan(
            originalQuery = "Show pictures with my wife where I am wearing white",
            intent = QueryIntent.FIND_MEDIA,
            semanticClauses = listOf(
                SemanticClause(
                    text = "Me is wearing white",
                    polarity = Polarity.POSITIVE,
                    hardness = ConstraintStrength.HARD,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = ME_CLUSTER,
                ),
                SemanticClause(
                    text = "Wife is wearing a white dress",
                    polarity = Polarity.POSITIVE,
                    hardness = ConstraintStrength.HARD,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = WIFE_CLUSTER,
                ),
            ),
            verification = VerificationPolicy.REQUIRED,
        )
        val conditions = VisualVerificationPolicy.conditions(plan)
        assertEquals(2, conditions.size)
        val response = JSONObject()
            .put(
                "conditions",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", conditions[0].id)
                            .put("verdict", PersonVisualVerdict.VERIFIED_FALSE.name)
                            .put("confidence", .99),
                    )
                    .put(
                        JSONObject()
                            .put("id", conditions[1].id)
                            .put("verdict", PersonVisualVerdict.VERIFIED_TRUE.name)
                            .put("confidence", .98),
                    ),
            )
            .put("overallMatch", false)
            .toString()
        val fixturePack = File(context.filesDir, FIXTURE_PACK_RELATIVE_PATH).apply {
            requireNotNull(parentFile).mkdirs()
            writeBytes(byteArrayOf(1))
        }
        val engine = RecordingVisionEngine(response)
        val sessions = GemmaSessionManager(
            resources = PassthroughResources(),
            factory = SharedGemmaEngineFactory { path, multimodal ->
                assertEquals(fixturePack.absolutePath, path)
                assertTrue(multimodal)
                engine
            },
            idleTimeoutMs = 60_000,
        )
        val verifier = LiteRtGemmaVisualVerifier(
            context = context,
            modelPacks = ModelPackManager(context),
            sessions = sessions,
            database = store,
            modelStatus = {
                ModelPackStatus(
                    installed = true,
                    path = fixturePack.absolutePath,
                    packId = "fixture-e2b",
                    packVersion = "production-verifier-v1",
                    tier = GemmaModelTier.E2B,
                    selectedTier = GemmaModelTier.E2B,
                    installedTiers = setOf(GemmaModelTier.E2B),
                    multimodal = true,
                )
            },
        )

        val result = verifier.verifyWhenNeeded(
            plan,
            listOf(SearchHit(item = item, score = 1.0, evidence = emptyList())),
        )

        assertTrue(result.applied)
        assertTrue(result.failures.joinToString(), result.failures.isEmpty())
        assertTrue(result.acceptedIds.isEmpty())
        assertFalse(result.evaluations.single().overallMatch)
        assertEquals(listOf(WIFE_CLUSTER), result.evidence.mapNotNull(EvidenceRecord::clusterId))
        assertEquals(
            mapOf(
                ME_CLUSTER to PersonVisualVerdict.VERIFIED_FALSE,
                WIFE_CLUSTER to PersonVisualVerdict.VERIFIED_TRUE,
            ),
            store.personVisualFactsForMedia(item.id).associate { fact -> fact.clusterId to fact.verdict },
        )
        assertEquals(1, sessions.initializationCount)
        assertEquals(1, engine.visionCalls)
        assertTrue(engine.lastImageBytes > 0)
        assertTrue(Regex("(?s)P1.*wearing white").containsMatchIn(engine.lastPrompt))
        assertTrue(Regex("(?s)P2.*white dress").containsMatchIn(engine.lastPrompt))

        sessions.evictNow()
        assertEquals(1, engine.closeCalls)
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

    private class PassthroughResources : InferenceResourceManager {
        override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T = block()
    }

    private class RecordingVisionEngine(private val response: String) : SharedGemmaEngine {
        override val backend = PlannerInferenceBackend.GPU
        var visionCalls = 0
        var closeCalls = 0
        var lastImageBytes = 0
        var lastPrompt = ""

        override suspend fun generateVision(imageBytes: ByteArray, prompt: String, seed: Int): String =
            respond(imageBytes, prompt)

        override suspend fun generateVision(
            imageBytes: ByteArray,
            prompt: String,
            options: GemmaGenerationOptions,
        ): String = respond(imageBytes, prompt)

        private fun respond(imageBytes: ByteArray, prompt: String): String {
            visionCalls++
            lastImageBytes = imageBytes.size
            lastPrompt = prompt
            return response
        }

        override fun close() {
            closeCalls++
        }
    }

    private companion object {
        const val TEST_DATABASE = "production-person-verifier-device-test.db"
        const val ME_CLUSTER = "person_me_verifier"
        const val WIFE_CLUSTER = "person_wife_verifier"
        const val FIXTURE_PACK_RELATIVE_PATH = "verification-fixture/fixture-verified-e2b.bin"
    }
}
