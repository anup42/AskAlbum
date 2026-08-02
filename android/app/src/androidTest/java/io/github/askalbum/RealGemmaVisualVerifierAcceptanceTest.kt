package io.github.anup42.askalbum

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealGemmaVisualVerifierAcceptanceTest {
    @Test
    fun installedE2bVerifiesOneSyntheticRelationshipImageWithEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
        val status = application.modelPackManager.status()
        assumeTrue("A verified multimodal E2B pack is required", status.installed && status.multimodal && status.tier == GemmaModelTier.E2B)
        val fixture = File(application.cacheDir, "gemma-visual-acceptance-${System.nanoTime()}.jpg")
        try {
            writeRelationshipFixture(fixture)
            val item = GalleryItem(
                id = "synthetic_people_relation_runtime",
                filename = fixture.name,
                title = "Synthetic people relation fixture",
                creator = "AskAlbum acceptance suite",
                location = "Synthetic studio",
                latitude = null,
                longitude = null,
                tags = listOf("person a", "person b", "yellow hat", "blue suit"),
                description = "Locally generated visual-verification fixture",
                license = "CC0-1.0",
                sourceUrl = "local-synthetic-fixture",
                assetPath = null,
                previewPath = fixture.absolutePath,
                source = MediaSource.PHOTO_PICKER,
                kind = MediaKind.IMAGE,
                mimeType = "image/jpeg",
                width = 1200,
                height = 900,
                sizeBytes = fixture.length(),
            )
            val plan = GalleryQueryPlan(
                originalQuery = "Show the image where Person A has a yellow hat and Person B has a blue suit, and nobody else wears a yellow hat.",
                intent = QueryIntent.FIND_MEDIA,
                mediaScope = MediaScope.IMAGES,
                semanticClauses = listOf(
                    SemanticClause("Person A is wearing a yellow hat", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON, relationToPerson = "person_a"),
                    SemanticClause("Person B is wearing a blue suit", hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON, relationToPerson = "person_b"),
                    SemanticClause("No visible person other than Person A is wearing a yellow hat", polarity = Polarity.NEGATIVE, hardness = ConstraintStrength.HARD, subject = SemanticSubject.PERSON),
                ),
                terms = listOf("yellow hat", "blue suit"),
                verification = VerificationPolicy.REQUIRED,
                limit = 8,
            )
            val hit = SearchHit(item, 1.0, emptyList())
            val pssBeforeKb = Debug.getPss()
            val started = SystemClock.elapsedRealtime()
            val result = withTimeout(6 * 60_000L) {
                application.services.visualVerifier.verifyWhenNeeded(plan, listOf(hit))
            }
            val wallMs = SystemClock.elapsedRealtime() - started
            val pssAfterCloseKb = Debug.getPss()
            val trace = requireNotNull(result.trace)
            val report = "REAL_GEMMA_VISUAL used=${trace.usedGemma} backend=${trace.backend} calls=${trace.generationCalls} " +
                "repaired=${trace.repairedCandidates} loadMs=${trace.engineLoadMs} generationMs=${trace.generationMs} " +
                "closeMs=${trace.engineCloseMs} elapsedMs=${trace.elapsedMs} wallMs=$wallMs " +
                "pssBeforeKb=$pssBeforeKb pssAfterCloseKb=$pssAfterCloseKb accepted=${result.acceptedIds.size} " +
                "evidence=${result.evidence.size} failures=${result.failures.size}"
            instrumentation.sendStatus(2, Bundle().apply { putString("real_gemma_visual_trace", report) })

            assertTrue("Gemma visual verification fell back: ${trace.fallbackReason}", trace.usedGemma)
            assertTrue(trace.backend in setOf(VerificationInferenceBackend.GPU, VerificationInferenceBackend.CPU))
            assertEquals(GemmaModelTier.E2B, trace.modelTier)
            assertEquals(1, trace.verifiedCandidates)
            assertTrue(trace.generationCalls in 1..2)
            assertTrue("Candidate was not proven: ${result.failures}", item.id in result.acceptedIds)
            assertEquals(3, result.evaluations.single().conditions.size)
            assertTrue(result.evaluations.single().conditions.all { it.satisfied && it.confidence in 0f..1f })
            assertEquals(setOf("c1", "c2", "c3"), result.evaluations.single().conditions.mapTo(mutableSetOf()) { it.id })
            assertEquals(3, result.evidence.size)
            assertTrue(result.evidence.all { it.mediaId == item.id && it.sourceField == "visual_verification" && it.producerVersion.contains("gemma-4-e2b") })
            assertTrue(result.evidence.map { it.id }.all { evidenceId -> result.evidence.any { it.id == evidenceId } })
            assertTrue(result.failures.isEmpty())
            assertTrue(trace.engineLoadMs > 0)
            assertTrue(trace.generationMs > 0)
        } finally {
            fixture.delete()
        }
    }

    private fun writeRelationshipFixture(file: File) {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(250, 248, 242))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.rgb(28, 34, 48)
        paint.textSize = 54f
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
}
