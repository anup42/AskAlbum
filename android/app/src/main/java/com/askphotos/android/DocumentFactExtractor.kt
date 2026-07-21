package com.askphotos.android

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Deterministic document/entity extraction. Generated answers consume these records, never guessed values. */
object DocumentFactExtractor {
    private const val PRODUCER = "document-facts-v2"
    private val amount = Regex("(?i)(?:\\u20B9|rs\\.?|inr|usd|\\$)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)|([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:\\u20B9|inr|usd)")
    private val date = Regex("(?i)\\b(?:[0-3]?\\d[-/.](?:0?\\d|1[0-2])[-/.](?:20)?\\d{2}|[0-3]?\\d\\s+(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|jun(?:e)?|jul(?:y)?|aug(?:ust)?|sep(?:tember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\\s+20\\d{2})\\b")
    private val email = Regex("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b")
    private val url = Regex("(?i)\\bhttps?://[^\\s]+|\\bwww\\.[^\\s]+")
    private val phone = Regex("(?<!\\d)(?:\\+?\\d[\\d -]{7,}\\d)(?!\\d)")
    private val order = Regex("(?i)\\b(?:order|booking|reference|ref)\\s*(?:id|no|number)?\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9-]{3,})")
    private val flight = Regex("(?i)\\bflight\\s*[:#-]?\\s*([A-Z]{2,3}\\s?\\d{2,4})\\b")
    private val password = Regex("(?i)\\b(?:password|passcode)\\s*[:=-]\\s*(\\S+)")
    private val positiveTotal = mapOf("grand total" to 7, "amount paid" to 7, "net payable" to 6, "total" to 4, "balance due" to 3)
    private val negativeTotal = mapOf("subtotal" to 9, "tax" to 7, "discount" to 7, "saving" to 5, "tip" to 4)

    fun extract(blocks: List<OcrBlockRecord>): List<OcrEntityRecord> {
        val entities = mutableListOf<OcrEntityRecord>()
        blocks.forEach { block ->
            amount.findAll(block.text).forEach { match ->
                val value = match.groupValues[1].ifBlank { match.groupValues[2] }
                entities += entity(OcrEntityType.AMOUNT, match.value, normalizeAmount(value), null, block, .88f)
            }
            findAll(date, block).forEach { (raw, _) -> entities += entity(OcrEntityType.DATE, raw, raw.uppercase(Locale.ROOT), null, block, .86f) }
            findAll(email, block).forEach { (raw, _) -> entities += entity(OcrEntityType.EMAIL, raw, raw.lowercase(Locale.ROOT), null, block, .96f) }
            findAll(url, block).forEach { (raw, _) -> entities += entity(OcrEntityType.URL, raw, raw.lowercase(Locale.ROOT), null, block, .94f) }
            findAll(phone, block).forEach { (raw, _) -> entities += entity(OcrEntityType.PHONE, raw, raw.filter(Char::isDigit), null, block, .82f) }
            findAll(order, block, 1).forEach { (raw, value) -> entities += entity(OcrEntityType.ORDER_ID, raw, value.uppercase(Locale.ROOT), "order_id", block, .91f) }
            findAll(flight, block, 1).forEach { (raw, value) -> entities += entity(OcrEntityType.FLIGHT_NUMBER, raw, value.replace(" ", "").uppercase(Locale.ROOT), "flight", block, .92f) }
            findAll(password, block, 1).forEach { (raw, value) -> entities += entity(OcrEntityType.PASSWORD, raw, value, "password", block, .94f) }
        }
        receiptTotal(blocks)?.let(entities::add)
        merchant(blocks)?.let(entities::add)
        return entities.distinctBy { listOf(it.type, it.normalizedValue, it.left, it.top) }
    }

    fun receiptTotal(blocks: List<OcrBlockRecord>): OcrEntityRecord? = blocks.mapNotNull { block ->
        val lower = block.normalizedText
        val match = amount.find(block.text) ?: return@mapNotNull null
        val value = match.groupValues[1].ifBlank { match.groupValues[2] }
        var score = positiveTotal.entries.sumOf { (word, weight) -> if (word in lower) weight else 0 }
        score -= negativeTotal.entries.sumOf { (word, weight) -> if (word in lower) weight else 0 }
        score += (block.top * 2f).toInt()
        if (match.value.contains(Regex("(?i)\\u20B9|rs|inr|usd|\\$"))) score += 1
        if (score <= 0) return@mapNotNull null
        Triple(score, block, match.value to value)
    }.maxWithOrNull(compareBy<Triple<Int, OcrBlockRecord, Pair<String, String>>> { it.first }.thenBy { it.second.top })?.let { (_, block, pair) ->
        val label = positiveTotal.keys.firstOrNull { it in block.normalizedText } ?: "total"
        entity(OcrEntityType.RECEIPT_TOTAL, pair.first, normalizeAmount(pair.second), label.replace(' ', '_'), block, (.72f + block.confidence * .25f).coerceAtMost(.98f))
    }

    private fun merchant(blocks: List<OcrBlockRecord>): OcrEntityRecord? {
        val block = blocks.asSequence().filter { it.text.length in 3..80 }.firstOrNull {
            val lower = it.normalizedText
            it.top < .35f && positiveTotal.keys.none(lower::contains) && amount.find(it.text) == null && date.find(it.text) == null &&
                listOf("synthetic test receipt", "receipt", "invoice").none { marker -> lower == marker }
        } ?: return null
        return entity(OcrEntityType.MERCHANT, block.text, block.text.trim().uppercase(Locale.ROOT), "merchant", block, .72f)
    }

    private fun findAll(regex: Regex, block: OcrBlockRecord, valueGroup: Int = 0): List<Pair<String, String>> = regex.findAll(block.text).map { match ->
        match.value to if (valueGroup == 0) match.value else match.groupValues[valueGroup]
    }.toList()

    private fun normalizeAmount(raw: String): String = runCatching {
        BigDecimal(raw.replace(",", "")).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
    }.getOrElse { raw.replace(",", "") }

    private fun entity(type: OcrEntityType, raw: String, normalized: String, label: String?, block: OcrBlockRecord, confidence: Float) = OcrEntityRecord(
        type = type,
        rawText = raw,
        normalizedValue = normalized,
        label = label,
        confidence = minOf(confidence, block.confidence.coerceIn(.1f, 1f)),
        left = block.left,
        top = block.top,
        right = block.right,
        bottom = block.bottom,
        producerVersion = PRODUCER,
    )
}
