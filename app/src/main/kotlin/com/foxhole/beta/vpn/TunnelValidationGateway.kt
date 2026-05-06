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
import kotlinx.coroutines.delay
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
        val trafficMode =
            snapshot.value.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { snapshot.value.trafficMode }
                ?: TrafficMode.TUNNEL
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && snapshot.value.state in ACTIVE_CONNECTION_STATES
        val vpnNetwork = if (tunnelConnected) currentVpnNetwork() ?: error("vpn network unavailable") else null
        val upstreamNetwork = if (tunnelConnected) null else currentUpstreamNetwork()
        val localGuardActive = !tunnelConnected && settings.localGuardModeOrNull() != null
        val dnsNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                tunnelConnected -> vpnNetwork
                else -> upstreamNetwork
            }
        val requestNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                tunnelConnected -> null
                localGuardActive -> upstreamNetwork
                else -> upstreamNetwork
            }
        val remoteDnsServers =
            snapshot.value.profileId
                ?.let { profileId ->
                    runCatching {
                        VpnDnsServerSelector.remoteDnsServerAddresses(profileRepository.getSession(profileId).configJson)
                    }.getOrDefault(emptyList())
                }.orEmpty()
        return when {
            trafficMode == TrafficMode.TUNNEL && tunnelConnected && vpnNetwork != null ->
                fetchTunnelIpInfo(
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    proxy = settings.tunnelRuntimeProxyAccess(),
                )
            else ->
                fetchDeviceIpInfo(
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    requestNetwork = requestNetwork,
                    requireRequestNetwork = localGuardActive,
                    proxy = proxyAccess,
                )
        }.withDnsServers(
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

    private suspend fun fetchTunnelIpInfo(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        proxy: HttpProxyAccess?,
    ): IpInfo =
        runCatching {
            val proxyAccess = proxy ?: error("runtime local proxy unavailable")
            ipInfoRepository.fetch(
                endpoint = endpoint,
                proxy = proxyAccess,
                resolverNetwork = currentUpstreamNetwork(),
                mode = fetchMode,
            )
        }.getOrElse { proxyError ->
            val proxyAccess = proxy
            if (proxyAccess != null) {
                delay(RUNTIME_PROXY_REFRESH_RETRY_DELAY_MS)
                runCatching {
                    ipInfoRepository.fetch(
                        endpoint = endpoint,
                        proxy = proxyAccess,
                        resolverNetwork = currentUpstreamNetwork(),
                        mode = IpInfoFetchMode.ENTRY_QUICK,
                    )
                }.getOrNull()?.let { return it.also { diagnosticsLogger.record("ip", "dashboard ip refreshed after vpn network detected") } }
            }
            diagnosticsLogger.record(
                "ip",
                "dashboard runtime local proxy refresh failed, retrying vpn-bound path",
            )
            runCatching {
                ipInfoRepository.fetch(
                    endpoint = endpoint,
                    network = tunnelValidationRequestNetwork(vpnNetwork),
                    resolverNetwork = currentUpstreamNetwork(),
                    mode = fetchMode,
                )
            }.getOrElse {
                throw proxyError
            }
        }.also { diagnosticsLogger.record("ip", "dashboard ip refreshed after vpn network detected") }
}

private const val RUNTIME_PROXY_REFRESH_RETRY_DELAY_MS = 1_000L
