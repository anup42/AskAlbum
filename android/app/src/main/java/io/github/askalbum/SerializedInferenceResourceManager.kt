package io.github.anup42.askalbum

import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single high-memory inference lease. Interactive work is admitted before queued
 * background work; an active inference is never interrupted unsafely.
 */
class SerializedInferenceResourceManager : InferenceResourceManager {
    private val state = Mutex()
    private val waiters = PriorityQueue<Waiter>(compareBy<Waiter> { it.priority.rank }.thenBy { it.sequence })
    private var active = false
    private var sequence = 0L

    override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T =
        withModel(capability, InferencePriority.BACKGROUND, block)

    override suspend fun <T> withModel(
        capability: ModelCapability,
        priority: InferencePriority,
        block: suspend () -> T,
    ): T {
        val waiter = Waiter(priority, sequence++)
        val admittedImmediately = state.withLock {
            if (!active) {
                active = true
                waiter.admitted = true
                true
            } else {
                waiters.add(waiter)
                false
            }
        }
        try {
            if (!admittedImmediately) waiter.granted.await()
            currentCoroutineContext().ensureActive()
            return block()
        } catch (cancelled: CancellationException) {
            if (cancelWaiter(waiter)) advance()
            throw cancelled
        } finally {
            if (releaseWaiter(waiter)) advance()
        }
    }

    private suspend fun cancelWaiter(waiter: Waiter): Boolean = state.withLock {
        waiter.cancelled = true
        if (!waiter.admitted) {
            waiters.remove(waiter)
            false
        } else if (!waiter.released) {
            waiter.released = true
            true
        } else {
            false
        }
    }

    private suspend fun releaseWaiter(waiter: Waiter): Boolean = state.withLock {
        if (!waiter.admitted || waiter.released) {
            false
        } else {
            waiter.released = true
            true
        }
    }

    private suspend fun advance() {
        while (true) {
            val next = state.withLock {
                while (waiters.isNotEmpty()) {
                    val candidate = waiters.poll()
                    if (candidate.cancelled) continue
                    candidate.admitted = true
                    return@withLock candidate
                }
                active = false
                null
            }
            if (next == null) return
            if (next.granted.complete(Unit)) return
            state.withLock { next.released = true }
        }
    }

    private class Waiter(
        val priority: InferencePriority,
        val sequence: Long,
        val granted: CompletableDeferred<Unit> = CompletableDeferred(),
        var admitted: Boolean = false,
        var cancelled: Boolean = false,
        var released: Boolean = false,
    )
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
