package io.github.anup42.askalbum

import android.content.ComponentName
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GemmaDownloadHarnessTest {
    @Test
    fun reportsPinnedE2bStateWithoutStartingNetworkWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val operationId = UUID.randomUUID().toString().replace("-", "")
        val status = File(context.filesDir, "test-models/gemma-e2b-status.json")
        status.delete()
        context.sendBroadcast(
            Intent("io.github.anup42.askalbum.test.REPORT_GEMMA")
                .setComponent(ComponentName(context, TestGemmaModelReceiver::class.java))
                .putExtra("tier", GemmaModelTier.E2B.name)
                .putExtra("operation_id", operationId),
        )
        val deadline = System.currentTimeMillis() + 10_000
        while (
            (!status.isFile || runCatching { JSONObject(status.readText()).optString("operationId") }.getOrNull() != operationId) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(50)
        }
        val payload = JSONObject(status.readText())
        assertEquals("COMPLETE", payload.getString("state"))
        assertEquals(operationId, payload.getString("operationId"))
        assertEquals(GemmaModelTier.E2B.name, payload.getString("tier"))
        assertEquals(GemmaModelCatalog.e2b.sha256, payload.getString("sha256"))
        assertEquals(GemmaModelCatalog.e2b.sizeBytes, payload.getLong("totalBytes"))
        assertTrue(payload.getString("downloadState") in GemmaDownloadState.entries.map { it.name })
    }
}
