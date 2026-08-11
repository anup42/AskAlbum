package io.github.anup42.askalbum

/**
 * Corrects only explicit, locally parseable person visual predicates. This is a deterministic
 * safety overlay over Gemma planning: it never guesses an identity and leaves ambiguous or
 * unknown references untouched so the existing People gate can fail closed.
 */
internal object PersonConditionCanonicalizationPolicy {
    private data class Token(val value: String)
    private data class VisualVerb(val index: Int, val family: String)
    private data class ExplicitCondition(
        val personId: String,
        val text: String,
        val polarity: Polarity,
        val family: String,
        val attributes: Set<String>,
    )

    private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val visualVerbFamilies = mapOf(
        "wear" to "wear",
        "wears" to "wear",
        "wearing" to "wear",
        "wore" to "wear",
        "hold" to "hold",
        "holds" to "hold",
        "holding" to "hold",
        "held" to "hold",
        "carry" to "carry",
        "carries" to "carry",
        "carrying" to "carry",
        "carried" to "carry",
        "use" to "use",
        "uses" to "use",
        "using" to "use",
        "used" to "use",
        "stand" to "stand",
        "stands" to "stand",
        "standing" to "stand",
        "sit" to "sit",
        "sits" to "sit",
        "sitting" to "sit",
        "interact" to "interact",
        "interacts" to "interact",
        "interacting" to "interact",
    )
    private val auxiliaries = setOf("am", "is", "are", "was", "were", "has", "have", "had", "been", "being")
    private val negations = setOf("not", "never", "without", "no")
    private val attributeGlue = setOf(
        "a", "an", "the", "and", "but", "or", "with", "where", "while", "who", "that",
        "am", "is", "are", "was", "were", "has", "have", "had", "been", "being", "my",
        "photo", "photos", "picture", "pictures", "image", "images", "show", "find", "display",
    ) + visualVerbFamilies.keys + negations
    private val trailingQueryWords = setOf(
        "photo", "photos", "picture", "pictures", "image", "images", "show", "find", "display", "please",
        "dikhao",
    )
    private val firstPersonClauseAliases = setOf("me", "myself", "i", "user", "self")
    private val possessiveFirstPersonTokens = setOf(
        "my", "mine", "mera", "mere", "meri", "\u092e\u0947\u0930\u093e", "\u092e\u0947\u0930\u0947", "\u092e\u0947\u0930\u0940",
    ).map(PersonIdentityNormalization::normalize).toSet()
    private val firstPersonSubjectTokens = setOf("i", "main", "mai", "\u092e\u0948\u0902")
        .map(PersonIdentityNormalization::normalize)
        .toSet()
    private val captureAuthorshipVerbs = setOf(
        "take", "takes", "taking", "took", "taken",
        "capture", "captures", "capturing", "captured",
        "shoot", "shoots", "shooting", "shot",
        "record", "records", "recording", "recorded",
        "save", "saves", "saving", "saved",
        "download", "downloads", "downloading", "downloaded",
        "scan", "scans", "scanning", "scanned",
        "create", "creates", "creating", "created",
        "receive", "receives", "receiving", "received",
    )
    private val imperativeRecipientVerbs = setOf("show", "find", "display", "give")
    private val presenceMarkers = setOf("with", "beside", "alongside", "near", "at", "in", "and", "of")

    fun apply(
        query: String,
        plan: GalleryQueryPlan,
        resolveReviewedIds: (String) -> Set<String>,
    ): GalleryQueryPlan {
        val canonicalClauses = plan.semanticClauses.map { clause ->
            val canonicalPerson = clause.relationToPerson
                ?.let(resolveReviewedIds)
                ?.singleOrNull()
            if (canonicalPerson == null) clause else clause.copy(relationToPerson = canonicalPerson)
        }.toMutableList()
        val explicit = detect(query, resolveReviewedIds)
        val canonicalPeople = removeNonPresenceFirstPersonClauses(
            query = query,
            clauses = plan.peopleClauses,
            explicitConditions = explicit,
            resolveReviewedIds = resolveReviewedIds,
        )
        if (explicit.isEmpty()) {
            return plan.copy(peopleClauses = canonicalPeople, semanticClauses = canonicalClauses)
        }

        val claimed = mutableSetOf<Int>()
        explicit.forEach { condition ->
            val matchingIndex = canonicalClauses.indices
                .filterNot(claimed::contains)
                .filter { index -> matches(canonicalClauses[index], condition) }
                .maxByOrNull { index -> matchScore(canonicalClauses[index], condition) }
            if (matchingIndex == null) {
                canonicalClauses += SemanticClause(
                    text = condition.text,
                    canonicalText = condition.text,
                    polarity = condition.polarity,
                    hardness = ConstraintStrength.HARD,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = condition.personId,
                )
            } else {
                claimed += matchingIndex
                canonicalClauses[matchingIndex] = canonicalClauses[matchingIndex].copy(
                    polarity = condition.polarity,
                    hardness = ConstraintStrength.HARD,
                    subject = SemanticSubject.PERSON,
                    relationToPerson = condition.personId,
                )
            }
        }
        return plan.copy(peopleClauses = canonicalPeople, semanticClauses = canonicalClauses)
    }

    private fun removeNonPresenceFirstPersonClauses(
        query: String,
        clauses: List<PersonClause>,
        explicitConditions: List<ExplicitCondition>,
        resolveReviewedIds: (String) -> Set<String>,
    ): List<PersonClause> {
        if (clauses.isEmpty()) return clauses
        val tokens = tokenPattern.findAll(PersonIdentityNormalization.normalize(query))
            .map { Token(it.value) }
            .toList()
        if (!hasNonPresenceFirstPersonContext(tokens) || hasExplicitFirstPersonPresence(tokens)) return clauses

        val reviewedSelfIds = resolveReviewedIds("me")
        if (explicitConditions.any { it.personId in reviewedSelfIds }) return clauses
        return clauses.filterNot { clause ->
            clause.personId in reviewedSelfIds ||
                PersonIdentityNormalization.normalize(clause.personId) in firstPersonClauseAliases
        }
    }

    private fun hasNonPresenceFirstPersonContext(tokens: List<Token>): Boolean {
        if (tokens.any { it.value in possessiveFirstPersonTokens }) return true
        if (tokens.indices.any { index ->
                tokens[index].value in firstPersonSubjectTokens &&
                    tokens.subList(index + 1, minOf(tokens.size, index + AUTHORSHIP_LOOKAHEAD + 1))
                        .any { it.value in captureAuthorshipVerbs }
            }
        ) {
            return true
        }
        return tokens.indices.any { index ->
            tokens[index].value == "me" && index > 0 && tokens[index - 1].value in imperativeRecipientVerbs
        }
    }

    private fun hasExplicitFirstPersonPresence(tokens: List<Token>): Boolean = tokens.indices.any { index ->
        val token = tokens[index].value
        if (PeopleQueryReferenceDetector.canonicalReference(token) != "me") return@any false
        if (token == "myself") return@any true

        val before = tokens.getOrNull(index - 1)?.value
        if (before in presenceMarkers) return@any true
        val after = tokens.subList(index + 1, minOf(tokens.size, index + PRESENCE_LOOKAHEAD + 1))
            .map(Token::value)
        val firstMeaningful = after.firstOrNull { it !in auxiliaries }
        firstMeaningful in presenceMarkers || after.any { it in visualVerbFamilies }
    }

    private fun detect(
        query: String,
        resolveReviewedIds: (String) -> Set<String>,
    ): List<ExplicitCondition> {
        val tokens = tokenPattern.findAll(PersonIdentityNormalization.normalize(query))
            .map { Token(it.value) }
            .toList()
        val verbs = tokens.mapIndexedNotNull { index, token ->
            visualVerbFamilies[token.value]?.let { VisualVerb(index, it) }
        }
        return verbs.mapNotNull { verb ->
            val subject = resolveSubject(tokens, verb.index, resolveReviewedIds) ?: return@mapNotNull null
            val predicateTokens = tokens.subList(verb.index, predicateEnd(tokens, verb.index, verbs))
                .map(Token::value)
                .toMutableList()
                .also { values -> while (values.lastOrNull() in trailingQueryWords) values.removeAt(values.lastIndex) }
            if (predicateTokens.isEmpty()) return@mapNotNull null
            val polarity = if (
                tokens.subList(maxOf(0, verb.index - NEGATION_LOOKBACK), verb.index)
                    .any { it.value in negations }
            ) {
                Polarity.NEGATIVE
            } else {
                Polarity.POSITIVE
            }
            val attributes = predicateTokens.filterNot(attributeGlue::contains).toSet()
            ExplicitCondition(
                personId = subject,
                text = predicateTokens.joinToString(" "),
                polarity = polarity,
                family = verb.family,
                attributes = attributes,
            )
        }.distinctBy { listOf(it.personId, it.text, it.polarity.name) }
    }

    private fun resolveSubject(
        tokens: List<Token>,
        verbIndex: Int,
        resolveReviewedIds: (String) -> Set<String>,
    ): String? {
        val minimum = maxOf(0, verbIndex - MAX_SUBJECT_DISTANCE)
        for (end in verbIndex - 1 downTo minimum) {
            if (tokens[end].value in auxiliaries || tokens[end].value in negations) continue
            for (length in 1..MAX_SUBJECT_TOKENS) {
                val start = end - length + 1
                if (start < minimum) break
                val phrase = tokens.subList(start, end + 1).joinToString(" ") { it.value }
                val canonical = if (length == 1) PeopleQueryReferenceDetector.canonicalReference(phrase) else null
                val resolved = resolveReviewedIds(canonical ?: phrase)
                if (resolved.size == 1) return resolved.single()
            }
        }
        return null
    }

    private fun predicateEnd(tokens: List<Token>, verbIndex: Int, verbs: List<VisualVerb>): Int {
        val nextVerb = verbs.firstOrNull { it.index > verbIndex } ?: return tokens.size
        val boundary = (verbIndex + 1 until nextVerb.index).indexOfFirst { index ->
            tokens[index].value in setOf("but", "while", "where")
        }
        return if (boundary < 0) nextVerb.index else verbIndex + 1 + boundary
    }

    private fun matches(clause: SemanticClause, condition: ExplicitCondition): Boolean {
        if (clause.subject != SemanticSubject.PERSON && clause.relationToPerson == null) return false
        val clauseTokens = semanticTokens(clause)
        val family = clauseTokens.firstNotNullOfOrNull(visualVerbFamilies::get) ?: return false
        if (family != condition.family) return false
        val attributes = clauseTokens.filterNot(attributeGlue::contains).toSet()
        return condition.attributes.isEmpty() || attributes.isEmpty() || attributes.intersect(condition.attributes).isNotEmpty()
    }

    private fun matchScore(clause: SemanticClause, condition: ExplicitCondition): Int {
        val attributes = semanticTokens(clause).filterNot(attributeGlue::contains).toSet()
        return attributes.intersect(condition.attributes).size
    }

    private fun semanticTokens(clause: SemanticClause): List<String> =
        tokenPattern.findAll(
            PersonIdentityNormalization.normalize(listOfNotNull(clause.text, clause.canonicalText).joinToString(" ")),
        ).map(MatchResult::value).toList()

    private const val MAX_SUBJECT_DISTANCE = 6
    private const val MAX_SUBJECT_TOKENS = 3
    private const val NEGATION_LOOKBACK = 3
    private const val AUTHORSHIP_LOOKAHEAD = 4
    private const val PRESENCE_LOOKAHEAD = 4
}
