package io.github.anup42.askalbum

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SensitiveOcrStorageTest {
    @Test
    fun highRiskOcrIsEncryptedAndRedactedOutsideAuthenticatedReads() {
        val original = "Wi-Fi password: local-only-secret"
        val encrypted = SensitiveOcrStorage.encrypt(original)

        assertNotEquals(original, encrypted)
        assertTrue(SensitiveOcrStorage.isEncrypted(encrypted))
        assertEquals(original, SensitiveOcrStorage.decrypt(encrypted))
        assertEquals(SensitiveOcrStorage.REDACTED, SensitiveOcrStorage.read(encrypted, false, true))
        assertEquals(original, SensitiveOcrStorage.read(encrypted, true, true))
    }
}
