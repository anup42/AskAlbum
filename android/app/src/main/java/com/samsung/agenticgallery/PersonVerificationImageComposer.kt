package com.samsung.agenticgallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.util.Locale

object PersonVerificationImageComposer {
    fun compose(jpeg: ByteArray, bindings: List<PersonVerificationBinding>): ByteArray {
        if (bindings.isEmpty()) return jpeg
        val source = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Verification image could not be decoded" }
        val cropSize = (source.width / bindings.size.coerceAtLeast(1)).coerceIn(160, 420)
        val output = Bitmap.createBitmap(source.width, source.height + cropSize * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, 0f, 0f, null)
        val colors = intArrayOf(Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.GREEN, Color.RED)
        val orderedCenters = bindings.mapIndexed { index, binding ->
            index to ((binding.left + binding.right) * source.width / 2f)
        }.sortedBy { it.second }
        val horizontalBounds = orderedCenters.mapIndexed { position, (index, center) ->
            val left = if (position == 0) 0 else ((orderedCenters[position - 1].second + center) / 2f).toInt()
            val right = if (position == orderedCenters.lastIndex) source.width else ((center + orderedCenters[position + 1].second) / 2f).toInt()
            index to (left..right)
        }.toMap()
        bindings.forEachIndexed { index, binding ->
            val color = colors[index % colors.size]
            val box = Rect(
                (binding.left * source.width).toInt(),
                (binding.top * source.height).toInt(),
                (binding.right * source.width).toInt(),
                (binding.bottom * source.height).toInt(),
            )
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = maxOf(4f, source.width / 240f)
                this.color = color
            }
            canvas.drawRect(box, paint)
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                this.color = color
                textSize = maxOf(28f, source.width / 28f)
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText(
                binding.stableLabel,
                box.left.toFloat(),
                (box.top.toFloat() - 8f).coerceAtLeast(labelPaint.textSize),
                labelPaint,
            )

            val faceWidth = box.width().coerceAtLeast(1)
            val faceHeight = box.height().coerceAtLeast(1)
            val upperBody = Rect(
                (box.left - faceWidth).coerceAtLeast(0),
                (box.top - faceHeight / 2).coerceAtLeast(0),
                (box.right + faceWidth).coerceAtMost(source.width),
                (box.bottom + faceHeight * 4).coerceAtMost(source.height),
            )
            val corridor = requireNotNull(horizontalBounds[index])
            val fullBody = Rect(
                corridor.first.coerceAtMost(box.left),
                (box.top - faceHeight / 2).coerceAtLeast(0),
                corridor.last.coerceAtLeast(box.right).coerceAtMost(source.width),
                source.height,
            )
            val fullDestination = Rect(index * cropSize, source.height, minOf(source.width, (index + 1) * cropSize), source.height + cropSize)
            canvas.drawBitmap(source, fullBody, fullDestination, null)
            canvas.drawText("${binding.stableLabel} FULL", fullDestination.left + 8f, fullDestination.top + labelPaint.textSize, labelPaint)
            val upperDestination = Rect(index * cropSize, source.height + cropSize, minOf(source.width, (index + 1) * cropSize), source.height + cropSize * 2)
            canvas.drawBitmap(source, upperBody, upperDestination, null)
            canvas.drawText("${binding.stableLabel} UPPER", upperDestination.left + 8f, upperDestination.top + labelPaint.textSize, labelPaint)
        }
        return try {
            ByteArrayOutputStream().use { bytes ->
                require(output.compress(Bitmap.CompressFormat.JPEG, 90, bytes)) { "Person verification image could not be encoded" }
                bytes.toByteArray()
            }
        } finally {
            source.recycle()
            output.recycle()
        }
    }
}

internal object PersonVerificationPromptBinding {
    fun bind(
        conditions: List<VerificationConditionSpec>,
        bindings: List<PersonVerificationBinding>,
    ): List<VerificationConditionSpec> {
        if (bindings.isEmpty()) return conditions
        return conditions.map { condition ->
            val explicit = bindings.firstOrNull { binding ->
                condition.relationToPerson == binding.clusterId ||
                    binding.identityTerms.any { it.equals(condition.relationToPerson, ignoreCase = true) }
            }
            var text = condition.text
            bindings.forEach { binding ->
                binding.identityTerms.sortedByDescending(String::length).forEach { term ->
                    if (term.isNotBlank()) {
                        text = text.replace(
                            Regex("(?i)(^|[^\\p{L}\\p{M}\\p{N}])${Regex.escape(term)}([^\\p{L}\\p{M}\\p{N}]|$)"),
                            "$1${binding.stableLabel}$2",
                        )
                    }
                }
            }
            val bound = explicit ?: bindings.singleOrNull()?.takeIf { condition.subject == SemanticSubject.PERSON }
            if (bound != null && bound.stableLabel.lowercase(Locale.ROOT) !in text.lowercase(Locale.ROOT)) {
                text = "${bound.stableLabel}: $text"
            }
            condition.copy(text = text, relationToPerson = bound?.clusterId ?: condition.relationToPerson)
        }
    }
}
