package io.github.anup42.askalbum

/**
 * Adds only well-known identity references when a planner is unavailable. User-defined names
 * and aliases remain database-resolved so an unreviewed cluster can never become an identity.
 */
internal object PeopleQueryReferenceDetector {
    private data class Token(val value: String)

    private val tokenPattern = Regex("[\\p{L}\\p{M}\\p{N}]+")
    private val canonicalReferences = listOf(
        "me", "myself", "wife", "husband", "spouse", "partner", "mother", "mom", "mum",
        "father", "dad", "brother", "sister", "child", "son", "daughter", "friend",
        "\u092d\u0948\u092f\u093e", // bhaiya
        "\u092d\u093e\u0908", // bhai
        "\u0926\u0940\u0926\u0940", // didi
        "\u092c\u0939\u0928", // sister
        "\u092a\u0924\u094d\u0928\u0940", // wife
        "\u092c\u0940\u0935\u0940", // wife
        "\u092a\u0924\u093f", // husband
        "\u092a\u092a\u094d\u092a\u093e", // papa
        "\u092a\u093f\u0924\u093e", // father
        "\u092e\u092e\u094d\u092e\u0940", // mummy
        "\u092e\u093e\u0901", // mother
        "\u092c\u0947\u091f\u093e", // son
        "\u092c\u0947\u091f\u0940", // daughter
        "\u092c\u091a\u094d\u091a\u093e", // child
    ).associate { reference ->
        PersonIdentityNormalization.normalize(reference) to PersonIdentityNormalization.normalize(reference)
    }
    private val referenceAliases = canonicalReferences + listOf(
        "i",
        "main",
        "mai",
        "\u092e\u0948\u0902", // I / me
    ).associate { alias -> PersonIdentityNormalization.normalize(alias) to "me" }

    private val firstPersonSubjectAliases = setOf("i", "main", "mai", "\u092e\u0948\u0902")
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
    private val requestRecipientVerbs = setOf("show", "find", "give", "display", "open", "locate")
    private val explicitFirstPersonContinuations = setOf(
        "and", "with", "wear", "wears", "wearing", "wore", "hold", "holds", "holding",
        "carry", "carries", "carrying", "use", "uses", "using", "stand", "stands", "standing",
        "sit", "sits", "sitting", "dance", "dances", "dancing", "run", "runs", "running",
    )

    private val negationTerms = setOf(
        "not", "no", "without", "exclude", "excluding", "except",
        "\u092c\u093f\u0928\u093e", // without
        "\u0928\u0939\u0940\u0902", // not
        "\u0928\u0939\u0940", // not
    ).map(PersonIdentityNormalization::normalize).toSet()
    private val postfixNegationTerms = setOf("\u092c\u093f\u0928\u093e", "bina")
        .map(PersonIdentityNormalization::normalize)
        .toSet()

    fun detect(query: String): List<PersonClause> {
        val tokens = tokenPattern.findAll(PersonIdentityNormalization.normalize(query))
            .map { Token(it.value) }
            .toList()
        if (tokens.isEmpty()) return emptyList()

        return referenceAliases.flatMap { (queryToken, personReference) ->
            val positions = tokens.withIndex()
                .filter { (_, token) -> token.value == queryToken }
                .map { it.index }
                .filterNot { index -> isCaptureAuthorshipReference(tokens, index, queryToken) }
                .filterNot { index -> isRequestRecipientReference(tokens, index, queryToken) }
            if (positions.isEmpty()) return@flatMap emptyList()

            val polarities = positions.map { index ->
                val start = maxOf(0, index - NEGATION_LOOKBACK)
                val end = minOf(tokens.size, index + NEGATION_LOOKBACK + 1)
                tokens.subList(start, index).none { it.value in negationTerms } &&
                    tokens.subList(index + 1, end).none { it.value in postfixNegationTerms }
            }
            when {
                polarities.all { !it } -> listOf(PersonClause(personReference, mustBePresent = false))
                polarities.all { it } -> listOf(PersonClause(personReference))
                else -> listOf(
                    // Contradictory mentions fail closed instead of broadening the search.
                    PersonClause(personReference),
                    PersonClause(personReference, mustBePresent = false),
                )
            }
        }.distinctBy { it.personId to it.mustBePresent }
    }

    fun canonicalReference(token: String): String? =
        referenceAliases[PersonIdentityNormalization.normalize(token)]

    private fun isCaptureAuthorshipReference(tokens: List<Token>, index: Int, queryToken: String): Boolean {
        if (queryToken !in firstPersonSubjectAliases) return false
        val end = minOf(tokens.size, index + AUTHORSHIP_LOOKAHEAD + 1)
        return tokens.subList(index + 1, end).any { it.value in captureAuthorshipVerbs }
    }

    private fun isRequestRecipientReference(tokens: List<Token>, index: Int, queryToken: String): Boolean {
        if (queryToken != "me" || index == 0 || tokens[index - 1].value !in requestRecipientVerbs) return false
        return tokens.getOrNull(index + 1)?.value !in explicitFirstPersonContinuations
    }

    private const val NEGATION_LOOKBACK = 3
    private const val AUTHORSHIP_LOOKAHEAD = 4
}
