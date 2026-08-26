package com.foxhole.core.runtime

import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.VpnSession

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

    suspend fun installDnsRuleSet(
        name: String,
        manifest: ByteArray,
        signature: ByteArray,
        artifact: ByteArray,
    ): RuntimeDnsRuleSetInstallOutcome = RuntimeDnsRuleSetInstallOutcome.Deferred

    fun syncLanProxy(
        request: LanProxyRequest?,
        blocked: LanProxyUnavailableReason? = null,
    ): LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    fun lanProxyStatus(): LanProxyStatusSnapshot = LanProxyStatusSnapshot()

    fun syncLocalProxy(request: LocalProxyRequest?): LocalProxyStatusSnapshot = LocalProxyStatusSnapshot()

    fun localProxyStatus(): LocalProxyStatusSnapshot = LocalProxyStatusSnapshot()

    fun syncTorProbeProxy(owner: TorProbeProxyOwner?): Result<TorProbeProxyLease?> =
        Result.failure(TorProbeProxyUnavailableException(TorProbeProxyFailure.NO_RUNTIME))

    /** Read-only current lease; credentials never leave process memory or enter settings. */
    fun torProbeProxyLease(): TorProbeProxyLease? = null

    /** Current owner-correlated typed failure; carries no network identity or credentials. */
    fun torProbeProxyIssue(): TorProbeProxyIssue? = null

    fun releaseTorProbeProxy(owner: TorProbeProxyOwner): Result<Boolean> =
        Result.failure(TorProbeProxyUnavailableException(TorProbeProxyFailure.NO_RUNTIME))

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
