package io.github.anup42.askalbum

import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Suite

@RunWith(Suite::class)
@Suite.SuiteClasses(
    RealGemmaPlannerAcceptanceTest::class,
    RealGemmaVisualVerifierAcceptanceTest::class,
    RealGemmaGroundedAnswerAcceptanceTest::class,
)
class RealGemmaSharedSessionAcceptanceSuite {
    companion object {
        private var initializationsBefore = 0
        private var startedAtMs = 0L

        @JvmStatic
        @BeforeClass
        fun prepareOneCleanSharedSession() = runBlocking {
            val application = InstrumentationRegistry.getInstrumentation()
                .targetContext.applicationContext as AskAlbumApplication
            val status = application.modelPackManager.status()
            assumeTrue(
                "A verified E2B pack is required: $status",
                status.installed && status.tier == GemmaModelTier.E2B && status.multimodal,
            )
            application.services.gemmaSessions.evictNow()
            initializationsBefore = application.services.gemmaSessions.initializationCount
            startedAtMs = SystemClock.elapsedRealtime()
        }

        @JvmStatic
        @AfterClass
        fun assertOneEngineServedEveryRole() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val application = instrumentation.targetContext.applicationContext as AskAlbumApplication
            val initializationsAfter = application.services.gemmaSessions.initializationCount
            val initializationDelta = initializationsAfter - initializationsBefore
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString(
                        "real_gemma_shared_session_trace",
                        "REAL_GEMMA_SHARED_SESSION initializationsBefore=$initializationsBefore " +
                            "initializationsAfter=$initializationsAfter delta=$initializationDelta " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
                    )
                },
            )
            assertEquals(
                "Planner, verifier, and composer did not reuse one Gemma engine",
                1,
                initializationDelta,
            )
        }
    }
}
