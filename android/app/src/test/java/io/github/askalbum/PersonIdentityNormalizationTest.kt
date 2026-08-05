package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonIdentityNormalizationTest {
    @Test
    fun compatibilityFormsAndCaseNormalizeToOneIdentity() {
        assertEquals("anup kumar", PersonIdentityNormalization.normalize("ＡＮＵＰ  KUMAR"))
        assertEquals("café", PersonIdentityNormalization.normalize("Cafe\u0301"))
        assertTrue(PersonIdentityNormalization.containsIdentityTerm("मेरे भैया के साथ", "भैया"))
        assertTrue(PersonIdentityNormalization.containsIdentityTerm("MY WIFE", "wife"))
    }

    @Test
    fun identityTermsUseUnicodeBoundaries() {
        assertTrue(PersonIdentityNormalization.containsIdentityTerm("Pichle saal bhaiya wali photos", "bhaiya"))
        assertFalse(PersonIdentityNormalization.containsIdentityTerm("my brotherhood photos", "brother"))
        assertFalse(PersonIdentityNormalization.containsIdentityTerm("", "wife"))
    }
}
