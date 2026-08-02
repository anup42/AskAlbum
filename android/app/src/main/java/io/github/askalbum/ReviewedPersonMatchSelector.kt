package io.github.anup42.askalbum

import java.text.Normalizer
import java.util.Locale
import java.util.UUID

internal data class ReviewedPersonMatchCandidate(
    val personId: String,
    val matchedIdentityTerms: List<String>,
    val faceCount: Int,
    val updatedAt: Long,
)

internal data class ReviewedPersonMatchGroup(
    val alternativeGroup: String,
    val personIds: Set<String>,
)

/**
 * Groups duplicate reviewed labels/relationships as alternative clusters for one identity.
 *
 * Different identity terms remain separate requirements. Multiple clusters carrying the same
 * reviewed term are alternatives until the user explicitly merges or renames them.
 */
internal object ReviewedPersonMatchSelector {
    fun group(candidates: List<ReviewedPersonMatchCandidate>): List<ReviewedPersonMatchGroup> {
        val idsByIdentityTerm = linkedMapOf<String, LinkedHashSet<String>>()
        candidates
            .sortedWith(
                compareByDescending<ReviewedPersonMatchCandidate> { it.faceCount }
                    .thenByDescending { it.updatedAt }
                    .thenBy { it.personId },
            )
            .forEach { candidate ->
                candidate.matchedIdentityTerms
                    .asSequence()
                    .map(::identityKey)
                    .filter(String::isNotBlank)
                    .forEach { term ->
                        idsByIdentityTerm.getOrPut(term, ::linkedSetOf).add(candidate.personId)
                    }
            }
        return idsByIdentityTerm.map { (term, personIds) ->
            ReviewedPersonMatchGroup(
                alternativeGroup = "identity_" + UUID.nameUUIDFromBytes(term.toByteArray(Charsets.UTF_8))
                    .toString()
                    .replace("-", ""),
                personIds = personIds,
            )
        }
    }

    private fun identityKey(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)
}
