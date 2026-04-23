package com.foxhole.beta.vpn

import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.mergeIpInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            ?: error("vpn network unavailable")
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        container.ipInfoRepository.fetch(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            network = boundNetworkForAppOwnedRequest(vpnNetwork),
        )
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
        TrafficMode.TUNNEL -> refreshVpnIpInfo(callTimeoutMs = callTimeoutMs, network = network)
        TrafficMode.PROXY -> refreshProxyIpInfo(callTimeoutMs = callTimeoutMs)
    }

internal suspend fun FoxholeVpnService.refreshConnectionIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? =
    when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
        TrafficMode.TUNNEL -> refreshVpnIpv4Info(callTimeoutMs = callTimeoutMs, network = network)
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

internal suspend fun FoxholeVpnService.refreshVpnIpv4InfoInternal(
    callTimeoutMs: Long,
    network: Network? = null,
): IpInfo? {
    val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
    val vpnNetwork = network ?: awaitVpnNetworkOrNull(FoxholeVpnService.VPN_NETWORK_WAIT_TIMEOUT_MS) ?: return null
    val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
    val info =
        container.ipInfoRepository.fetchIpv4(
            endpoint = endpoint,
            callTimeoutMs = callTimeoutMs,
            network = boundNetworkForAppOwnedRequest(vpnNetwork),
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

internal fun FoxholeVpnService.scheduleValidationInternal(
    session: VpnSession,
    failOnFailure: Boolean,
    expectedFreshVpnNetworkHandle: Long? = null,
    onSuccess: (Network) -> Unit,
) {
    validationJob?.cancel()
    val job =
        scope.launch(Dispatchers.Main.immediate) {
            val validation = validateTunnelConnectivity(expectedFreshVpnNetworkHandle)
            if (activeSession?.profileId != session.profileId) {
                return@launch
            }
            if (validation.isSuccess) {
                container.diagnosticsLogger.record("dns", "post-start probe passed")
                onSuccess(validation.getOrThrow())
            } else {
                val message = validation.exceptionOrNull()?.message ?: getString(R.string.error_dns_probe_failed)
                container.diagnosticsLogger.record("dns", "post-start probe failed: $message")
                if (failOnFailure) {
                    fail(message)
                }
            }
        }
    validationJob = job
}

internal suspend fun FoxholeVpnService.validateTunnelConnectivityInternal(
    expectedFreshVpnNetworkHandle: Long? = null,
): Result<Network> =
    withContext(Dispatchers.IO) {
        val validationStartedAt = System.currentTimeMillis()
        val activeProtocolHint = activeSession?.protocolHint
        container.diagnosticsLogger.recordStructured(
            "dns",
            "Tunnel validation started",
            activeProtocolHint?.name?.lowercase(),
        )
        val result =
            TunnelConnectivityProbe.run(
                attempts = FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS,
                initialDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_INITIAL_DELAY_MS,
                retryDelayMs = FoxholeVpnService.CONNECTIVITY_PROBE_RETRY_DELAY_MS,
                timeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS,
                onFailure = { attemptIndex, error ->
                    container.diagnosticsLogger.record(
                        "dns",
                        "probe attempt ${attemptIndex + 1}/${FoxholeVpnService.CONNECTIVITY_PROBE_ATTEMPTS} failed: ${error.message.orEmpty()}",
                    )
                },
            ) {
                val vpnNetwork =
                    awaitVpnNetworkOrNull(
                        FoxholeVpnService.CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS,
                        excludedHandle = expectedFreshVpnNetworkHandle,
                    ) ?: error("vpn network unavailable")
                val ipRefresh =
                    runCatching {
                        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
                        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
                        container.ipInfoRepository.fetch(
                            endpoint = endpoint,
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                            network = boundNetworkForAppOwnedRequest(vpnNetwork),
                            mode = IpInfoFetchMode.ENTRY_QUICK,
                        ).withDnsServers(
                            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
                            remoteDnsServers = remoteDnsServers,
                        )
                    }
                if (ipRefresh.isSuccess) {
                    FoxholeVpnRuntimeBridge.updateIpInfo(ipRefresh.getOrThrow())
                    container.diagnosticsLogger.record("dns", "vpn network passed in-process ip refresh")
                    return@run vpnNetwork
                }
                if (!isVpnNetworkValidated(vpnNetwork)) {
                    throw (ipRefresh.exceptionOrNull() ?: IllegalStateException("vpn ip refresh failed"))
                }
                val ipErrorMessage = ipRefresh.exceptionOrNull()?.message.orEmpty()
                container.diagnosticsLogger.record(
                    "dns",
                    "vpn network validated by android; in-process ip refresh failed, probing connectivity: $ipErrorMessage",
                )
                val dnsIndependentFallback =
                    runCatching {
                        probeDnsIndependentConnectivityFallback(FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS)
                    }
                if (dnsIndependentFallback.isSuccess) {
                    container.diagnosticsLogger.record(
                        "dns",
                        "dns-independent public reachability probe passed after ip refresh failure",
                    )
                    scope.launch(Dispatchers.IO) {
                        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                    }
                    return@run vpnNetwork
                }
                container.diagnosticsLogger.record(
                    "dns",
                    "dns-independent public reachability probe failed: ${dnsIndependentFallback.exceptionOrNull()?.message.orEmpty()}",
                )
                val endpointProbe =
                    runCatching {
                        probeConnectivityEndpoints(
                            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
                            network = boundNetworkForAppOwnedRequest(vpnNetwork),
                        )
                    }
                if (endpointProbe.isFailure) {
                    val evidence = inspectValidatedTunnelEvidence(validationStartedAt)
                    val gracePolicy = selectTunnelValidationGracePolicy(activeProtocolHint, evidence)
                    if (gracePolicy != null) {
                        container.diagnosticsLogger.recordStructured(
                            "dns",
                            "Validated tunnel grace retry started",
                            activeProtocolHint?.name?.lowercase(),
                            "attempts=${gracePolicy.attempts}",
                            "window_ms=${gracePolicy.totalTimeoutMs}",
                        )
                        val graceResult = retryValidatedTunnelConnectivityWithGrace(vpnNetwork, gracePolicy)
                        if (graceResult.isSuccess) {
                            container.diagnosticsLogger.record("dns", "validated tunnel grace retry passed")
                            return@run vpnNetwork
                        }
                        container.diagnosticsLogger.record(
                            "dns",
                            "validated tunnel grace retry failed: ${graceResult.exceptionOrNull()?.message.orEmpty()}",
                        )
                    }
                    evidence?.let {
                        container.diagnosticsLogger.record(
                            "dns",
                            "validated tunnel had libbox activity, but vpn-bound endpoint probe still failed; failing closed",
                        )
                    }
                    throw (endpointProbe.exceptionOrNull() ?: IllegalStateException("connectivity probe failed"))
                }
                scope.launch(Dispatchers.IO) {
                    refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
                }
                vpnNetwork
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record("dns", "vpn network passed ip validation")
            return@withContext result
        }
        Result.failure(IllegalStateException(getString(R.string.error_dns_probe_failed)))
    }

internal suspend fun FoxholeVpnService.retryValidatedTunnelConnectivityWithGraceInternal(
    vpnNetwork: Network,
    policy: TunnelValidationGracePolicy,
): Result<Unit> =
    TunnelConnectivityProbe.run(
        attempts = policy.attempts,
        initialDelayMs = policy.initialDelayMs,
        retryDelayMs = policy.retryDelayMs,
        timeoutMs = policy.totalTimeoutMs,
        onFailure = { attemptIndex, error ->
            container.diagnosticsLogger.record(
                "dns",
                "grace retry ${attemptIndex + 1}/${policy.attempts} failed: ${error.message.orEmpty()}",
            )
        },
    ) {
        val ipRefresh =
            runCatching {
                refreshVpnIpInfo(
                    callTimeoutMs = policy.callTimeoutMs,
                    network = vpnNetwork,
                )
            }
        if (ipRefresh.isSuccess) {
            FoxholeVpnRuntimeBridge.updateIpInfo(ipRefresh.getOrThrow())
            return@run Unit
        }
        runCatching {
            probeDnsIndependentConnectivityFallback(policy.callTimeoutMs)
        }.recoverCatching {
            probeConnectivityEndpoints(
                callTimeoutMs = policy.callTimeoutMs,
                network = boundNetworkForAppOwnedRequest(vpnNetwork),
            )
        }.getOrThrow()
        refreshValidatedTunnelIpInfoBestEffort(vpnNetwork)
    }

internal fun FoxholeVpnService.inspectValidatedTunnelEvidenceInternal(validationStartedAt: Long): TunnelValidationEvidence? {
    val evidence =
        TunnelValidationEvidenceClassifier.classify(
            entries = container.diagnosticsLogger.entries.value,
            sinceMs = validationStartedAt,
        )
    evidence.fatalRuntimeMessage?.let { fatalMessage ->
        container.diagnosticsLogger.record(
            "dns",
            "validated tunnel evidence rejected due to runtime error: $fatalMessage",
        )
        return null
    }
    return evidence.takeIf(TunnelValidationEvidence::hasSuccessfulTunnelActivity)
}

internal suspend fun FoxholeVpnService.refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork: Network) {
    runCatching {
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
        container.ipInfoRepository.fetch(
            endpoint = endpoint,
            callTimeoutMs = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
            network = boundNetworkForAppOwnedRequest(vpnNetwork),
            mode = IpInfoFetchMode.ENTRY_QUICK,
        ).withDnsServers(
            localDnsServers = connectivityManager.dnsServerAddresses(vpnNetwork),
            remoteDnsServers = remoteDnsServers,
        )
    }
        .onSuccess(FoxholeVpnRuntimeBridge::updateIpInfo)
        .onFailure { error ->
            container.diagnosticsLogger.record(
                "ip",
                "validated tunnel ip refresh deferred: ${error.message.orEmpty()}",
            )
        }
}

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsInternal(
    callTimeoutMs: Long = FoxholeVpnService.CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
    network: Network? = null,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatching {
                container.ipInfoRepository.probe(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    network = network,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record("dns", "validation endpoint ok: $endpoint")
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.record(
            "dns",
            "validation endpoint failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("connectivity probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("connectivity probe failed")
}

internal suspend fun FoxholeVpnService.connectivityProbeEndpointsInternal(): List<String> {
    val preferredEndpoint = container.settingsRepository.current().connection.ipInfoEndpoint.trim()
    return buildList {
        preferredEndpoint.takeIf { it.isNotBlank() }?.let(::add)
        FoxholeVpnService.CONNECTIVITY_PROBE_ENDPOINTS.forEach { endpoint ->
            if (endpoint != preferredEndpoint) {
                add(endpoint)
            }
        }
    }
}

internal suspend fun FoxholeVpnService.runNotificationConnectivityProbeInternal(session: VpnSession): Boolean =
    withContext(Dispatchers.IO) {
        val result =
            runCatching {
                when (FoxholeVpnRuntimeBridge.snapshot.value.trafficMode) {
                    TrafficMode.TUNNEL -> {
                        if (runCatching { currentVpnNetwork() }.getOrNull() == null) {
                            return@withContext false
                        }
                        val target = VpnHealthProbeTargetSelector.select(session.configJson)
                        if (target != null) {
                            probeSessionTarget(target)
                        } else {
                            probeConnectivityEndpoints(callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS)
                        }
                    }
                    TrafficMode.PROXY -> {
                        val proxyAccess = container.settingsRepository.current().preferredAppProxyAccess()
                            ?: error("proxy surface is unavailable")
                        probeConnectivityEndpointsOverLocalProxy(
                            proxy = proxyAccess,
                            callTimeoutMs = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                        )
                    }
                }
            }
        result
            .onFailure { error ->
                container.diagnosticsLogger.record(
                    "health",
                    "notification probe failed: ${error.message.orEmpty()}",
                )
            }.isSuccess
    }

internal suspend fun FoxholeVpnService.probeConnectivityEndpointsOverLocalProxyInternal(
    proxy: com.foxhole.beta.core.network.HttpProxyAccess,
    callTimeoutMs: Long,
) {
    var lastFailure: Throwable? = null
    connectivityProbeEndpoints().forEach { endpoint ->
        val result =
            runCatching {
                container.ipInfoRepository.probe(
                    endpoint = endpoint,
                    callTimeoutMs = callTimeoutMs,
                    proxy = proxy,
                )
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record("health", "proxy probe ok: $endpoint")
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.record(
            "health",
            "proxy probe failed: $endpoint reason=${lastFailure?.message.orEmpty()}",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("proxy probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("proxy probe failed")
}

internal suspend fun FoxholeVpnService.probeDnsIndependentConnectivityFallbackInternal(callTimeoutMs: Long) {
    var lastFailure: Throwable? = null
    dnsIndependentConnectivityProbeTargets().forEach { target ->
        val result =
            runCatching {
                probeSessionTarget(target)
            }
        if (result.isSuccess) {
            container.diagnosticsLogger.record(
                "dns",
                "dns-independent public reachability probe ok: ${target.host}:${target.port}",
            )
            return
        }
        lastFailure = result.exceptionOrNull()
        container.diagnosticsLogger.record(
            "dns",
            "dns-independent public reachability probe failed: ${target.host}:${target.port} reason=${lastFailure?.message.orEmpty()} timeout_ms=$callTimeoutMs",
        )
        if (!currentCoroutineContext().isActive) {
            throw lastFailure ?: IllegalStateException("dns-independent connectivity probe cancelled")
        }
    }
    throw lastFailure ?: IllegalStateException("dns-independent connectivity probe failed")
}

internal fun FoxholeVpnService.probeSessionTargetInternal(target: VpnHealthProbeTarget) {
    val address = resolveProbeAddress(target.host)
    when (target.transport) {
        VpnHealthProbeTransport.TCP -> {
            Socket().use {
                it.soTimeout = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS.toInt()
                it.connect(
                    InetSocketAddress(address, target.port),
                    FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS.toInt(),
                )
            }
        }

        VpnHealthProbeTransport.UDP -> {
            DatagramSocket().use { socket ->
                socket.soTimeout = FoxholeVpnService.NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS.toInt()
                socket.connect(address, target.port)
                socket.send(
                    DatagramPacket(
                        FoxholeVpnService.UDP_HEALTH_PROBE_PAYLOAD,
                        FoxholeVpnService.UDP_HEALTH_PROBE_PAYLOAD.size,
                    ),
                )
            }
        }
    }
}

internal fun FoxholeVpnService.resolveProbeAddressInternal(host: String): InetAddress =
    InetAddress.getAllByName(host).firstOrNull() ?: error("probe target unavailable")

internal fun FoxholeVpnService.refreshDefaultNetworkAvailabilityInternal() {
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

internal fun FoxholeVpnService.onDefaultNetworkCapabilitiesChangedInternal(
    capabilities: NetworkCapabilities?,
    reason: String,
) {
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    recordDefaultNetworkCapabilities(reason = reason, capabilities = capabilities)
    if (FoxholeVpnRuntimeBridge.snapshot.value.state !in FoxholeVpnService.NOTIFICATION_HEALTH_VISIBLE_STATES) {
        return
    }
    if (defaultNetworkAvailable) {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.CHECKING,
            resetFailures = true,
        )
    } else {
        markNotificationConnectivityOffline()
    }
}

internal fun FoxholeVpnService.markNotificationConnectivityOfflineInternal() {
    updateNotificationConnectivityHealth(
        state = ConnectivityHealthState.OFFLINE,
        force = true,
    )
    consecutiveNotificationHealthFailures = FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD
}

internal fun FoxholeVpnService.updateNotificationConnectivityHealthInternal(
    state: ConnectivityHealthState,
    resetFailures: Boolean = false,
    force: Boolean = false,
) {
    if (resetFailures) {
        consecutiveNotificationHealthFailures = 0
    }
    if (!force && notificationConnectivityHealthState == state) {
        return
    }
    notificationConnectivityHealthState = state
    updateNotification()
}

internal fun FoxholeVpnService.isUpstreamNetworkInternal(network: Network): Boolean {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

internal fun FoxholeVpnService.recordDefaultNetworkCapabilitiesInternal(
    reason: String,
    capabilities: NetworkCapabilities?,
) {
    val summary = describeNetworkCapabilities(capabilities)
    if (reason == "default network changed" && summary == lastDefaultNetworkSummary) {
        return
    }
    lastDefaultNetworkSummary = summary
    container.diagnosticsLogger.recordStructured(
        "network",
        reason.replaceFirstChar(Char::uppercaseChar),
        summary,
    )
}

internal fun FoxholeVpnService.recordNetworkEventInternal(
    message: String,
    capabilities: NetworkCapabilities?,
) {
    container.diagnosticsLogger.recordStructured(
        "network",
        message.replaceFirstChar(Char::uppercaseChar),
        describeNetworkCapabilities(capabilities),
    )
}

internal fun FoxholeVpnService.describeNetworkCapabilitiesInternal(capabilities: NetworkCapabilities?): String {
    if (capabilities == null) {
        return "unavailable"
    }
    val transport =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    val traits =
        buildList {
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("unmetered")
        }
    return if (traits.isEmpty()) {
        transport
    } else {
        "$transport • ${traits.joinToString(separator = " • ")}"
    }
}

internal fun FoxholeVpnService.currentVpnNetworkInternal(excludedHandle: Long? = null): Network =
    currentVpnNetworkOrNull(excludedHandle) ?: error("vpn network unavailable")

internal fun FoxholeVpnService.currentVpnNetworkOrNullInternal(excludedHandle: Long? = null): Network? =
    ConnectivityNetworkRegistry.snapshot(this).firstOrNull { network ->
        connectivityManager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            network.networkHandle != excludedHandle
    }

internal fun FoxholeVpnService.isVpnNetworkValidatedInternal(network: Network): Boolean =
    connectivityManager
        .getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

internal suspend fun FoxholeVpnService.awaitVpnNetworkOrNullInternal(
    timeoutMs: Long,
    excludedHandle: Long? = null,
): Network? =
    withTimeoutOrNull(timeoutMs) {
        while (currentCoroutineContext().isActive) {
            currentVpnNetworkOrNull(excludedHandle)?.let { return@withTimeoutOrNull it }
            delay(FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS)
        }
        null
    }

internal fun FoxholeVpnService.onConnectionStartedInternal(
    session: VpnSession,
    trafficMode: TrafficMode,
) {
    if (trafficJob == null) {
        trafficSampler.start()
        startTrafficUpdates()
    }
    FoxholeVpnRuntimeBridge.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            trafficMode = trafficMode,
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
        ),
    )
    updateNotification()
    startGeoRefresh()
}

internal fun FoxholeVpnService.onTunnelValidatedInternal(
    session: VpnSession,
    vpnNetwork: Network,
) {
    if (FoxholeVpnRuntimeBridge.snapshot.value.state != ConnectionState.CONNECTED) {
        onConnectionStarted(session, TrafficMode.TUNNEL)
    }
    startGeoRefresh(vpnNetwork)
}

internal fun FoxholeVpnService.currentNotificationSnapshotInternal(): NotificationSnapshot {
    val connection = FoxholeVpnRuntimeBridge.snapshot.value
    val ipInfo = FoxholeVpnRuntimeBridge.ipInfo.value
    val traffic = FoxholeVpnRuntimeBridge.traffic.value
    return NotificationSnapshot(
        profileName = connection.profileName,
        state = connection.state,
        statusMessage = connection.message,
        connectivityHealthState = notificationConnectivityHealthState,
        ipAddress = ipInfo?.ipv4 ?: ipInfo?.ip,
        countryCode = ipInfo?.countryCode,
        countryName = ipInfo?.countryName,
        trafficAvailable = traffic.available,
        txRate = traffic.txBytesPerSec,
        rxRate = traffic.rxBytesPerSec,
        txTotal = traffic.txTotalBytes,
        rxTotal = traffic.rxTotalBytes,
        updatedAt = maxOf(connection.lastChangeAt, ipInfo?.fetchedAt ?: 0L, traffic.sampledAt),
    )
}

internal fun FoxholeVpnService.notificationCollapsedTextInternal(snapshot: NotificationSnapshot): String =
    notificationHealthText(snapshot).orEmpty()

internal fun FoxholeVpnService.notificationExpandedTextInternal(snapshot: NotificationSnapshot): String? = notificationHealthText(snapshot)

internal fun FoxholeVpnService.notificationHealthTextInternal(snapshot: NotificationSnapshot): String? {
    if (snapshot.state !in FoxholeVpnService.NOTIFICATION_HEALTH_VISIBLE_STATES) {
        return null
    }
    val labelRes =
        when (snapshot.connectivityHealthState) {
            ConnectivityHealthState.CHECKING -> R.string.notification_health_checking
            ConnectivityHealthState.ONLINE -> R.string.notification_health_online
            ConnectivityHealthState.OFFLINE -> R.string.notification_health_offline
        }
    return listOfNotNull(
        StealthNotificationFormatter.subtext(snapshot),
        getString(labelRes),
    ).joinToString(separator = " · ")
}

internal suspend fun FoxholeVpnService.persistProfileTrafficInternal(
    session: VpnSession,
    traffic: TrafficSnapshot,
) {
    if (!traffic.available && traffic.rxTotalBytes <= 0L && traffic.txTotalBytes <= 0L) {
        return
    }
    container.settingsRepository.accumulateProfileTraffic(
        profileId = session.profileId,
        profileName = session.profileName,
        protocolHint = session.protocolHint,
        rxBytes = traffic.rxTotalBytes,
        txBytes = traffic.txTotalBytes,
        updatedAt = System.currentTimeMillis(),
    )
}

internal fun FoxholeVpnService.notificationStateLabelInternal(snapshot: NotificationSnapshot): String =
    when {
        snapshot.state == ConnectionState.CONNECTED -> getString(R.string.notification_status_connected)
        snapshot.state == ConnectionState.CONNECTING &&
            snapshot.statusMessage == getString(R.string.notification_status_analysis) ->
            getString(R.string.notification_status_analysis)
        else -> getString(R.string.notification_status_disconnected)
    }
