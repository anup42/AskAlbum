package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Loads a bounded model image only from Kotlin-owned gallery records. */
class GalleryImageLoader(private val context: Context) {
    suspend fun loadJpeg(item: GalleryItem): ByteArray = withContext(Dispatchers.IO) {
        require(item.kind == MediaKind.IMAGE) { "Only images can be visually verified" }
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        open(item).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Image could not be decoded" }
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > MAX_EDGE_PX) sample *= 2
        val bitmap = open(item).use { stream ->
            BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().also { it.inSampleSize = sample })
        } ?: error("Image could not be decoded")
        try {
            ByteArrayOutputStream().use { output ->
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "Image preparation failed" }
                output.toByteArray().also { require(it.size <= MAX_MODEL_IMAGE_BYTES) { "Prepared image exceeds verifier bound" } }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun open(item: GalleryItem): InputStream = when {
        item.previewPath != null -> privatePreview(item.previewPath).inputStream()
        item.assetPath != null -> context.assets.open(requireSafeAssetPath(item.assetPath))
        item.contentUri != null -> {
            val uri = Uri.parse(item.contentUri)
            require(uri.scheme == "content") { "Only content URIs are accepted" }
            requireNotNull(context.contentResolver.openInputStream(uri)) { "Gallery image is inaccessible" }
        }
        else -> error("Gallery item has no readable image source")
    }

    private fun privatePreview(path: String): File {
        val candidate = File(path).canonicalFile
        val roots = listOf(context.filesDir, context.cacheDir, context.noBackupFilesDir)
        require(isWithinRoots(candidate, roots)) { "Preview is outside app-private storage" }
        require(candidate.isFile) { "Preview is unavailable" }
        return candidate
    }

    companion object {
        private const val MAX_EDGE_PX = 1600
        private const val JPEG_QUALITY = 90
        private const val MAX_MODEL_IMAGE_BYTES = 8 * 1024 * 1024
        private val safeAsset = Regex("[A-Za-z0-9._/-]{1,240}")

        internal fun requireSafeAssetPath(path: String): String {
            require(safeAsset.matches(path) && '\\' !in path && !path.startsWith('/')) { "Unsafe asset path" }
            require(path.split('/').none { it.isBlank() || it == "." || it == ".." }) { "Unsafe asset path" }
            return path
        }

        internal fun isWithinRoots(candidate: File, roots: List<File>): Boolean {
            val canonical = candidate.canonicalFile
            return roots.any { root -> canonical.path.startsWith(root.canonicalFile.path + File.separator) }
        }
    }
}
