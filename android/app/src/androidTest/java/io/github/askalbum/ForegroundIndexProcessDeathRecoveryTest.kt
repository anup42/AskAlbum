package io.github.anup42.askalbum

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ForegroundIndexProcessDeathRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext.applicationContext
    private val workManager = WorkManager.getInstance(context)

    @After
    fun stopTestIndexing() {
        context.stopService(Intent(context, InitialImportService::class.java))
        IndexScheduler.cancelAndWait(context)
        EmbeddingIndexScheduler.cancelAndWait(context)
        CaptionEmbeddingScheduler.cancelAndWait(context)
        PeopleIndexScheduler.cancelAndWait(context)
        SemanticEnrichmentScheduler.cancelAndWait(context)
    }

    @Test
    fun forcedDozeKeepsRecoveryWorkAndUnforceLeavesItAvailable() {
        InitialImportService.startIndexing(context)
        val before = waitForRecoveryWork()
        assertTrue(before.any { it.state != androidx.work.WorkInfo.State.CANCELLED })

        val forceResult = shell("cmd deviceidle force-idle")
        assertTrue("Device did not enter forced idle: $forceResult", forceResult.contains("idle", ignoreCase = true))
        try {
            SystemClock.sleep(3_000L)
            val duringIdle = workManager.getWorkInfosForUniqueWork("gallery-index").get()
            assertTrue("Doze cancelled all recovery work", duringIdle.any { it.state != androidx.work.WorkInfo.State.CANCELLED })
        } finally {
            shell("cmd deviceidle unforce")
        }

        val afterUnforce = waitForRecoveryWork()
        assertTrue("Recovery work was not retained after leaving Doze", afterUnforce.any { it.state != androidx.work.WorkInfo.State.CANCELLED })
    }

    private fun waitForRecoveryWork(): List<androidx.work.WorkInfo> {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var work = emptyList<androidx.work.WorkInfo>()
        while ((work.isEmpty() || work.all { it.state == androidx.work.WorkInfo.State.CANCELLED }) &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            work = workManager.getWorkInfosForUniqueWork("gallery-index").get()
            if (work.isEmpty() || work.all { it.state == androidx.work.WorkInfo.State.CANCELLED }) {
                SystemClock.sleep(100L)
            }
        }
        assertFalse("No non-cancelled gallery recovery request appeared", work.isEmpty() || work.all { it.state == androidx.work.WorkInfo.State.CANCELLED })
        return work
    }

    private fun shell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }.trim()
    }

}
