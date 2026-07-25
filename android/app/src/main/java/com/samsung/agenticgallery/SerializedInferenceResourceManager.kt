package com.samsung.agenticgallery

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single high-memory inference lease. Interactive orchestration can later add
 * priority and thermal policies without model implementations coordinating directly.
 */
class SerializedInferenceResourceManager : InferenceResourceManager {
    private val lease = Mutex()

    override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T =
        lease.withLock { block() }
}

class GroundedClaimValidator {
    fun validate(answer: SearchAnswer, availableEvidence: Collection<EvidenceRecord>): SearchAnswer {
        val knownIds = availableEvidence.mapTo(mutableSetOf()) { it.id }
        val supportedClaims = answer.claims.filter { claim ->
            claim.evidenceIds.isNotEmpty() && claim.evidenceIds.all { it in knownIds }
        }
        val safeEvidenceIds = answer.evidenceIds.filter { it in knownIds }.distinct()
        return answer.copy(
            evidenceIds = safeEvidenceIds,
            claims = supportedClaims,
            warnings = answer.warnings + if (supportedClaims.size < answer.claims.size) {
                listOf("Unsupported claims were removed")
            } else {
                emptyList()
            },
        )
    }
}
