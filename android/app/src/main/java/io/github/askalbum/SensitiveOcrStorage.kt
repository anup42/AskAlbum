package io.github.anup42.askalbum

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps high-risk OCR values encrypted at rest and redacted from ordinary
 * search/indexing paths. The Keystore key never leaves the app process.
 */
object SensitiveOcrStorage {
    const val REDACTED = "[REDACTED]"

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "askalbum.sensitive-ocr.v1"
    private const val PREFIX = "askalbum-ks1:"
    private const val TAG_BITS = 128
    private val keyLock = Any()
    private val sensitiveAssignment = Regex(
        "(?i)(password|passcode|pin|cvv|account number|medical record|diagnosis|aadhaar|passport|social security|ssn)(\\s*[:=-]\\s*)[^\\r\\n,;]+",
    )
    private val protectedTypeNames = setOf(
        "PASSWORD",
        "PIN",
        "CVV",
        "AUTHENTICATION_CODE",
        "IDENTITY_NUMBER",
        "AADHAAR",
        "PASSPORT",
        "SSN",
        "EMAIL",
        "PHONE",
        "ORDER_ID",
    )

    fun isEncrypted(value: String?): Boolean = value?.startsWith(PREFIX) == true

    fun shouldProtectEntity(typeName: String, rawText: String, normalizedValue: String): Boolean =
        typeName.uppercase() in protectedTypeNames ||
            SensitiveContentClassifier.isSensitive(rawText) ||
            SensitiveContentClassifier.isSensitive(normalizedValue)

    fun redact(text: String): String {
        if (!SensitiveContentClassifier.isSensitive(text)) return text
        return text.lineSequence().joinToString("\n") { line ->
            if (!SensitiveContentClassifier.isSensitive(line)) {
                line
            } else {
                val replaced = sensitiveAssignment.replace(line) { match ->
                    "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
                }
                if (replaced == line) REDACTED else replaced
            }
        }
    }

    fun protect(value: String): String = if (value.isBlank() || isEncrypted(value)) value else encrypt(value)

    fun protectIfNeeded(value: String, shouldProtect: Boolean): String =
        if (shouldProtect && value.isNotBlank()) protect(value) else value

    fun read(value: String, includeSensitiveContent: Boolean, protected: Boolean): String {
        if (!protected) return value
        if (!includeSensitiveContent) return REDACTED
        return if (isEncrypted(value)) decrypt(value) ?: REDACTED else value
    }

    fun encrypt(value: String): String {
        if (value.isBlank() || isEncrypted(value)) return value
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return buildString {
            append(PREFIX)
            append(Base64.encodeToString(iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    fun decrypt(value: String): String? {
        if (!isEncrypted(value)) return value
        return runCatching {
            val payload = value.removePrefix(PREFIX).split(':', limit = 2)
            require(payload.size == 2)
            val iv = Base64.decode(payload[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(payload[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun key(): SecretKey = synchronized(keyLock) {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generator.generateKey()
        }
    }
}
