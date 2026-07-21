package com.askphotos.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealFaceDetectionAcceptanceTest {
    @Test
    fun bundledDetectorRunsOfflineAndReturnsOnlyBoundedFaceRecords() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val jpeg = instrumentation.targetContext.assets.open("images/lake-turquoise.jpg").use { it.readBytes() }
        val started = SystemClock.elapsedRealtime()
        val detections = MlKitFaceDetectionEngine().use { engine ->
            withTimeout(60_000L) { engine.detect(jpeg) }
        }
        val elapsed = SystemClock.elapsedRealtime() - started

        assertTrue(detections.size <= MlKitFaceDetectionEngine.MAX_FACES_PER_MEDIA)
        assertTrue(detections.all {
            it.left in 0f..1f && it.top in 0f..1f && it.right in 0f..1f && it.bottom in 0f..1f &&
                it.left < it.right && it.top < it.bottom && it.quality in 0f..1f
        })
        instrumentation.sendStatus(2, Bundle().apply {
            putString("face_detection_trace", "REAL_FACE_DETECTION elapsedMs=$elapsed detections=${detections.size} bytes=${jpeg.size}")
        })
    }
}
