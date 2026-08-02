package io.github.anup42.askalbum

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class GemmaPackSecurityTest {
    @Test
    fun foreignSignedPackIsRejectedWithoutChangingActiveGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = ModelPackManager(context)
        val before = manager.status()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val manifest = manifest(fingerprint(keys.public.encoded)).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(manifest)
            sign()
        }
        val archive = File(context.cacheDir, "foreign-signed.agemma")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.entry("manifest.json", manifest)
            zip.entry("manifest.sig", Base64.getEncoder().encode(signature))
            zip.entry("gemma.litertlm", byteArrayOf(1))
            zip.entry("LICENSE.txt", "license".toByteArray())
        }

        try {
            val error = assertThrows(IllegalArgumentException::class.java) { manager.installVerified(archive) }
            assertTrue(error.message.orEmpty().contains("signing key"))
            val after = manager.status()
            assertEquals(before.path, after.path)
            assertEquals(before.packVersion, after.packVersion)
            assertNotNull(after.deviceAssessment)
            assertTrue(after.deviceAssessment!!.totalRamBytes > 0)
        } finally {
            archive.delete()
        }
    }

    private fun manifest(key: String) = """{"schemaVersion":1,"packId":"gemma-4-e2b","packVersion":"security-test","model":{"family":"gemma-4","tier":"E2B","multimodal":true,"maxContextTokens":4096},"source":{"revision":"${"1a".repeat(20)}","license":"gemma-terms"},"runtime":{"name":"LiteRT-LM","version":"0.14.0"},"device":{"minimumRamBytes":4294967296},"signing":{"algorithm":"SHA256withRSA","keySha256":"$key"},"files":[{"role":"model","name":"gemma.litertlm","sizeBytes":52428800,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":7,"sha256":"${"33".repeat(32)}"}]}"""

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ZipOutputStream.entry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
