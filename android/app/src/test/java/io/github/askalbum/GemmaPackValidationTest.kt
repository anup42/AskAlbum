package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

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

    @Test
    fun installedGenerationRejectsArtifactAndSignatureTampering() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val directory = Files.createTempDirectory("gemma-generation-test").toFile()
        try {
            val model = File(directory, "gemma.litertlm")
            RandomAccessFile(model, "rw").use { it.setLength(50L * 1024 * 1024) }
            val license = File(directory, "LICENSE.txt").apply { writeText("license") }
            val manifestBytes = installedManifest(
                fingerprint(keys.public.encoded),
                sha256(model),
                sha256(license),
            )
            val signature = Signature.getInstance("SHA256withRSA").run {
                initSign(keys.private)
                update(manifestBytes)
                sign()
            }
            File(directory, "manifest.json").writeBytes(manifestBytes)
            File(directory, "manifest.sig").writeText(Base64.getEncoder().encodeToString(signature))
            val verifier = GemmaInstalledGenerationVerifier(GemmaPackSignatureVerifier(keys.public))

            assertEquals("pack-v1", verifier.verify(directory).packVersion)
            RandomAccessFile(model, "rw").use { file ->
                file.seek(0)
                file.write(1)
            }
            assertThrows(IllegalArgumentException::class.java) { verifier.verify(directory) }

            RandomAccessFile(model, "rw").use { file ->
                file.seek(0)
                file.write(0)
            }
            val changedSignature = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            File(directory, "manifest.sig").writeText(Base64.getEncoder().encodeToString(changedSignature))
            assertThrows(IllegalArgumentException::class.java) { verifier.verify(directory) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun manifest(key: String, tier: String = "E2B", minimumRam: Long = 4L * 1024 * 1024 * 1024) = """
        {"schemaVersion":1,"packId":"gemma-4-${tier.lowercase()}","packVersion":"pack-v1","model":{"family":"gemma-4","tier":"$tier","multimodal":true,"maxContextTokens":4096},"source":{"revision":"${"1a".repeat(20)}","license":"gemma-terms"},"runtime":{"name":"LiteRT-LM","version":"0.14.0"},"device":{"minimumRamBytes":$minimumRam},"signing":{"algorithm":"SHA256withRSA","keySha256":"$key"},"files":[{"role":"model","name":"gemma.litertlm","sizeBytes":52428800,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":10,"sha256":"${"33".repeat(32)}"}]}
    """.trimIndent().toByteArray()

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun installedManifest(key: String, modelSha: String, licenseSha: String) = JSONObject()
        .put("schemaVersion", 1)
        .put("packId", "gemma-4-e2b")
        .put("packVersion", "pack-v1")
        .put("model", JSONObject().put("family", "gemma-4").put("tier", "E2B").put("multimodal", true).put("maxContextTokens", 4096))
        .put("source", JSONObject().put("revision", "1a".repeat(20)).put("license", "gemma-terms"))
        .put("runtime", JSONObject().put("name", "LiteRT-LM").put("version", LITERT_LM_VERSION))
        .put("device", JSONObject().put("minimumRamBytes", 4L * 1024 * 1024 * 1024))
        .put("signing", JSONObject().put("algorithm", "SHA256withRSA").put("keySha256", key))
        .put(
            "files",
            JSONArray()
                .put(JSONObject().put("role", "model").put("name", "gemma.litertlm").put("sizeBytes", 50L * 1024 * 1024).put("sha256", modelSha))
                .put(JSONObject().put("role", "license").put("name", "LICENSE.txt").put("sizeBytes", 7).put("sha256", licenseSha)),
        )
        .toString()
        .toByteArray()

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
