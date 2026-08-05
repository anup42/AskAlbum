package io.github.anup42.askalbum

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Strict model-output boundary. The model can fill this schema; it cannot name tools, SQL, URIs, or result IDs. */
class GemmaPlanCodec(private val validator: GalleryQueryPlanValidator = GalleryQueryPlanValidator()) {
    fun decode(query: String, response: String, activeResultIds: Set<String>?): GalleryQueryPlan {
        val json = parseSingleObject(response)
        json.requireOnly(
            "version", "intent", "followUp", "mediaScope", "filter", "semanticClauses", "peopleClauses", "ocrClause",
            "grouping", "aggregation", "sort", "verification", "answerMode", "limit", "terms", "place", "comparisonScopes",
        )
        val intent = enum<QueryIntent>(json, "intent")
        val semantic = json.optJSONArray("semanticClauses")?.objects(MAX_SEMANTIC_CLAUSES) { item ->
            item.requireOnly("text", "canonicalText", "polarity", "hardness", "subject", "relationToPerson")
            SemanticPolarityNormalizer.normalize(SemanticClause(
                text = item.getString("text").trim(),
                canonicalText = item.optNullableString("canonicalText"),
                polarity = item.optEnum("polarity", Polarity.POSITIVE),
                hardness = item.optEnum("hardness", ConstraintStrength.SOFT),
                subject = item.optEnum("subject", SemanticSubject.WHOLE_MEDIA),
                relationToPerson = item.optNullableString("relationToPerson"),
            ))
        }.orEmpty()
        val filterJson = json.optJSONObject("filter")
        val terms = (
            json.optJSONArray("terms")?.strings(MAX_TERMS).orEmpty() +
                filterJson?.takeIf { it.isTermsOnlyObject() }?.filterTerms().orEmpty()
            ).map { it.lowercase(Locale.ROOT) }.distinct()
        val place = json.optNullableString("place")
        val normalizedPlace = place?.lowercase(Locale.ROOT)
        val structuralListSemantics = semantic.filterNot { clause ->
            intent == QueryIntent.LIST &&
                clause.subject == SemanticSubject.WHOLE_MEDIA &&
                clause.relationToPerson.isNullOrBlank() &&
                (clause.canonicalText ?: clause.text).trim().lowercase(Locale.ROOT) in LIST_STRUCTURAL_TERMS
        }
        val filteredTerms = terms.filterNot { term ->
            intent == QueryIntent.LIST && (term in LIST_STRUCTURAL_TERMS || term == normalizedPlace)
        }
        val finalTerms = if (filteredTerms.isNotEmpty()) {
            filteredTerms
        } else {
            structuralListSemantics.mapNotNull { it.canonicalText ?: it.text }
                .map { it.lowercase(Locale.ROOT) }
                .distinct()
        }
        val heuristicFollowUp = FollowUpLanguage.isFollowUp(query, !activeResultIds.isNullOrEmpty())
        val followUp = if (!json.has("followUp") || json.isNull("followUp")) {
            heuristicFollowUp
        } else {
            require(json.get("followUp") is Boolean) { "Planner followUp must be a boolean" }
            json.getBoolean("followUp")
        }
        if (followUp) require(!activeResultIds.isNullOrEmpty()) { "Follow-up requires an active result set" }
        require(finalTerms.isNotEmpty() || structuralListSemantics.isNotEmpty() || followUp || intent in setOf(QueryIntent.COUNT, QueryIntent.LIST, QueryIntent.TIMELINE, QueryIntent.COMPARE)) {
            "Planner produced no searchable constraints"
        }
        val comparisonScopes = json.optJSONArray("comparisonScopes")?.strings(MAX_COMPARISON_SCOPES).orEmpty()
        val people = json.optJSONArray("peopleClauses")?.objects(MAX_PEOPLE_CLAUSES) { item ->
            item.requireOnly("personId", "mustBePresent", "hardness")
            PersonClause(
                personId = item.getString("personId"),
                mustBePresent = item.optBoolean("mustBePresent", true),
                hardness = item.optEnum("hardness", ConstraintStrength.HARD),
            )
        }.orEmpty().let(PeopleClauseSanitizer::sanitize)
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
            filter = filterJson?.let { filterObject ->
                if (filterObject.isEmptyUnfilteredObject() || filterObject.isTermsOnlyObject()) FilterExpression.True else parseFilter(filterObject)
            } ?: FilterExpression.True,
            // Terms already drive lexical, concept, and original-query retrieval.
            // Do not turn ordinary terms into semantic predicates: doing so makes
            // deterministic OCR and aggregation plans look like bounded visual work.
            semanticClauses = structuralListSemantics,
            peopleClauses = people,
            ocrClause = ocr,
            grouping = json.optEnum("grouping", Grouping.NONE),
            aggregation = aggregation ?: defaultAggregation(intent),
            sort = json.optEnum("sort", SortSpec.RELEVANCE),
            verification = json.optEnum("verification", VerificationPolicy.AUTO),
            answerMode = json.optAnswerMode(),
            terms = finalTerms,
            place = place,
            comparisonScopes = comparisonScopes,
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
        The root field "verification" must be exactly one quoted scalar string: "AUTO", "REQUIRED", or "NEVER". It must never be an array or object.
        For ordinary category, scene, activity, place, event-name, or free-text search, use terms/place and return semanticClauses as []. Use semanticClauses only for relational, negative, comparative, or fine-grained visual conditions.
        Each semanticClauses item uses subject "WHOLE_MEDIA", "PERSON", "EVENT", or "DOCUMENT" only. Categories such as family, pet, trip, food, or clothing belong in text/canonicalText, never in subject.
        Use integer version 1. Copy numbers as uninterrupted decimal digits. Omit optional fields instead of guessing them.
        Do not add SQL, code, paths, URIs, tool names, result IDs, or fields outside the supplied schema.
    """.trimIndent()

    private fun parseFilter(json: JSONObject): FilterExpression {
        if (json.isEmptyUnfilteredObject() || json.isTermsOnlyObject()) return FilterExpression.True
        val op = json.filterOperation()
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

    private fun JSONObject.filterOperation(): String {
        val raw = if (has("op") && !isNull("op")) opt("op") as? String else null
        val explicit = raw?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true) && !it.equals("undefined", ignoreCase = true)
        }
        if (explicit != null) return explicit.uppercase(Locale.ROOT)
        return when {
            has("clauses") -> "AND"
            has("startEpochMs") || has("endEpochMs") -> "TIME_RANGE"
            has("kind") -> "MEDIA_KIND"
            has("album") -> "ALBUM"
            else -> error("Filter operation is required; fields=${keys().asSequence().toList().sorted().joinToString(",")}")
        }
    }

    private fun parseSingleObject(text: String): JSONObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        require(trimmed.startsWith('{') && trimmed.endsWith('}')) { "Planner must return one JSON object" }
        return JSONObject(trimmed)
    }

    private inline fun <reified T : Enum<T>> enum(json: JSONObject, key: String): T {
        val raw = json.getString(key)
        return enumValues<T>().singleOrNull { it.name == raw }
            ?: throw IllegalArgumentException(
                "\"$key\" must be one of ${enumValues<T>().joinToString(prefix = "[", postfix = "]") { it.name }}; received ${JSONObject.quote(raw)}",
            )
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, default: T): T =
        if (!has(key) || isNull(key)) default else enum(this, key)

    private fun JSONObject.optAnswerMode(): AnswerMode {
        if (!has("answerMode") || isNull("answerMode")) return AnswerMode.RESULTS_AND_SUMMARY
        val raw = getString("answerMode")
        return if (raw == "LIST") AnswerMode.RESULTS_AND_SUMMARY else
            enumValues<AnswerMode>().singleOrNull { it.name == raw }
                ?: throw IllegalArgumentException(
                    "\"answerMode\" must be one of ${enumValues<AnswerMode>().joinToString(prefix = "[", postfix = "]") { it.name }}; received ${JSONObject.quote(raw)}",
                )
    }

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
        const val MAX_COMPARISON_SCOPES = 4
        const val MAX_FILTER_CLAUSES = 12
        val LIST_STRUCTURAL_TERMS = setOf(
            "list", "show", "which", "place", "places", "location", "locations",
            "people", "persons", "day", "days", "date", "dates", "merchant", "merchants",
            "recent", "image", "images", "photo", "photos", "picture", "pictures",
        )
    }
}

object FollowUpLanguage {
    private val prefixes = listOf(
        "only ", "what about ", "with ", "without ", "which ", "best ", "and now ", "same but ",
        "sirf ", "bas ", "keval ", "केवल ", "सिर्फ़ ", "सिर्फ ",
    )
    private val contextualForms = listOf(
        Regex("""^(?:now|same\b|exclude\b|excluding\b|remove\b|clear\b|show\s+close[- ]?ups?\b)"""),
        Regex("""^(?:अब|वही|हटाओ|निकालो)\b"""),
    )

    private val naturalRefinements = listOf(
        Regex("""\b(?:make|keep|turn)\s+(?:them|these|those)\b"""),
        Regex("""\b(?:same\s+(?:event|trip)|from\s+those|among\s+them)\b"""),
        Regex("""\b(?:show|give)\s+(?:me\s+)?(?:the\s+)?same\b"""),
    )
    private val mediaScopeRefinement = Regex(
        """\b(?:same\s+(?:event|trip)|from\s+those|among\s+them)\b.*\b(?:photos?|pictures?|images?|videos?)\b""",
        RegexOption.IGNORE_CASE,
    )

    fun isFollowUp(query: String, activeResultAvailable: Boolean = false): Boolean {
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (prefixes.any(normalized::startsWith)) return true
        return activeResultAvailable && (
            contextualForms.any { it.containsMatchIn(normalized) } ||
                naturalRefinements.any { it.containsMatchIn(normalized) }
        )
    }

    fun permitsMediaScopeRefinement(query: String): Boolean = mediaScopeRefinement.containsMatchIn(query)
}

private fun JSONObject.requireOnly(vararg allowed: String): JSONObject {
    require(keys().asSequence().all { it in allowed }) { "Planner emitted unsupported fields" }
    return this
}

private fun JSONObject.optNullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).trim().takeIf(String::isNotBlank)

private fun JSONObject.optNullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)

private fun JSONObject.isEmptyUnfilteredObject(): Boolean {
    if (length() == 0) return true
    if (length() != 1 || !has("op")) return false
    if (isNull("op")) return true
    val operation = opt("op") as? String ?: return false
    return operation.isBlank() || operation.equals("null", ignoreCase = true) || operation.equals("undefined", ignoreCase = true)
}

private fun JSONObject.isTermsOnlyObject(): Boolean =
    length() == 1 && has("terms")

private fun JSONObject.filterTerms(): List<String> = when (val raw = opt("terms")) {
    is JSONArray -> raw.strings(16)
    is String -> listOf(raw.trim()).filter(String::isNotBlank)
    else -> emptyList()
}

private fun JSONArray.strings(max: Int): List<String> {
    require(length() <= max) { "Planner array exceeds its bound" }
    return List(length()) { index -> getString(index).trim().also { require(it.isNotBlank()) } }
}

private fun <T> JSONArray.objects(max: Int, transform: (JSONObject) -> T): List<T> {
    require(length() <= max) { "Planner array exceeds its bound" }
    return List(length()) { index -> transform(getJSONObject(index)) }
}
