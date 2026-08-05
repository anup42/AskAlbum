package io.github.anup42.askalbum

import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads only scalar JSON strings that are safe to persist as derived text.
 * JSON null, non-string values, and common model placeholders are absent.
 */
internal fun JSONObject.optionalSafeString(key: String, maximumLength: Int): String? =
    if (!has(key) || isNull(key)) null else safeJsonString(opt(key), maximumLength)

internal fun JSONArray.optionalSafeString(index: Int, maximumLength: Int): String? =
    if (index !in 0 until length()) null else safeJsonString(opt(index), maximumLength)

private fun safeJsonString(value: Any?, maximumLength: Int): String? {
    if (maximumLength <= 0 || value !is String) return null
    val normalized = value.trim()
    if (normalized.isBlank()) return null
    if (normalized.equals("null", ignoreCase = true)) return null
    if (normalized.equals("undefined", ignoreCase = true)) return null
    if (normalized.equals("unknown", ignoreCase = true)) return null
    return normalized.take(maximumLength)
}
