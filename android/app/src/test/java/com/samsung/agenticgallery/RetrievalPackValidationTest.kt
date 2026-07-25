package com.samsung.agenticgallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

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

    @Test
    fun pinnedOnnxQuantizedDualEncoderManifestIsAccepted() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val manifest = RetrievalPackManifest.parse(onnxManifestBytes(sha256(keys.public.encoded)))

        assertEquals(RETRIEVAL_RUNTIME_ONNX, manifest.runtime)
        assertEquals(ONNX_RUNTIME_VERSION, manifest.runtimeVersion)
        assertEquals(ONNX_SIGLIP2_REPOSITORY, manifest.artifactRepository)
        assertEquals("ba1f3b0843f24bc5417d38e19c37b287d719b2f4", manifest.artifactRevision)
    }

    @Test
    fun onnxRuntimeRejectsTfliteArtifacts() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val invalid = onnxManifestBytes(sha256(keys.public.encoded)).toString(Charsets.UTF_8)
            .replace("vision_model_quantized.onnx", "vision_model_quantized.tflite")

        assertTrue(runCatching { RetrievalPackManifest.parse(invalid.toByteArray()) }.isFailure)
    }

    @Test
    fun exportedTokenizerMatchesPinnedSentencePieceNormalizationAndFallbackRules() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val manifest = RetrievalPackManifest.parse(onnxManifestBytes(sha256(keys.public.encoded)))
        val vocab = File.createTempFile("siglip-tokenizer-", ".vocab")
        try {
            vocab.bufferedWriter().use { output ->
                output.appendLine("AGTOK1")
                repeat(1_000) { id ->
                    val piece = when (id) {
                        0 -> "<pad>"
                        1 -> "<eos>"
                        2 -> "<bos>"
                        3 -> "<unk>"
                        in 4..259 -> "<0x${(id - 4).toString(16).uppercase().padStart(2, '0')}>"
                        300 -> "h"
                        301 -> "e"
                        302 -> "l"
                        303 -> "o"
                        304 -> "\u2581"
                        305 -> "w"
                        306 -> "r"
                        307 -> "d"
                        310 -> "he"
                        311 -> "hel"
                        312 -> "hell"
                        313 -> "hello"
                        320 -> "wo"
                        321 -> "wor"
                        322 -> "worl"
                        323 -> "world"
                        331 -> "\u2581world"
                        332 -> "\u2581\u2581"
                        333 -> "\u2581hello"
                        else -> "unused$id"
                    }
                    val score = when (id) {
                        310, 320 -> -1
                        311, 321 -> -2
                        312, 322 -> -3
                        313, 323 -> -4
                        331 -> -5
                        332 -> -1
                        333 -> -5
                        else -> -id
                    }
                    output.append(id.toString()).append('\t').append(score.toString()).append('\t')
                        .append(Base64.getEncoder().encodeToString(piece.toByteArray())).appendLine()
                }
            }

            val tokenizer = Siglip2VocabTokenizer.load(vocab)

            assertTrue(tokenizer.encode("Hello world", manifest).take(3) == listOf(313, 331, 1))
            assertTrue(tokenizer.encode("Hello  world", manifest).take(4) == listOf(313, 332, 323, 1))
            assertTrue(tokenizer.encode(" hello", manifest).take(2) == listOf(333, 1))
            assertTrue(tokenizer.encode("hello\tworld", manifest).take(4) == listOf(313, 13, 323, 1))
        } finally {
            vocab.delete()
        }
    }

    private fun manifestBytes(keyFingerprint: String): ByteArray = """
        {"schemaVersion":1,"packId":"siglip2-base-p16-224","packVersion":"test-1","source":{"model":"google/siglip2-base-patch16-224","revision":"0123456789012345678901234567890123456789","license":"apache-2.0"},"runtime":{"name":"LiteRT","version":"2.1.0"},"embedding":{"dimension":768,"normalized":true,"minimumSimilarity":0.1},"image":{"size":224,"layout":"NCHW","resize":"BICUBIC","mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},"text":{"length":64,"lowercase":true,"padTokenId":0,"eosTokenId":1,"inputType":"INT64"},"signing":{"algorithm":"SHA256withRSA","keySha256":"$keyFingerprint"},"files":[{"role":"image_encoder","name":"image_encoder.tflite","sizeBytes":1,"sha256":"${"00".repeat(32)}"},{"role":"text_encoder","name":"text_encoder.tflite","sizeBytes":1,"sha256":"${"11".repeat(32)}"},{"role":"tokenizer_vocab","name":"tokenizer.vocab","sizeBytes":1,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":1,"sha256":"${"33".repeat(32)}"}]}
    """.trimIndent().toByteArray()

    private fun onnxManifestBytes(keyFingerprint: String): ByteArray = """
        {"schemaVersion":1,"packId":"siglip2-base-p16-224-q8","packVersion":"ba1f3b0-q8","source":{"model":"google/siglip2-base-patch16-224","revision":"75de2d55ec2d0b4efc50b3e9ad70dba96a7b2fa2","license":"apache-2.0"},"artifact":{"repository":"onnx-community/siglip2-base-patch16-224-ONNX","revision":"ba1f3b0843f24bc5417d38e19c37b287d719b2f4"},"runtime":{"name":"ONNX Runtime","version":"1.23.2"},"embedding":{"dimension":768,"normalized":true,"minimumSimilarity":0.1},"image":{"size":224,"layout":"NCHW","resize":"BICUBIC","mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},"text":{"length":64,"lowercase":true,"padTokenId":0,"eosTokenId":1,"inputType":"INT64"},"signing":{"algorithm":"SHA256withRSA","keySha256":"$keyFingerprint"},"files":[{"role":"image_encoder","name":"vision_model_quantized.onnx","sizeBytes":1,"sha256":"${"00".repeat(32)}"},{"role":"text_encoder","name":"text_model_quantized.onnx","sizeBytes":1,"sha256":"${"11".repeat(32)}"},{"role":"tokenizer_vocab","name":"tokenizer.vocab","sizeBytes":1,"sha256":"${"22".repeat(32)}"},{"role":"license","name":"LICENSE.txt","sizeBytes":1,"sha256":"${"33".repeat(32)}"}]}
    """.trimIndent().toByteArray()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
