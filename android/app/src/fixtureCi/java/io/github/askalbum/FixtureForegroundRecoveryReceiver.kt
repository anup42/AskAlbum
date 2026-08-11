package io.github.anup42.askalbum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixture-only host bridge for recovery checks that must kill the target app process. */
class FixtureForegroundRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        check(BuildConfig.MODEL_INDEPENDENT && BuildConfig.DISTRIBUTION == "fixtureCi")
        val pending = goAsync()
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                when (intent.action) {
                    ACTION_ARM -> arm(context.applicationContext)
                    ACTION_VERIFY -> verify(context.applicationContext)
                    else -> error("Unsupported fixture recovery action")
                }
            } catch (error: Throwable) {
                writeResult(context, "FAILED|${safeMessage(error)}")
            } finally {
                pending.finish()
                executor.shutdown()
            }
        }
    }

    private fun arm(context: Context) {
        resultFile(context).delete()
        InitialImportService.startIndexing(context)
        val workManager = WorkManager.getInstance(context)
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var active = emptyList<WorkInfo>()
        while (active.isEmpty() && System.currentTimeMillis() < deadline) {
            active = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK).get(10, TimeUnit.SECONDS)
                .filterNot { it.state.isFinished }
            if (active.isEmpty()) Thread.sleep(100L)
        }
        require(active.isNotEmpty()) { "No active recovery request was scheduled" }
        val ids = active.map { it.id.toString() }.toSet()
        check(
            preferences(context).edit()
                .putInt(KEY_ARMED_PID, Process.myPid())
                .putStringSet(KEY_WORK_IDS, ids)
                .putLong(KEY_ARMED_AT, System.currentTimeMillis())
                .commit(),
        ) { "Could not persist the recovery checkpoint" }
        writeResult(context, "ARMED|${Process.myPid()}|${ids.sorted().joinToString(",")}")
    }

    private fun verify(context: Context) {
        resultFile(context).delete()
        val stored = preferences(context)
        val armedPid = stored.getInt(KEY_ARMED_PID, -1)
        val workIds = stored.getStringSet(KEY_WORK_IDS, emptySet()).orEmpty()
        require(armedPid > 0 && workIds.isNotEmpty()) { "No armed recovery checkpoint exists" }
        require(Process.myPid() != armedPid) { "Verification did not start in a fresh process" }

        val workManager = WorkManager.getInstance(context)
        val recovered = workIds.mapNotNull { encodedId ->
            runCatching {
                workManager.getWorkInfoById(UUID.fromString(encodedId)).get(10, TimeUnit.SECONDS)
            }.getOrNull()
        }
        require(recovered.any { it.state != WorkInfo.State.CANCELLED }) {
            "The original recovery requests were lost or cancelled"
        }
        writeResult(
            context,
            "RECOVERED|$armedPid|${Process.myPid()}|" +
                recovered.joinToString(",") { "${it.id}:${it.state.name}" },
        )
        stored.edit().clear().commit()
    }

    private fun preferences(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun resultFile(context: Context) = File(context.filesDir, RESULT_FILE)

    private fun writeResult(context: Context, result: String) {
        resultFile(context).writeText(result)
    }

    private fun safeMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).replace(Regex("[^A-Za-z0-9_.: -]"), "_").take(220)

    companion object {
        const val ACTION_ARM = "io.github.anup42.askalbum.fixture.ARM_FOREGROUND_RECOVERY"
        const val ACTION_VERIFY = "io.github.anup42.askalbum.fixture.VERIFY_FOREGROUND_RECOVERY"
        const val RESULT_FILE = "foreground-index-recovery-result.txt"
        private const val PREFERENCES = "fixture-foreground-index-recovery"
        private const val KEY_ARMED_PID = "armed_pid"
        private const val KEY_WORK_IDS = "work_ids"
        private const val KEY_ARMED_AT = "armed_at"
        private const val UNIQUE_WORK = "gallery-index"
        private const val WAIT_MILLIS = 10_000L
    }
}
