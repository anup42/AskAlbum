package io.github.anup42.askalbum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveSearchProjectionTest {
    @Test
    fun typedProtectedOcrEvidenceKeepsItsAuthenticationBoundary() {
        assertEquals("document_password", ocrEvidenceSourceField(OcrEntityType.PASSWORD))
        assertEquals("document_phone", ocrEvidenceSourceField(OcrEntityType.PHONE))
        assertEquals("ocr_text", ocrEvidenceSourceField(null))
    }

    @Test
    fun searchableProjectionKeepsLabelsButRemovesSensitiveValues() {
        val projected = SensitiveContentClassifier.redactForSearch(
            "Wi-Fi password: mango-tree-2048; email: user@example.com; phone: +91 98765 43210",
        )

        assertTrue(projected.contains("password", ignoreCase = true))
        assertTrue(projected.contains("[REDACTED]"))
        assertFalse(projected.contains("mango-tree-2048"))
        assertFalse(projected.contains("user@example.com"))
        assertFalse(projected.contains("98765 43210"))
    }
}
