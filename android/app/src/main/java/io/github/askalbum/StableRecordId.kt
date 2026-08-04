package io.github.anup42.askalbum

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object StableRecordId {
    fun of(namespace: String, vararg parts: String): String {
        val canonical = buildString {
            append(namespace.length).append(':').append(namespace)
            parts.forEach { part -> append('|').append(part.length).append(':').append(part) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        val hex = "0123456789abcdef"
        val token = buildString(digest.size * 2) {
            digest.forEach { value ->
                val unsigned = value.toInt() and 0xff
                append(hex[unsigned ushr 4])
                append(hex[unsigned and 0x0f])
            }
        }
        return "$namespace:$token"
    }
}
