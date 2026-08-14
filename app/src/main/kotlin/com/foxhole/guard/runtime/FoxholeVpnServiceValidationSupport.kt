package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.TunnelValidationEvidence
import com.foxhole.core.runtime.TunnelValidationGracePolicy
import com.foxhole.core.runtime.VpnHealthProbeTarget
import java.net.InetAddress

/**
 * Tunnel-validation orchestration and connectivity-probe entry points for [FoxholeVpnService],
 * extracted from the service body in the Phase B split by responsibility. These delegate to the
 * validation/probe implementations in RuntimeValidationCoordinator and related support files.
 */

internal fun FoxholeVpnService.scheduleValidation(
    session: VpnSession,
    failOnFailure: Boolean,
    expectedFreshVpnNetworkHandle: Long? = null,
    expectedFreshVpnInterfaceName: String? = null,
    onSuccess: (Network) -> Unit,
) = scheduleValidationInternal(
    session,
    failOnFailure,
    expectedFreshVpnNetworkHandle,
    expectedFreshVpnInterfaceName,
    onSuccess,
)

internal fun FoxholeVpnService.beginValidationEpoch(reason: String): Long {
    validationJob?.cancel()
    validationJob = null
    validationEpoch += 1L
    container.diagnosticsLogger.record("dns", "validation epoch started reason=$reason epoch=$validationEpoch")
    return validationEpoch
}

internal fun FoxholeVpnService.invalidateValidationEpoch(reason: String) {
    validationJob?.cancel()
    validationJob = null
    validationEpoch += 1L
    container.diagnosticsLogger.record("dns", "validation epoch invalidated reason=$reason epoch=$validationEpoch")
}

internal fun FoxholeVpnService.isCurrentValidationEpoch(epoch: Long): Boolean = validationEpoch == epoch

internal suspend fun FoxholeVpnService.validateTunnelConnectivity(
    expectedFreshVpnNetworkHandle: Long? = null,
    expectedFreshVpnInterfaceName: String? = null,
    session: VpnSession? = null,
): Result<Network> =
    validateTunnelConnectivityInternal(expectedFreshVpnNetworkHandle, expectedFreshVpnInterfaceName, session)

internal fun FoxholeVpnService.inspectValidatedTunnelEvidence(validationStartedAt: Long): TunnelValidationEvidence? =
    inspectValidatedTunnelEvidenceInternal(validationStartedAt)

internal suspend fun FoxholeVpnService.retryValidatedTunnelConnectivityWithGrace(
    vpnNetwork: Network,
    policy: TunnelValidationGracePolicy,
    preferIpv4: Boolean = false,
    session: VpnSession? = null,
): Result<Unit> = retryValidatedTunnelConnectivityWithGraceInternal(vpnNetwork, policy, preferIpv4, session)

internal suspend fun FoxholeVpnService.probeDnsIndependentConnectivityFallback(
    callTimeoutMs: Long,
    network: Network? = null,
) = probeDnsIndependentConnectivityFallbackInternal(callTimeoutMs, network)

internal suspend fun FoxholeVpnService.refreshValidatedTunnelIpInfoBestEffort(
    vpnNetwork: Network,
    session: VpnSession? = null,
) = refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork, session)

internal suspend fun FoxholeVpnService.probeConnectivityEndpoints(
    callTimeoutMs: Long = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
    network: Network? = null,
    resolverNetwork: Network? = null,
    preferIpv4: Boolean = false,
) = probeConnectivityEndpointsInternal(callTimeoutMs, network, resolverNetwork, preferIpv4)

internal suspend fun FoxholeVpnService.connectivityProbeEndpoints(): List<String> = connectivityProbeEndpointsInternal()

internal suspend fun FoxholeVpnService.runNotificationConnectivityProbe(): Boolean =
    runNotificationConnectivityProbeInternal()

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsOverLocalProxy(
    proxy: com.foxhole.core.runtime.network.HttpProxyAccess,
    callTimeoutMs: Long,
) = probeConnectivityEndpointsOverLocalProxyInternal(proxy, callTimeoutMs)

internal fun FoxholeVpnService.probeSessionTarget(
    target: VpnHealthProbeTarget,
    network: Network? = null,
    timeoutMs: Long = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
) = probeSessionTargetInternal(target, network, timeoutMs)

internal fun FoxholeVpnService.resolveProbeAddress(
    host: String,
    network: Network? = null,
): InetAddress = resolveProbeAddressInternal(host, network)
