package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.CONNECTIVITY_PROBE_ENDPOINTS
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.shouldPreferIpv4TunnelValidation
import com.foxhole.core.runtime.stoppedRuntimeSnapshot
import com.foxhole.core.runtime.tunnelValidationRequestNetwork
import com.foxhole.guard.R
import kotlinx.coroutines.delay

// Foreground VPN-network reconciliation for FoxholeConnectionController: detects a stale tunnel
// snapshot whose VPN network vanished (process restart, system kill) and either restores the
// network binding or fails the session closed. Extracted from the controller body (split by
// responsibility); behaviour-preserving extension functions on the same class.

suspend fun FoxholeConnectionController.reconcileActiveVpnNetworkIfNeeded(): Boolean {
    val currentSnapshot = snapshot.value
    val vpnNetwork = currentVpnNetwork()
    return when {
        currentSnapshot.isStaleTunnelSnapshotWithoutVpn(vpnNetwork) -> {
            val restoredVpnNetwork = awaitVpnNetworkForActiveSnapshot()
            if (restoredVpnNetwork != null) {
                diagnosticsLogger.record(
                    "connection",
                    "active tunnel snapshot kept after vpn network appeared during foreground grace",
                )
                false
            } else {
                diagnosticsLogger.record(
                    "connection",
                    "active tunnel snapshot failed closed after foreground grace; vpn network missing",
                )
                failClosedMissingActiveVpnNetwork()
                false
            }
        }

        currentSnapshot.state in ACTIVE_CONNECTION_STATES || vpnNetwork == null -> false

        RuntimeResumeStateStore.hasRecentUserStop(context) -> {
            diagnosticsLogger.record(
                "connection",
                "active vpn network found after recent user stop; teardown requested instead of restore",
            )
            clearAppliedRuntime()
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                stoppedRuntimeSnapshot(
                    previous = currentSnapshot,
                    trafficMode = settingsRepository.current().traffic.mode,
                ),
                refreshLastChangeAt = false,
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = true,
            )
            false
        }

        settingsRepository.current().localGuardModeOrNull() != null -> {
            diagnosticsLogger.record(
                "connection",
                "active local guard vpn found with idle snapshot",
            )
            false
        }

        else -> restoreActiveVpnNetwork(vpnNetwork)
    }
}

private suspend fun FoxholeConnectionController.failClosedMissingActiveVpnNetwork() {
    clearAppliedRuntime()
    FoxholeVpnRuntimeBridge.clearTransientState()
    FoxholeVpnRuntimeBridge.update(
        failClosedMissingActiveVpnNetworkSnapshot(
            trafficMode = settingsRepository.current().traffic.mode,
            message = context.getString(R.string.error_runtime_stopped),
        ),
    )
}

private suspend fun FoxholeConnectionController.awaitVpnNetworkForActiveSnapshot(
    timeoutMs: Long = 1_500L,
    pollMs: Long = 100L,
): Network? {
    var vpnNetwork = currentVpnNetwork()
    val deadline = System.currentTimeMillis() + timeoutMs
    while (vpnNetwork == null && System.currentTimeMillis() < deadline) {
        delay(pollMs)
        vpnNetwork = currentVpnNetwork()
    }
    return vpnNetwork
}

private suspend fun FoxholeConnectionController.restoreActiveVpnNetwork(vpnNetwork: Network): Boolean {
    val activeProfile = profileRepository.getActiveProfile()
    val resumeState = RuntimeResumeStateStore.read(context)
    val restoredProtocolOptionId =
        resumeState
            ?.protocolOptionId
            ?.takeIf { resumeState.profileId == activeProfile?.id }
            ?: activeProfile?.selectedProtocolOptionId
    val restoredProtocolHint =
        activeProfile
            ?.protocolOptions
            ?.firstOrNull { option -> option.id == restoredProtocolOptionId }
            ?.protocolHint
            ?: activeProfile?.protocolHint
    diagnosticsLogger.record(
        "connection",
        "active vpn network found with idle snapshot; validating before restore",
    )
    FoxholeVpnRuntimeBridge.update(
        ConnectionSnapshot(
            state = ConnectionState.RECONNECTING,
            trafficMode = TrafficMode.TUNNEL,
            profileId = activeProfile?.id,
            profileName = activeProfile?.name,
            protocolHint = restoredProtocolHint,
            protocolOptionId = restoredProtocolOptionId,
            message = context.getString(R.string.status_reconnecting),
        ),
    )
    return validateRestoredVpnNetwork(vpnNetwork).fold(
        onSuccess = { validation ->
            diagnosticsLogger.record(
                "connection",
                when {
                    validation.ipInfo != null -> "active vpn restore passed vpn-bound ip validation"
                    else -> "active vpn restore passed vpn-bound endpoint validation"
                },
            )
            validation.ipInfo?.let(FoxholeVpnRuntimeBridge::updateIpInfo)
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = activeProfile?.id,
                    profileName = activeProfile?.name,
                    protocolHint = restoredProtocolHint,
                    protocolOptionId = restoredProtocolOptionId,
                ),
            )
            true
        },
        onFailure = { error ->
            diagnosticsLogger.record(
                "connection",
                "active vpn restore failed vpn-bound validation: ${error.message.orEmpty()}",
            )
            clearAppliedRuntime()
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = settingsRepository.current().traffic.mode,
                    message = context.getString(R.string.error_dns_probe_failed),
                ),
            )
            false
        },
    )
}

private suspend fun FoxholeConnectionController.validateRestoredVpnNetwork(vpnNetwork: Network): Result<RestoredVpnValidation> =
    runCatching {
        RestoredVpnValidation(ipInfo = refreshRestoredVpnIpInfo(vpnNetwork))
    }.recoverCatching { ipError ->
        diagnosticsLogger.record(
            "connection",
            "active vpn restore ip validation failed, trying vpn-bound endpoint probe: ${ipError.message.orEmpty()}",
        )
        probeRestoredVpnConnectivityEndpoint(vpnNetwork)
        RestoredVpnValidation(ipInfo = null)
    }

private suspend fun FoxholeConnectionController.refreshRestoredVpnIpInfo(vpnNetwork: Network): IpInfo {
    val endpoint = settingsRepository.current().connection.ipInfoEndpoint
    val activeProfile = profileRepository.getActiveProfile()
    val resolvedConfig =
        activeProfile?.id?.let { profileId ->
            runCatching { profileRepository.getResolvedConfig(profileId) }.getOrNull()
        }
    val preferIpv4Validation =
        shouldPreferIpv4TunnelValidation(
            protocolHint = activeProfile?.protocolHint,
            configJson = resolvedConfig,
        )
    val info =
        fetchRestoredVpnIpInfoOnProcessPath(
            endpoint = endpoint,
            vpnNetwork = vpnNetwork,
            preferIpv4Validation = preferIpv4Validation,
        )
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = emptyList(),
    )
}

private suspend fun FoxholeConnectionController.fetchRestoredVpnIpInfoOnProcessPath(
    endpoint: String,
    vpnNetwork: Network,
    preferIpv4Validation: Boolean,
): IpInfo {
    val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetwork()
    return if (preferIpv4Validation) {
        ipInfoRepository.fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            network = requestNetwork,
            resolverNetwork = resolverNetwork,
        ) ?: error("vpn ipv4 refresh failed")
    } else {
        ipInfoRepository.fetch(
            endpoint = endpoint,
            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            network = requestNetwork,
            resolverNetwork = resolverNetwork,
            mode = IpInfoFetchMode.FULL,
        )
    }
}

private suspend fun FoxholeConnectionController.probeRestoredVpnConnectivityEndpoint(vpnNetwork: Network) {
    val activeProfile = profileRepository.getActiveProfile()
    val resolvedConfig =
        activeProfile?.id?.let { profileId ->
            runCatching { profileRepository.getResolvedConfig(profileId) }.getOrNull()
        }
    val preferIpv4Validation =
        shouldPreferIpv4TunnelValidation(
            protocolHint = activeProfile?.protocolHint,
            configJson = resolvedConfig,
        )
    val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetwork()
    var lastFailure: Throwable? = null
    restoredVpnConnectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatching {
                if (preferIpv4Validation) {
                    ipInfoRepository.probeIpv4(
                        endpoint = endpoint,
                        callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                        network = requestNetwork,
                        resolverNetwork = resolverNetwork,
                    )
                } else {
                    ipInfoRepository.probe(
                        endpoint = endpoint,
                        callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                        network = requestNetwork,
                        resolverNetwork = resolverNetwork,
                    )
                }
            }.recoverCatching {
                ipInfoRepository.probeIpv4(
                    endpoint = endpoint,
                    callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                    network = requestNetwork,
                    resolverNetwork = resolverNetwork,
                )
            }
        if (result.isSuccess) {
            diagnosticsLogger.record("connection", "active vpn restore endpoint probe ok: $endpoint")
            return
        }
        lastFailure = result.exceptionOrNull()
        diagnosticsLogger.record(
            "connection",
            "active vpn restore endpoint probe failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
    }
    throw lastFailure ?: error("vpn endpoint validation failed")
}

private suspend fun FoxholeConnectionController.restoredVpnConnectivityProbeEndpoints(): List<String> {
    val preferredEndpoint = settingsRepository.current().connection.ipInfoEndpoint.trim()
    return buildList {
        preferredEndpoint.takeIf(String::isNotBlank)?.let(::add)
        CONNECTIVITY_PROBE_ENDPOINTS.forEach { endpoint ->
            if (endpoint != preferredEndpoint) {
                add(endpoint)
            }
        }
    }
}

private data class RestoredVpnValidation(
    val ipInfo: IpInfo?,
)

private fun ConnectionSnapshot.isStaleTunnelSnapshotWithoutVpn(vpnNetwork: Network?): Boolean =
    trafficMode == TrafficMode.TUNNEL &&
        state in STALE_VPN_SNAPSHOT_STATES &&
        vpnNetwork == null
