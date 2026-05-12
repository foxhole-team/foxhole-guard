package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.core.content.getSystemService
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow

internal class TunnelValidationGateway(
    context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val ipInfoRepository: IpInfoRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
) {
    private val connectivityManager by lazy { context.getSystemService<ConnectivityManager>()!! }

    suspend fun refreshIpInfo(fetchMode: IpInfoFetchMode): IpInfo {
        val settings = settingsRepository.current()
        val endpoint = settings.connection.ipInfoEndpoint
        val currentSnapshot = snapshot.value
        val trafficMode =
            currentSnapshot.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { currentSnapshot.trafficMode }
                ?: TrafficMode.TUNNEL
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && currentSnapshot.state in ACTIVE_CONNECTION_STATES
        val localGuardActive = tunnelConnected && currentSnapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        val activeTunnelConnected = tunnelConnected && !localGuardActive
        val session =
            currentSnapshot.profileId
                ?.let { profileId ->
                    runCatching { profileRepository.getSession(profileId) }.getOrNull()
                }
        val remoteDnsServers = session?.configJson?.let(VpnDnsServerSelector::remoteDnsServerAddresses).orEmpty()
        if (activeTunnelConnected) {
            val vpnNetwork = currentVpnNetwork() ?: error("vpn network unavailable")
            val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
            val resolverNetwork = currentUpstreamNetwork()
            val preferIpv4Validation = shouldPreferIpv4TunnelValidation(currentSnapshot.protocolHint, session?.configJson)
            val info =
                if (preferIpv4Validation) {
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
                        mode = fetchMode,
                    )
                }
            return info.withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                remoteDnsServers = remoteDnsServers,
            )
        }
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val upstreamNetwork = currentUpstreamNetwork()
        val localGuardRuntimeActive = localGuardActive || (!tunnelConnected && settings.localGuardModeOrNull() != null)
        val dnsNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                else -> upstreamNetwork
            }
        val requestNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                else -> upstreamNetwork
            }
        return fetchDeviceIpInfo(
            endpoint = endpoint,
            fetchMode = fetchMode,
            requestNetwork = requestNetwork,
            requireRequestNetwork = localGuardRuntimeActive,
            proxy = proxyAccess,
        ).withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(dnsNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }

    private suspend fun fetchDeviceIpInfo(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        requestNetwork: Network?,
        requireRequestNetwork: Boolean,
        proxy: HttpProxyAccess?,
    ): IpInfo =
        if (proxy != null) {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                proxy = proxy,
                mode = fetchMode,
            )
        } else if (requestNetwork != null) {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                network = requestNetwork,
                mode = fetchMode,
            )
        } else if (requireRequestNetwork) {
            error("upstream network unavailable")
        } else {
            runCatching {
                ipInfoRepository.fetch(
                    endpoint = endpoint,
                    mode = fetchMode,
                )
            }.getOrElse { error ->
                val upstreamNetwork = requestNetwork ?: throw error
                diagnosticsLogger.record(
                    "ip",
                    "device ip refresh failed on default path, retrying explicit upstream network",
                )
                ipInfoRepository.fetch(
                    endpoint = endpoint,
                    network = upstreamNetwork,
                    mode = fetchMode,
                )
            }
        }

}
