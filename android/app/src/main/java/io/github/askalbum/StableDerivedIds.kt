package io.github.anup42.askalbum

import java.security.MessageDigest

/** Collision-resistant deterministic IDs for derived evidence; never used for notification IDs. */
internal object StableDerivedId {
    fun sha256(vararg parts: String): String {
        val input = parts.joinToString("\u001f")
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
