package com.foxhole.core.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.withDnsServers
import com.foxhole.core.runtime.network.HttpProxyAccess
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.network.ProxyAccessType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

@Suppress("TooManyFunctions")
class TunnelValidationGateway(
    context: Context,
    private val profileRepository: RuntimeProfiles,
    private val settingsRepository: RuntimeSettings,
    private val ipInfoRepository: IpInfoRepository,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val currentVpnNetwork: () -> Network?,
    private val currentUpstreamNetwork: () -> Network?,
    private val currentTorSocksPort: () -> Int? = { null },
) {
    private val connectivityManager by lazy {
        context.requireSystemServiceSafe<ConnectivityManager>("connectivity")
    }
    private var activeTunnelIpInfoCache: ActiveTunnelIpInfoCache? = null

    suspend fun refreshIpInfo(fetchMode: IpInfoFetchMode): IpInfo =
        refreshIpInfo(
            fetchMode = fetchMode,
            forceRuntimeProxyOnly = false,
        )

    suspend fun refreshTorRouteIpInfo(fetchMode: IpInfoFetchMode): IpInfo {
        // A tunnel-bound probe only egresses through Tor when Tor owns the WHOLE tunnel
        // (all-apps scope). With selected apps this app itself is not Tor-routed, so that probe
        // returned the VPN identity forever and the Tor start never validated in the UI. The tor
        // process's own SOCKS port answers with the true exit for every scope.
        currentTorSocksPort()?.let { socksPort ->
            val socksResult = runCatching { fetchTorExitIpInfoViaTorSocks(fetchMode, socksPort) }
            socksResult.getOrNull()?.let { return it }
            diagnosticsLogger.record(
                "ip",
                "tor socks exit refresh failed: " +
                    "${socksResult.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}; falling back to tunnel path",
            )
        }
        return refreshIpInfo(
            fetchMode = fetchMode,
            forceRuntimeProxyOnly = true,
        )
    }

    private suspend fun fetchTorExitIpInfoViaTorSocks(
        fetchMode: IpInfoFetchMode,
        socksPort: Int,
    ): IpInfo =
        ipInfoRepository.fetch(
            endpoint = settingsRepository.current().connection.ipInfoEndpoint,
            callTimeoutMs = activeTunnelRuntimeProxyIpRefreshCallTimeoutMs(fetchMode),
            proxy = HttpProxyAccess(host = "127.0.0.1", port = socksPort, type = ProxyAccessType.SOCKS),
            resolverNetwork = currentUpstreamNetwork(),
            mode = fetchMode,
            torExit = true,
        )

    @Suppress("CyclomaticComplexMethod")
    private suspend fun refreshIpInfo(
        fetchMode: IpInfoFetchMode,
        forceRuntimeProxyOnly: Boolean,
    ): IpInfo {
        val settings = settingsRepository.current()
        val endpoint = settings.connection.ipInfoEndpoint
        val currentSnapshot = snapshot.value
        val trafficMode =
            currentSnapshot.state
                .takeIf { it in ACTIVE_CONNECTION_STATES }
                ?.let { currentSnapshot.trafficMode }
                ?: TrafficMode.TUNNEL
        val tunnelConnected = trafficMode == TrafficMode.TUNNEL && currentSnapshot.state in ACTIVE_CONNECTION_STATES
        val localGuardActive = tunnelConnected && currentSnapshot.profileId == LOCAL_GUARD_PROFILE_ID
        val activeTunnelConnected = tunnelConnected && !localGuardActive
        val resolvedConfig =
            currentSnapshot.profileId
                ?.let { profileId ->
                    runCatching { profileRepository.getResolvedConfig(profileId) }.getOrNull()
                }
        val remoteDnsServers = resolvedConfig?.let(VpnDnsServerSelector::remoteDnsServerAddresses).orEmpty()
        if (activeTunnelConnected) {
            val vpnNetwork = currentVpnNetwork() ?: error("vpn network unavailable")
            val preferIpv4Validation =
                shouldPreferIpv4TunnelValidation(
                    currentSnapshot.protocolHint,
                    resolvedConfig,
                )
            val info =
                fetchActiveTunnelIpInfo(
                    settings = settings,
                    endpoint = endpoint,
                    fetchMode = fetchMode,
                    vpnNetwork = vpnNetwork,
                    preferIpv4Validation = preferIpv4Validation,
                    currentSnapshot = currentSnapshot,
                    forceRuntimeProxyOnly = forceRuntimeProxyOnly,
                )
            return info.withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                remoteDnsServers = remoteDnsServers,
            )
        }
        val proxyAccess = if (trafficMode == TrafficMode.PROXY) settings.preferredAppProxyAccess() else null
        val upstreamNetwork = currentUpstreamNetwork()
        val localGuardRuntimeActive = localGuardActive || (!tunnelConnected && settings.localGuardModeOrNull() != null)
        // Device truth must never be read through our own tun. The presence of the VPN network is
        // checked directly (data plane) because during connect/stop the tun can already be up while
        // the control-plane snapshot still says IDLE — that exact race let a Tor/tunnel egress into
        // deviceIpInfo, and the traffic-map origin latch then pinned the phone to the Tor exit
        // country until process death. When bypass is required and the upstream network is missing,
        // fetchDeviceIpInfo fails with "upstream network unavailable" instead of silently falling
        // back to the (tunnel-routed) default network.
        val mustBypassRuntimeTunnel =
            deviceIpRefreshMustBypassRuntimeTunnel(
                trafficMode = trafficMode,
                vpnNetworkPresent = currentVpnNetwork() != null,
                sessionEngaged = currentSnapshot.state in ACTIVE_CONNECTION_STATES,
                localGuardRuntimeActive = localGuardRuntimeActive,
            )
        val dnsNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                else -> upstreamNetwork
            }
        val requestNetwork =
            when {
                trafficMode != TrafficMode.TUNNEL -> null
                mustBypassRuntimeTunnel -> upstreamNetwork
                // App-owned requests run unbound (explicit binding EPERMs on several vendors).
                else -> null
            }
        return fetchDeviceIpInfo(
            endpoint = endpoint,
            fetchMode = fetchMode,
            requestNetwork = requestNetwork,
            requireRequestNetwork = mustBypassRuntimeTunnel,
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
            val upstreamNetwork = currentUpstreamNetwork() ?: throw error
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
        forceRuntimeProxyOnly: Boolean,
    ): IpInfo =
        fetchActiveTunnelIpInfo(
            settings = settings,
            endpoint = endpoint,
            fetchMode = fetchMode,
            vpnNetwork = vpnNetwork,
            preferIpv4Validation = preferIpv4Validation,
            currentSnapshot = currentSnapshot,
            androidValidatedVpnNetwork = isVpnNetworkValidated(vpnNetwork),
            forceRuntimeProxyOnly = forceRuntimeProxyOnly,
        )

    private suspend fun fetchActiveTunnelIpInfo(
        settings: Settings,
        endpoint: String,
        fetchMode: IpInfoFetchMode,
        vpnNetwork: Network,
        preferIpv4Validation: Boolean,
        currentSnapshot: ConnectionSnapshot,
        androidValidatedVpnNetwork: Boolean,
        forceRuntimeProxyOnly: Boolean,
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
        if (forceRuntimeProxyOnly) {
            diagnosticsLogger.record(
                "ip",
                "active tunnel ip refresh forced to runtime proxy path",
            )
        }
        return if (
            !forceRuntimeProxyOnly &&
            settings.shouldPreferVpnBoundIpRefresh(currentSnapshot, androidValidatedVpnNetwork)
        ) {
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
                // Allow the vpn-bound fallback even for the forced Tor-route probe. In tunnel mode
                // there is no runtime proxy port listening (it belongs to PROXY mode), so the forced
                // runtime-proxy probe always hits ECONNREFUSED; the vpn-bound path goes through the
                // tunnel -> runtime -> Tor and returns the real Tor exit. The caller's
                // canAcceptTorRouteIpRefresh guard (info != current VPN IP) prevents mislabeling the
                // VPN exit as the Tor exit in the bypass case where vpn-bound is the VPN exit.
                allowVpnBoundFallback = true,
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
        allowVpnBoundFallback: Boolean,
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
                    allowVpnBoundFallback = allowVpnBoundFallback,
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
                    allowVpnBoundFallback = allowVpnBoundFallback,
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
                    allowVpnBoundFallback = allowVpnBoundFallback,
                    error = error,
                )
            }
        }.recoverCatching { refreshError ->
            recoverCachedActiveTunnelIpInfo(
                currentSnapshot = currentSnapshot,
                allowCachedRecovery =
                allowVpnBoundFallback &&
                    settings.canRecoverCachedActiveTunnelIpInfo(
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
        allowVpnBoundFallback: Boolean,
        error: Exception,
    ): IpInfo {
        if (error is CancellationException) {
            throw error
        }
        if (
            !allowVpnBoundFallback ||
            !settings.canUseVpnBoundIpRefreshFallback(currentSnapshot, androidValidatedVpnNetwork)
        ) {
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
        // These probes also run through the Tor circuit (Tor-route exit refresh), where a TLS
        // handshake alone routinely takes 1-4s. The old 1.2s/2.5s windows timed out on nearly
        // every Tor fetch, so the exit geo (country/city) never resolved and the map could not
        // plot the Tor node. Geo enrichment races its candidates in parallel, so a longer
        // per-call timeout does not stack.
        const val DASHBOARD_IP_REFRESH_CALL_TIMEOUT_MS = 4_000L
        const val DASHBOARD_GEO_ENRICHMENT_CALL_TIMEOUT_MS = 8_000L
        const val ACTIVE_TUNNEL_VPN_BOUND_IP_REFRESH_TOTAL_TIMEOUT_MS = 15_000L
        const val ACTIVE_TUNNEL_RUNTIME_PROXY_IP_REFRESH_CALL_TIMEOUT_MS = 6_000L
        const val ACTIVE_TUNNEL_IP_REFRESH_CACHE_MAX_AGE_MS = 10 * 60 * 1_000L
    }
}
