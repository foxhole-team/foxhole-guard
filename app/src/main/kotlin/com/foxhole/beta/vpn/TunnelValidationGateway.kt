package com.foxhole.beta.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.IpInfoRepository
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

@Suppress("TooManyFunctions")
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
    private val connectivityManager by lazy {
        context.requireSystemServiceSafe<ConnectivityManager>("connectivity")
    }
    private var activeTunnelIpInfoCache: ActiveTunnelIpInfoCache? = null

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
                else -> boundNetworkForAppOwnedRequest(upstreamNetwork)
            }
        return fetchDeviceIpInfo(
            endpoint = endpoint,
            fetchMode = fetchMode,
            requestNetwork = requestNetwork,
            requireRequestNetwork = localGuardRuntimeActive && requestNetwork != null,
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
                    callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                    proxy = proxy,
                    mode = fetchMode,
                )
            requestNetwork != null ->
                fetchDeviceIpInfoFromNetwork(
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    requestNetwork = requestNetwork,
                )
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
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                network = requestNetwork,
                mode = fetchMode,
            )
        }.recoverCatching { error ->
            if (error is CancellationException) {
                throw error
            }
            if (!shouldFallbackAppOwnedNetworkRequest(error)) {
                throw error
            }
            diagnosticsLogger.record(
                "ip",
                "device ip refresh explicit upstream path failed, retrying normal process path: ${error.javaClass.simpleName}",
            )
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                mode = fetchMode,
            )
        }.getOrThrow()

    private suspend fun fetchDeviceIpInfoFromDefaultNetwork(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
    ): IpInfo =
        runCatching {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                mode = fetchMode,
            )
        }.recoverCatching { error ->
            if (error is CancellationException) {
                throw error
            }
            val upstreamNetwork = boundNetworkForAppOwnedRequest(currentUpstreamNetwork()) ?: throw error
            diagnosticsLogger.record(
                "ip",
                "device ip refresh failed on default path, retrying explicit upstream network",
            )
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                network = upstreamNetwork,
                mode = fetchMode,
            )
        }.getOrThrow()

    private suspend fun fetchActiveTunnelIpInfo(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        currentSnapshot: ConnectionSnapshot,
    ): IpInfo =
        fetchActiveTunnelIpInfo(
            settings = settings,
            endpoint = endpoint,
            fetchMode = fetchMode,
            vpnNetwork = vpnNetwork,
            preferIpv4Validation = preferIpv4Validation,
            currentSnapshot = currentSnapshot,
            androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork),
        )

    private suspend fun fetchActiveTunnelIpInfo(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        currentSnapshot: ConnectionSnapshot,
        androidValidatedVpnNetwork: Boolean,
    ): IpInfo {
        val effectiveFetchMode = validatedTunnelIpRefreshFetchMode(fetchMode, androidValidatedVpnNetwork)
        val effectiveEndpoint = activeTunnelIpRefreshEndpoint(endpoint, androidValidatedVpnNetwork)
        if (effectiveFetchMode != fetchMode) {
            diagnosticsLogger.record(
                "ip",
                "active tunnel ip refresh using quick mode after android validation",
            )
        }
        if (effectiveEndpoint != endpoint) {
            diagnosticsLogger.record(
                "ip",
                "active tunnel ip refresh using dns-independent endpoint after android validation",
            )
        }
        return if (settings.shouldPreferVpnBoundIpRefresh(currentSnapshot, androidValidatedVpnNetwork)) {
            fetchActiveTunnelIpInfoWithVpnBoundPreference(
                settings = settings,
                currentSnapshot = currentSnapshot,
                endpoint = effectiveEndpoint,
                fetchMode = effectiveFetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            )
        } else {
            fetchActiveTunnelIpInfoWithRuntimeProxyPreference(
                settings = settings,
                currentSnapshot = currentSnapshot,
                androidValidatedVpnNetwork = androidValidatedVpnNetwork,
                endpoint = effectiveEndpoint,
                fetchMode = effectiveFetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            )
        }
    }

    private suspend fun fetchActiveTunnelIpInfoWithVpnBoundPreference(
        settings: Settings,
        currentSnapshot: ConnectionSnapshot,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
    ): IpInfo =
        runCatching {
            fetchActiveTunnelIpInfoOnProcessPathWithTimeout(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            ).also { info -> rememberActiveTunnelIpInfo(currentSnapshot, info) }
        }.recoverCatching { processError ->
            fetchActiveTunnelIpInfoViaRuntimeProxyAfterProcessFailure(
                settings = settings,
                endpoint = endpoint,
                fetchMode = fetchMode,
                preferIpv4Validation = preferIpv4Validation,
                processError = processError,
            )
        }.recoverCatching { refreshError ->
            recoverCachedActiveTunnelIpInfo(
                currentSnapshot = currentSnapshot,
                error = refreshError,
            )
        }.getOrThrow()

    private suspend fun fetchActiveTunnelIpInfoViaRuntimeProxyAfterProcessFailure(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        preferIpv4Validation: Boolean,
        processError: Throwable,
    ): IpInfo {
        if (processError is CancellationException) {
            throw processError
        }
        diagnosticsLogger.record(
            "ip",
            "validated vpn-bound ip refresh failed, trying runtime proxy path: ${processError.javaClass.simpleName}",
        )
        return fetchActiveTunnelIpInfoViaRuntimeProxy(
            settings = settings,
            endpoint = endpoint,
            fetchMode = fetchMode,
            preferIpv4Validation = preferIpv4Validation,
        )
    }

    private suspend fun fetchActiveTunnelIpInfoWithRuntimeProxyPreference(
        settings: Settings,
        currentSnapshot: ConnectionSnapshot,
        androidValidatedVpnNetwork: Boolean,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
    ): IpInfo =
        runCatching {
            try {
                fetchActiveTunnelIpInfoViaRuntimeProxy(
                    settings = settings,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    preferIpv4Validation = preferIpv4Validation,
                )
            } catch (error: IOException) {
                recoverActiveTunnelIpInfoAfterRuntimeProxyFailure(
                    settings = settings,
                    currentSnapshot = currentSnapshot,
                    androidValidatedVpnNetwork = androidValidatedVpnNetwork,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    preferIpv4Validation = preferIpv4Validation,
                    error = error,
                )
            } catch (error: IllegalStateException) {
                recoverActiveTunnelIpInfoAfterRuntimeProxyFailure(
                    settings = settings,
                    currentSnapshot = currentSnapshot,
                    androidValidatedVpnNetwork = androidValidatedVpnNetwork,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    preferIpv4Validation = preferIpv4Validation,
                    error = error,
                )
            } catch (error: IllegalArgumentException) {
                recoverActiveTunnelIpInfoAfterRuntimeProxyFailure(
                    settings = settings,
                    currentSnapshot = currentSnapshot,
                    androidValidatedVpnNetwork = androidValidatedVpnNetwork,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    preferIpv4Validation = preferIpv4Validation,
                    error = error,
                )
            }
        }.recoverCatching { refreshError ->
            recoverCachedActiveTunnelIpInfo(
                currentSnapshot = currentSnapshot,
                allowCachedRecovery = settings.canRecoverCachedActiveTunnelIpInfo(
                    snapshot = currentSnapshot,
                    androidValidatedVpnNetwork = androidValidatedVpnNetwork,
                ),
                error = refreshError,
            )
        }.getOrThrow()

    private fun recoverCachedActiveTunnelIpInfo(
        currentSnapshot: ConnectionSnapshot,
        allowCachedRecovery: Boolean = true,
        error: Throwable,
    ): IpInfo {
        if (error is CancellationException) {
            throw error
        }
        if (!allowCachedRecovery) {
            throw error
        }
        return cachedActiveTunnelIpInfoOrThrow(
            currentSnapshot = currentSnapshot,
            error = error,
        )
    }

    private suspend fun recoverActiveTunnelIpInfoAfterRuntimeProxyFailure(
        settings: Settings,
        currentSnapshot: ConnectionSnapshot,
        androidValidatedVpnNetwork: Boolean,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        error: Exception,
    ): IpInfo {
        if (error is CancellationException) {
            throw error
        }
        if (!settings.canUseVpnBoundIpRefreshFallback(currentSnapshot, androidValidatedVpnNetwork)) {
            throw error
        }
        return fetchActiveTunnelIpInfoOnProcessPathAfterRuntimeProxyFailure(
            currentSnapshot = currentSnapshot,
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
        currentSnapshot: ConnectionSnapshot,
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
        return runCatching {
            fetchActiveTunnelIpInfoOnProcessPathWithTimeout(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            ).also { info -> rememberActiveTunnelIpInfo(currentSnapshot, info) }
        }.recoverCatching { processError ->
            if (processError is CancellationException) {
                throw processError
            }
            diagnosticsLogger.record(
                "ip",
                "vpn process path ip refresh failed after runtime proxy failure: ${processError.javaClass.simpleName}",
            )
            throw processError
        }.getOrThrow()
    }

    private suspend fun fetchActiveTunnelIpInfoOnProcessPathWithTimeout(
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
    ): IpInfo =
        withTimeoutOrNull(ACTIVE_TUNNEL_VPN_BOUND_IP_REFRESH_TOTAL_TIMEOUT_MS) {
            fetchActiveTunnelIpInfoOnProcessPath(
                endpoint = endpoint,
                fetchMode = fetchMode,
                vpnNetwork = vpnNetwork,
                preferIpv4Validation = preferIpv4Validation,
            )
        } ?: error("vpn-bound ip refresh timed out")

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
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
            ) ?: error("vpn ipv4 refresh failed")
        } else {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = dashboardIpRefreshCallTimeoutMs(fetchMode),
                network = requestNetwork,
                resolverNetwork = resolverNetwork,
                mode = fetchMode,
            )
        }
    }

    private fun rememberActiveTunnelIpInfo(
        currentSnapshot: ConnectionSnapshot,
        info: IpInfo,
    ) {
        activeTunnelIpInfoCache =
            ActiveTunnelIpInfoCache(
                key = currentSnapshot.activeTunnelIpInfoCacheKey(),
                info = info,
                updatedAtMs = System.currentTimeMillis(),
            )
    }

    private fun cachedActiveTunnelIpInfoOrThrow(
        currentSnapshot: ConnectionSnapshot,
        error: Throwable,
    ): IpInfo {
        val now = System.currentTimeMillis()
        val cached =
            activeTunnelIpInfoCache
                ?.takeIf { cache -> cache.key == currentSnapshot.activeTunnelIpInfoCacheKey() }
                ?.takeIf { cache -> now - cache.updatedAtMs <= ACTIVE_TUNNEL_IP_REFRESH_CACHE_MAX_AGE_MS }
                ?.info
        if (cached != null) {
            diagnosticsLogger.record(
                "ip",
                "active tunnel ip refresh reused last vpn-bound result after ${error.javaClass.simpleName}",
            )
            return cached
        }
        throw error
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
                callTimeoutMs = activeTunnelRuntimeProxyIpRefreshCallTimeoutMs(fetchMode),
                proxy = proxy,
                resolverNetwork = resolverNetwork,
            ) ?: error("runtime proxy ipv4 refresh failed")
        } else {
            ipInfoRepository.fetch(
                endpoint = endpoint,
                callTimeoutMs = activeTunnelRuntimeProxyIpRefreshCallTimeoutMs(fetchMode),
                proxy = proxy,
                resolverNetwork = resolverNetwork,
                mode = fetchMode,
            )
        }
    }

    private fun dashboardIpRefreshCallTimeoutMs(fetchMode: IpInfoFetchMode): Long =
        when (fetchMode) {
            IpInfoFetchMode.GEO_ENRICHMENT -> DASHBOARD_GEO_ENRICHMENT_CALL_TIMEOUT_MS
            IpInfoFetchMode.FULL,
            IpInfoFetchMode.ENTRY_QUICK,
            -> DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS
        }

    private fun activeTunnelRuntimeProxyIpRefreshCallTimeoutMs(fetchMode: IpInfoFetchMode): Long =
        when (fetchMode) {
            IpInfoFetchMode.GEO_ENRICHMENT -> DASHBOARD_GEO_ENRICHMENT_CALL_TIMEOUT_MS
            IpInfoFetchMode.FULL,
            IpInfoFetchMode.ENTRY_QUICK,
            -> ACTIVE_TUNNEL_RUNTIME_PROXY_IP_REFRESH_CALL_TIMEOUT_MS
        }

    private companion object {
        const val DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS = 2_500L
        const val DASHBOARD_GEO_ENRICHMENT_CALL_TIMEOUT_MS = 1_200L
        const val ACTIVE_TUNNEL_VPN_BOUND_IP_REFRESH_TOTAL_TIMEOUT_MS = 7_500L
        const val ACTIVE_TUNNEL_RUNTIME_PROXY_IP_REFRESH_CALL_TIMEOUT_MS = 4_000L
        const val ACTIVE_TUNNEL_IP_REFRESH_CACHE_MAX_AGE_MS = 10 * 60 * 1_000L
    }
}

private data class ActiveTunnelIpInfoCache(
    val key: ActiveTunnelIpInfoCacheKey,
    val info: IpInfo,
    val updatedAtMs: Long,
)

private data class ActiveTunnelIpInfoCacheKey(
    val profileId: Long?,
    val protocolOptionId: String?,
    val connectedAtMs: Long,
)

private fun ConnectionSnapshot.activeTunnelIpInfoCacheKey(): ActiveTunnelIpInfoCacheKey =
    ActiveTunnelIpInfoCacheKey(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        connectedAtMs = lastChangeAt,
    )

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

internal fun Settings.allowsRuntimeProxyTunnelValidation(
    snapshot: ConnectionSnapshot,
    profileId: Long? = snapshot.profileId,
): Boolean {
    if (
        snapshot.state !in ACTIVE_CONNECTION_STATES ||
        snapshot.trafficMode != TrafficMode.TUNNEL ||
        profileId == null ||
        profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    ) {
        return false
    }
    val includeOnlySplit = expert.runtimeHasIncludeOnlyAppSplit()
    val torOnlySelectedApps =
        profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            privacyRoute.scope == PrivacyRouteScope.SELECTED_APPS &&
            privacyRoute.selectedPackages.any(String::isNotBlank)
    return includeOnlySplit || torOnlySelectedApps
}

private fun ExpertSettings.runtimeHasIncludeOnlyAppSplit(): Boolean =
    perAppRoutingMode == PerAppRoutingMode.INCLUDE_SELECTED_APPS &&
        (
            selectedPackages.any(String::isNotBlank) ||
                (blockedPackagesEnabled && blockedPackages.any(String::isNotBlank))
            )

internal fun Settings.canUseVpnBoundIpRefreshFallback(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean = !requiresStrictRuntimeProxyIpRefresh(snapshot) || androidValidatedVpnNetwork

internal fun Settings.canRecoverCachedActiveTunnelIpInfo(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean = canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork)

internal fun Settings.shouldPreferVpnBoundIpRefresh(
    snapshot: ConnectionSnapshot,
    androidValidatedVpnNetwork: Boolean,
): Boolean =
    androidValidatedVpnNetwork &&
        canUseVpnBoundIpRefreshFallback(snapshot, androidValidatedVpnNetwork = true) &&
        snapshot.profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID

internal fun validatedTunnelIpRefreshFetchMode(
    requestedMode: IpInfoFetchMode,
    androidValidatedVpnNetwork: Boolean,
): IpInfoFetchMode =
    if (androidValidatedVpnNetwork && requestedMode == IpInfoFetchMode.FULL) {
        IpInfoFetchMode.ENTRY_QUICK
    } else {
        requestedMode
    }

internal fun activeTunnelIpRefreshEndpoint(
    configuredEndpoint: String,
    androidValidatedVpnNetwork: Boolean,
): String {
    if (!androidValidatedVpnNetwork) {
        return configuredEndpoint
    }
    val normalized = configuredEndpoint.trim().ifBlank { BuildConfig.DEFAULT_IP_INFO_ENDPOINT }
    return if (normalized.equals(BuildConfig.DEFAULT_IP_INFO_ENDPOINT, ignoreCase = true)) {
        DNS_INDEPENDENT_IP_INFO_ENDPOINT
    } else {
        configuredEndpoint
    }
}

internal fun Settings.shouldPublishRuntimeProxyIpInfoToDashboard(snapshot: ConnectionSnapshot): Boolean =
    !shouldHoldRuntimeProxyIpInfoForTorOverVpn(snapshot)

private fun Settings.shouldHoldRuntimeProxyIpInfoForTorOverVpn(snapshot: ConnectionSnapshot): Boolean =
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

private fun ConnectionSnapshot.requiresRuntimeProxyForActiveTunnelIpRefresh(): Boolean =
    appOwnedRequestPath() == AppOwnedRequestPath.NORMAL_PROCESS &&
        state in ACTIVE_CONNECTION_STATES &&
        trafficMode == TrafficMode.TUNNEL &&
        profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
