package io.github.anup42.askalbum

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed envelope for sensitive derived OCR, query, and retrieval values. */
internal class SensitiveDataAtRest {
    fun protect(value: String): String {
        if (value.isBlank() || value.startsWith(PREFIX)) return value
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val payload = ByteBuffer.allocate(cipher.iv.size + encrypted.size)
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun reveal(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val payload = Base64.decode(value.removePrefix(PREFIX), Base64.DEFAULT)
            require(payload.size > GCM_IV_BYTES) { "Protected OCR value is truncated" }
            val iv = payload.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        return synchronized(this) {
            cachedKey?.let { return@synchronized it }
            val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val loaded = (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
            }.generateKey()
            cachedKey = loaded
            loaded
        }
    }

    companion object {
        const val MIGRATION_VERSION = 7
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "askalbum_sensitive_ocr_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX = "askalbum:v1:"
        private const val GCM_TAG_BITS = 128
        private const val GCM_IV_BYTES = 12
        @Volatile
        private var cachedKey: SecretKey? = null
    }
}

internal fun OcrEntityType.isHighRiskAtRest(): Boolean = this in setOf(
    OcrEntityType.AMOUNT,
    OcrEntityType.RECEIPT_TOTAL,
    OcrEntityType.PASSWORD,
    OcrEntityType.EMAIL,
    OcrEntityType.PHONE,
    OcrEntityType.ORDER_ID,
)
