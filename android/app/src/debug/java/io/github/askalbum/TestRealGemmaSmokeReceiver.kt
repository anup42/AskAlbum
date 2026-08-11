package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * Same-UID debug hook for real-model acceptance without consumer instrumentation.
 *
 * The receiver uses synthetic, non-sensitive evidence and writes only a bounded private report.
 * It never changes model selection, gallery rows, indexes, People data, or consent.
 */
class TestRealGemmaSmokeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SMOKE_REAL_GEMMA) return
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID)
            ?.takeIf(OPERATION_ID::matches)
            ?: return
        val application = context.applicationContext as AskAlbumApplication
        val reportFile = File(context.filesDir, "test-models/real-gemma-smoke-$operationId.json")
        val startedAt = System.currentTimeMillis()
        val startedElapsed = SystemClock.elapsedRealtime()

        scope.launch {
            try {
                writeReport(
                    reportFile,
                    JSONObject()
                        .put("state", "RUNNING")
                        .put("operationId", operationId)
                        .put("startedAt", startedAt),
                )
                val report = withTimeout(SMOKE_TIMEOUT_MS) {
                    runSmoke(application, operationId, startedAt, startedElapsed)
                }
                writeReport(reportFile, report)
            } catch (error: Throwable) {
                writeReport(
                    reportFile,
                    JSONObject()
                        .put("state", "FAILED")
                        .put("operationId", operationId)
                        .put("startedAt", startedAt)
                        .put("completedAt", System.currentTimeMillis())
                        .put("errorType", error::class.java.simpleName.take(MAX_TEXT_LENGTH))
                        .put("error", error.message.safeReportText()),
                )
            }
        }
    }

    private suspend fun runSmoke(
        application: AskAlbumApplication,
        operationId: String,
        startedAt: Long,
        startedElapsed: Long,
    ): JSONObject {
        val status = application.modelPackManager.status()
        check(status.installed) { "No verified Gemma pack is active" }
        check(status.tier == GemmaModelTier.E2B) { "The active default pack is not E2B" }
        val modelPath = requireNotNull(status.path) { "The verified Gemma artifact path is absent" }
        val sessions = application.services.gemmaSessions
        val initializationsBefore = sessions.initializationCount
        val planner = LiteRtLmQueryPlanner(application.modelPackManager, sessions)
        val previousYear = LocalDate.now().year - 1
        val expectedRange = calendarYear(previousYear)
        val plannerCases = PLANNER_CASES.map { case ->
            val trace = planner.compileWithTrace(case.query, activeResultIds = null)
            val caseValidation = GalleryQueryPlanValidator().validate(trace.plan)
            val searchable = searchableText(trace.plan)
            check(trace.usedGemma) { "${case.id}: ${trace.fallbackReason ?: "planner did not use Gemma"}" }
            check(trace.modelTier == GemmaModelTier.E2B) { "${case.id}: planner did not use E2B" }
            check(trace.backend in setOf(PlannerInferenceBackend.GPU, PlannerInferenceBackend.CPU)) {
                "${case.id}: planner did not use a model backend"
            }
            check(caseValidation.isValid) { "${case.id}: invalid plan ${caseValidation.errors.joinToString()}" }
            check(trace.plan.originalQuery == case.query) { "${case.id}: original language was not preserved" }
            check(trace.plan.intent in setOf(QueryIntent.FIND_MEDIA, QueryIntent.LIST)) {
                "${case.id}: unexpected intent ${trace.plan.intent}"
            }
            check(timeRanges(trace.plan.filter).singleOrNull() == expectedRange) {
                "${case.id}: previous year was not overlaid as an exact calendar range"
            }
            case.requiredTermGroups.forEach { alternatives ->
                check(alternatives.any(searchable::contains)) {
                    "${case.id}: none of $alternatives appeared in searchable plan text"
                }
            }
            PlannerSmokeEvidence(case, trace, searchable)
        }
        val plannerTrace = plannerCases.first().trace
        val validation = GalleryQueryPlanValidator().validate(plannerTrace.plan)

        val runtime = sessions.withEngine(
            modelPath = modelPath,
            multimodal = status.multimodal,
            priority = InferencePriority.INTERACTIVE,
        ) { lease ->
            RuntimeEvidence(
                backend = lease.engine.backend,
                mtpSupported = lease.engine.mtpSupported,
                mtpEnabled = lease.engine.mtpEnabled,
            )
        }
        check(plannerCases.all { it.trace.backend == runtime.backend }) {
            "A planner case and the shared session reported different backends"
        }

        val visual = runVisualSmoke(application, operationId)
        val expectedEvidenceIds = visual.evidence.mapTo(linkedSetOf(), EvidenceRecord::id)
        val baseline = SearchAnswer(
            headline = "Found 1 likely match",
            detail = "One synthetic candidate satisfies the identity-bound visual conditions.",
            evidenceIds = expectedEvidenceIds.toList(),
            exactness = ResultExactness.ESTIMATED_FROM_RETRIEVAL,
            indexedEligibleCount = 1,
            totalEligibleCount = 1,
        )
        val composition = application.services.groundedAnswerComposer.compose(
            GroundedAnswerInput(
                plan = visual.plan,
                hits = listOf(SearchHit(visual.item, 1.0, visual.evidence)),
                deterministicAnswer = baseline,
            ),
        )
        check(composition.trace.usedGemma) {
            composition.trace.fallbackReason ?: "Grounded composer did not use Gemma"
        }
        check(composition.trace.modelTier == GemmaModelTier.E2B) { "Grounded composer did not use E2B" }
        check(composition.answer.evidenceIds.isNotEmpty()) { "Grounded answer omitted evidence" }
        check(composition.answer.evidenceIds.all(expectedEvidenceIds::contains)) { "Grounded answer invented evidence" }
        check(composition.answer.claims.all { claim ->
            claim.evidenceIds.isNotEmpty() && claim.evidenceIds.all(expectedEvidenceIds::contains)
        }) { "A grounded claim lacks the synthetic evidence" }

        val initializationsAfter = sessions.initializationCount
        val initializationDelta = initializationsAfter - initializationsBefore
        check(initializationDelta in 0..1) {
            "Planner and composer initialized Gemma $initializationDelta times"
        }

        return JSONObject()
            .put("state", "COMPLETE")
            .put("operationId", operationId)
            .put("startedAt", startedAt)
            .put("completedAt", System.currentTimeMillis())
            .put("elapsedMs", SystemClock.elapsedRealtime() - startedElapsed)
            .put("activeTier", status.tier.name)
            .put("selectedTier", status.selectedTier.name)
            .put("packVersion", status.packVersion ?: JSONObject.NULL)
            .put("modelSha256", status.sha256 ?: JSONObject.NULL)
            .put("multimodal", status.multimodal)
            .put("backend", runtime.backend.name)
            .put("mtpSupported", runtime.mtpSupported)
            .put("mtpEnabled", runtime.mtpEnabled)
            .put("initializationsBefore", initializationsBefore)
            .put("initializationsAfter", initializationsAfter)
            .put("initializationDelta", initializationDelta)
            .put("planner", JSONObject()
                .put("usedGemma", plannerTrace.usedGemma)
                .put("generationCalls", plannerTrace.generationCalls)
                .put("repaired", plannerTrace.repaired)
                .put("loadMs", plannerTrace.engineLoadMs)
                .put("generationMs", plannerTrace.generationMs)
                .put("intent", plannerTrace.plan.intent.name)
                .put("validationErrors", JSONArray(validation.errors)))
            .put("plannerCases", JSONArray().apply {
                plannerCases.forEach { result ->
                    put(JSONObject()
                        .put("id", result.case.id)
                        .put("usedGemma", result.trace.usedGemma)
                        .put("backend", result.trace.backend.name)
                        .put("generationCalls", result.trace.generationCalls)
                        .put("repaired", result.trace.repaired)
                        .put("loadMs", result.trace.engineLoadMs)
                        .put("generationMs", result.trace.generationMs)
                        .put("intent", result.trace.plan.intent.name)
                        .put("searchableText", result.searchableText.take(MAX_TEXT_LENGTH)))
                }
            })
            .put("verifier", JSONObject()
                .put("usedGemma", true)
                .put("backend", visual.backend.name)
                .put("generationCalls", visual.generationCalls)
                .put("repairedCandidates", visual.repairedCandidates)
                .put("loadMs", visual.loadMs)
                .put("generationMs", visual.generationMs)
                .put("acceptedCount", 1)
                .put("conditionCount", visual.conditionCount)
                .put("evidenceCount", visual.evidence.size)
                .put("clusterIds", JSONArray(visual.clusterIds.sorted()))
                .put("swappedVerdict", visual.swappedVerdict.name)
                .put("swappedAcceptedCount", 0)
                .put("swappedEvidenceCount", 0)
                .put("swappedGenerationCalls", visual.swappedGenerationCalls)
                .put("swappedLoadMs", visual.swappedLoadMs)
                .put("swappedGenerationMs", visual.swappedGenerationMs))
            .put("videoVerifier", JSONObject()
                .put("usedGemma", true)
                .put("backend", visual.videoBackend.name)
                .put("generationCalls", visual.videoGenerationCalls)
                .put("loadMs", visual.videoLoadMs)
                .put("generationMs", visual.videoGenerationMs)
                .put("parentVideoId", visual.videoParentId)
                .put("timestampMs", visual.videoTimestampMs)
                .put("acceptedCount", 1)
                .put("evidenceCount", 1))
            .put("composer", JSONObject()
                .put("usedGemma", composition.trace.usedGemma)
                .put("generationCalls", composition.trace.generationCalls)
                .put("repaired", composition.trace.repaired)
                .put("loadMs", composition.trace.engineLoadMs)
                .put("generationMs", composition.trace.generationMs)
                .put("claimCount", composition.answer.claims.size)
                .put("evidenceIds", JSONArray(composition.answer.evidenceIds)))
    }

    private suspend fun runVisualSmoke(
        application: AskAlbumApplication,
        operationId: String,
    ): VisualSmokeEvidence {
        val databaseName = "debug-real-gemma-vision-$operationId.db"
        val fixture = File(application.cacheDir, "debug-real-gemma-vision-$operationId.jpg")
        val videoFixture = File(application.cacheDir, "debug-real-gemma-video-$operationId.jpg")
        val database = GalleryDatabase(application, databaseName)
        try {
            writeRelationshipFixture(fixture)
            writeVideoKeyframeFixture(videoFixture)
            database.seedDemoIfEmpty()
            database.ensureStageRows()
            database.enablePeopleIndexing(GalleryDatabase.PEOPLE_CONSENT_VERSION)
            database.ensureAutomaticPersonCluster(PERSON_A_CLUSTER)
            database.ensureAutomaticPersonCluster(PERSON_B_CLUSTER)
            val stored = database.allItems().first { it.kind == MediaKind.IMAGE }
            database.completeEmbeddedFaces(
                stored.id,
                listOf(
                    face(.1875f, .2333f, .3625f, .4667f, 0),
                    face(.6375f, .2333f, .8125f, .4667f, 1),
                ),
                listOf(PERSON_A_CLUSTER, PERSON_B_CLUSTER),
                "debug-real-gemma-vision-face-v1",
            )
            database.saveReviewedPersonCluster(PERSON_A_CLUSTER, "Person A", "Me", emptyList())
            database.saveReviewedPersonCluster(PERSON_B_CLUSTER, "Person B", "partner", emptyList())
            val item = stored.copy(
                filename = fixture.name,
                title = "Synthetic people relation fixture",
                creator = "AskAlbum debug acceptance",
                location = "Synthetic studio",
                tags = listOf("person a", "person b", "yellow hat", "blue suit"),
                description = "Locally generated visual-verification fixture",
                license = "CC0-1.0",
                sourceUrl = "local-synthetic-fixture",
                previewPath = fixture.absolutePath,
                mimeType = "image/jpeg",
                width = 1200,
                height = 900,
                sizeBytes = fixture.length(),
            )
            val plan = GalleryQueryPlan(
                originalQuery = "Show the image where Person A has a yellow hat and red clothing, and Person B has a blue suit.",
                intent = QueryIntent.FIND_MEDIA,
                mediaScope = MediaScope.IMAGES,
                semanticClauses = listOf(
                    SemanticClause("Person A is wearing a yellow hat", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON, relationToPerson = PERSON_A_CLUSTER),
                    SemanticClause("Person B is wearing a blue suit", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON, relationToPerson = PERSON_B_CLUSTER),
                    SemanticClause("Person A is wearing red clothing", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON, relationToPerson = PERSON_A_CLUSTER),
                ),
                terms = listOf("yellow hat", "blue suit"),
                verification = VerificationPolicy.REQUIRED,
                limit = 8,
            )
            val verifier = LiteRtGemmaVisualVerifier(
                application,
                application.modelPackManager,
                application.services.gemmaSessions,
                database,
            )
            val verified = verifier.verifyWhenNeeded(plan, listOf(SearchHit(item, 1.0, emptyList())))
            val trace = requireNotNull(verified.trace) { "Visual verification trace is absent" }
            check(trace.usedGemma) { trace.fallbackReason ?: "Visual verifier did not use Gemma" }
            check(trace.modelTier == GemmaModelTier.E2B) { "Visual verifier did not use E2B" }
            check(trace.backend in setOf(VerificationInferenceBackend.GPU, VerificationInferenceBackend.CPU)) {
                "Visual verifier did not use an inference backend"
            }
            check(item.id in verified.acceptedIds) { "Identity-bound visual candidate was not accepted" }
            check(verified.failures.isEmpty()) { "Visual verification reported a candidate failure" }
            val evaluation = verified.evaluations.single()
            check(evaluation.conditions.size == 3 && evaluation.conditions.all {
                it.satisfied && it.verdict == PersonVisualVerdict.VERIFIED_TRUE && it.confidence in 0f..1f
            }) { "The true person conditions were not all verified" }
            check(verified.evidence.size == 3) { "Visual verifier did not emit one record per true condition" }
            val clusterIds = verified.evidence.mapNotNullTo(linkedSetOf(), EvidenceRecord::clusterId)
            check(clusterIds == setOf(PERSON_A_CLUSTER, PERSON_B_CLUSTER)) {
                "Visual evidence lost reviewed-cluster binding"
            }

            val swappedPlan = GalleryQueryPlan(
                originalQuery = "Show Person A with Person B where Person A is wearing a blue suit",
                intent = QueryIntent.FIND_MEDIA,
                peopleClauses = listOf(PersonClause(PERSON_A_CLUSTER), PersonClause(PERSON_B_CLUSTER)),
                semanticClauses = listOf(
                    SemanticClause(
                        text = "P1 is wearing a blue suit",
                        hardness = ConstraintStrength.HARD,
                        subject = SemanticSubject.PERSON,
                        relationToPerson = PERSON_A_CLUSTER,
                    ),
                ),
                terms = listOf("blue suit"),
                verification = VerificationPolicy.REQUIRED,
            )
            val swapped = verifier.verifyWhenNeeded(swappedPlan, listOf(SearchHit(item, 1.0, emptyList())))
            val swappedTrace = requireNotNull(swapped.trace) { "Swapped-person verification trace is absent" }
            check(swappedTrace.usedGemma) { swappedTrace.fallbackReason ?: "Swapped-person verifier did not use Gemma" }
            val swappedCondition = swapped.evaluations.single().conditions.single()
            check(swapped.acceptedIds.isEmpty() && swapped.evidence.isEmpty()) {
                "Person B's blue clothing incorrectly satisfied Person A"
            }
            check(swappedCondition.verdict == PersonVisualVerdict.VERIFIED_FALSE && !swappedCondition.satisfied) {
                "Swapped-person condition did not fail closed"
            }
            check(database.personVisualFactsForMedia(item.id).any { fact ->
                fact.clusterId == PERSON_A_CLUSTER && fact.verdict == PersonVisualVerdict.VERIFIED_FALSE
            }) { "The identity-bound false verdict was not cached" }

            val videoId = "debug-real-gemma-video-$operationId"
            check(database.upsertImported(listOf(
                ImportedMedia(
                    stableId = videoId,
                    uri = "content://media/external/video/media/$operationId",
                    displayName = "synthetic-yellow-bicycle.mp4",
                    mimeType = "video/mp4",
                    source = MediaSource.MEDIA_STORE,
                    capturedAt = 1_700_000_000_000L,
                    modifiedAt = 1_700_000_000_000L,
                    durationMs = 20_000L,
                    width = 1200,
                    height = 900,
                    sizeBytes = 1_024L,
                    album = "Debug acceptance",
                ),
            )) == 1) { "Synthetic parent video was not inserted" }
            val keyframe = VideoKeyframeRecord(
                id = "$videoId:keyframe:9000",
                mediaId = videoId,
                timestampMs = VIDEO_TIMESTAMP_MS,
                previewPath = videoFixture.absolutePath,
                labels = listOf("yellow bicycle", "video keyframe"),
                ocrText = "",
                perceptualHash = 0xA11B1C1L,
                qualityScore = .99f,
                producerVersion = VideoKeyframePolicy.PRODUCER_VERSION,
                embeddingVersion = "siglip-debug-smoke",
                embeddingState = "COMPLETE",
            )
            database.completeIndex(
                id = videoId,
                labels = keyframe.labels,
                description = "Synthetic video with a yellow bicycle at nine seconds",
                ocrText = "",
                faceCount = 0,
                previewPath = null,
                blocks = emptyList(),
                entities = emptyList(),
                ocrAttempted = false,
                ocrProducerVersion = null,
                visualFeatures = VisualFeatures(0L, 0f, 0f, 1f),
                keyframes = listOf(keyframe),
            )
            val video = database.allItems().single { it.id == videoId }
            check(video.kind == MediaKind.VIDEO) { "Synthetic parent media is not a video" }
            val selectorEvidence = EvidenceRecord(
                id = "$videoId:video_keyframe:9000",
                mediaId = videoId,
                sourceField = "video_keyframe",
                text = "A yellow bicycle appears in the matched video keyframe.",
                confidence = .99f,
                producerVersion = "debug-keyframe-selector-v1",
                timestampMs = VIDEO_TIMESTAMP_MS,
                scope = SemanticFactScope.MEDIA,
                scopeId = videoId,
                evidenceMediaId = videoId,
            )
            val videoPlan = GalleryQueryPlan(
                originalQuery = "Show the video where a yellow bicycle appears.",
                intent = QueryIntent.FIND_MEDIA,
                mediaScope = MediaScope.VIDEOS,
                semanticClauses = listOf(
                    SemanticClause(
                        text = "A yellow bicycle is visible",
                        hardness = ConstraintStrength.HARD,
                        subject = SemanticSubject.WHOLE_MEDIA,
                    ),
                ),
                terms = listOf("yellow bicycle"),
                verification = VerificationPolicy.REQUIRED,
            )
            val videoResult = verifier.verifyWhenNeeded(
                videoPlan,
                listOf(SearchHit(video, 1.0, listOf(selectorEvidence))),
            )
            val videoTrace = requireNotNull(videoResult.trace) { "Video verification trace is absent" }
            check(videoTrace.usedGemma) { videoTrace.fallbackReason ?: "Video verifier did not use Gemma" }
            check(videoTrace.modelTier == GemmaModelTier.E2B) { "Video verifier did not use E2B" }
            check(videoId in videoResult.acceptedIds && videoResult.failures.isEmpty()) {
                "Matched video keyframe was not accepted"
            }
            val videoCondition = videoResult.evaluations.single().conditions.single()
            check(videoCondition.satisfied && videoCondition.verdict == PersonVisualVerdict.VERIFIED_TRUE) {
                "Matched video keyframe predicate was not verified"
            }
            val videoEvidence = videoResult.evidence.single()
            check(videoEvidence.mediaId == videoId && videoEvidence.evidenceMediaId == videoId) {
                "Video verification did not return the parent video"
            }
            check(videoEvidence.timestampMs == VIDEO_TIMESTAMP_MS) {
                "Video verification lost the matched keyframe timestamp"
            }

            return VisualSmokeEvidence(
                item = item,
                plan = plan,
                evidence = verified.evidence,
                backend = trace.backend,
                generationCalls = trace.generationCalls,
                repairedCandidates = trace.repairedCandidates,
                loadMs = trace.engineLoadMs,
                generationMs = trace.generationMs,
                conditionCount = evaluation.conditions.size,
                clusterIds = clusterIds,
                swappedVerdict = swappedCondition.verdict,
                swappedGenerationCalls = swappedTrace.generationCalls,
                swappedLoadMs = swappedTrace.engineLoadMs,
                swappedGenerationMs = swappedTrace.generationMs,
                videoParentId = videoId,
                videoTimestampMs = requireNotNull(videoEvidence.timestampMs),
                videoBackend = videoTrace.backend,
                videoGenerationCalls = videoTrace.generationCalls,
                videoLoadMs = videoTrace.engineLoadMs,
                videoGenerationMs = videoTrace.generationMs,
            )
        } finally {
            database.close()
            application.deleteDatabase(databaseName)
            File(application.cacheDir, "$databaseName.lck").delete()
            fixture.delete()
            videoFixture.delete()
        }
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

    private fun searchableText(plan: GalleryQueryPlan): String = buildList {
        addAll(plan.terms)
        plan.place?.let(::add)
        plan.semanticClauses.forEach { clause ->
            add(clause.text)
            clause.canonicalText?.let(::add)
        }
        plan.peopleClauses.forEach { add(it.personId) }
        collectAlbums(plan.filter, this)
    }.joinToString(" ").lowercase(Locale.ROOT)

    private fun collectAlbums(filter: FilterExpression, output: MutableList<String>) {
        when (filter) {
            is FilterExpression.AlbumIs -> output += filter.album
            is FilterExpression.And -> filter.clauses.forEach { collectAlbums(it, output) }
            else -> Unit
        }
    }

    private fun timeRanges(filter: FilterExpression): List<FilterExpression.TimeRange> = when (filter) {
        is FilterExpression.TimeRange -> listOf(filter)
        is FilterExpression.And -> filter.clauses.flatMap(::timeRanges)
        else -> emptyList()
    }

    private fun calendarYear(year: Int): FilterExpression.TimeRange {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return FilterExpression.TimeRange(start, end)
    }

    private fun writeRelationshipFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 248, 242))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.rgb(28, 34, 48)
            textSize = 54f
        }
        canvas.drawText("SYNTHETIC PEOPLE RELATION FIXTURE", 600f, 75f, paint)
        drawPerson(canvas, paint, 330f, "PERSON A", Color.rgb(210, 47, 47), yellowHat = true, blueSuit = false)
        drawPerson(canvas, paint, 870f, "PERSON B", Color.rgb(25, 90, 190), yellowHat = false, blueSuit = true)
        paint.color = Color.rgb(28, 34, 48)
        paint.textSize = 40f
        canvas.drawText("ONLY PERSON A WEARS A YELLOW HAT", 600f, 830f, paint)
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
        yellowHat: Boolean,
        blueSuit: Boolean,
    ) {
        paint.color = Color.rgb(28, 34, 48)
        paint.textSize = 48f
        canvas.drawText(label, centerX, 155f, paint)
        paint.color = Color.rgb(225, 184, 150)
        canvas.drawCircle(centerX, 315f, 105f, paint)
        if (yellowHat) {
            paint.color = Color.rgb(255, 214, 10)
            canvas.drawRect(centerX - 110f, 205f, centerX + 110f, 270f, paint)
            canvas.drawOval(centerX - 155f, 252f, centerX + 155f, 292f, paint)
        } else {
            paint.color = Color.rgb(45, 37, 32)
            paint.strokeWidth = 14f
            canvas.drawLine(centerX - 55f, 235f, centerX + 55f, 235f, paint)
        }
        paint.color = bodyColor
        canvas.drawRoundRect(centerX - 165f, 420f, centerX + 165f, 720f, 28f, 28f, paint)
        if (blueSuit) {
            paint.color = Color.WHITE
            canvas.drawRect(centerX - 45f, 420f, centerX + 45f, 590f, paint)
            paint.color = Color.rgb(20, 25, 38)
            val tie = android.graphics.Path().apply {
                moveTo(centerX, 455f)
                lineTo(centerX - 25f, 520f)
                lineTo(centerX, 610f)
                lineTo(centerX + 25f, 520f)
                close()
            }
            canvas.drawPath(tie, paint)
        }
        paint.color = if (yellowHat) Color.rgb(160, 115, 0) else Color.rgb(10, 55, 140)
        paint.textSize = 38f
        canvas.drawText(if (yellowHat) "YELLOW HAT" else "BLUE SUIT", centerX, 770f, paint)
    }

    private fun writeVideoKeyframeFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(247, 249, 244))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.rgb(25, 32, 42)
            textSize = 56f
        }
        canvas.drawText("MATCHED VIDEO KEYFRAME AT 00:09", 600f, 90f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 28f
        paint.color = Color.rgb(35, 42, 52)
        canvas.drawCircle(360f, 610f, 145f, paint)
        canvas.drawCircle(850f, 610f, 145f, paint)
        paint.strokeWidth = 42f
        paint.color = Color.rgb(244, 195, 0)
        canvas.drawLine(360f, 610f, 560f, 390f, paint)
        canvas.drawLine(560f, 390f, 680f, 610f, paint)
        canvas.drawLine(680f, 610f, 360f, 610f, paint)
        canvas.drawLine(560f, 390f, 790f, 390f, paint)
        canvas.drawLine(790f, 390f, 850f, 610f, paint)
        canvas.drawLine(790f, 390f, 830f, 310f, paint)
        canvas.drawLine(810f, 310f, 900f, 310f, paint)
        canvas.drawLine(540f, 365f, 500f, 300f, paint)
        canvas.drawLine(455f, 300f, 545f, 300f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(25, 32, 42)
        paint.textSize = 62f
        canvas.drawText("YELLOW BICYCLE", 600f, 840f, paint)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output))
            output.fd.sync()
        }
        bitmap.recycle()
    }

    private suspend fun writeReport(file: File, report: JSONObject) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(report.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (file.exists()) check(file.delete()) { "Could not replace the prior smoke report" }
        check(temporary.renameTo(file)) { "Could not commit the smoke report" }
    }

    private data class RuntimeEvidence(
        val backend: PlannerInferenceBackend,
        val mtpSupported: Boolean,
        val mtpEnabled: Boolean,
    )

    private data class PlannerCase(
        val id: String,
        val query: String,
        val requiredTermGroups: List<Set<String>>,
    )

    private data class PlannerSmokeEvidence(
        val case: PlannerCase,
        val trace: PlannerExecutionTrace,
        val searchableText: String,
    )

    private data class VisualSmokeEvidence(
        val item: GalleryItem,
        val plan: GalleryQueryPlan,
        val evidence: List<EvidenceRecord>,
        val backend: VerificationInferenceBackend,
        val generationCalls: Int,
        val repairedCandidates: Int,
        val loadMs: Long,
        val generationMs: Long,
        val conditionCount: Int,
        val clusterIds: Set<String>,
        val swappedVerdict: PersonVisualVerdict,
        val swappedGenerationCalls: Int,
        val swappedLoadMs: Long,
        val swappedGenerationMs: Long,
        val videoParentId: String,
        val videoTimestampMs: Long,
        val videoBackend: VerificationInferenceBackend,
        val videoGenerationCalls: Int,
        val videoLoadMs: Long,
        val videoGenerationMs: Long,
    )

    private fun String?.safeReportText(): String = this
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.take(MAX_TEXT_LENGTH)
        ?: "Unknown real-model smoke failure"

    private companion object {
        const val ACTION_SMOKE_REAL_GEMMA = "io.github.anup42.askalbum.test.SMOKE_REAL_GEMMA"
        const val EXTRA_OPERATION_ID = "operation_id"
        const val SMOKE_TIMEOUT_MS = 12 * 60_000L
        const val MAX_TEXT_LENGTH = 500
        const val PERSON_A_CLUSTER = "person_a_debug_real_gemma"
        const val PERSON_B_CLUSTER = "person_b_debug_real_gemma"
        const val VIDEO_TIMESTAMP_MS = 9_000L
        val PLANNER_CASES = listOf(
            PlannerCase(
                id = "english",
                query = "Show family photos from last year's Goa trip.",
                requiredTermGroups = listOf(setOf("goa"), setOf("family")),
            ),
            PlannerCase(
                id = "hindi",
                query = "\u092A\u093F\u091B\u0932\u0947 \u0938\u093E\u0932 \u0915\u0940 \u0917\u094B\u0935\u093E \u092B\u0948\u092E\u093F\u0932\u0940 \u092B\u094B\u091F\u094B \u0926\u093F\u0916\u093E\u0913\u0964",
                requiredTermGroups = listOf(
                    setOf("goa", "\u0917\u094B\u0935\u093E"),
                    setOf("family", "\u092B\u0948\u092E\u093F\u0932\u0940", "\u092A\u0930\u093F\u0935\u093E\u0930"),
                ),
            ),
            PlannerCase(
                id = "hinglish",
                query = "Pichle saal Goa wali family photos dikhao.",
                requiredTermGroups = listOf(setOf("goa"), setOf("family")),
            ),
        )
        val OPERATION_ID = Regex("[a-fA-F0-9]{32}")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
