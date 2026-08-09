package io.github.anup42.askalbum

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SemanticCaptionValuePolicyTest {
    @Test
    fun placeholderAndNonStringValuesAreAbsent() {
        assertEquals("", SemanticCaptionValuePolicy.text(JSONObject.NULL, 80))
        assertEquals("", SemanticCaptionValuePolicy.text("null", 80))
        assertEquals("", SemanticCaptionValuePolicy.text("undefined", 80))
        assertEquals("", SemanticCaptionValuePolicy.text("unknown", 80))
        assertEquals("", SemanticCaptionValuePolicy.text(mapOf("value" to "gift"), 80))
        assertEquals("A person is holding a gift", SemanticCaptionValuePolicy.text(" A person is holding a gift ", 80))
    }
}
