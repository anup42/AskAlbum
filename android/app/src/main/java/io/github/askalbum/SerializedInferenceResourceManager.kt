package io.github.anup42.askalbum

import java.util.PriorityQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single high-memory inference lease. Interactive work is admitted before queued
 * background work; an active inference is never interrupted unsafely.
 */
class SerializedInferenceResourceManager : InferenceResourceManager {
    private val state = Mutex()
    private val waiters = PriorityQueue<Waiter>(compareBy<Waiter> { it.priority.rank }.thenBy { it.sequence })
    private var active: Waiter? = null
    private var sequence = 0L

    override suspend fun <T> withModel(capability: ModelCapability, block: suspend () -> T): T =
        withModel(capability, InferencePriority.BACKGROUND, block)

    override suspend fun <T> withModel(
        capability: ModelCapability,
        priority: InferencePriority,
        block: suspend () -> T,
    ): T = coroutineScope {
        val waiter = Waiter(priority)
        var activeExecutionToPreempt: Job? = null
        val admittedImmediately = state.withLock {
            waiter.sequence = sequence++
            val current = active
            if (current == null) {
                active = waiter
                waiter.admitted = true
                true
            } else {
                waiters.add(waiter)
                if (
                    priority == InferencePriority.INTERACTIVE &&
                    current.priority == InferencePriority.BACKGROUND &&
                    !current.preemptionRequested
                ) {
                    current.preemptionRequested = true
                    activeExecutionToPreempt = current.execution
                }
                false
            }
        }
        activeExecutionToPreempt?.cancel(InferencePreemptedCancellationException())
        try {
            if (!admittedImmediately) waiter.granted.await()
            currentCoroutineContext().ensureActive()
            val execution = async(start = CoroutineStart.LAZY) { block() }
            val preemptBeforeStart = state.withLock {
                waiter.execution = execution
                waiter.preemptionRequested
            }
            if (preemptBeforeStart) execution.cancel(InferencePreemptedCancellationException())
            execution.start()
            try {
                execution.await()
            } catch (cancelled: CancellationException) {
                if (waiter.preemptionRequested && currentCoroutineContext().isActive) {
                    throw InferencePreemptedException()
                }
                throw cancelled
            }
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
            if (active === waiter) active = null
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
            if (active === waiter) active = null
            true
        }
    }

    private suspend fun advance() {
        while (true) {
            val next = state.withLock {
                while (waiters.isNotEmpty()) {
                    val candidate = requireNotNull(waiters.poll())
                    if (candidate.cancelled) continue
                    candidate.admitted = true
                    active = candidate
                    return@withLock candidate
                }
                active = null
                null
            }
            val admitted = next ?: return
            if (admitted.granted.complete(Unit)) return
            state.withLock { admitted.released = true }
        }
    }

    private class Waiter(
        val priority: InferencePriority,
        var sequence: Long = 0L,
        val granted: CompletableDeferred<Unit> = CompletableDeferred(),
        var admitted: Boolean = false,
        var cancelled: Boolean = false,
        var released: Boolean = false,
        var preemptionRequested: Boolean = false,
        var execution: Job? = null,
    )
}

internal class InferencePreemptedException : IllegalStateException(
    "Background inference yielded to an interactive request",
)

private class InferencePreemptedCancellationException : CancellationException(
    "Background inference yielded to an interactive request",
)

internal suspend fun <T> retryBackgroundInferenceAfterPreemption(
    priority: InferencePriority,
    block: suspend () -> T,
): T {
    while (true) {
        try {
            return block()
        } catch (preempted: InferencePreemptedException) {
            if (priority != InferencePriority.BACKGROUND) throw preempted
        }
    }
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
