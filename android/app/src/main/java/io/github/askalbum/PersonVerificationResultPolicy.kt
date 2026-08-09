package io.github.anup42.askalbum

internal data class ResolvedVerificationCondition(
    val spec: VerificationConditionSpec,
    val evaluation: VerificationConditionEvaluation,
    val binding: PersonVerificationBinding?,
    val matched: Boolean,
)

/** Keeps identity binding and polarity decisions deterministic after Gemma decoding. */
internal object PersonVerificationResultPolicy {
    fun resolve(
        conditions: List<VerificationConditionSpec>,
        evaluations: List<VerificationConditionEvaluation>,
        bindings: List<PersonVerificationBinding>,
    ): List<ResolvedVerificationCondition> = evaluations.map { evaluation ->
        val spec = conditions.single { it.id == evaluation.id }
        val binding = spec.relationToPerson?.let { clusterId ->
            bindings.singleOrNull { it.clusterId == clusterId }
        }
        ResolvedVerificationCondition(
            spec = spec,
            evaluation = evaluation,
            binding = binding,
            matched = SemanticPolarityNormalizer.conditionMatched(spec, evaluation),
        )
    }
}
