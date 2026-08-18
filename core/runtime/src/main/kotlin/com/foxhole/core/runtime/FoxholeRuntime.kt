package com.foxhole.core.runtime

import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.VpnSession

/**
 * FOXHOLE RUNTIME — the Android control-plane seam for the Foxhole Core engine.
 *
 * The service owns Android lifecycle and the master TUN descriptor. Foxhole Core owns all packet,
 * protocol, DNS, Tor and routing work behind this contract.
 */
interface FoxholeRuntime {
    suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun quiesceForInterfaceHandover(): Boolean

    suspend fun stop(policy: RuntimeStopPolicy = RuntimeStopPolicy()): RuntimeStopResult

    suspend fun forceKill(reason: String): RuntimeKillResult =
        RuntimeKillResult(
            reason = reason,
            tunClosed = true,
            serverDetached = false,
        )

    fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot.NONE

    fun currentDnsServerAddress(): String? = null

    fun runtimeStatsJson(): String? = null

    fun runtimeTrafficMapJson(): String? = null

    fun drainRuntimeTrafficEventsJson(max: Int): String? = null

    fun drainRuntimeAuditEventsJson(max: Int): String? = null

    /** Installs an already persisted signed DNS bundle into the current engine generation. */
    suspend fun installDnsRuleSet(
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): RuntimeDnsRuleSetInstallOutcome = RuntimeDnsRuleSetInstallOutcome.Deferred

    /**
     * Brings the LAN proxy in line with [request] on the live session, or takes it down when the
     * request is null. [blocked] states a reason the caller already knows makes publishing
     * impossible (no Wi-Fi, no password, a packet-tunnel profile) so the status carries the honest
     * cause instead of an anonymous failure. Returns what the core reports back.
     */
    fun syncLanProxy(
        request: LanProxyRequest?,
        blocked: LanProxyUnavailableReason? = null,
    ): LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    fun lanProxyStatus(): LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    /**
     * The device-local proxy: brings the named loopback listener in line with [request], or takes
     * it down when the request is null. Returns what the core reports, including the port it bound
     * — which the caller cannot know in advance, because an ephemeral port is the recommended form.
     */
    fun syncLocalProxy(request: LocalProxyRequest?): LocalProxyStatusSnapshot = LocalProxyStatusSnapshot()

    fun localProxyStatus(): LocalProxyStatusSnapshot = LocalProxyStatusSnapshot()

    /** Private authenticated Tor-only HTTP CONNECT probe, scoped to one runtime generation. */
    fun syncTorProbeProxy(owner: TorProbeProxyOwner?): Result<TorProbeProxyLease?> =
        Result.failure(TorProbeProxyUnavailableException(TorProbeProxyFailure.NO_RUNTIME))

    /** Read-only current lease; credentials never leave process memory or enter settings. */
    fun torProbeProxyLease(): TorProbeProxyLease? = null

    /** Current owner-correlated typed failure; carries no network identity or credentials. */
    fun torProbeProxyIssue(): TorProbeProxyIssue? = null

    /** Stale-safe transition teardown: closes only the listener still owned by [owner]. */
    fun releaseTorProbeProxy(owner: TorProbeProxyOwner): Result<Boolean> =
        Result.failure(TorProbeProxyUnavailableException(TorProbeProxyFailure.NO_RUNTIME))

    /**
     * Cuts the live flows matching [target] on the running session.
     *
     * Separate from [reload] on purpose: a reload leaves open flows alone, because a routing change
     * must not kill a download. Blocking an app is the opposite promise and needs this call as
     * well — otherwise "blocked" only takes effect when the app's sockets happen to close.
     */
    fun revokeFlows(target: RevokeTarget): RevokeOutcome = RevokeOutcome.NotRunning

    fun onDefaultNetworkAvailable() {
    }

    fun onDefaultNetworkLost() {
    }
}

sealed interface RuntimeDnsRuleSetInstallOutcome {
    data class Installed(val revision: Long) : RuntimeDnsRuleSetInstallOutcome

    data object Deferred : RuntimeDnsRuleSetInstallOutcome

    data object Superseded : RuntimeDnsRuleSetInstallOutcome

    data object Rejected : RuntimeDnsRuleSetInstallOutcome
}
