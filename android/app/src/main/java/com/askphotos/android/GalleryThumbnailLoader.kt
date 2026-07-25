package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

private val thumbnailBuckets = intArrayOf(128, 256, 384, 512, 768, 1024, 1536)

internal fun thumbnailEdgeBucket(requestedEdgePx: Int): Int =
    thumbnailBuckets.firstOrNull { it >= requestedEdgePx.coerceAtLeast(1) } ?: thumbnailBuckets.last()

internal fun thumbnailSampleSize(sourceWidth: Int, sourceHeight: Int, requestedEdgePx: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0) return 1
    val target = thumbnailEdgeBucket(requestedEdgePx)
    val longestEdge = maxOf(sourceWidth, sourceHeight)
    var sample = 1
    while (longestEdge / (sample * 2) >= target) sample *= 2
    return sample
}

internal object GalleryThumbnailLoader {
    private val decodeSlots = Semaphore(3)
    private val cacheSizeKiB = (Runtime.getRuntime().maxMemory() / 10L / 1024L)
        .toInt()
        .coerceIn(24 * 1024, 96 * 1024)
    private val cache = object : LruCache<String, Bitmap>(cacheSizeKiB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun cached(item: GalleryItem, requestedEdgePx: Int): Bitmap? =
        cache.get(cacheKey(item, requestedEdgePx))

    suspend fun load(context: Context, item: GalleryItem, requestedEdgePx: Int): Bitmap? {
        val edge = thumbnailEdgeBucket(requestedEdgePx)
        val key = cacheKey(item, edge)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeSlots.withPermit {
                cache.get(key)?.let { return@withPermit it }
                val cancellationSignal = CancellationSignal()
                val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) cancellationSignal.cancel()
                }
                try {
                    runCatching { decode(context, item, edge, cancellationSignal) }
                        .getOrNull()
                        ?.also {
                            it.prepareToDraw()
                            cache.put(key, it)
                        }
                } finally {
                    cancellationHandle?.dispose()
                }
            }
        }
    }

    private fun cacheKey(item: GalleryItem, requestedEdgePx: Int): String =
        "${item.id}|${item.modifiedAt ?: 0L}|${thumbnailEdgeBucket(requestedEdgePx)}"

    private fun decode(
        context: Context,
        item: GalleryItem,
        edge: Int,
        cancellationSignal: CancellationSignal,
    ): Bitmap? = when {
        item.previewPath != null -> decodeSampledFile(item.previewPath, edge)
        item.assetPath != null -> decodeSampledStream({ context.assets.open(item.assetPath) }, edge)
        item.contentUri != null -> {
            val uri = Uri.parse(item.contentUri)
            runCatching {
                context.contentResolver.loadThumbnail(uri, Size(edge, edge), cancellationSignal)
            }.getOrNull() ?: decodeSampledStream(
                opener = { context.contentResolver.openInputStream(uri) },
                requestedEdgePx = edge,
            )
        }
        else -> null
    }

    private fun decodeSampledFile(path: String, requestedEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, requestedEdgePx)
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
    }

    private fun decodeSampledStream(opener: () -> InputStream?, requestedEdgePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        opener()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        return opener()?.use {
            BitmapFactory.decodeStream(
                it,
                null,
                BitmapFactory.Options().apply {
                    inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight, requestedEdgePx)
                    inPreferredConfig = Bitmap.Config.RGB_565
                },
            )
        }
    }
}

@Composable
internal fun CachedGalleryImage(
    item: GalleryItem,
    modifier: Modifier,
    contentScale: ContentScale,
    requestedEdgePx: Int,
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val initial = remember(item.id, item.modifiedAt, requestedEdgePx) {
        GalleryThumbnailLoader.cached(item, requestedEdgePx)
    }
    val bitmap by produceState(
        initialValue = initial,
        item.id,
        item.modifiedAt,
        item.assetPath,
        item.contentUri,
        item.previewPath,
        requestedEdgePx,
    ) {
        if (value == null) value = GalleryThumbnailLoader.load(context, item, requestedEdgePx)
    }
    if (bitmap != null) {
        Image(
            bitmap = requireNotNull(bitmap).asImageBitmap(),
            contentDescription = item.description,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {}
    }
}
