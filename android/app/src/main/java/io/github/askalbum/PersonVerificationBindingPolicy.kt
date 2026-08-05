package io.github.anup42.askalbum

internal object PersonVerificationBindingPolicy {
    fun conditionPersonIds(conditions: List<VerificationConditionSpec>): Set<String> =
        conditions.mapNotNull { it.relationToPerson?.trim()?.takeIf(String::isNotBlank) }.toSet()

    fun allConditionPeopleBound(
        conditionPersonIds: Set<String>,
        bindings: List<PersonVerificationBinding>,
    ): Boolean = conditionPersonIds.all { requested ->
        bindings.count { binding ->
            binding.clusterId.equals(requested, ignoreCase = true) ||
                binding.identityTerms.any { term -> term.equals(requested, ignoreCase = true) }
        } == 1
    }
}
