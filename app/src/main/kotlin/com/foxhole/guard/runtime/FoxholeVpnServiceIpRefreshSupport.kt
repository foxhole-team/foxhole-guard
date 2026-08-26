package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.mergeIpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun FoxholeVpnService.startGeoRefresh() {
    stopGeoRefresh()
    if (container.settingsRepository.settings.value.connection.geoOfflineMode) {
        return
    }
    geoRefreshJob =
        scope.launch(Dispatchers.IO) {
            if (FoxholeVpnService.GEO_REFRESH_INITIAL_DELAY_MS > 0) {
                delay(FoxholeVpnService.GEO_REFRESH_INITIAL_DELAY_MS)
            }
            repeat(FoxholeVpnService.GEO_REFRESH_ATTEMPTS) { attempt ->
                val success =
                    runCatchingUnlessCancelled {
                        refreshAppOwnedIpInfo(callTimeoutMs = FoxholeVpnService.GEO_REFRESH_CALL_TIMEOUT_MS)
                    }
                        .onSuccess {
                            val allowNewDeviceAddress = shouldAcceptNewDeviceIpInfoFromAppOwnedRefresh()
                            val deviceInfoAccepted =
                                bridgeWriter.updateDeviceIpInfo(
                                    value = it,
                                    allowNewAddress = allowNewDeviceAddress,
                                )
                            if (shouldPublishAppOwnedIpInfo()) {
                                bridgeWriter.updateIpInfo(it)
                            }
                            container.diagnosticsLogger.record("ip", "geo refreshed")
                            launch(Dispatchers.Main.immediate) { updateNotification() }
                            if (deviceInfoAccepted) {
                                startIpv4EnrichmentIfNeeded(
                                    info = it,
                                    allowNewDeviceAddress = allowNewDeviceAddress,
                                )
                            } else {
                                container.diagnosticsLogger.record("ip", "device geo refresh ignored during active tunnel")
                            }
                        }
                        .onFailure { error ->
                            container.diagnosticsLogger.recordFailure("ip", "geo refresh failed: ${error.message.orEmpty()}")
                        }.isSuccess
                if (success) {
                    return@launch
                }
                if (attempt < FoxholeVpnService.GEO_REFRESH_ATTEMPTS - 1) {
                    delay(FoxholeVpnService.GEO_REFRESH_RETRY_DELAY_MS)
                }
            }
        }
}

internal fun FoxholeVpnService.stopGeoRefresh() {
    geoRefreshJob?.cancel()
    geoRefreshJob = null
    ipv4EnrichmentJob?.cancel()
    ipv4EnrichmentJob = null
}

internal fun FoxholeVpnService.startIpv4EnrichmentIfNeeded(
    info: IpInfo,
    allowNewDeviceAddress: Boolean = true,
) {
    if (info.ipv4 != null) {
        ipv4EnrichmentJob?.cancel()
        ipv4EnrichmentJob = null
        return
    }
    ipv4EnrichmentJob?.cancel()
    ipv4EnrichmentJob =
        scope.launch(Dispatchers.IO) {
            val ipv4Info =
                runCatching {
                    refreshAppOwnedIpv4Info(callTimeoutMs = FoxholeVpnService.IPV4_ENRICHMENT_CALL_TIMEOUT_MS)
                }.getOrNull() ?: return@launch
            val merged =
                mergeIpInfo(
                    primary = FoxholeVpnRuntimeBridge.deviceIpInfo.value ?: info,
                    ipv4 = ipv4Info,
                    ipv6 = null,
                )
            val deviceInfoAccepted =
                bridgeWriter.updateDeviceIpInfo(
                    value = merged,
                    allowNewAddress = allowNewDeviceAddress,
                )
            if (shouldPublishAppOwnedIpInfo()) {
                bridgeWriter.updateIpInfo(merged)
            }
            container.diagnosticsLogger.record(
                "ip",
                if (deviceInfoAccepted) "ipv4 enriched" else "ipv4 enrichment ignored during active tunnel",
            )
            launch(Dispatchers.Main.immediate) { updateNotification() }
        }
}

private suspend fun FoxholeVpnService.refreshAppOwnedIpInfo(callTimeoutMs: Long): IpInfo {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    return container.ipInfoRepository
        .fetch(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            mode = IpInfoFetchMode.ENTRY_QUICK,
        ).withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(null),
            remoteDnsServers = emptyList(),
        )
}

private suspend fun FoxholeVpnService.refreshAppOwnedIpv4Info(callTimeoutMs: Long): IpInfo? {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    return container.ipInfoRepository
        .fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
        )?.withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(null),
            remoteDnsServers = emptyList(),
        )
}

internal suspend fun FoxholeVpnService.refreshVpnIpInfo(
    callTimeoutMs: Long,
    network: Network? = null,
    expectedFreshVpnNetworkHandle: Long? = null,
): IpInfo = refreshVpnIpInfoInternal(callTimeoutMs, network, expectedFreshVpnNetworkHandle)

internal suspend fun FoxholeVpnService.refreshProxyIpInfo(callTimeoutMs: Long): IpInfo = refreshProxyIpInfoInternal(
    callTimeoutMs
)

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpInfo(callTimeoutMs: Long): IpInfo =
    refreshTunnelRuntimeProxyIpInfoInternal(callTimeoutMs)

internal suspend fun FoxholeVpnService.refreshVpnIpv4Info(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? = refreshVpnIpv4InfoInternal(callTimeoutMs, network)

internal suspend fun FoxholeVpnService.refreshProxyIpv4Info(callTimeoutMs: Long): IpInfo? = refreshProxyIpv4InfoInternal(
    callTimeoutMs
)

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpv4Info(callTimeoutMs: Long): IpInfo? =
    refreshTunnelRuntimeProxyIpv4InfoInternal(callTimeoutMs)
