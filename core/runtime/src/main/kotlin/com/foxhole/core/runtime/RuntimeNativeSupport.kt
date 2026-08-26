package com.foxhole.core.runtime

import android.content.Context
import android.net.Network
import android.net.VpnService
import com.foxhole.core.model.TransportProtocol
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

interface RuntimeServiceHost {
    val runtimeContext: Context

    fun stopRuntimeService()

    fun protectSocket(socket: Int): Boolean

    fun onNativeProcessPoisoned(outcome: NativeForceStopOutcome) = Unit

    fun hasVpnPermission(): Boolean = true

    fun createTunBuilder(): VpnService.Builder? = null

    /** Certificate identity for native first-packet quarantine; null always fails closed. */
    fun signingDigestForPackage(packageName: String): String? =
        AndroidApplicationIdentityResolver(runtimeContext).signingCertificateSha256(packageName)

    fun currentUnderlyingNetwork(): Network? = null
}

internal interface RuntimeNativeStartFenceOwner {
    fun openNativeStartPermit(reason: String): Long

    fun invalidateNativeStartPermit(
        generation: Long,
        reason: String,
    ): Boolean

    suspend fun startWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit>

    suspend fun reloadWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit>

    /** Atomically fences and tears down only the transition/session still owned by generation. */
    suspend fun abortNativeTransition(
        generation: Long,
        reason: String,
    ): RuntimeKillResult?
}

internal class RuntimeGenerationGuard(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
) {
    // Check and commit share this lock so a stale native completion cannot publish into its successor.
    private val transitionLock = Any()

    @Volatile
    private var nativeGeneration = 0L

    fun current(): Long = nativeGeneration

    fun next(reason: String): Long =
        synchronized(transitionLock) {
            RuntimeGenerationClock.next().also { generation ->
                nativeGeneration = generation
                diagnosticsLogger.recordStructured(
                    "runtime",
                    "runtime generation advanced",
                    "reason=$reason",
                    "generation=$generation",
                )
            }
        }

    fun isCurrent(generation: Long): Boolean = nativeGeneration == generation

    fun invalidateIfCurrent(
        generation: Long,
        reason: String,
    ): Boolean =
        synchronized(transitionLock) {
            if (nativeGeneration != generation) {
                false
            } else {
                RuntimeGenerationClock.next().also { invalidationGeneration ->
                    nativeGeneration = invalidationGeneration
                    diagnosticsLogger.recordStructured(
                        "runtime",
                        "runtime generation advanced",
                        "reason=$reason",
                        "generation=$invalidationGeneration",
                    )
                }
                true
            }
        }

    fun commitIfCurrent(
        generation: Long,
        commit: () -> Unit,
    ): Boolean =
        synchronized(transitionLock) {
            if (nativeGeneration != generation) {
                false
            } else {
                commit()
                true
            }
        }

    suspend fun ensureCurrent(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(generation)) {
            throw CancellationException("runtime generation superseded")
        }
    }
}

class RuntimeInstanceStore(
    private val createRuntime: () -> FoxholeRuntime,
) {
    private val lock = Any()

    private var nextServiceOwnerGeneration = 0L
    private var serviceOwner: RuntimeServiceOwnerLease? = null
    private var serviceDestroyDrain: CompletableDeferred<Unit>? = null
    private var serviceDestroyCleanupPending = false

    @Volatile
    private var instance: FoxholeRuntime? = null

    fun claimServiceOwner(): RuntimeServiceOwnerLease =
        synchronized(lock) {
            RuntimeServiceOwnerLease(
                generation = ++nextServiceOwnerGeneration,
                predecessorDrain = serviceDestroyDrain?.takeUnless { drain -> drain.isCompleted },
            ).also { owner -> serviceOwner = owner }
        }

    fun get(): FoxholeRuntime =
        synchronized(lock) {
            check(serviceDestroyDrain?.isCompleted != false && !serviceDestroyCleanupPending) {
                "previous runtime service teardown is still draining"
            }
            instance ?: createRuntime().also { runtime -> instance = runtime }
        }

    @Volatile
    // Retained after detach so teardown can distinguish the process-owned master TUN from a leaked descriptor.
    private var lastMasterTunFd: Int? = null

    private val retiredInstances = ArrayDeque<FoxholeRuntime>()

    fun current(): FoxholeRuntime? = instance

    fun nativeSnapshot(): NativeRuntimeSnapshot {
        val live = current()?.nativeSnapshot()
        if (live != null) {
            lastMasterTunFd = live.masterTunFd
            return live
        }

        return NativeRuntimeSnapshot.NONE.copy(masterTunFd = lastMasterTunFd)
    }

    /** Forget the remembered descriptor; the master behind it is closed. */
    fun forgetMasterTunFd() {
        lastMasterTunFd = null
    }

    fun clear() {
        synchronized(lock) {
            instance = null
        }
    }

    fun beginServiceDestroy(owner: RuntimeServiceOwnerLease): Boolean =
        synchronized(lock) {
            if (serviceOwner !== owner) return@synchronized false
            owner.destroyStarted = true
            serviceDestroyDrain = owner.destroyCompletion
            owner.predecessorDrain?.invokeOnCompletion {
                synchronized(lock) { completeServiceDestroyIfDrained(owner) }
            }
            true
        }

    /** Detaches only the runtime still owned by the Android service being destroyed. */
    fun detachCurrentForServiceDestroy(owner: RuntimeServiceOwnerLease): FoxholeRuntime? =
        synchronized(lock) {
            if (serviceOwner !== owner || !owner.destroyStarted) return@synchronized null
            instance?.also { current ->
                lastMasterTunFd = current.nativeSnapshot().masterTunFd
                instance = null
                registerServiceDestroyStop(owner)
            }
        }

    fun retireCurrent(expected: FoxholeRuntime? = null): FoxholeRuntime? =
        synchronized(lock) {
            instance
                ?.takeIf { current -> expected == null || current === expected }
                ?.also { retiring ->
                    retiredInstances.addLast(retiring)
                    instance = null
                }
        }

    suspend fun quiesceAndRetireCurrent(): Boolean {
        val retiring = current() ?: return true
        if (!retiring.quiesceForInterfaceHandover()) {
            return false
        }
        return retireCurrent(expected = retiring) === retiring
    }

    fun takeRetired(): FoxholeRuntime? =
        synchronized(lock) {
            retiredInstances.removeFirstOrNull()
        }

    fun takeRetiredForServiceDestroy(owner: RuntimeServiceOwnerLease): FoxholeRuntime? =
        synchronized(lock) {
            if (serviceOwner !== owner || !owner.destroyStarted) return@synchronized null
            retiredInstances.removeFirstOrNull()?.also { registerServiceDestroyStop(owner) }
        }

    fun sealServiceDestroy(
        owner: RuntimeServiceOwnerLease,
        cleanup: () -> Unit,
    ) {
        synchronized(lock) {
            check(owner.destroyStarted)
            owner.destroyCleanup = cleanup
            owner.destroySealed = true
            completeServiceDestroyIfDrained(owner)
        }
    }

    fun completeServiceDestroyRuntime(owner: RuntimeServiceOwnerLease) {
        synchronized(lock) {
            if (owner.pendingStops > 0) owner.pendingStops -= 1
            completeServiceDestroyIfDrained(owner)
        }
    }

    suspend fun prepareServiceOwnerForRuntime(
        owner: RuntimeServiceOwnerLease,
        timeoutMs: Long,
        cleanup: () -> Unit,
    ): Boolean {
        val predecessor = owner.predecessorDrain
        if (
            predecessor != null &&
            withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                predecessor.await()
                true
            } != true
        ) {
            return false
        }
        return synchronized(lock) {
            if (serviceOwner !== owner || serviceDestroyDrain?.isCompleted == false) {
                return@synchronized false
            }
            if (serviceDestroyCleanupPending) {
                if (runCatching(cleanup).isFailure) return@synchronized false
                serviceDestroyCleanupPending = false
            }
            true
        }
    }

    private fun registerServiceDestroyStop(owner: RuntimeServiceOwnerLease) {
        owner.pendingStops += 1
        serviceDestroyCleanupPending = true
    }

    private fun serviceDestroyDrainReady(owner: RuntimeServiceOwnerLease): Boolean {
        if (!owner.destroySealed || owner.pendingStops != 0) return false
        if (owner.predecessorDrain?.isCompleted == false) return false
        return !owner.destroyCompletion.isCompleted
    }

    private fun completeServiceDestroyIfDrained(owner: RuntimeServiceOwnerLease) {
        if (!serviceDestroyDrainReady(owner)) return
        if (serviceOwner === owner && serviceDestroyCleanupPending) {
            val cleanup = owner.destroyCleanup
            if (cleanup != null && runCatching(cleanup).isSuccess) {
                serviceDestroyCleanupPending = false
            }
        }
        owner.destroyCompletion.complete(Unit)
    }

    fun hasRetired(): Boolean = synchronized(lock) { retiredInstances.isNotEmpty() }
}

class RuntimeServiceOwnerLease internal constructor(
    val generation: Long,
    internal val predecessorDrain: CompletableDeferred<Unit>?,
) {
    internal val destroyCompletion = CompletableDeferred<Unit>()
    internal var destroyStarted = false
    internal var destroySealed = false
    internal var pendingStops = 0
    internal var destroyCleanup: (() -> Unit)? = null
}

fun foxCoreLastStopDiagnostics(): String =
    runCatching { FoxholeNativeEngine.nativeLastStopDiagnostics().orEmpty() }
        .getOrElse { error -> "unavailable:${error.javaClass.simpleName}" }
        .ifBlank { "unavailable:empty" }

fun VpnSession.runtimeTransportProtocol(): TransportProtocol =
    when (VpnHealthProbeTargetSelector.select(configJson)?.transport) {
        VpnHealthProbeTransport.TCP -> TransportProtocol.TCP
        VpnHealthProbeTransport.UDP -> TransportProtocol.UDP
        null -> TransportProtocol.UNKNOWN
    }
