package com.foxhole.guard.runtime

import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeCommandQueueSnapshot
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.core.runtime.RuntimeSupervisor
import com.foxhole.core.runtime.isIdleWithoutAttachedRuntimeResources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal sealed interface QuarantineRuntimeOwner {
    data class Profile(
        val profileId: Long,
        val correlationId: String,
    ) : QuarantineRuntimeOwner

    data class LocalGuard(val mode: LocalGuardMode) : QuarantineRuntimeOwner
}

internal data class AppliedQuarantinePolicy(
    val revision: Long,
    val runtimeFingerprint: Int,
    val runtimeGeneration: Long,
    val owner: QuarantineRuntimeOwner,
)

internal data class QuarantineRuntimeEnforcementSnapshot(
    val owner: QuarantineRuntimeOwner?,
    val runtimeGeneration: Long,
    val commandQueue: RuntimeCommandQueueSnapshot,
    val nativeSnapshot: NativeRuntimeSnapshot,
    val retiredRuntimePresent: Boolean,
) {
    val genuinelyIdle: Boolean
        get() =
            owner == null &&
                !commandQueue.closed &&
                commandQueue.commandQueueDepth == 0 &&
                nativeSnapshot.isIdleWithoutAttachedRuntimeResources() &&
                !nativeSnapshot.cleanupDraining &&
                nativeSnapshot.masterTunFd == null &&
                !retiredRuntimePresent
}

internal fun quarantineRuntimeEnforcementSnapshot(
    runtimeSupervisor: RuntimeSupervisor,
    runtimeInstanceStore: RuntimeInstanceStore,
): QuarantineRuntimeEnforcementSnapshot {
    val ownership = runtimeSupervisor.ownership.value
    val owner =
        ownership.activeSession?.let { session ->
            QuarantineRuntimeOwner.Profile(session.profileId, session.correlationId)
        } ?: ownership.activeLocalGuardMode?.let(QuarantineRuntimeOwner::LocalGuard)
    return QuarantineRuntimeEnforcementSnapshot(
        owner = owner,
        runtimeGeneration = runtimeSupervisor.currentGeneration(),
        commandQueue = runtimeSupervisor.queueSnapshot(),
        nativeSnapshot = runtimeInstanceStore.nativeSnapshot(),
        retiredRuntimePresent = runtimeInstanceStore.hasRetired(),
    )
}

internal enum class QuarantineEnforcementOutcome {
    APPLIED,
    GENUINELY_IDLE,
    RETRY,
}

/** Process-local applied receipt. Process death intentionally clears it and re-arms durable work. */
internal class QuarantineRuntimeEnforcementTracker {
    private val appliedMutable = MutableStateFlow<AppliedQuarantinePolicy?>(null)
    val applied: StateFlow<AppliedQuarantinePolicy?> = appliedMutable

    fun acknowledge(
        session: VpnSession,
        runtimeGeneration: Long,
        localGuardMode: LocalGuardMode? = null,
    ) {
        val fingerprint = session.runtimeConfigFingerprint ?: return
        val owner =
            localGuardMode?.let(QuarantineRuntimeOwner::LocalGuard)
                ?: QuarantineRuntimeOwner.Profile(session.profileId, session.correlationId)
        appliedMutable.value =
            AppliedQuarantinePolicy(
                revision = session.quarantinePolicyRevision,
                runtimeFingerprint = fingerprint,
                runtimeGeneration = runtimeGeneration,
                owner = owner,
            )
    }

    suspend fun awaitCurrent(
        revision: Long,
        timeoutMs: Long,
        snapshot: () -> QuarantineRuntimeEnforcementSnapshot,
    ): AppliedQuarantinePolicy? =
        withTimeoutOrNull(timeoutMs) {
            applied.filterNotNull().first { receipt -> receipt.isCurrentFor(revision, snapshot()) }
        }
}

internal class QuarantineEnforcementCoordinator(
    private val snapshot: () -> QuarantineRuntimeEnforcementSnapshot,
    private val currentReceipt: () -> AppliedQuarantinePolicy?,
    private val dispatch: (revision: Long) -> Boolean,
    private val awaitReceipt: suspend (revision: Long) -> AppliedQuarantinePolicy?,
) {
    suspend fun enforce(revision: Long): QuarantineEnforcementOutcome {
        val before = snapshot()
        if (currentReceipt()?.isCurrentFor(revision, before) == true) {
            return QuarantineEnforcementOutcome.APPLIED
        }
        if (before.genuinelyIdle) {
            return QuarantineEnforcementOutcome.GENUINELY_IDLE
        }
        if (!dispatch(revision)) {
            return QuarantineEnforcementOutcome.RETRY
        }
        val receipt = awaitReceipt(revision)
        val after = snapshot()
        return when {
            receipt?.isCurrentFor(revision, after) == true -> QuarantineEnforcementOutcome.APPLIED
            after.genuinelyIdle -> QuarantineEnforcementOutcome.GENUINELY_IDLE
            else -> QuarantineEnforcementOutcome.RETRY
        }
    }
}

private fun AppliedQuarantinePolicy.isCurrentFor(
    requestedRevision: Long,
    snapshot: QuarantineRuntimeEnforcementSnapshot,
): Boolean =
    revision >= requestedRevision &&
        runtimeGeneration == snapshot.runtimeGeneration &&
        owner == snapshot.owner

internal const val QUARANTINE_ENFORCEMENT_ACK_TIMEOUT_MS = 12_000L
