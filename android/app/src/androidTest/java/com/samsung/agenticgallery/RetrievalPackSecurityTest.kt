package com.samsung.agenticgallery

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
class RetrievalPackSecurityTest {
    @Test
    fun packSignedByUnknownKeyIsRejectedWithoutChangingActiveGeneration() {
        val context = ApplicationProvider.getApplicationContext<AgenticGalleryApplication>()
        val manager = RetrievalModelPackManager(context)
        val before = manager.status()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val files = linkedMapOf(
            "image_encoder.tflite" to byteArrayOf(1),
            "text_encoder.tflite" to byteArrayOf(2),
            "tokenizer.vocab" to byteArrayOf(3),
            "LICENSE.txt" to byteArrayOf(4),
        )
        val manifest = manifest(files, sha256(keys.public.encoded)).toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keys.private)
            update(manifest)
            sign()
        }
        val archive = File(context.cacheDir, "unknown-key-${System.nanoTime()}.agretrieval")
        try {
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("manifest.json", manifest)
                entry("manifest.sig", Base64.getEncoder().encode(signature))
                files.forEach(::entry)
            }

            val failure = runCatching { manager.installVerified(archive) }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("signing key"))
            assertEquals(before, manager.status())
        } finally {
            archive.delete()
        }
    }

    private fun manifest(files: Map<String, ByteArray>, keyFingerprint: String): String {
        val roles = listOf("image_encoder", "text_encoder", "tokenizer_vocab", "license")
        val specs = files.entries.mapIndexed { index, entry ->
            "{\"role\":\"${roles[index]}\",\"name\":\"${entry.key}\",\"sizeBytes\":${entry.value.size},\"sha256\":\"${sha256(entry.value)}\"}"
        }.joinToString(",")
        return """{"schemaVersion":1,"packId":"siglip2-base-p16-224","packVersion":"security-test","source":{"model":"google/siglip2-base-patch16-224","revision":"0123456789012345678901234567890123456789","license":"apache-2.0"},"runtime":{"name":"LiteRT","version":"2.1.0"},"embedding":{"dimension":768,"normalized":true,"minimumSimilarity":0.1},"image":{"size":224,"layout":"NCHW","resize":"BICUBIC","mean":[0.5,0.5,0.5],"std":[0.5,0.5,0.5]},"text":{"length":64,"lowercase":true,"padTokenId":0,"eosTokenId":1,"inputType":"INT64"},"signing":{"algorithm":"SHA256withRSA","keySha256":"$keyFingerprint"},"files":[$specs]}"""
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
