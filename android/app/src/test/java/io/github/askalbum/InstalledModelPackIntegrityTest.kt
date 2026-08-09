package io.github.anup42.askalbum

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class InstalledModelPackIntegrityTest {
    @Test
    fun ocrReactivationRejectsArtifactMutation() {
        val directory = Files.createTempDirectory("ocr-generation-").toFile()
        try {
            val bytes = byteArrayOf(1, 2, 3, 4)
            val spec = OcrModelSpec(
                packId = "fixture-ocr",
                version = "v1",
                displayName = "Fixture OCR",
                license = "Apache-2.0",
                languages = "fixture",
                artifacts = listOf(
                    OcrModelArtifact("det.onnx", "fixture", "revision", "det.onnx", bytes.size.toLong(), sha256(bytes)),
                ),
            )
            val artifact = File(directory, "det.onnx")
            artifact.writeBytes(bytes)
            verifyOcrModelDirectory(directory, spec)
            artifact.writeBytes(byteArrayOf(9, 2, 3, 4))
            assertThrows(IllegalArgumentException::class.java) { verifyOcrModelDirectory(directory, spec) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sfaceReactivationRejectsArtifactMutationEvenWithMatchingSize() {
        val directory = Files.createTempDirectory("sface-generation-").toFile()
        try {
            val bytes = byteArrayOf(5, 6, 7, 8)
            val spec = FaceModelSpec(
                packId = "fixture-face",
                packVersion = "v1",
                displayName = "Fixture SFace",
                repository = "fixture/repository",
                revision = "0123456789012345678901234567890123456789",
                fileName = "face.onnx",
                sizeBytes = bytes.size.toLong(),
                sha256 = sha256(bytes),
                license = "Apache-2.0",
                embeddingDimension = 128,
                inputSize = 112,
                cosineThreshold = .3f,
            )
            val artifact = File(directory, spec.fileName)
            artifact.writeBytes(bytes)
            verifyFaceModelArtifact(artifact, spec)
            artifact.writeBytes(byteArrayOf(5, 6, 7, 9))
            assertThrows(IllegalArgumentException::class.java) { verifyFaceModelArtifact(artifact, spec) }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
