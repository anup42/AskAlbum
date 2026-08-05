package io.github.anup42.askalbum

internal object PersonVerificationBindingPolicy {
    fun conditionPersonIds(conditions: List<VerificationConditionSpec>): Set<String> =
        conditions.mapNotNull { it.relationToPerson?.trim()?.takeIf(String::isNotBlank) }.toSet()

    fun matchesRequestedIdentity(binding: PersonVerificationBinding, requested: String): Boolean {
        val normalizedRequested = PersonIdentityNormalization.normalize(requested)
        if (normalizedRequested.isBlank()) return false
        return PersonIdentityNormalization.normalize(binding.clusterId) == normalizedRequested ||
            binding.identityTerms.any { PersonIdentityNormalization.normalize(it) == normalizedRequested }
    }

    fun allConditionPeopleBound(
        conditionPersonIds: Set<String>,
        bindings: List<PersonVerificationBinding>,
    ): Boolean = conditionPersonIds.all { requested ->
        bindings.count { binding -> matchesRequestedIdentity(binding, requested) } == 1
    }
}
