package com.samsung.agenticgallery

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

/** Converts EXIF wall time plus its optional explicit offset into an epoch timestamp. */
object ExifDateParser {
    private val formatter = DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss", Locale.ROOT)
        .withResolverStyle(ResolverStyle.STRICT)

    fun parse(value: String?, offset: String?, fallbackZone: ZoneId = ZoneId.systemDefault()): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val local = LocalDateTime.parse(value.trim(), formatter)
            val instant = offset?.trim()?.takeIf(String::isNotEmpty)?.let { declared ->
                local.toInstant(ZoneOffset.of(declared))
            } ?: local.atZone(fallbackZone).toInstant()
            instant.toEpochMilli().takeIf { it > 0 }
        }.getOrNull()
    }
}
