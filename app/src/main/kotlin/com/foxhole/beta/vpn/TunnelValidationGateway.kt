package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

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
    private val appContext = context.applicationContext
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
            val preferIpv4Validation =
                shouldPreferIpv4TunnelValidation(
                    currentSnapshot.protocolHint,
                    session?.configJson,
                )
            val info =
                fetchActiveTunnelIpInfo(
                    settings = settings,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    preferIpv4Validation = preferIpv4Validation,
                    currentSnapshot = currentSnapshot,
                )
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
        when {
            proxy != null ->
                ipInfoRepository.fetch(
                    endpoint = endpoint,
                    callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                    proxy = proxy,
                    mode = fetchMode,
                )
            requestNetwork != null -> fetchDeviceIpInfoFromNetwork(endpoint, fetchMode, requestNetwork)
            requireRequestNetwork -> error("upstream network unavailable")
            else -> fetchDeviceIpInfoFromDefaultNetwork(endpoint, fetchMode)
        }

    private suspend fun fetchDeviceIpInfoFromNetwork(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        requestNetwork: Network,
    ): IpInfo =
        runCatching {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                network = requestNetwork,
                mode = fetchMode,
            )
        }.getOrElse { error ->
            localDeviceIpInfo(requestNetwork)
                ?.also {
                    diagnosticsLogger.record(
                        "ip",
                        "device ip refresh used local network fallback after ${error.javaClass.simpleName}",
                    )
                }
                ?: throw error
        }

    private suspend fun fetchDeviceIpInfoFromDefaultNetwork(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
    ): IpInfo =
        runCatching {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                mode = fetchMode,
            )
        }.recoverCatching { error ->
            val upstreamNetwork = currentUpstreamNetwork() ?: throw error
            diagnosticsLogger.record(
                "ip",
                "device ip refresh failed on default path, retrying explicit upstream network",
            )
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                network = upstreamNetwork,
                mode = fetchMode,
            )
        }.getOrElse { error ->
            localDeviceIpInfo(connectivityManager.activeNetwork)
                ?.also {
                    diagnosticsLogger.record(
                        "ip",
                        "device ip refresh used local network fallback after ${error.javaClass.simpleName}",
                    )
                }
                ?: throw error
        }

    private fun localDeviceIpInfo(network: Network?): IpInfo? {
        val address =
            network
                ?.let(connectivityManager::getLinkProperties)
                ?.linkAddresses
                .orEmpty()
                .mapNotNull { linkAddress -> linkAddress.address.hostAddress?.substringBefore('%') }
                .filterNot { value -> value.isBlank() || value.startsWith("127.") || value.equals("::1", ignoreCase = true) }
                .filterNot { value -> value.startsWith("fe80:", ignoreCase = true) || value.startsWith("169.254.") }
                .sortedBy { value -> if (value.contains('.')) 0 else 1 }
                .firstOrNull()
                ?: return null
        val capabilities = network?.let(connectivityManager::getNetworkCapabilities)
        return IpInfo(
            ip = address,
            ipv4 = address.takeIf { it.contains('.') },
            ipv6 = address.takeIf { it.contains(':') },
            countryCode = null,
            countryName = appContext.getString(R.string.home_network_local_network),
            city = null,
            isp = capabilities.localNetworkProviderLabel(),
            fetchedAt = System.currentTimeMillis(),
        )
    }

    private fun NetworkCapabilities?.localNetworkProviderLabel(): String =
        when {
            this?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ->
                appContext.getString(R.string.home_network_wifi_provider)
            this?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true ->
                appContext.getString(R.string.home_network_cellular_provider)
            else -> appContext.getString(R.string.home_network_local_network)
        }

    private suspend fun fetchActiveTunnelIpInfo(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        currentSnapshot: ConnectionSnapshot,
    ): IpInfo =
        try {
            fetchActiveTunnelIpInfoViaRuntimeProxy(
                settings = settings,
                endpoint = endpoint,
                fetchMode = fetchMode,
                preferIpv4Validation = preferIpv4Validation,
            )
        } catch (error: IOException) {
            if (!settings.canUseVpnBoundIpRefreshFallback(currentSnapshot, isVpnNetworkValidated(vpnNetwork))) {
                throw error
            }
            fetchActiveTunnelIpInfoOnProcessPathAfterRuntimeProxyFailure(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
                error = error,
            )
        } catch (error: IllegalStateException) {
            if (!settings.canUseVpnBoundIpRefreshFallback(currentSnapshot, isVpnNetworkValidated(vpnNetwork))) {
                throw error
            }
            fetchActiveTunnelIpInfoOnProcessPathAfterRuntimeProxyFailure(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
                error = error,
            )
        } catch (error: IllegalArgumentException) {
            if (!settings.canUseVpnBoundIpRefreshFallback(currentSnapshot, isVpnNetworkValidated(vpnNetwork))) {
                throw error
            }
            fetchActiveTunnelIpInfoOnProcessPathAfterRuntimeProxyFailure(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
                error = error,
            )
        }

    private fun isVpnNetworkValidated(network: Network): Boolean =
        connectivityManager
            .getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    private suspend fun fetchActiveTunnelIpInfoOnProcessPathAfterRuntimeProxyFailure(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        error: Exception,
    ): IpInfo {
        diagnosticsLogger.record(
            "ip",
            "runtime local proxy ip refresh failed, retrying vpn process path: ${error.javaClass.simpleName}",
        )
        return fetchActiveTunnelIpInfoOnProcessPath(
            endpoint = endpoint,
            fetchMode = fetchMode,
            vpnNetwork = vpnNetwork,
            preferIpv4Validation = preferIpv4Validation,
        )
    }

    private suspend fun fetchActiveTunnelIpInfoOnProcessPath(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
    ): IpInfo {
        val requestNetwork = tunnelValidationRequestNetwork(vpnNetwork)
        val resolverNetwork = currentUpstreamNetwork()
        return if (preferIpv4Validation) {
            ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: error("vpn ipv4 refresh failed")
        } else {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
                mode = fetchMode,
            )
        }
    }

    private suspend fun fetchActiveTunnelIpInfoViaRuntimeProxy(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        preferIpv4Validation: Boolean,
    ): IpInfo {
        val proxy = settings.tunnelRuntimeProxyAccess()
        val resolverNetwork = currentUpstreamNetwork()
        return if (preferIpv4Validation) {
            ipInfoRepository.fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                proxy = proxy,
                resolverNetwork = resolverNetwork,
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS,
                proxy = proxy,
                resolverNetwork = resolverNetwork,
                mode = fetchMode,
            )
        }
    }

    private companion object {
        const val DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS = 2_500L
    }
}

internal fun Settings.requiresStrictRuntimeProxyIpRefresh(snapshot: ConnectionSnapshot): Boolean =
    snapshot.requiresRuntimeProxyForActiveTunnelIpRefresh() ||
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
        (
            privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
                traffic.mode == TrafficMode.TUNNEL &&
                when (privacyRoute.scope) {
                    PrivacyRouteScope.ALL_APPS -> true
                    PrivacyRouteScope.SELECTED_APPS -> privacyRoute.selectedPackages.any(String::isNotBlank)
                }
            )

internal fun Settings.canUseVpnBoundIpRefreshFallback(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean = !requiresStrictRuntimeProxyIpRefresh(snapshot) || androidValidatedVpnNetwork

internal fun Settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot: ConnectionSnapshot): Boolean =
    !(
        privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
            snapshot.state in ACTIVE_CONNECTION_STATES &&
            snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.profileId != null &&
            snapshot.profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
            snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            when (privacyRoute.scope) {
                PrivacyRouteScope.ALL_APPS -> true
                PrivacyRouteScope.SELECTED_APPS -> privacyRoute.selectedPackages.any(String::isNotBlank)
            }
    )

private fun ConnectionSnapshot.requiresRuntimeProxyForActiveTunnelIpRefresh(): Boolean =
    appOwnedRequestPath() == AppOwnedRequestPath.NORMAL_PROCESS &&
        state in ACTIVE_CONNECTION_STATES &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
