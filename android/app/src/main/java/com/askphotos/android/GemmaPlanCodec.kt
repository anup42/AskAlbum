package com.askphotos.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Strict model-output boundary. The model can fill this schema; it cannot name tools, SQL, URIs, or result IDs. */
class GemmaPlanCodec(private val validator: GalleryQueryPlanValidator = GalleryQueryPlanValidator()) {
    fun decode(query: String, response: String, activeResultIds: Set<String>?): GalleryQueryPlan {
        val json = parseSingleObject(response)
        json.requireOnly(
            "version", "intent", "mediaScope", "filter", "semanticClauses", "peopleClauses", "ocrClause",
            "grouping", "aggregation", "sort", "verification", "answerMode", "limit", "terms", "place",
        )
        val intent = enum<QueryIntent>(json, "intent")
        val semantic = json.optJSONArray("semanticClauses")?.objects(MAX_SEMANTIC_CLAUSES) { item ->
            item.requireOnly("text", "canonicalText", "polarity", "hardness", "subject", "relationToPerson")
            SemanticClause(
                text = item.getString("text").trim(),
                canonicalText = item.optNullableString("canonicalText"),
                polarity = item.optEnum("polarity", Polarity.POSITIVE),
                hardness = item.optEnum("hardness", ConstraintStrength.SOFT),
                subject = item.optEnum("subject", SemanticSubject.WHOLE_MEDIA),
                relationToPerson = item.optNullableString("relationToPerson"),
            )
        }.orEmpty()
        val terms = json.optJSONArray("terms")?.strings(MAX_TERMS).orEmpty().map { it.lowercase(Locale.ROOT) }.distinct()
        val finalTerms = if (terms.isNotEmpty()) terms else semantic.mapNotNull { it.canonicalText ?: it.text }.map { it.lowercase(Locale.ROOT) }.distinct()
        val followUp = FollowUpLanguage.isFollowUp(query)
        require(finalTerms.isNotEmpty() || followUp || intent in setOf(QueryIntent.COUNT, QueryIntent.LIST, QueryIntent.TIMELINE)) {
            "Planner produced no searchable constraints"
        }
        val people = json.optJSONArray("peopleClauses")?.objects(MAX_PEOPLE_CLAUSES) { item ->
            item.requireOnly("personId", "mustBePresent", "hardness")
            PersonClause(
                personId = item.getString("personId"),
                mustBePresent = item.optBoolean("mustBePresent", true),
                hardness = item.optEnum("hardness", ConstraintStrength.HARD),
            )
        }.orEmpty()
        val ocr = json.optJSONObject("ocrClause")?.let { item ->
            item.requireOnly("query", "merchant", "requestedField")
            OcrClause(item.optNullableString("query"), item.optNullableString("merchant"), item.optNullableString("requestedField"))
        }
        val aggregation = json.optJSONObject("aggregation")?.let { item ->
            item.requireOnly("operation", "field")
            AggregationSpec(enum(item, "operation"), item.optNullableString("field"))
        }
        val plan = GalleryQueryPlan(
            version = json.optInt("version", 1),
            originalQuery = query,
            intent = intent,
            mediaScope = json.optEnum("mediaScope", MediaScope.ALL),
            filter = json.optJSONObject("filter")?.let(::parseFilter) ?: FilterExpression.True,
            semanticClauses = semantic.ifEmpty { finalTerms.map { SemanticClause(it, it) } },
            peopleClauses = people,
            ocrClause = ocr,
            grouping = json.optEnum("grouping", Grouping.NONE),
            aggregation = aggregation ?: defaultAggregation(intent),
            sort = json.optEnum("sort", SortSpec.RELEVANCE),
            verification = json.optEnum("verification", VerificationPolicy.AUTO),
            answerMode = json.optEnum("answerMode", AnswerMode.RESULTS_AND_SUMMARY),
            terms = finalTerms,
            place = json.optNullableString("place"),
            baseResultIds = if (followUp) activeResultIds else null,
            limit = json.optInt("limit", 100),
        )
        return validator.requireValid(plan, if (followUp) activeResultIds else null)
    }

    fun repairPrompt(query: String, invalidResponse: String, error: String): String = """
        Your previous gallery-plan JSON was invalid. Repair it once and return one JSON object only.
        Error: ${JSONObject.quote(error.take(240))}
        Original query: ${JSONObject.quote(query)}
        Invalid response: ${JSONObject.quote(invalidResponse.take(1200))}
        Required shape: {"version":1,"intent":"FIND_MEDIA","mediaScope":"IMAGES","filter":{"op":"TRUE"},"semanticClauses":[],"peopleClauses":[],"grouping":"NONE","sort":"RELEVANCE","verification":"AUTO","answerMode":"RESULTS_AND_SUMMARY","limit":100,"terms":["search phrase"]}
        Use integer version 1. Copy numbers as uninterrupted decimal digits. Omit optional fields instead of guessing them.
        Do not add SQL, code, paths, URIs, tool names, result IDs, or fields outside the supplied schema.
    """.trimIndent()

    private fun parseFilter(json: JSONObject): FilterExpression {
        val op = json.getString("op")
        return when (op) {
            "TRUE" -> {
                json.requireOnly("op")
                FilterExpression.True
            }
            "AND" -> {
                json.requireOnly("op", "clauses")
                FilterExpression.And(json.getJSONArray("clauses").objects(MAX_FILTER_CLAUSES, ::parseFilter))
            }
            "TIME_RANGE" -> {
                json.requireOnly("op", "startEpochMs", "endEpochMs")
                FilterExpression.TimeRange(json.optNullableLong("startEpochMs"), json.optNullableLong("endEpochMs"))
            }
            "MEDIA_KIND" -> {
                json.requireOnly("op", "kind")
                FilterExpression.MediaKindIs(enum(json, "kind"))
            }
            "ALBUM" -> {
                json.requireOnly("op", "album")
                FilterExpression.AlbumIs(json.getString("album"))
            }
            else -> error("Unsupported filter operation")
        }
    }

    private fun parseSingleObject(text: String): JSONObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Planner must return one JSON object" }
        return JSONObject(trimmed)
    }

    private inline fun <reified T : Enum<T>> enum(json: JSONObject, key: String): T = enumValueOf(json.getString(key))
    private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, default: T): T =
        optNullableString(key)?.let { enumValueOf<T>(it) } ?: default

    private fun defaultAggregation(intent: QueryIntent): AggregationSpec? = when (intent) {
        QueryIntent.COUNT -> AggregationSpec(AggregationOperation.COUNT)
        QueryIntent.SUM -> AggregationSpec(AggregationOperation.SUM)
        QueryIntent.MIN_MAX -> AggregationSpec(AggregationOperation.MIN_MAX)
        else -> null
    }

    private companion object {
        const val MAX_TERMS = 16
        const val MAX_SEMANTIC_CLAUSES = 16
        const val MAX_PEOPLE_CLAUSES = 8
        const val MAX_FILTER_CLAUSES = 12
    }
}

object FollowUpLanguage {
    private val prefixes = listOf(
        "only ", "what about ", "with ", "without ", "which ", "best ", "and now ", "same but ",
        "sirf ", "bas ", "keval ", "केवल ", "सिर्फ़ ", "सिर्फ ",
    )
    fun isFollowUp(query: String): Boolean = prefixes.any(query.trim().lowercase(Locale.ROOT)::startsWith)
}

private fun JSONObject.requireOnly(vararg allowed: String): JSONObject {
    require(keys().asSequence().all { it in allowed }) { "Planner emitted unsupported fields" }
    return this
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).trim().takeIf(String::isNotBlank)

private fun JSONObject.optNullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

private fun JSONArray.strings(max: Int): List<String> {
    require(length() <= max) { "Planner array exceeds its bound" }
    return List(length()) { index -> getString(index).trim().also { require(it.isNotBlank()) } }
}

private fun <T> JSONArray.objects(max: Int, transform: (JSONObject) -> T): List<T> {
    require(length() <= max) { "Planner array exceeds its bound" }
    return List(length()) { index -> transform(getJSONObject(index)) }
}
