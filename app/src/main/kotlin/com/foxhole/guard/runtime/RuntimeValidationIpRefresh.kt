package com.foxhole.guard.runtime

import android.net.Network
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.RuntimeFailureCode
import com.foxhole.core.model.RuntimeFailureException
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.VpnDnsServerSelector
import com.foxhole.core.runtime.dnsServerAddresses
import com.foxhole.core.runtime.preferredAppProxyAccess
import com.foxhole.core.runtime.shouldPreferIpv4TunnelValidation
import com.foxhole.core.runtime.tunnelRuntimeProxyAccess
import com.foxhole.core.runtime.tunnelValidationRequestNetwork

// The IP-identity half of validation: refreshing what address the session actually egresses from
// (VPN, proxy, tunnel runtime-proxy, and their IPv4 variants), which is what the validation gate and
// the dashboard both read.

internal suspend fun FoxholeVpnService.refreshVpnIpInfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
    expectedFreshVpnNetworkHandle: Long? = null,
): IpInfo {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val vpnNetwork =
        network
            ?: awaitVpnNetworkOrNull(
                FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS,
                excludedHandle = expectedFreshVpnNetworkHandle,
            )
            ?: throw RuntimeFailureException(RuntimeFailureCode.VPN_NETWORK_MISSING, "vpn network unavailable")
    val requestNetwork = network ?: tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetworkOrNull()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        if (shouldPreferIpv4TunnelValidation(activeSession?.protocolHint, activeSession?.configJson)) {
            container.ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: throw RuntimeFailureException(RuntimeFailureCode.DNS_FAILURE, "vpn ipv4 refresh failed")
        } else {
            container.ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            )
        }
    return info.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

internal suspend fun FoxholeVpnService.refreshConnectionIpInfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo =
    when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
        TrafficMode.TUNNEL ->
            runRuntimeValidationCatchingUnlessCancelled {
                refreshTunnelRuntimeProxyIpInfo(callTimeoutMs = callTimeoutMs)
            }.getOrElse { proxyError ->
                container.diagnosticsLogger.record(
                    "ip",
                    "runtime local proxy ip refresh failed, retrying vpn process path: ${proxyError.message.orEmpty()}",
                )
                refreshVpnIpInfo(callTimeoutMs = callTimeoutMs, network = network)
            }
        TrafficMode.PROXY -> refreshProxyIpInfo(callTimeoutMs = callTimeoutMs)
    }

internal suspend fun FoxholeVpnService.refreshConnectionIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? =
    when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
        TrafficMode.TUNNEL ->
            refreshTunnelRuntimeProxyIpv4Info(callTimeoutMs = callTimeoutMs)
                ?: refreshVpnIpv4Info(callTimeoutMs = callTimeoutMs, network = network)
        TrafficMode.PROXY -> refreshProxyIpv4Info(callTimeoutMs = callTimeoutMs)
    }

internal suspend fun FoxholeVpnService.refreshProxyIpInfoInternal(callTimeoutMs: Long): IpInfo {
    val settings = container.settingsRepository.current()
    val proxyAccess = settings.preferredAppProxyAccess() ?: error("proxy surface is unavailable")
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetch(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = proxyAccess,
        ).withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpInfoInternal(callTimeoutMs: Long): IpInfo {
    val settings = container.settingsRepository.current()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetch(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = settings.tunnelRuntimeProxyAccess(),
            resolverNetwork = currentUpstreamNetworkOrNull(),
            torExit = activeSessionCarriesTor(),
        ).withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

// The runtime proxy fetch egresses through Tor whenever the session is the standalone Tor-only
// runtime or a VPN session carrying the Tor route: lead the IP-info fetch with the Tor Project exit
// check so a slow/hostile exit can't time the probe out (which used to tear the Tor session down).
internal fun FoxholeVpnService.activeSessionCarriesTor(): Boolean =
    activeSession?.let { session ->
        session.torActive || session.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
    } ?: false

internal suspend fun FoxholeVpnService.refreshVpnIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val vpnNetwork = network ?: awaitVpnNetworkOrNull(FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS) ?: return null
    val requestNetwork = network ?: tunnelValidationRequestNetwork(vpnNetwork)
    val resolverNetwork = currentUpstreamNetworkOrNull()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        container.ipInfoRepository.fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            network = requestNetwork,
            resolverNetwork = resolverNetwork,
        )
    return info?.withDnsServers(
        localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
        remoteDnsServers = remoteDnsServers,
    )
}

internal suspend fun FoxholeVpnService.refreshProxyIpv4InfoInternal(callTimeoutMs: Long): IpInfo? {
    val settings = container.settingsRepository.current()
    val proxyAccess = settings.preferredAppProxyAccess() ?: return null
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetchIpv4(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = proxyAccess,
        )?.withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}

internal suspend fun FoxholeVpnService.refreshTunnelRuntimeProxyIpv4InfoInternal(callTimeoutMs: Long): IpInfo? {
    val settings = container.settingsRepository.current()
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    return container.ipInfoRepository
        .fetchIpv4(
            endpoint = settings.connection.ipInfoEndpoint,
            callTimeoutMs = callTimeoutMs,
            proxy = settings.tunnelRuntimeProxyAccess(),
            resolverNetwork = currentUpstreamNetworkOrNull(),
            torExit = activeSessionCarriesTor(),
        )?.withDnsServers(
            localDnsServers = emptyList(),
            remoteDnsServers = remoteDnsServers,
        )
}
