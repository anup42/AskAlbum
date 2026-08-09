package io.github.anup42.askalbum

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
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
class RealGemmaRepositoryPersonAppearanceAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private var database: GalleryDatabase? = null
    private var fixture: File? = null

    @Before
    fun prepare() {
        context.deleteDatabase(TEST_DATABASE)
    }

    @After
    fun cleanup() {
        database?.close()
        database = null
        context.deleteDatabase(TEST_DATABASE)
        fixture?.delete()
        fixture = null
    }

    @Test
    fun realPlannerAndVerifierKeepAppearanceBoundToReviewedIdentity() = runBlocking {
        assumeFalse("Real Gemma acceptance requires the consumer variant", BuildConfig.MODEL_INDEPENDENT)
        val application = context.applicationContext as AskAlbumApplication
        val model = application.services.modelPackManager.status()
        assumeTrue(
            "A verified multimodal E2B pack is required: $model",
            model.installed && model.multimodal && model.tier == GemmaModelTier.E2B,
        )

        val image = File(context.cacheDir, "real-repository-person-appearance-${System.nanoTime()}.jpg")
            .also { fixture = it }
        writeAppearanceFixture(image)

        val store = GalleryDatabase(context, TEST_DATABASE).also { database = it }
        assertEquals(
            1,
            store.upsertImported(
                listOf(
                    ImportedMedia(
                        stableId = MEDIA_ID,
                        uri = "content://io.github.anup42.askalbum.test/real-person-appearance",
                        displayName = "real-person-appearance.jpg",
                        mimeType = "image/jpeg",
                        source = MediaSource.PHOTO_PICKER,
                        capturedAt = 1_720_000_000_000L,
                        modifiedAt = 1_720_000_000_000L,
                        durationMs = null,
                        width = 1200,
                        height = 900,
                        sizeBytes = image.length(),
                        album = "Acceptance fixtures",
                    ),
                ),
            ),
        )
        store.ensureStageRows()
        store.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
        val item = requireNotNull(store.itemById(MEDIA_ID))
        store.completeIndex(
            id = item.id,
            labels = listOf("two people", "red outfit", "white dress", "standing together"),
            description = "Two people stand together; one wears red and the other wears a white dress.",
            ocrText = "",
            faceCount = 2,
            previewPath = image.absolutePath,
            blocks = emptyList(),
            entities = emptyList(),
            ocrAttempted = false,
            ocrProducerVersion = null,
            visualFeatures = VisualFeatures(0L, .95f, .95f, .98f),
            keyframes = emptyList(),
        )
        store.ensureAutomaticPersonCluster(ME_CLUSTER)
        store.ensureAutomaticPersonCluster(WIFE_CLUSTER)
        store.completeEmbeddedFaces(
            mediaId = item.id,
            faces = listOf(
                face(.1875f, .2333f, .3625f, .4667f, 0),
                face(.6375f, .2333f, .8125f, .4667f, 1),
            ),
            clusterIds = listOf(ME_CLUSTER, WIFE_CLUSTER),
            producerVersion = FACE_PRODUCER,
        )
        store.saveReviewedPersonCluster(ME_CLUSTER, "Me", "Me", listOf("myself", "main"))
        store.saveReviewedPersonCluster(WIFE_CLUSTER, "Anita", "partner", listOf("wife", "spouse", "biwi"))

        val reviewedBindings = store.reviewedFaceBindings(item.id, setOf(ME_CLUSTER, WIFE_CLUSTER))
        val allBindings = store.verificationFaceBindingsForMedia(item.id)
        assertEquals(setOf(ME_CLUSTER, WIFE_CLUSTER), reviewedBindings.mapTo(linkedSetOf()) { it.clusterId })
        assertEquals(2, reviewedBindings.size)
        assertEquals(2, allBindings.size)
        val storedItem = requireNotNull(store.itemById(item.id))
        val loaded = GalleryImageLoader(context).loadForVerification(
            SearchHit(storedItem, 1.0, emptyList()),
            emptyList(),
        )
        val composite = PersonVerificationImageComposer.compose(loaded.bytes, allBindings)
        assertTrue("Labelled verification composite was empty", composite.isNotEmpty())
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "real_repository_person_preflight",
                    "bindings=${allBindings.map { "${it.stableLabel}:${it.clusterId}" }} " +
                        "sourceBytes=${loaded.bytes.size} compositeBytes=${composite.size}",
                )
            },
        )

        application.services.gemmaSessions.evictNow()
        val initializationsBefore = application.services.gemmaSessions.initializationCount
        val candidateErrors = mutableListOf<Throwable>()
        val verifier = RecordingVerifier(
            LiteRtGemmaVisualVerifier(
                context = context,
                modelPacks = application.services.modelPackManager,
                sessions = application.services.gemmaSessions,
                database = store,
                candidateErrorObserver = candidateErrors::add,
            ),
        )
        val repository = GalleryRepository(
            context = context,
            database = store,
            planner = application.services.queryPlanCompiler,
            visualVerifier = verifier,
        )

        val negative = withTimeout(6 * 60_000L) {
            repository.search(
                "Show pictures with my wife where I am wearing white",
                setOf(item.id),
            )
        }
        val negativeConditions = negative.plan.semanticClauses.filter { it.subject == SemanticSubject.PERSON }
        val negativeDiagnostic = diagnostic(
            "negative",
            negative,
            negativeConditions,
            verifier.results.lastOrNull(),
            candidateErrors.lastOrNull(),
        )
        instrumentation.sendStatus(2, Bundle().apply { putString("real_repository_person_negative", negativeDiagnostic) })
        val negativeVisual = requireNotNull(
            negative.channelReports.singleOrNull { it.channel == RetrievalChannel.VISUAL_VERIFICATION },
        ) { "Visual verification did not run: $negativeDiagnostic" }

        assertEquals(setOf(ME_CLUSTER, WIFE_CLUSTER), resolvedPeople(store, negative.plan))
        assertTrue(
            "Planner did not bind the white appearance predicate to Me: $negativeDiagnostic",
            negativeConditions.any { clause ->
                clause.relationToPerson == ME_CLUSTER && "white" in clause.text.lowercase()
            },
        )
        assertEquals(ChannelStatus.SUCCESS, negativeVisual.status)
        assertEquals(1, negativeVisual.eligibleCount)
        assertEquals(1, negativeVisual.searchedCount)
        assertTrue("Wrong-person clothing escaped verification: $negativeDiagnostic", negative.hits.isEmpty())
        assertTrue("Rejected media leaked answer evidence", negative.answer.evidenceIds.isEmpty())
        assertTrue("Rejected media produced grounded claims", negative.answer.claims.isEmpty())
        assertTrue(
            "The negative verdict was not cached against Me",
            store.personVisualFactsForMedia(item.id).any { fact ->
                fact.clusterId == ME_CLUSTER && fact.verdict == PersonVisualVerdict.VERIFIED_FALSE
            },
        )

        val positive = withTimeout(6 * 60_000L) {
            repository.search(
                "Show pictures where my wife is wearing a white dress",
                setOf(item.id),
            )
        }
        val positiveConditions = positive.plan.semanticClauses.filter { it.subject == SemanticSubject.PERSON }
        val positiveDiagnostic = diagnostic(
            "positive",
            positive,
            positiveConditions,
            verifier.results.lastOrNull(),
            candidateErrors.lastOrNull(),
        )
        instrumentation.sendStatus(2, Bundle().apply { putString("real_repository_person_positive", positiveDiagnostic) })
        val positiveVisual = requireNotNull(
            positive.channelReports.singleOrNull { it.channel == RetrievalChannel.VISUAL_VERIFICATION },
        ) { "Visual verification did not run: $positiveDiagnostic" }

        assertEquals(setOf(WIFE_CLUSTER), resolvedPeople(store, positive.plan))
        assertTrue(
            "Planner did not bind the white dress predicate to Wife: $positiveDiagnostic",
            positiveConditions.any { clause ->
                clause.relationToPerson == WIFE_CLUSTER &&
                    "white" in clause.text.lowercase() &&
                    "dress" in clause.text.lowercase()
            },
        )
        assertEquals(ChannelStatus.SUCCESS, positiveVisual.status)
        assertEquals(1, positiveVisual.searchedCount)
        assertEquals(listOf(item.id), positive.hits.map { it.item.id })
        assertTrue(
            "Accepted result lacks Wife-bound verification evidence",
            positive.hits.single().evidence.any { evidence ->
                evidence.sourceField == "visual_verification" && evidence.clusterId == WIFE_CLUSTER
            },
        )
        assertTrue(
            "The positive verdict was not cached against Wife",
            store.personVisualFactsForMedia(item.id).any { fact ->
                fact.clusterId == WIFE_CLUSTER && fact.verdict == PersonVisualVerdict.VERIFIED_TRUE
            },
        )

        val initializationDelta = application.services.gemmaSessions.initializationCount - initializationsBefore
        assertEquals("Planning and both verification passes initialized Gemma more than once", 1, initializationDelta)
        instrumentation.sendStatus(
            2,
            Bundle().apply {
                putString(
                    "real_repository_person_appearance_trace",
                    "REAL_REPOSITORY_PERSON_APPEARANCE negativeHits=${negative.hits.size} " +
                        "positiveHits=${positive.hits.size} initDelta=$initializationDelta " +
                        "negativePeople=${resolvedPeople(store, negative.plan)} " +
                        "positivePeople=${resolvedPeople(store, positive.plan)}",
                )
            },
        )
    }

    private fun resolvedPeople(store: GalleryDatabase, plan: GalleryQueryPlan): Set<String> =
        PeopleClauseResolver.requiredGroups(plan.peopleClauses)
            .flatMap { alternatives ->
                alternatives.flatMap { clause ->
                    store.resolveReviewedPersonIds(clause.personId).ifEmpty { setOf(clause.personId) }
                }
            }
            .toSet()

    private fun diagnostic(
        label: String,
        outcome: SearchOutcome,
        conditions: List<SemanticClause>,
        verification: VerificationResult?,
        candidateError: Throwable?,
    ): String = "$label people=${outcome.plan.peopleClauses} conditions=$conditions " +
        "terms=${outcome.plan.terms} hits=${outcome.hits.map { it.item.id }} " +
        "channels=${outcome.channelReports.associate { it.channel to "${it.status}:${it.searchedCount}/${it.eligibleCount}:${it.errorCode}" }} " +
        "verificationFailures=${verification?.failures} evaluations=${verification?.evaluations} trace=${verification?.trace} " +
        "candidateError=${candidateError?.javaClass?.simpleName}:${candidateError?.message?.take(500)} " +
        "headline=${outcome.answer.headline} detail=${outcome.answer.detail}"

    private class RecordingVerifier(private val delegate: CandidateVerifier) : CandidateVerifier {
        val results = mutableListOf<VerificationResult>()

        override suspend fun verifyWhenNeeded(
            plan: GalleryQueryPlan,
            candidates: List<SearchHit>,
        ): VerificationResult = delegate.verifyWhenNeeded(plan, candidates).also(results::add)
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
        quality = .98f,
    )

    private fun writeAppearanceFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(242, 240, 232))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        paint.color = Color.rgb(26, 31, 43)
        paint.textSize = 52f
        canvas.drawText("TWO PEOPLE STANDING TOGETHER", 600f, 75f, paint)
        drawPerson(canvas, paint, 330f, "PERSON A", Color.rgb(205, 40, 45), "RED OUTFIT")
        drawPerson(canvas, paint, 870f, "PERSON B", Color.WHITE, "WHITE DRESS", whiteDress = true)
        paint.color = Color.rgb(26, 31, 43)
        paint.textSize = 36f
        canvas.drawText("PERSON A DOES NOT WEAR WHITE", 600f, 845f, paint)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
            output.fd.sync()
        }
        bitmap.recycle()
    }

    private fun drawPerson(
        canvas: Canvas,
        paint: Paint,
        centerX: Float,
        label: String,
        bodyColor: Int,
        clothingLabel: String,
        whiteDress: Boolean = false,
    ) {
        paint.color = Color.rgb(26, 31, 43)
        paint.textSize = 46f
        canvas.drawText(label, centerX, 155f, paint)
        paint.color = Color.rgb(225, 184, 150)
        canvas.drawCircle(centerX, 315f, 105f, paint)
        paint.color = Color.rgb(45, 37, 32)
        paint.strokeWidth = 14f
        canvas.drawLine(centerX - 55f, 235f, centerX + 55f, 235f, paint)
        paint.color = bodyColor
        if (whiteDress) {
            val dress = android.graphics.Path().apply {
                moveTo(centerX - 100f, 420f)
                lineTo(centerX + 100f, 420f)
                lineTo(centerX + 180f, 720f)
                lineTo(centerX - 180f, 720f)
                close()
            }
            canvas.drawPath(dress, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.rgb(30, 35, 48)
            paint.strokeWidth = 8f
            canvas.drawPath(dress, paint)
            paint.style = Paint.Style.FILL
        } else {
            canvas.drawRoundRect(centerX - 165f, 420f, centerX + 165f, 720f, 28f, 28f, paint)
        }
        paint.color = Color.rgb(26, 31, 43)
        paint.textSize = 38f
        canvas.drawText(clothingLabel, centerX, 780f, paint)
    }

    private companion object {
        const val TEST_DATABASE = "real-repository-person-appearance-acceptance.db"
        const val MEDIA_ID = "real_repository_person_appearance_media"
        const val ME_CLUSTER = "person_me_real_repository"
        const val WIFE_CLUSTER = "person_wife_real_repository"
        const val FACE_PRODUCER = "real-repository-person-appearance-face-v1"
    }
}
