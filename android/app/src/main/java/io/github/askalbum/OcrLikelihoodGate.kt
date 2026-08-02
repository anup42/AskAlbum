package io.github.anup42.askalbum

import java.util.Locale
import kotlin.math.abs

data class OcrGateDecision(val shouldRun: Boolean, val score: Int, val reasons: List<String>)

/** Cheap, deterministic gate that avoids OCR on ordinary photos while retaining likely documents. */
object OcrLikelihoodGate {
    private val documentWords = setOf(
        "receipt", "invoice", "document", "menu", "ticket", "boarding", "pass", "form", "text", "screen",
    )
    private val filenameWords = documentWords + setOf("screenshot", "screen_shot", "wifi", "calendar", "confirmation")

    fun decide(item: GalleryItem, labels: Collection<String>, argb: IntArray, width: Int, height: Int): OcrGateDecision {
        if (item.kind == MediaKind.PDF) return OcrGateDecision(true, 100, listOf("pdf"))
        val reasons = mutableListOf<String>()
        var score = 0
        val name = item.filename.lowercase(Locale.ROOT)
        if (filenameWords.any(name::contains)) {
            score += 4
            reasons += "filename"
        }
        if (labels.any { label -> documentWords.any { word -> word in label.lowercase(Locale.ROOT) } }) {
            score += 3
            reasons += "visual_label"
        }
        val longSide = maxOf(width, height).coerceAtLeast(1)
        val shortSide = minOf(width, height).coerceAtLeast(1)
        val ratio = longSide.toFloat() / shortSide
        if (ratio in 1.45f..2.4f && width * height >= 700_000) {
            score += 1
            reasons += "screen_geometry"
        }
        if (edgeDensity(argb, width, height) >= 0.16f) {
            score += 2
            reasons += "edge_density"
        }
        return OcrGateDecision(score >= 3, score, reasons)
    }

    private fun edgeDensity(argb: IntArray, width: Int, height: Int): Float {
        if (width < 3 || height < 3 || argb.size < width * height) return 0f
        val step = (maxOf(width, height) / 160).coerceAtLeast(1)
        var edges = 0
        var samples = 0
        fun gray(pixel: Int): Int = (((pixel shr 16) and 0xff) * 77 + ((pixel shr 8) and 0xff) * 150 + (pixel and 0xff) * 29) shr 8
        var y = step
        while (y < height) {
            var x = step
            while (x < width) {
                val current = gray(argb[y * width + x])
                val delta = abs(current - gray(argb[y * width + x - step])) +
                    abs(current - gray(argb[(y - step) * width + x]))
                if (delta >= 70) edges++
                samples++
                x += step
            }
            y += step
        }
        return if (samples == 0) 0f else edges.toFloat() / samples
    }
}
