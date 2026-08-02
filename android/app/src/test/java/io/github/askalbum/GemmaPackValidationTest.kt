package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class GemmaPackValidationTest {
    @Test
    fun parsesPinnedE2bManifestAndRejectsUnknownFields() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = manifest(fingerprint(keys.public.encoded))
        val parsed = GemmaPackManifest.parse(bytes)

        assertEquals(GemmaModelTier.E2B, parsed.tier)
        assertEquals("0.14.0", parsed.runtimeVersion)
        assertEquals(4096, parsed.maxContextTokens)

        val changed = bytes.toString(Charsets.UTF_8).replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"unexpected\":true")
        assertThrows(IllegalArgumentException::class.java) { GemmaPackManifest.parse(changed.toByteArray()) }
    }

    @Test
    fun signatureVerifierRejectsManifestMutation() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = manifest(fingerprint(keys.public.encoded))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(bytes)
            sign()
        }
        GemmaPackSignatureVerifier(keys.public).verify(bytes, signature, GemmaPackManifest.parse(bytes))

        val changed = bytes.toString(Charsets.UTF_8).replace("pack-v1", "pack-v2").toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            GemmaPackSignatureVerifier(keys.public).verify(changed, signature, GemmaPackManifest.parse(changed))
        }
    }

    @Test
    fun e4bCannotUnderstateRamRequirement() {
        val bytes = manifest("00".repeat(32), tier = "E4B", minimumRam = 4L * 1024 * 1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { GemmaPackManifest.parse(bytes) }
    }

    private fun manifest(key: String, tier: String = "E2B", minimumRam: Long = 4L * 1024 * 1024 * 1024) = """
        {"schemaVersion":1,"packId":"gemma-4-${tier.lowercase()}","packVersion":"pack-v1","model":{"family":"gemma-4","tier":"$tier","multimodal":true,"maxContextTokens":4096},"source":{"revision":"${"1a".repeat(20)}","license":"gemma-terms"},"runtime":{"name":"LiteRT-LM","version":"0.14.0"},"device":{"minimumRamBytes":$minimumRam},"signing":{"algorithm":"SHA256withRSA","keySha256":"$key"},"files":[{"role":"model","name":"gemma.litertlm","sizeBytes":52428800,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":10,"sha256":"${"33".repeat(32)}"}]}
    """.trimIndent().toByteArray()

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
