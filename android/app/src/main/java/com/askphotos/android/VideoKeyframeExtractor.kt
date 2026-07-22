package com.askphotos.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlin.math.ceil

data class ExtractedVideoKeyframe(
    val id: String,
    val timestampMs: Long,
    val bitmap: Bitmap,
    val visualFeatures: VisualFeatures,
    val previewPath: String,
)

object VideoKeyframePolicy {
    const val PRODUCER_VERSION = "video-keyframes-v1"
    const val MAX_KEYFRAMES = 12
    private const val SHORT_VIDEO_MS = 30_000L
    private const val SHORT_INTERVAL_MS = 5_000L
    private const val MIN_ADJACENT_HASH_DISTANCE = 8

    fun candidateTimestamps(durationMs: Long): List<Long> {
        if (durationMs <= 1L) return listOf(0L)
        val count = if (durationMs <= SHORT_VIDEO_MS) {
            ceil(durationMs.toDouble() / SHORT_INTERVAL_MS).toInt().coerceIn(1, 6)
        } else {
            MAX_KEYFRAMES
        }
        return (0 until count).map { index ->
            (((index + 0.5) * durationMs) / count).toLong().coerceIn(0L, durationMs - 1L)
        }.distinct()
    }

    fun shouldKeep(previousHash: Long?, candidateHash: Long): Boolean =
        previousHash == null || java.lang.Long.bitCount(previousHash xor candidateHash) >= MIN_ADJACENT_HASH_DISTANCE

    fun stableId(mediaId: String, timestampMs: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$mediaId:$timestampMs".toByteArray(Charsets.UTF_8))
            .take(15)
            .joinToString("") { "%02x".format(it) }
        return "vf-$digest"
    }
}

/** Extracts bounded, low-resolution, adjacent-scene-distinct frames into app-private storage. */
class VideoKeyframeExtractor(private val context: Context) {
    fun extract(item: GalleryItem): List<ExtractedVideoKeyframe> {
        require(item.kind == MediaKind.VIDEO) { "Keyframes require a video item" }
        val uri = Uri.parse(requireNotNull(item.contentUri))
        require(uri.scheme == "content") { "Only content video URIs are accepted" }
        val directory = privateDirectory(item.id)
        directory.listFiles()?.filter { it.isFile && it.extension.equals("jpg", true) }?.forEach(File::delete)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val measuredDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val duration = measuredDuration ?: item.durationMs ?: 0L
            val selected = mutableListOf<ExtractedVideoKeyframe>()
            var previousHash: Long? = null
            VideoKeyframePolicy.candidateTimestamps(duration).forEach { timestamp ->
                val bitmap = retriever.getScaledFrameAtTime(
                    timestamp * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    FRAME_EDGE_PX,
                    FRAME_EDGE_PX,
                ) ?: retriever.getFrameAtTime(timestamp * 1_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bitmap != null) {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    val visual = VisualFeatureExtractor.extract(pixels, bitmap.width, bitmap.height)
                    if (VideoKeyframePolicy.shouldKeep(previousHash, visual.perceptualHash) || selected.isEmpty()) {
                        val id = VideoKeyframePolicy.stableId(item.id, timestamp)
                        val preview = File(directory, "$timestamp.jpg")
                        preview.outputStream().use { output ->
                            require(bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, output)) {
                                "Could not write video keyframe"
                            }
                        }
                        selected += ExtractedVideoKeyframe(id, timestamp, bitmap, visual, preview.absolutePath)
                        previousHash = visual.perceptualHash
                    } else {
                        bitmap.recycle()
                    }
                }
            }
            require(selected.isNotEmpty()) { "Video contains no decodable frame" }
            selected.take(VideoKeyframePolicy.MAX_KEYFRAMES)
        } finally {
            retriever.release()
        }
    }

    private fun privateDirectory(mediaId: String): File {
        val name = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { "%02x".format(it) }
        val root = File(context.filesDir, "video-keyframes").canonicalFile.apply {
            require(exists() || mkdirs()) { "Could not create keyframe storage" }
        }
        return File(root, name).canonicalFile.also { directory ->
            require(directory.toPath().startsWith(root.toPath())) { "Unsafe keyframe directory" }
            require(directory.exists() || directory.mkdirs()) { "Could not create keyframe directory" }
        }
    }

    private companion object {
        const val FRAME_EDGE_PX = 512
        const val PREVIEW_QUALITY = 88
    }
}
