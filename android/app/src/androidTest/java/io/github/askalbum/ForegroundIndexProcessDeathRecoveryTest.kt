package io.github.anup42.askalbum

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
        workManager.cancelUniqueWork("gallery-index").result.get(10, TimeUnit.SECONDS)
        workManager.cancelUniqueWork("gallery-image-embeddings").result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun killingForegroundProcessLeavesARecoverableIndexRequest() {
        InitialImportService.startIndexing(context)
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        var beforePid: String? = null
        while (beforePid.isNullOrBlank() && SystemClock.elapsedRealtime() < deadline) {
            beforePid = targetPid()
            if (beforePid.isNullOrBlank()) SystemClock.sleep(100L)
        }
        assumeTrue("Foreground service process did not become observable", !beforePid.isNullOrBlank())

        val workBeforeKill = workManager.getWorkInfosForUniqueWork("gallery-index").get()
        assertTrue("Foreground start did not leave recovery work", workBeforeKill.isNotEmpty())
        assertFalse("Recovery request was cancelled before interruption", workBeforeKill.all { it.state.isFinished && it.state.name == "CANCELLED" })

        shell("kill -9 $beforePid")
        SystemClock.sleep(1_000L)

        val recoveryDeadline = SystemClock.elapsedRealtime() + 15_000L
        var workAfterKill = emptyList<androidx.work.WorkInfo>()
        while (SystemClock.elapsedRealtime() < recoveryDeadline) {
            workAfterKill = workManager.getWorkInfosForUniqueWork("gallery-index").get()
            if (workAfterKill.isNotEmpty() && workAfterKill.none { it.state == androidx.work.WorkInfo.State.CANCELLED }) break
            SystemClock.sleep(250L)
        }
        assertNotNull("No durable recovery request survived process death", workAfterKill.firstOrNull())
        assertTrue("Process death left only cancelled recovery work", workAfterKill.any { it.state != androidx.work.WorkInfo.State.CANCELLED })
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

    private fun targetPid(): String? = shell("pidof ${context.packageName}").
        lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

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
