package com.samsung.agenticgallery

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import java.security.MessageDigest

class MediaImporter(private val context: Context) {
    private val resolver = context.contentResolver

    fun scanAccessibleMediaStore(): MediaScanSnapshot {
        val imageScan = scanCollection(MediaKind.IMAGE)
        val videoScan = scanCollection(MediaKind.VIDEO)
        val complete = buildSet {
            if (imageScan.completed && hasFullPermission(MediaKind.IMAGE)) add(MediaKind.IMAGE)
            if (videoScan.completed && hasFullPermission(MediaKind.VIDEO)) add(MediaKind.VIDEO)
        }
        return MediaScanSnapshot(imageScan.items + videoScan.items, complete)
    }

    fun inspectUris(uris: List<Uri>, source: MediaSource): List<ImportedMedia> = uris.mapNotNull { uri ->
        runCatching {
            if (source != MediaSource.MEDIA_STORE) {
                runCatching {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            var name = uri.lastPathSegment ?: "Imported media"
            var size = 0L
            var capturedAt: Long? = null
            var modifiedAt: Long? = null
            var duration: Long? = null
            var width = 0
            var height = 0
            var album = ""
            val projection = buildList {
                add(OpenableColumns.DISPLAY_NAME)
                add(OpenableColumns.SIZE)
                if (source == MediaSource.MEDIA_STORE) add(MediaStore.Images.ImageColumns.DATE_TAKEN)
                add(MediaStore.MediaColumns.DATE_MODIFIED)
                add(MediaStore.MediaColumns.WIDTH)
                add(MediaStore.MediaColumns.HEIGHT)
            }.toTypedArray()
            resolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.columnText(OpenableColumns.DISPLAY_NAME)?.let { name = it }
                    cursor.columnLong(OpenableColumns.SIZE)?.let { size = it }
                    if (source == MediaSource.MEDIA_STORE) {
                        capturedAt = cursor.columnLong(MediaStore.Images.ImageColumns.DATE_TAKEN)
                    }
                    modifiedAt = cursor.columnLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000)
                    width = cursor.columnLong(MediaStore.MediaColumns.WIDTH)?.toInt() ?: 0
                    height = cursor.columnLong(MediaStore.MediaColumns.HEIGHT)?.toInt() ?: 0
                }
            }
            if (mime.startsWith("video/")) {
                resolver.query(uri, arrayOf(MediaStore.Video.VideoColumns.DURATION), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) duration = cursor.columnLong(MediaStore.Video.VideoColumns.DURATION)
                }
            }
            if (source == MediaSource.MEDIA_STORE) {
                album = runCatching {
                    resolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.columnText(MediaStore.MediaColumns.RELATIVE_PATH).toAlbumName() else ""
                    }.orEmpty()
                }.getOrDefault("")
            }
            val embedded = readEmbeddedMetadata(uri, mime)
            ImportedMedia(
                stableId = "uri-${sha256(uri.toString())}",
                uri = uri.toString(),
                displayName = name,
                mimeType = mime,
                source = source,
                capturedAt = embedded?.capturedAt ?: capturedAt ?: modifiedAt,
                modifiedAt = modifiedAt,
                durationMs = duration,
                width = width,
                height = height,
                sizeBytes = size,
                album = album,
                latitude = embedded?.latitude,
                longitude = embedded?.longitude,
            )
        }.getOrNull()
    }

    private fun scanCollection(kind: MediaKind): CollectionScan {
        val collection = when (kind) {
            MediaKind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            MediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            MediaKind.PDF -> return CollectionScan(emptyList(), false)
        }
        val durationColumn = MediaStore.Video.VideoColumns.DURATION
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            if (kind == MediaKind.IMAGE) add(MediaStore.Images.ImageColumns.DATE_TAKEN)
            if (kind == MediaKind.VIDEO) {
                add(MediaStore.Video.VideoColumns.DATE_TAKEN)
                add(durationColumn)
            }
        }.distinct().toTypedArray()
        val cursor = runCatching {
            resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
        }.getOrNull() ?: return CollectionScan(emptyList(), false)
        return CollectionScan(cursor.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.columnLong(MediaStore.MediaColumns._ID) ?: continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    val mime = cursor.columnText(MediaStore.MediaColumns.MIME_TYPE)
                        ?: if (kind == MediaKind.VIDEO) "video/*" else "image/*"
                    val embedded = if (kind == MediaKind.IMAGE) readEmbeddedMetadata(uri, mime) else null
                    add(
                        ImportedMedia(
                            stableId = "mediastore-${kind.name.lowercase()}-$id",
                            uri = uri.toString(),
                            displayName = cursor.columnText(MediaStore.MediaColumns.DISPLAY_NAME) ?: "Media $id",
                            mimeType = mime,
                            source = MediaSource.MEDIA_STORE,
                            capturedAt = embedded?.capturedAt ?: cursor.columnLong(MediaStore.Images.ImageColumns.DATE_TAKEN)
                                ?: cursor.columnLong(MediaStore.MediaColumns.DATE_ADDED)?.times(1000),
                            modifiedAt = cursor.columnLong(MediaStore.MediaColumns.DATE_MODIFIED)?.times(1000),
                            durationMs = if (kind == MediaKind.VIDEO) cursor.columnLong(durationColumn) else null,
                            width = cursor.columnLong(MediaStore.MediaColumns.WIDTH)?.toInt() ?: 0,
                            height = cursor.columnLong(MediaStore.MediaColumns.HEIGHT)?.toInt() ?: 0,
                            sizeBytes = cursor.columnLong(MediaStore.MediaColumns.SIZE) ?: 0,
                            album = cursor.columnText(MediaStore.MediaColumns.RELATIVE_PATH).toAlbumName(),
                            latitude = embedded?.latitude,
                            longitude = embedded?.longitude,
                        ),
                    )
                }
            }
        }, true)
    }

    private fun hasFullPermission(kind: MediaKind): Boolean = when {
        Build.VERSION.SDK_INT < 33 -> ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        kind == MediaKind.IMAGE -> ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_MEDIA_IMAGES,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        kind == MediaKind.VIDEO -> ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_MEDIA_VIDEO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        else -> false
    }

    private data class CollectionScan(val items: List<ImportedMedia>, val completed: Boolean)

    private fun readEmbeddedMetadata(uri: Uri, mimeType: String): EmbeddedMetadata? {
        if (!mimeType.startsWith("image/")) return null
        return runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val exif = ExifInterface(descriptor.fileDescriptor)
                val capturedAt = ExifDateParser.parse(
                    exif.getAttribute("DateTimeOriginal")
                        ?: exif.getAttribute("DateTimeDigitized")
                        ?: exif.getAttribute("DateTime"),
                    exif.getAttribute("OffsetTimeOriginal")
                        ?: exif.getAttribute("OffsetTimeDigitized")
                        ?: exif.getAttribute("OffsetTime"),
                )
                embeddedMetadata(capturedAt) {
                    val coordinates = FloatArray(2)
                    coordinates.takeIf { exif.getLatLong(it) }
                        ?.let { it[0].toDouble() to it[1].toDouble() }
                }
            }
        }.getOrNull()
    }

    private fun android.database.Cursor.columnText(name: String): String? {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.columnLong(name: String): Long? {
        val index = getColumnIndex(name)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun String?.toAlbumName(): String = this
        ?.trim('/')
        ?.substringAfterLast('/')
        ?.take(160)
        .orEmpty()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal data class EmbeddedMetadata(
    val capturedAt: Long?,
    val latitude: Double?,
    val longitude: Double?,
)

/** Location may be redacted or malformed; that must not discard an independently valid EXIF date. */
internal fun embeddedMetadata(
    capturedAt: Long?,
    coordinates: () -> Pair<Double, Double>?,
): EmbeddedMetadata? {
    val location = runCatching(coordinates).getOrNull()
    return EmbeddedMetadata(capturedAt, location?.first, location?.second)
        .takeIf { it.capturedAt != null || location != null }
}
