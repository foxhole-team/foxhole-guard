
package com.foxhole.core.runtime

import android.os.ParcelFileDescriptor
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single Android owner of a FoxCore JNI handle and its kernel TUN. A master descriptor stays
 * in Java while native code owns a duplicate: an immutable outbound change can replace the Rust
 * engine on the same Android interface without dropping the OS VPN, while final stop still has
 * one explicit owner that closes the master.
 */
@Suppress("LargeClass")
internal class FoxCoreRuntime internal constructor(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val translator: FoxCoreConfigTranslator = FoxCoreConfigTranslator(),
    private val native: FoxCoreNativeApi = JniFoxCoreNativeApi,
    shareNative: FoxCoreShareNativeApi = JniFoxCoreShareNativeApi,
    private val shareRuntime: FoxCoreShareRuntimeAdapter = FoxCoreShareRuntimeAdapter(shareNative),
) : FoxholeRuntime,
    RuntimeNativeStartFenceOwner,
    FoxCoreShareRuntime by shareRuntime {
    private val transitionMutex = Mutex()
    private val stateLock = Any()

    /** Owns the LAN listener for as long as this runtime owns a native handle. */
    private val lanProxy = LanProxyController(native)
    private val localProxy = LocalProxyController(native)
    private val torProbeProxy = TorProbeProxyController(native)

    /** Owns Android's master TUN descriptor: establish, close and the ownership journal. */
    private val tunOwner = FoxCoreTunOwner(diagnosticsLogger)
    private val nativeEngine = FoxCoreNativeEngineOperations(native, diagnosticsLogger, translator)
    private val nativeStartOwnership = RuntimeNativeStartOwnership(diagnosticsLogger, tunOwner)
    private val transitionExecutor = NativeTransitionExecutor()

    @Volatile
    private var active: ActiveFoxCoreSession? = null

    @Volatile
    private var state: RuntimeState = RuntimeState.IDLE

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var activeTransitionGeneration: Long? = null

    @Volatile
    private var lastStopReason: String? = null

    @Volatile
    private var cleanupDraining: Boolean = false

    @Volatile
    private var handoverQuiesced: Boolean = false

    /** Native JNI work runs outside [transitionMutex]; this is the owned transition it may finish. */
    private var pendingTransition: PendingNativeTransition? = null

    override suspend fun start(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = startWithNativePermit(session, host, openNativeStartPermit("direct_start"))

    override fun openNativeStartPermit(reason: String): Long =
        nativeStartOwnership.open(reason)

    override fun invalidateNativeStartPermit(
        generation: Long,
        reason: String,
    ): Boolean = nativeStartOwnership.invalidate(generation, reason)

    override suspend fun startWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        val preparation =
            transitionMutex.withLock {
                preemptSupersededPendingStart(generation)
                when {
                    !nativeStartOwnership.isCurrent(generation) ->
                        NativeTransitionPreparation.Failed(
                            rejectSupersededNativeStart(stage = "before_prepare"),
                        )
                    pendingTransition != null || active != null ->
                        NativeTransitionPreparation.Failed(
                            foxCoreFailure(FoxCoreRuntimeFailure.ALREADY_RUNNING),
                        )
                    else -> {
                        setState(RuntimeState.PREPARING)
                        val translated = nativeEngine.translate(session, expectedPolicyRevision = null)
                        if (translated == null) {
                            NativeTransitionPreparation.Failed(
                                failStart(FoxCoreRuntimeFailure.CONFIG_REJECTED),
                            )
                        } else {
                            pendingTransition = PendingNativeTransition.Start(generation = generation)
                            NativeTransitionPreparation.Ready(translated)
                        }
                    }
                }
            }
        return when (preparation) {
            is NativeTransitionPreparation.Failed -> preparation.failure
            is NativeTransitionPreparation.Ready ->
                transitionExecutor.finishStart(preparation.value, host, generation)
        }
    }

    private suspend fun finishPreparedStartFailure(
        generation: Long,
        failure: Result<Unit>,
    ): Result<Unit> =
        transitionMutex.withLock {
            val pending = pendingTransition as? PendingNativeTransition.Start
            if (pending?.generation != generation) {
                return@withLock rejectSupersededNativeStart(stage = "after_native")
            }
            discardPendingStartTunIfOwned(generation)
            if (!nativeStartOwnership.isCurrent(generation)) {
                return@withLock rejectSupersededNativeStart(stage = "after_native")
            }
            setState(RuntimeState.ERROR)
            failure
        }

    private fun ownsPendingStart(generation: Long): Boolean =
        (pendingTransition as? PendingNativeTransition.Start)?.generation == generation &&
            nativeStartOwnership.isCurrent(generation)

    private fun preemptSupersededPendingStart(generation: Long) {
        val pending = pendingTransition as? PendingNativeTransition.Start ?: return
        if (pending.generation == generation || nativeStartOwnership.isCurrent(pending.generation)) {
            return
        }
        pending.tun?.let(tunOwner::close)
        pendingTransition = null
    }

    private fun discardPendingStartTunIfOwned(generation: Long): Boolean {
        val pending = pendingTransition as? PendingNativeTransition.Start ?: return true
        if (pending.generation != generation) return true
        val closed = pending.tun?.let(tunOwner::close) ?: true
        pendingTransition = null
        return closed
    }

    override suspend fun reload(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> = reloadWithNativePermit(session, host, openNativeStartPermit("direct_reload"))

    override suspend fun reloadWithNativePermit(
        session: VpnSession,
        host: RuntimeServiceHost,
        generation: Long,
    ): Result<Unit> {
        val preparation =
            transitionMutex.withLock {
                val current = active
                when {
                    !nativeStartOwnership.isCurrent(generation) ->
                        NativeTransitionPreparation.Failed(
                            rejectSupersededNativeStart(stage = "reload_prepare"),
                        )
                    pendingTransition != null ->
                        NativeTransitionPreparation.Failed(
                            foxCoreFailure(FoxCoreRuntimeFailure.ALREADY_RUNNING),
                        )
                    current == null ->
                        NativeTransitionPreparation.Failed(
                            foxCoreFailure(FoxCoreRuntimeFailure.NOT_RUNNING),
                        )
                    else -> {
                        val translated = nativeEngine.translate(session, current.policyRevision)
                        val nextFingerprint =
                            translated?.let { config ->
                                nativeEngine.immutableFingerprint(config.engineConfigJson)
                            }
                        when {
                            translated == null || nextFingerprint == null ->
                                NativeTransitionPreparation.Failed(
                                    restoreRunningFailure(FoxCoreRuntimeFailure.CONFIG_REJECTED),
                                )
                            translated.tunPlan != current.translated.tunPlan ->
                                NativeTransitionPreparation.Failed(
                                    restoreRunningFailure(FoxCoreRuntimeFailure.TUN_PLAN_CHANGED),
                                )
                            else -> {
                                setState(RuntimeState.RELOADING)
                                pendingTransition = PendingNativeTransition.Reload(generation, current)
                                NativeTransitionPreparation.Ready(
                                    PreparedNativeReload(current, translated, nextFingerprint),
                                )
                            }
                        }
                    }
                }
            }
        return when (preparation) {
            is NativeTransitionPreparation.Failed -> preparation.failure
            is NativeTransitionPreparation.Ready ->
                transitionExecutor.finishReload(preparation.value, session, host, generation)
        }
    }

    /** JNI transition continuations stay outside the mutex and commit only under generation. */
    private inner class NativeTransitionExecutor {
        suspend fun finishStart(
            translated: FoxCoreSessionConfig,
            host: RuntimeServiceHost,
            generation: Long,
        ): Result<Unit> {
            // ABI/capability probes are JNI too. A wedged load must not retain transitionMutex.
            val preflight = nativeEngine.preflight()
            val underlying = host.currentUnderlyingNetwork()
            return when {
                preflight.isFailure -> finishPreparedStartFailure(generation, preflight)
                underlying == null || underlying.networkHandle <= 0L ->
                    finishPreparedStartFailure(
                        generation,
                        foxCoreFailure(FoxCoreRuntimeFailure.UNDERLYING_NETWORK_UNAVAILABLE),
                    )
                else ->
                    when (val tun = prepareTun(translated, host, underlying, generation)) {
                        is NativeTransitionPreparation.Failed -> tun.failure
                        is NativeTransitionPreparation.Ready ->
                            startAndCommit(translated, host, underlying, tun.value, generation)
                    }
            }
        }

        private suspend fun prepareTun(
            translated: FoxCoreSessionConfig,
            host: RuntimeServiceHost,
            underlying: android.net.Network,
            generation: Long,
        ): NativeTransitionPreparation<ParcelFileDescriptor> =
            transitionMutex.withLock {
                if (!ownsPendingStart(generation)) {
                    discardPendingStartTunIfOwned(generation)
                    NativeTransitionPreparation.Failed(
                        rejectSupersededNativeStart(stage = "before_tun"),
                    )
                } else {
                    val established = tunOwner.establish(host, underlying, translated.tunPlan)
                    if (established == null) {
                        pendingTransition = null
                        NativeTransitionPreparation.Failed(
                            failStart(FoxCoreRuntimeFailure.TUN_ESTABLISH_FAILED),
                        )
                    } else {
                        pendingTransition = PendingNativeTransition.Start(generation, established)
                        setState(RuntimeState.STARTING)
                        NativeTransitionPreparation.Ready(established)
                    }
                }
            }

        private suspend fun startAndCommit(
            translated: FoxCoreSessionConfig,
            host: RuntimeServiceHost,
            underlying: android.net.Network,
            tun: ParcelFileDescriptor,
            generation: Long,
        ): Result<Unit> {
            val started = nativeEngine.start(tun, underlying, host, translated)
                ?: return finishPreparedStartFailure(
                    generation,
                    foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_START_FAILED),
                )
            val next = started.copy(dnsServers = translated.tunPlan.advertisedDnsServers)
            val committed =
                transitionMutex.withLock {
                    if (!ownsPendingStart(generation)) {
                        false
                    } else {
                        pendingTransition = null
                        nativeStartOwnership.commitIfCurrent(generation) {
                            publishState(next, generation)
                        }
                    }
                }
            return if (committed) {
                recordPublished(next)
                Result.success(Unit)
            } else {
                discardSupersededNativeStart(next, generation)
            }
        }

        suspend fun finishReload(
            prepared: PreparedNativeReload,
            session: VpnSession,
            host: RuntimeServiceHost,
            generation: Long,
        ): Result<Unit> {
            // Both paths cross JNI without transitionMutex; stop can fence the generation.
            val outcome =
                if (prepared.nextFingerprint == prepared.current.immutableFingerprint) {
                    executePolicyReload(prepared, session, generation)
                } else {
                    executeReplacementReload(prepared, host, generation)
                }
            val stillOwned =
                transitionMutex.withLock {
                    ownsPendingReload(generation, prepared.current)
                }
            if (!stillOwned) {
                outcome.startedSessionOrNull()?.let { late ->
                    discardSupersededNativeStart(late, generation)
                }
                return rejectSupersededNativeStart(stage = "reload_commit")
            }
            return transitionMutex.withLock {
                if (!ownsPendingReload(generation, prepared.current)) {
                    rejectSupersededNativeStart(stage = "reload_commit_race")
                } else {
                    pendingTransition = null
                    commitReloadOutcome(prepared, outcome, generation)
                }
            }
        }
    }

    private fun commitReloadOutcome(
        prepared: PreparedNativeReload,
        outcome: NativeReloadOutcome,
        generation: Long,
    ): Result<Unit> =
        when (outcome) {
            is NativeReloadOutcome.PolicyApplied -> {
                publish(
                    prepared.current.copy(
                        translated = outcome.translated,
                        policyRevision = outcome.revision,
                    ),
                    generation,
                )
                Result.success(Unit)
            }
            is NativeReloadOutcome.Replaced -> {
                publish(outcome.session, generation)
                Result.success(Unit)
            }
            is NativeReloadOutcome.RestoredFailure -> {
                outcome.restored?.let { restored -> publish(restored, generation) }
                restoreRunningFailure(outcome.failure)
            }
            NativeReloadOutcome.PolicyCallFailed ->
                restoreRunningFailure(FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED)
            is NativeReloadOutcome.PolicyRefused ->
                restoreRunningFailure(policyReloadFailure(outcome.code))
            NativeReloadOutcome.StopFailed -> {
                clearActive(reason = "replace_stop_failed")
                setState(RuntimeState.ERROR)
                foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_STOP_FAILED)
            }
            NativeReloadOutcome.Superseded ->
                rejectSupersededNativeStart(stage = "reload_native")
        }

    private fun ownsPendingReload(
        generation: Long,
        current: ActiveFoxCoreSession,
    ): Boolean {
        val pending = pendingTransition as? PendingNativeTransition.Reload
        return pending?.generation == generation && pending.session === current &&
            active === current && nativeStartOwnership.isCurrent(generation)
    }

    private fun executePolicyReload(
        prepared: PreparedNativeReload,
        session: VpnSession,
        generation: Long,
    ): NativeReloadOutcome {
        val first = nativeEngine.reloadPolicy(prepared.current, prepared.translated)
        if (first !is PolicyReloadOutcome.Refused ||
            policyReloadFailure(first.code) != FoxCoreRuntimeFailure.POLICY_REVISION_CONFLICT
        ) {
            return first.toNativeReloadOutcome(prepared.translated)
        }
        if (!nativeStartOwnership.isCurrent(generation)) {
            return NativeReloadOutcome.Superseded
        }
        val unconditional = nativeEngine.translate(session, expectedPolicyRevision = null)
            ?: return NativeReloadOutcome.RestoredFailure(
                restored = prepared.current,
                failure = FoxCoreRuntimeFailure.CONFIG_REJECTED,
            )
        return nativeEngine.reloadPolicy(prepared.current, unconditional).toNativeReloadOutcome(unconditional)
    }

    private suspend fun executeReplacementReload(
        prepared: PreparedNativeReload,
        host: RuntimeServiceHost,
        generation: Long,
    ): NativeReloadOutcome {
        val stopped = nativeEngine.stop(prepared.current.handle, REPLACEMENT_STOP_POLICY)
        val network = host.currentUnderlyingNetwork()
        return when {
            !stopped.stopped -> NativeReloadOutcome.StopFailed
            !nativeStartOwnership.isCurrent(generation) -> NativeReloadOutcome.Superseded
            network == null || network.networkHandle <= 0L ->
                NativeReloadOutcome.RestoredFailure(
                    restored = restorePreviousNativeSession(prepared.current, generation),
                    failure = FoxCoreRuntimeFailure.UNDERLYING_NETWORK_UNAVAILABLE,
                )
            else -> {
                val replacement =
                    nativeEngine.start(prepared.current.tun, network, host, prepared.translated)
                        ?.copy(
                            immutableFingerprint = prepared.nextFingerprint,
                            dnsServers = prepared.current.dnsServers,
                        )
                replacement?.let(NativeReloadOutcome::Replaced)
                    ?: NativeReloadOutcome.RestoredFailure(
                        restored = restorePreviousNativeSession(prepared.current, generation),
                        failure = FoxCoreRuntimeFailure.NATIVE_START_FAILED,
                    )
            }
        }
    }

    private fun restorePreviousNativeSession(
        previous: ActiveFoxCoreSession,
        generation: Long,
    ): ActiveFoxCoreSession? {
        if (!nativeStartOwnership.isCurrent(generation)) return null
        val network = previous.host.currentUnderlyingNetwork() ?: return null
        return nativeEngine.start(
            tun = previous.tun,
            network = network,
            host = previous.host,
            translated = previous.translated,
        )?.copy(
            policyRevision = previous.policyRevision,
            dnsServers = previous.dnsServers,
        )
    }

    override suspend fun quiesceForInterfaceHandover(): Boolean =
        transitionMutex.withLock {
            val current = active ?: return@withLock true
            if (handoverQuiesced) {
                return@withLock true
            }
            setState(RuntimeState.STOPPING)
            cleanupDraining = true
            val stopped = nativeEngine.stop(current.handle, REPLACEMENT_STOP_POLICY)
            val nativeTunProbe = probeTunDescriptor(current.nativeTunFd)
            if (!stopped.stopped || nativeTunProbe != NativeTunDescriptorProbe.CLOSED) {
                cleanupDraining = false
                setState(RuntimeState.ERROR)
                return@withLock false
            }
            handoverQuiesced = true
            shareRuntime.detach()
            lanProxy.onSessionGone()
            localProxy.sync(handle = null, request = null)
            torProbeProxy.sync(handle = null, requestedOwner = null)
            synchronized(stateLock) {
                generation += 1L
                lastStopReason = "interface_handover"
            }
            cleanupDraining = false
            diagnosticsLogger.recordStructured(
                "foxcore",
                "native runtime quiesced for interface handover",
                "generation=$generation",
                "master_tun_held=${current.tun.fileDescriptor.valid()}",
                "native_tun_released=true",
                "escalated=${stopped.escalated}",
            )
            true
        }

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        transitionMutex.withLock {
            val startedAt = System.nanoTime()
            val pendingTunClosed = cancelPendingTransition("stop")
            val current = active
            if (current == null) {
                setState(RuntimeState.IDLE)
                return@withLock RuntimeStopResult(
                    closeServiceOk = true,
                    closeServerOk = true,
                    tunClosed = pendingTunClosed,
                    escalatedToKill = false,
                    elapsedMs = elapsedMillis(startedAt),
                )
            }
            setState(RuntimeState.STOPPING)
            cleanupDraining = true
            val wasQuiesced = handoverQuiesced
            val stop =
                if (wasQuiesced) {
                    NativeStopOutcome(stopped = true, escalated = false)
                } else {
                    nativeEngine.stop(current.handle, policy)
                }
            val nativeStopped = stop.stopped
            val nativeTunProbe =
                if (wasQuiesced) NativeTunDescriptorProbe.CLOSED else probeTunDescriptor(current.nativeTunFd)
            val tunClosed =
                if (shouldCloseMasterTun(nativeTunProbe)) {
                    tunOwner.close(current.tun)
                } else {
                    false
                }
            applyTeardownSettlement(nativeReleased = nativeStopped, tunClosed = tunClosed, reason = "stop")
            tunOwner.recordOwnershipAfterClose(
                session = current,
                nativeTunProbe = nativeTunProbe,
                masterTunClosed = tunClosed,
                reason = "stop",
            )
            cleanupDraining = false
            RuntimeStopResult(
                closeServiceOk = nativeStopped,
                closeServerOk = nativeStopped,
                tunClosed = tunClosed,
                escalatedToKill = stop.escalated,
                elapsedMs = elapsedMillis(startedAt),
            )
        }

    override suspend fun forceKill(reason: String): RuntimeKillResult =
        transitionMutex.withLock {
            forceKillLocked(reason)
        }

    override suspend fun abortNativeTransition(
        generation: Long,
        reason: String,
    ): RuntimeKillResult? =
        transitionMutex.withLock {
            val ownsPending = pendingTransition?.generation == generation
            val ownsActive = activeTransitionGeneration == generation
            if (!ownsPending && !ownsActive) return@withLock null
            nativeStartOwnership.invalidate(generation, reason)
            forceKillLocked(reason)
        }

    private suspend fun forceKillLocked(reason: String): RuntimeKillResult {
        val pendingTunClosed = cancelPendingTransition("force_kill:$reason")
        val current = active
        if (current == null) {
            activeTransitionGeneration = null
            lastStopReason = safeReason(reason)
            setState(RuntimeState.IDLE)
            return RuntimeKillResult(
                reason = safeReason(reason),
                tunClosed = pendingTunClosed,
                serverDetached = true,
            )
        }
        setState(RuntimeState.KILLING)
        cleanupDraining = true
        val wasQuiesced = handoverQuiesced
        val killed = wasQuiesced || nativeEngine.forceKill(current.handle)
        val nativeTunProbe =
            if (wasQuiesced) NativeTunDescriptorProbe.CLOSED else probeTunDescriptor(current.nativeTunFd)
        val tunClosed =
            if (shouldCloseMasterTun(nativeTunProbe)) {
                tunOwner.close(current.tun)
            } else {
                false
            }
        applyTeardownSettlement(nativeReleased = killed, tunClosed = tunClosed, reason = reason)
        tunOwner.recordOwnershipAfterClose(
            session = current,
            nativeTunProbe = nativeTunProbe,
            masterTunClosed = tunClosed,
            reason = "force_kill",
        )
        cleanupDraining = false
        return RuntimeKillResult(
            reason = safeReason(reason),
            tunClosed = tunClosed,
            serverDetached = killed,
            closeDetached = !killed || !tunClosed,
        )
    }

    /** Fences the JNI frame before releasing any Android-owned pending TUN. Mutex-only. */
    private fun cancelPendingTransition(reason: String): Boolean {
        val pending = pendingTransition ?: return true
        nativeStartOwnership.invalidate(pending.generation, reason)
        pendingTransition = null
        return when (pending) {
            is PendingNativeTransition.Start -> pending.tun?.let(tunOwner::close) ?: true
            is PendingNativeTransition.Reload -> true
        }
    }

    override fun nativeSnapshot(): NativeRuntimeSnapshot {
        val current = active
        return NativeRuntimeSnapshot(
            hasEngineHandle = current != null && !handoverQuiesced,
            hasTunFileDescriptor = current?.tun?.fileDescriptor?.valid() == true,
            hasHost = current != null && !handoverQuiesced,
            hasConfig = current != null,
            dnsServerAddress = current?.dnsServers?.firstOrNull(),
            nativeGeneration = generation,
            nativeState = state,
            cleanupDraining = cleanupDraining,
            lastStopReason = lastStopReason,
            lastCloseDetached = false,
            masterTunFd = tunOwner.masterTunFd,
        )
    }

    override fun currentDnsServerAddress(): String? = active?.dnsServers?.firstOrNull()

    override fun onDefaultNetworkAvailable() {
        val current = active ?: return
        val network = current.host.currentUnderlyingNetwork() ?: return
        if (network.networkHandle <= 0L || network.networkHandle == current.networkHandle) {
            return
        }
        runCatching {
            native.networkChangedWithHandle(current.handle, network.networkHandle)
            synchronized(stateLock) {
                if (active === current) {
                    active = current.copy(networkHandle = network.networkHandle)
                }
            }
        }.onFailure {
            diagnosticsLogger.record("network", "foxcore network handoff failed")
        }
    }

    override fun onDefaultNetworkLost() {
        val current = active ?: return
        runCatching { native.networkChanged(current.handle) }
            .onFailure {
                diagnosticsLogger.record("network", "foxcore network-loss notification failed")
            }
    }

    override fun runtimeStatsJson(): String? =
        active?.let { current ->
            runCatching { native.stats(current.handle) }.getOrNull()
        }

    override fun runtimeTrafficMapJson(): String? =
        active?.let { current ->
            runCatching { native.connections(current.handle) }.getOrNull()
        }

    override fun drainRuntimeTrafficEventsJson(max: Int): String? =
        active?.let { current ->
            runCatching {
                native.drainTrafficEvents(current.handle, max.coerceIn(1, MAX_TRAFFIC_EVENT_BATCH))
            }.getOrNull()
        }

    override fun drainRuntimeAuditEventsJson(max: Int): String? =
        active?.let { current ->
            runCatching { native.drainEvents(current.handle, max.coerceIn(1, MAX_EVENT_BATCH)) }
                .getOrNull()
        }

    private fun publish(
        next: ActiveFoxCoreSession,
        ownerGeneration: Long,
    ) {
        publishState(next, ownerGeneration)
        recordPublished(next)
    }

    /** State-only half used by the generation guard's atomic check-and-commit section. */
    private fun publishState(
        next: ActiveFoxCoreSession,
        ownerGeneration: Long,
    ) {
        synchronized(stateLock) {
            generation += 1L
            active = next
            activeTransitionGeneration = ownerGeneration
            shareRuntime.attach(next.handle)
            state = RuntimeState.RUNNING
            lastStopReason = null
        }
    }

    private fun recordPublished(next: ActiveFoxCoreSession) {
        diagnosticsLogger.recordStructured(
            "foxcore",
            "native runtime active",
            "generation=$generation",
            "policy_revision=${next.policyRevision}",
        )
        nativeEngine.recordUnavailableOutbounds(next.handle)
    }

    private suspend fun discardSupersededNativeStart(
        next: ActiveFoxCoreSession,
        ownerGeneration: Long,
    ): Result<Unit> {
        cleanupDraining = true
        val discarded = nativeStartOwnership.discardLateSession(
            session = next,
            releaseNative = nativeEngine::forceKill,
        )
        transitionMutex.withLock {
            val pendingGeneration = pendingTransition?.generation
            if (pendingGeneration == ownerGeneration) {
                pendingTransition = null
            }
            cleanupDraining = false
            // A successor may already own a pending transition or an active session. Late cleanup
            // belongs only to its own generation and must never overwrite the successor's state.
            if (active == null && pendingTransition == null) {
                synchronized(stateLock) {
                    state =
                        if (discarded.nativeReleased && discarded.tunClosed) {
                            RuntimeState.IDLE
                        } else {
                            RuntimeState.ERROR
                        }
                    lastStopReason = "superseded_start"
                }
            }
        }
        return foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_START_FAILED)
    }

    private fun rejectSupersededNativeStart(stage: String): Result<Unit> =
        nativeStartOwnership.rejectBeforeNative(stage) {
            synchronized(stateLock) {
                state = RuntimeState.IDLE
                lastStopReason = "superseded_start"
            }
        }

    /**
     * Publishes (or takes down) the LAN proxy on the live session. Returns what the core actually
     * reports, so the caller never has to guess whether its own request took effect.
     */
    override fun syncLanProxy(
        request: LanProxyRequest?,
        blocked: LanProxyUnavailableReason?,
    ): LanProxyStatusSnapshot =
        lanProxy.sync(
            handle = active?.handle,
            request = request,
            blocked = blocked,
        )

    override fun lanProxyStatus(): LanProxyStatusSnapshot = lanProxy.status()

    override fun syncLocalProxy(request: LocalProxyRequest?): LocalProxyStatusSnapshot =
        localProxy.sync(handle = active?.handle, request = request)

    override fun localProxyStatus(): LocalProxyStatusSnapshot = localProxy.status()

    override fun syncTorProbeProxy(owner: TorProbeProxyOwner?): Result<TorProbeProxyLease?> =
        torProbeProxy.sync(handle = active?.handle, requestedOwner = owner)

    override fun torProbeProxyLease(): TorProbeProxyLease? = torProbeProxy.currentLease()

    override fun torProbeProxyIssue(): TorProbeProxyIssue? = torProbeProxy.currentIssue()

    override fun releaseTorProbeProxy(owner: TorProbeProxyOwner): Result<Boolean> =
        torProbeProxy.release(handle = active?.handle, expectedOwner = owner)

    override fun revokeFlows(target: RevokeTarget): RevokeOutcome {
        val handle = active?.handle ?: return RevokeOutcome.NotRunning
        val outcome = revokeOutcomeForCode(native.revokeFlows(handle, target.toTargetJson()))
        diagnosticsLogger.recordStructured(
            "foxcore",
            "live flows revoked",
            "target=${target::class.simpleName?.lowercase().orEmpty()}",
            "outcome=$outcome",
        )
        return outcome
    }

    /** Apply the decision [settleTeardown] made. */
    private fun applyTeardownSettlement(
        nativeReleased: Boolean,
        tunClosed: Boolean,
        reason: String,
    ) {
        when (settleTeardown(nativeReleased = nativeReleased, masterTunClosed = tunClosed)) {
            TeardownSettlement.NOT_RELEASED -> setState(RuntimeState.ERROR)
            TeardownSettlement.RELEASED -> clearActive(reason)
            TeardownSettlement.RELEASED_WITH_TUN_OPEN -> {
                clearActive(reason)
                setState(RuntimeState.ERROR)
            }
        }
    }

    private fun clearActive(reason: String) {
        synchronized(stateLock) {
            active = null
            activeTransitionGeneration = null
            handoverQuiesced = false
            shareRuntime.detach()
            generation += 1L
            state = RuntimeState.IDLE
            lastStopReason = safeReason(reason)
        }
        // The LAN listener relays into this session; it cannot outlive it. The core drops it with
        // the handle anyway — this is the half that stops the dashboard claiming it is still up.
        lanProxy.onSessionGone()
        localProxy.sync(handle = null, request = null)
        torProbeProxy.sync(handle = null, requestedOwner = null)
    }

    private fun setState(next: RuntimeState) {
        state = next
    }

    private fun failStart(failure: FoxCoreRuntimeFailure): Result<Unit> {
        setState(RuntimeState.ERROR)
        return foxCoreFailure(failure)
    }

    private fun restoreRunningFailure(failure: FoxCoreRuntimeFailure): Result<Unit> {
        if (active != null) {
            setState(RuntimeState.RUNNING)
        } else {
            setState(RuntimeState.ERROR)
        }
        return foxCoreFailure(failure)
    }

    private fun safeReason(reason: String): String =
        reason
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .take(MAX_REASON_LENGTH)
            .ifBlank { "unspecified" }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private data class PreparedNativeReload(
        val current: ActiveFoxCoreSession,
        val translated: FoxCoreSessionConfig,
        val nextFingerprint: String,
    )

    private sealed interface NativeReloadOutcome {
        data class PolicyApplied(
            val translated: FoxCoreSessionConfig,
            val revision: Long,
        ) : NativeReloadOutcome

        data class Replaced(val session: ActiveFoxCoreSession) : NativeReloadOutcome

        data class RestoredFailure(
            val restored: ActiveFoxCoreSession?,
            val failure: FoxCoreRuntimeFailure,
        ) : NativeReloadOutcome

        data object PolicyCallFailed : NativeReloadOutcome
        data class PolicyRefused(val code: Long) : NativeReloadOutcome
        data object StopFailed : NativeReloadOutcome
        data object Superseded : NativeReloadOutcome

        fun startedSessionOrNull(): ActiveFoxCoreSession? =
            when (this) {
                is Replaced -> session
                is RestoredFailure -> restored
                else -> null
            }
    }

    private fun PolicyReloadOutcome.toNativeReloadOutcome(
        translated: FoxCoreSessionConfig,
    ): NativeReloadOutcome =
        when (this) {
            is PolicyReloadOutcome.Applied -> NativeReloadOutcome.PolicyApplied(translated, revision)
            PolicyReloadOutcome.CallFailed -> NativeReloadOutcome.PolicyCallFailed
            is PolicyReloadOutcome.Refused -> NativeReloadOutcome.PolicyRefused(code)
        }

    private sealed interface PendingNativeTransition {
        val generation: Long

        data class Start(
            override val generation: Long,
            val tun: ParcelFileDescriptor? = null,
        ) : PendingNativeTransition

        data class Reload(
            override val generation: Long,
            val session: ActiveFoxCoreSession,
        ) : PendingNativeTransition
    }

    private companion object {
        const val MAX_EVENT_BATCH = 512
        const val MAX_TRAFFIC_EVENT_BATCH = 4_096
        const val MAX_REASON_LENGTH = 96

        /**
         * Replacing the engine on a live TUN escalates — either the old engine is gone or the
         * new one cannot have the tunnel. Declining to escalate left a timed-out stop in the
         * core's reaper with the app claiming to run on it.
         */
        val REPLACEMENT_STOP_POLICY =
            RuntimeStopPolicy(
                closeTunFdImmediately = false,
                closeServiceTimeoutMs = 1_500L,
                closeServerTimeoutMs = 1_500L,
                totalGracefulTimeoutMs = 3_000L,
                forceKillAfterTimeout = true,
            )
    }
}

private sealed interface NativeTransitionPreparation<out Value> {
    data class Ready<Value>(val value: Value) : NativeTransitionPreparation<Value>

    data class Failed(val failure: Result<Unit>) : NativeTransitionPreparation<Nothing>
}
