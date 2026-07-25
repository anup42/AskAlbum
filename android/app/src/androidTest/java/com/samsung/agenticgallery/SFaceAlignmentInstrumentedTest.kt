package com.samsung.agenticgallery

import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SFaceAlignmentInstrumentedTest {
    @Test
    fun similarityTransformMapsAllFiveCanonicalPoints() {
        val source = listOf(
            PointF(48.2946f, 71.6963f),
            PointF(83.5318f, 71.5014f),
            PointF(66.0252f, 91.7366f),
            PointF(51.5493f, 112.3655f),
            PointF(80.7299f, 112.2041f),
        )
        val target = listOf(
            PointF(38.2946f, 51.6963f),
            PointF(73.5318f, 51.5014f),
            PointF(56.0252f, 71.7366f),
            PointF(41.5493f, 92.3655f),
            PointF(70.7299f, 92.2041f),
        )
        val mapped = source.flatMap { listOf(it.x, it.y) }.toFloatArray()

        SFaceImagePreprocessor.similarityTransform(source, target).mapPoints(mapped)

        target.forEachIndexed { index, point ->
            assertEquals(point.x, mapped[index * 2], .001f)
            assertEquals(point.y, mapped[index * 2 + 1], .001f)
        }
    }
}
