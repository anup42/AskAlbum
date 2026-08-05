package io.github.anup42.askalbum

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonValuePolicyTest {
    @Test
    fun objectStringsRejectNullPlaceholdersAndNonStrings() {
        val json = JSONObject(
            """{"valid":"  Café  ","nullValue":null,"number":42,"blank":"  ","placeholder":"unknown"}""",
        )

        assertEquals("Café", json.optionalSafeString("valid", 40))
        assertNull(json.optionalSafeString("nullValue", 40))
        assertNull(json.optionalSafeString("number", 40))
        assertNull(json.optionalSafeString("blank", 40))
        assertNull(json.optionalSafeString("placeholder", 40))
        assertNull(json.optionalSafeString("missing", 40))
    }

    @Test
    fun arrayStringsSkipInvalidValuesAndRespectBound() {
        val values = JSONArray().apply {
            put(" red ")
            put(JSONObject.NULL)
            put(7)
            put("null")
            put("blue")
        }

        assertEquals("red", values.optionalSafeString(0, 40))
        assertNull(values.optionalSafeString(1, 40))
        assertNull(values.optionalSafeString(2, 40))
        assertNull(values.optionalSafeString(3, 40))
        assertEquals("blu", values.optionalSafeString(4, 3))
        assertNull(values.optionalSafeString(99, 40))
    }
}
