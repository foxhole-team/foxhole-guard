
package com.foxhole.core.runtime

import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.FoxCoreTunPlan
import com.foxhole.core.model.VpnSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger

/**
 * The single Android owner of a FoxCore JNI handle and its kernel TUN. A master descriptor stays
 * in Java while native code owns a duplicate: an immutable outbound change can replace the Rust
 * engine on the same Android interface without dropping the OS VPN, while final stop still has
 * one explicit owner that closes the master.
 */
class FoxCoreRuntime internal constructor(
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val translator: FoxCoreConfigTranslator = FoxCoreConfigTranslator(),
    private val native: FoxCoreNativeApi = JniFoxCoreNativeApi,
    shareNative: FoxCoreShareNativeApi = JniFoxCoreShareNativeApi,
    private val shareRuntime: FoxCoreShareRuntimeAdapter = FoxCoreShareRuntimeAdapter(shareNative),
) : FoxholeRuntime,
    FoxCoreShareRuntime by shareRuntime {
    private val transitionMutex = Mutex()
    private val stateLock = Any()
    private val json = Json { ignoreUnknownKeys = false }
    private val nativeSessionStarter = FoxCoreNativeSessionStarter(native, diagnosticsLogger)

    @Volatile
    private var active: ActiveFoxCoreSession? = null

    @Volatile
    private var state: RuntimeState = RuntimeState.IDLE

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var lastStopReason: String? = null

    @Volatile
    private var cleanupDraining: Boolean = false

    /**
     * Master TUN descriptor number, kept after [active] is cleared: the teardown watchdog runs
     * when the session is gone and otherwise cannot tell the master we hold by design from a
     * descriptor the core failed to close — and its answer to a leak is killing the process.
     * Cleared only where the master is actually closed.
     */
    @Volatile
    private var masterTunFd: Int? = null

    override suspend fun start(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> =
        transitionMutex.withLock {
            if (active != null) {
                return@withLock foxCoreFailure(FoxCoreRuntimeFailure.ALREADY_RUNNING)
            }
            setState(RuntimeState.PREPARING)
            val translated = translateOrFailure(session, expectedPolicyRevision = null)
                ?: return@withLock foxCoreFailure(FoxCoreRuntimeFailure.CONFIG_REJECTED)
            val preflight = preflightNative()
            if (preflight.isFailure) {
                setState(RuntimeState.ERROR)
                return@withLock preflight
            }
            val underlying = host.currentUnderlyingNetwork()
                ?: return@withLock failStart(FoxCoreRuntimeFailure.UNDERLYING_NETWORK_UNAVAILABLE)
            if (underlying.networkHandle <= 0L) {
                return@withLock failStart(FoxCoreRuntimeFailure.UNDERLYING_NETWORK_UNAVAILABLE)
            }
            val tun = establishTun(host, underlying, translated.tunPlan)
                ?: return@withLock failStart(FoxCoreRuntimeFailure.TUN_ESTABLISH_FAILED)
            setState(RuntimeState.STARTING)
            val started = startNative(tun, underlying, host, translated)
            if (started == null) {
                closeTun(tun)
                return@withLock failStart(FoxCoreRuntimeFailure.NATIVE_START_FAILED)
            }
            publish(started.copy(dnsServers = translated.tunPlan.advertisedDnsServers))
            Result.success(Unit)
        }

    override suspend fun reload(
        session: VpnSession,
        host: RuntimeServiceHost,
    ): Result<Unit> =
        transitionMutex.withLock {
            val current = active
                ?: return@withLock foxCoreFailure(FoxCoreRuntimeFailure.NOT_RUNNING)
            setState(RuntimeState.RELOADING)
            val translated = translateOrFailure(session, current.policyRevision)
                ?: return@withLock restoreRunningFailure(FoxCoreRuntimeFailure.CONFIG_REJECTED)
            if (translated.tunPlan != current.translated.tunPlan) {
                return@withLock restoreRunningFailure(FoxCoreRuntimeFailure.TUN_PLAN_CHANGED)
            }
            val nextFingerprint = nativeSessionStarter.immutableEngineFingerprint(translated.engineConfigJson)
                ?: return@withLock restoreRunningFailure(FoxCoreRuntimeFailure.CONFIG_REJECTED)

            if (nextFingerprint == current.immutableFingerprint) {
                val applied = reloadPolicy(current, translated)
                if (applied.isSuccess) {
                    return@withLock applied
                }
                // A revision conflict is the one refusal that is a retry, not an answer: this
                // app is the only policy writer (a signed DNS rule-set install bumps the
                // revision). Retry once without the guard rather than silently dropping a
                // routing change — which is what collapsing all eight codes into one failure did.
                val refusal = (applied.exceptionOrNull() as? FoxCoreRuntimeException)?.failure
                if (refusal != FoxCoreRuntimeFailure.POLICY_REVISION_CONFLICT) {
                    return@withLock applied
                }
                val unconditional = translateOrFailure(session, expectedPolicyRevision = null)
                    ?: return@withLock restoreRunningFailure(FoxCoreRuntimeFailure.CONFIG_REJECTED)
                return@withLock reloadPolicy(current, unconditional)
            }

            replaceNativeOnExistingTun(
                current = current,
                next = translated,
                nextFingerprint = nextFingerprint,
                host = host,
            )
        }

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult =
        transitionMutex.withLock {
            val startedAt = System.nanoTime()
            val current = active
            if (current == null) {
                setState(RuntimeState.IDLE)
                return@withLock RuntimeStopResult(
                    closeServiceOk = true,
                    closeServerOk = true,
                    tunClosed = true,
                    escalatedToKill = false,
                    elapsedMs = elapsedMillis(startedAt),
                )
            }
            setState(RuntimeState.STOPPING)
            cleanupDraining = true
            val stop = stopNative(current.handle, policy)
            val nativeStopped = stop.stopped
            val nativeTunProbe = probeTunDescriptor(current.nativeTunFd)
            val tunClosed =
                if (shouldCloseMasterTun(nativeTunProbe)) {
                    closeTun(current.tun)
                } else {
                    false
                }
            applyTeardownSettlement(nativeReleased = nativeStopped, tunClosed = tunClosed, reason = "stop")
            recordTunOwnershipAfterClose(
                current = current,
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
            val current = active
            if (current == null) {
                lastStopReason = safeReason(reason)
                setState(RuntimeState.IDLE)
                return@withLock RuntimeKillResult(
                    reason = safeReason(reason),
                    tunClosed = true,
                    serverDetached = true,
                )
            }
            setState(RuntimeState.KILLING)
            cleanupDraining = true
            val killed = forceKillNative(current.handle)
            val nativeTunProbe = probeTunDescriptor(current.nativeTunFd)
            val tunClosed =
                if (shouldCloseMasterTun(nativeTunProbe)) {
                    closeTun(current.tun)
                } else {
                    false
                }
            applyTeardownSettlement(nativeReleased = killed, tunClosed = tunClosed, reason = reason)
            recordTunOwnershipAfterClose(
                current = current,
                nativeTunProbe = nativeTunProbe,
                masterTunClosed = tunClosed,
                reason = "force_kill",
            )
            cleanupDraining = false
            RuntimeKillResult(
                reason = safeReason(reason),
                tunClosed = tunClosed,
                serverDetached = killed,
                closeDetached = !killed || !tunClosed,
            )
        }

    override fun nativeSnapshot(): NativeRuntimeSnapshot {
        val current = active
        return NativeRuntimeSnapshot(
            hasEngineHandle = current != null,
            hasTunFileDescriptor = current?.tun?.fileDescriptor?.valid() == true,
            hasHost = current != null,
            hasConfig = current != null,
            dnsServerAddress = current?.dnsServers?.firstOrNull(),
            nativeGeneration = generation,
            nativeState = state,
            cleanupDraining = cleanupDraining,
            lastStopReason = lastStopReason,
            lastCloseDetached = false,
            masterTunFd = masterTunFd,
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

    override fun drainRuntimeAuditEventsJson(max: Int): String? =
        active?.let { current ->
            runCatching { native.drainEvents(current.handle, max.coerceIn(1, MAX_EVENT_BATCH)) }
                .getOrNull()
        }

    private fun translateOrFailure(
        session: VpnSession,
        expectedPolicyRevision: Long?,
    ): FoxCoreSessionConfig? =
        try {
            translator.requirePrepared(session, expectedPolicyRevision)
        } catch (error: FoxCoreConfigTranslationException) {
            diagnosticsLogger.recordStructured(
                "foxcore",
                "prepared config rejected",
                "reason=${error.rejection.name.lowercase()}",
                "path=${error.path}",
                // The explanation is a sentence this repository wrote — nothing from the profile.
                error.explanation?.let { "detail=$it" },
            )
            null
        } catch (_: Exception) {
            diagnosticsLogger.record("foxcore", "prepared config validation failed")
            null
        }

    /** See the top-level [preflightNative]: tells a missing library from an ABI mismatch. */
    private fun preflightNative(): Result<Unit> =
        preflightNative(native, diagnosticsLogger, json)

    private fun establishTun(
        host: RuntimeServiceHost,
        underlying: Network,
        plan: FoxCoreTunPlan,
    ): ParcelFileDescriptor? {
        if (!host.hasVpnPermission()) {
            return null
        }
        val builder =
            host.createTunBuilder()
                ?.setSession("FoxHole")
                ?.setMtu(plan.mtu)
                ?: return null
        builder.setUnderlyingNetworks(arrayOf(underlying))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(isMetered(host, underlying))
        }
        builder.addAddress(plan.ipv4Address, plan.ipv4PrefixLength)
        val ipv6Address = plan.ipv6Address
        val ipv6PrefixLength = plan.ipv6PrefixLength
        if (ipv6Address != null && ipv6PrefixLength != null) {
            builder.addAddress(ipv6Address, ipv6PrefixLength)
        }
        plan.routes.forEach { route ->
            builder.addRoute(route.address, route.prefixLength)
        }
        if (!applyApplicationSplit(builder, plan)) {
            return null
        }
        if (plan.advertisedDnsServers.isEmpty()) {
            return null
        }
        plan.advertisedDnsServers.forEach(builder::addDnsServer)
        return runCatching { builder.establish() }
            .onFailure { diagnosticsLogger.record("foxcore", "TUN establish failed") }
            .getOrNull()
            ?.also { established -> masterTunFd = established.fd }
    }

    private fun applyApplicationSplit(
        builder: android.net.VpnService.Builder,
        plan: FoxCoreTunPlan,
    ): Boolean {
        val include = plan.allowedApplications
        val exclude = plan.disallowedApplications
        if (include.isNotEmpty() && exclude.isNotEmpty()) {
            return false
        }
        var applied = 0
        var skipped = 0
        val packages = if (include.isNotEmpty()) include else exclude
        packages.forEach { packageName ->
            try {
                if (include.isNotEmpty()) {
                    builder.addAllowedApplication(packageName)
                } else {
                    builder.addDisallowedApplication(packageName)
                }
                applied += 1
            } catch (_: PackageManager.NameNotFoundException) {
                skipped += 1
            }
        }
        diagnosticsLogger.recordStructured(
            "split",
            "FoxCore application split prepared",
            "mode=${if (include.isNotEmpty()) "include" else if (exclude.isNotEmpty()) "exclude" else "all"}",
            "requested=${packages.size}",
            "applied=$applied",
            "skipped=$skipped",
        )
        return include.isEmpty() || applied > 0
    }

    private fun startNative(
        tun: ParcelFileDescriptor,
        network: Network,
        host: RuntimeServiceHost,
        translated: FoxCoreSessionConfig,
    ): ActiveFoxCoreSession? =
        nativeSessionStarter
            .start(
                tun = tun,
                network = network,
                host = host,
                translated = translated,
            )?.let { started ->
                ActiveFoxCoreSession(
                    handle = started.handle,
                    tun = tun,
                    masterTunFd = tun.fd,
                    nativeTunFd = started.nativeTunFd,
                    host = host,
                    translated = translated,
                    immutableFingerprint = started.immutableFingerprint,
                    policyRevision = started.policyRevision,
                    networkHandle = network.networkHandle,
                    dnsServers = emptyList(),
                )
            }

    private fun reloadPolicy(
        current: ActiveFoxCoreSession,
        translated: FoxCoreSessionConfig,
    ): Result<Unit> =
        when (val outcome = attemptPolicyReload(current, translated)) {
            is PolicyReloadOutcome.Applied -> {
                publish(
                    current.copy(
                        translated = translated,
                        policyRevision = outcome.revision,
                    ),
                )
                Result.success(Unit)
            }
            PolicyReloadOutcome.CallFailed ->
                restoreRunningFailure(FoxCoreRuntimeFailure.POLICY_RELOAD_FAILED)
            is PolicyReloadOutcome.Refused ->
                restoreRunningFailure(policyReloadFailure(outcome.code))
        }

    private fun attemptPolicyReload(
        current: ActiveFoxCoreSession,
        translated: FoxCoreSessionConfig,
    ): PolicyReloadOutcome =
        attemptPolicyReload(
            native = native,
            handle = current.handle,
            policyJson = translated.policyConfigJson,
            diagnosticsLogger = diagnosticsLogger,
        )

    private suspend fun replaceNativeOnExistingTun(
        current: ActiveFoxCoreSession,
        next: FoxCoreSessionConfig,
        nextFingerprint: String,
        host: RuntimeServiceHost,
    ): Result<Unit> {
        val stopped = stopNative(current.handle, REPLACEMENT_STOP_POLICY)
        if (!stopped.stopped) {
            // Not `restoreRunningFailure`: a graceful stop that timed out hands the worker to
            // the core's reaper, which drops the handle from the registry. Publishing RUNNING
            // again would leave the UI connected over a handle resolving to nothing (empty
            // stats, reload accepted and ignored) — traffic into a TUN nobody reads, which looks
            // exactly like a working tunnel. Fail closed: the TUN stays up so nothing escapes,
            // and the session is genuinely over.
            clearActive(reason = "replace_stop_failed")
            setState(RuntimeState.ERROR)
            return foxCoreFailure(FoxCoreRuntimeFailure.NATIVE_STOP_FAILED)
        }
        val network = host.currentUnderlyingNetwork()
        if (network == null || network.networkHandle <= 0L) {
            restorePreviousNative(current)
            return restoreRunningFailure(FoxCoreRuntimeFailure.UNDERLYING_NETWORK_UNAVAILABLE)
        }
        val replacement =
            startNative(current.tun, network, host, next)
                ?.copy(immutableFingerprint = nextFingerprint, dnsServers = current.dnsServers)
        if (replacement != null) {
            publish(replacement)
            return Result.success(Unit)
        }
        val restored = restorePreviousNative(current)
        return if (restored) {
            restoreRunningFailure(FoxCoreRuntimeFailure.NATIVE_START_FAILED)
        } else {
            setState(RuntimeState.ERROR)
            foxCoreFailure(FoxCoreRuntimeFailure.ROLLBACK_FAILED)
        }
    }

    private fun restorePreviousNative(previous: ActiveFoxCoreSession): Boolean {
        val network = previous.host.currentUnderlyingNetwork() ?: return false
        val restored =
            startNative(
                tun = previous.tun,
                network = network,
                host = previous.host,
                translated = previous.translated,
            ) ?: return false
        publish(
            restored.copy(
                policyRevision = previous.policyRevision,
                dnsServers = previous.dnsServers,
            ),
        )
        return true
    }

    private suspend fun stopNative(
        handle: Long,
        policy: RuntimeStopPolicy,
    ): NativeStopOutcome {
        val result = AtomicInteger(FoxholeNativeEngine.STOP_PANICKED)
        val completed =
            runBlockingRuntimeClose(policy.totalGracefulTimeoutMs) {
                result.set(native.stop(handle))
            }
        val stopped =
            completed &&
                result.get() in
                setOf(
                    FoxholeNativeEngine.STOPPED,
                    FoxholeNativeEngine.ALREADY_STOPPED,
                    FoxholeNativeEngine.STOP_UNKNOWN_HANDLE,
                )
        if (stopped || !policy.forceKillAfterTimeout) {
            return NativeStopOutcome(stopped = stopped, escalated = false)
        }
        return NativeStopOutcome(
            stopped = forceKillNative(handle),
            escalated = true,
        )
    }

    /**
     * Force-kill releases the handle whatever the worker does next. `STOP_TIMED_OUT` belongs in
     * the accepted set: the native side removes the handle from its registry BEFORE it can
     * report a timeout (the timeout describes a worker sent to quarantine, not a runtime still
     * ours). Treating it as a failed kill left [active] non-null forever — every later [start]
     * answered ALREADY_RUNNING until the process died. `STOP_PANICKED` and a Kotlin-side timeout
     * stay failures: there the call never reached the handle release.
     */
    private suspend fun forceKillNative(handle: Long): Boolean {
        val result = AtomicInteger(FoxholeNativeEngine.STOP_PANICKED)
        val completed =
            runBlockingRuntimeClose(FORCE_KILL_TIMEOUT_MS) {
                result.set(native.forceKill(handle))
            }
        return completed && forceKillReleasedHandle(result.get())
    }

    private fun publish(next: ActiveFoxCoreSession) {
        synchronized(stateLock) {
            generation += 1L
            active = next
            shareRuntime.attach(next.handle)
            state = RuntimeState.RUNNING
            lastStopReason = null
        }
        diagnosticsLogger.recordStructured(
            "foxcore",
            "native runtime active",
            "generation=$generation",
            "policy_revision=${next.policyRevision}",
        )
        recordUnavailableOutbounds(next.handle)
    }

    private fun recordUnavailableOutbounds(handle: Long) {
        val unavailable =
            runCatching { native.stats(handle) }
                .getOrNull()
                ?.let(::parseNativeUnavailableOutbounds)
                .orEmpty()
        unavailable.forEach { outbound ->
            diagnosticsLogger.recordStructured(
                "foxcore",
                "native outbound unavailable",
                "id=${outbound.id}",
                "kind=${outbound.kind}",
                "reason=${outbound.reason}",
                "attempts=${outbound.attempts}",
                outbound.message.takeIf { outbound.kind == "tor" }?.let { "message=$it" },
            )
        }
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
            shareRuntime.detach()
            generation += 1L
            state = RuntimeState.IDLE
            lastStopReason = safeReason(reason)
        }
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

    private fun isMetered(
        host: RuntimeServiceHost,
        network: Network,
    ): Boolean {
        val connectivity =
            host.runtimeContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(network)
        return capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    private fun closeTun(tun: ParcelFileDescriptor): Boolean =
        runCatching {
            tun.close()
            val closed = !tun.fileDescriptor.valid()
            if (closed) {
                // Only here, and only on a close that took: while this points at a descriptor,
                // the teardown watchdog must treat it as ours, not a leak.
                masterTunFd = null
            }
            closed
        }.onFailure {
            diagnosticsLogger.record("foxcore", "TUN close failed")
        }.getOrDefault(false)

    private fun recordTunOwnershipAfterClose(
        current: ActiveFoxCoreSession,
        nativeTunProbe: NativeTunDescriptorProbe,
        masterTunClosed: Boolean,
        reason: String,
    ) {
        val masterTunProbe = probeTunDescriptor(current.masterTunFd)
        diagnosticsLogger.recordStructured(
            "foxcore",
            "TUN ownership after native close",
            "reason=$reason",
            "master_fd=${current.masterTunFd}",
            "master_fd_state=${masterTunProbe.name.lowercase()}",
            "master_fd_open=${masterTunProbe == NativeTunDescriptorProbe.OPEN}",
            "master_close_ok=$masterTunClosed",
            "native_fd=${current.nativeTunFd}",
            "native_fd_state=${nativeTunProbe.name.lowercase()}",
            "native_fd_open=${nativeTunProbe == NativeTunDescriptorProbe.OPEN}",
            // Only when something is still held: clean teardowns must not add a line per stop to
            // an exportable journal; unclean ones need to say whether the core gave up waiting
            // for its own descriptor.
            "core=${foxCoreLastStopDiagnostics()}"
                .takeIf { nativeTunProbe == NativeTunDescriptorProbe.OPEN || !masterTunClosed },
        )
    }

    private fun probeTunDescriptor(fd: Int): NativeTunDescriptorProbe =
        try {
            if (Os.readlink("/proc/self/fd/$fd").startsWith("/dev/tun")) {
                NativeTunDescriptorProbe.OPEN
            } else {
                NativeTunDescriptorProbe.CLOSED
            }
        } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.EBADF) {
                NativeTunDescriptorProbe.CLOSED
            } else {
                NativeTunDescriptorProbe.UNKNOWN
            }
        } catch (_: RuntimeException) {
            NativeTunDescriptorProbe.UNKNOWN
        }

    private fun safeReason(reason: String): String =
        reason
            .lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_")
            .take(MAX_REASON_LENGTH)
            .ifBlank { "unspecified" }

    private fun elapsedMillis(startedAtNanos: Long): Long =
        (System.nanoTime() - startedAtNanos).coerceAtLeast(0L) / 1_000_000L

    private companion object {
        const val FORCE_KILL_TIMEOUT_MS = 1_500L
        const val MAX_EVENT_BATCH = 512
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
