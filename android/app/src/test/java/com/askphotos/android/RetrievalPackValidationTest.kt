package com.askphotos.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature

class RetrievalPackValidationTest {
    @Test
    fun validPinnedManifestAndApkKeySignatureAreAccepted() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val bytes = manifestBytes(sha256(keys.public.encoded))
        val manifest = RetrievalPackManifest.parse(bytes)
        val signer = Signature.getInstance("SHA256withRSA").apply {
            initSign(keys.private)
            update(bytes)
        }

        RetrievalPackSignatureVerifier(keys.public).verify(bytes, signer.sign(), manifest)

        assertEquals("google/siglip2-base-patch16-224", manifest.sourceModel)
        assertEquals(768, manifest.embeddingDimension)
        assertEquals("NCHW", manifest.imageLayout)
    }

    @Test
    fun changedManifestIsRejectedBySignature() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val original = manifestBytes(sha256(keys.public.encoded))
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(original)
            sign()
        }
        val changed = original.toString(Charsets.UTF_8).replace("\"dimension\":768", "\"dimension\":769").toByteArray()

        val failure = runCatching {
            RetrievalPackSignatureVerifier(keys.public).verify(changed, signature, RetrievalPackManifest.parse(changed))
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun unpinnedSourceRevisionIsRejected() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val invalid = manifestBytes(sha256(keys.public.encoded)).toString(Charsets.UTF_8)
            .replace("0123456789012345678901234567890123456789", "main")

        assertTrue(runCatching { RetrievalPackManifest.parse(invalid.toByteArray()) }.isFailure)
    }

    @Test
    fun imagePreprocessingHonorsNchwAndNormalization() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val manifest = RetrievalPackManifest.parse(manifestBytes(sha256(keys.public.encoded)))
        val image = ModelImage(byteArrayOf(0, 128.toByte(), 255.toByte()), width = 1, height = 1)

        val output = Siglip2ImagePreprocessor.preprocess(image, manifest)

        assertEquals(3 * 224 * 224, output.size)
        assertEquals(-1f, output[0], 1e-6f)
        assertEquals((128f / 255f - .5f) / .5f, output[224 * 224], 1e-6f)
        assertEquals(1f, output[2 * 224 * 224], 1e-6f)
    }

    private fun manifestBytes(keyFingerprint: String): ByteArray = """
        {"schemaVersion":1,"packId":"siglip2-base-p16-224","packVersion":"test-1","source":{"model":"google/siglip2-base-patch16-224","revision":"0123456789012345678901234567890123456789","license":"apache-2.0"},"runtime":{"name":"LiteRT","version":"2.1.0"},"embedding":{"dimension":768,"normalized":true,"minimumSimilarity":0.1},"image":{"size":224,"layout":"NCHW","resize":"BICUBIC","mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},"text":{"length":64,"lowercase":true,"padTokenId":0,"eosTokenId":1,"inputType":"INT64"},"signing":{"algorithm":"SHA256withRSA","keySha256":"$keyFingerprint"},"files":[{"role":"image_encoder","name":"image_encoder.tflite","sizeBytes":1,"sha256":"${"00".repeat(32)}"},{"role":"text_encoder","name":"text_encoder.tflite","sizeBytes":1,"sha256":"${"11".repeat(32)}"},{"role":"tokenizer_vocab","name":"tokenizer.vocab","sizeBytes":1,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":1,"sha256":"${"33".repeat(32)}"}]}
    """.trimIndent().toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
