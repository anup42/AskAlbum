package io.github.anup42.askalbum

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class TestSeedContentProviderTest {
    @Test
    fun base64ChunkRoundTripRequiresExactHashes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = Uri.parse("content://${context.packageName}.testseed")
        val runId = "provider_roundtrip_01"
        val payload = ByteArray(12 * 1024) { index -> (index * 31).toByte() }
        val sha256 = payload.sha256()
        try {
            val initialized = resolver.call(uri, "init", runId, Bundle().apply {
                putString("total_bytes", payload.size.toString())
                putString("chunk_size", payload.size.toString())
                putString("chunk_count", "1")
                putString("sha256", sha256)
            })
            assertEquals("READY", initialized?.getString("state"))
            assertEquals("0", initialized?.getString("present_bitmap"))
            val written = resolver.call(uri, "write_chunk", runId, Bundle().apply {
                putString("index", "0")
                putString("expected_length", payload.size.toString())
                putString("sha256", sha256)
                putString("data", Base64.encodeToString(payload, Base64.URL_SAFE or Base64.NO_WRAP))
            })
            assertEquals("WRITTEN", written?.getString("state"))
            assertEquals(payload.size.toLong(), written?.getLong("size"))
            val finalized = resolver.call(uri, "finalize", runId, null)
            assertEquals("COMPLETE", finalized?.getString("state"))
            assertEquals(payload.size.toLong(), finalized?.getLong("size"))
            assertEquals(sha256, finalized?.getString("sha256"))
        } finally {
            resolver.call(uri, "abort", runId, null)
        }
    }

    @Test
    fun externalFileRoundTripRequiresExactHashesAndDeletesSource() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = Uri.parse("content://${context.packageName}.testseed")
        val runId = "provider_external_01"
        val payload = ByteArray(64 * 1024) { index -> (index * 19).toByte() }
        val sha256 = payload.sha256()
        try {
            val prepared = resolver.call(uri, "prepare_external", runId, null)
            assertEquals("READY", prepared?.getString("state"))
            val external = File(requireNotNull(prepared?.getString("path")))
            external.writeBytes(payload)
            val adopted = resolver.call(uri, "adopt_external", runId, Bundle().apply {
                putString("total_bytes", payload.size.toString())
                putString("sha256", sha256)
            })
            assertEquals("COMPLETE", adopted?.getString("state"))
            assertEquals(payload.size.toLong(), adopted?.getLong("size"))
            assertEquals(sha256, adopted?.getString("sha256"))
            assertEquals(false, external.exists())
            val finalized = resolver.call(uri, "finalize", runId, null)
            assertEquals("COMPLETE", finalized?.getString("state"))
        } finally {
            resolver.call(uri, "abort", runId, null)
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it) }
}
