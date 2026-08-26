package com.foxhole.guard.runtime

import android.net.Network
import android.os.Build
import android.os.SystemClock
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.DnsRuntimeStats
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.VpnSession
import com.foxhole.core.model.disableUnverifiedRuleSetRuntimeDns
import com.foxhole.core.runtime.DnsFilterRuntimePaths
import com.foxhole.core.runtime.FoxCoreConfigTranslator
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.I2pdEndpoints
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.LocalGuardStartResolution
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isSupportedForSystemDnsProtection
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.localGuardUsesFullCapture
import com.foxhole.core.runtime.nativeForceStopOutcomeOrNull
import com.foxhole.core.runtime.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.core.runtime.requireI2pPrivateDnsCompatibility
import com.foxhole.core.runtime.resolveLocalGuardStart
import com.foxhole.core.runtime.runtimeProfileName
import com.foxhole.core.runtime.runtimeSettingsAfterCleanProfileDisconnect
import com.foxhole.core.runtime.shouldDeferLocalGuardStartForActiveProfileRuntime
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.ensureNewAppQuarantineBaseline
import com.foxhole.guard.core.settings.updateDnsReplaceSystemDns
import com.foxhole.guard.diagnosticFailureLabel
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private data class PendingLocalGuardActivation(
    val mode: LocalGuardMode,
    val settings: Settings,
    val session: VpnSession,
    val vpnNetwork: Network,
    val analysisMessage: String?,
    val transitionGeneration: Long,
)

internal suspend fun FoxholeVpnService.startLocalGuard(
    requestedMode: LocalGuardMode,
    commandStartId: Int,
) = startLocalGuardInternal(
    requestedMode = requestedMode,
    commandStartId = commandStartId,
    preserveDisconnectingState = false,
)

internal suspend fun FoxholeVpnService.startLocalGuardAfterProfileDisconnect(
    requestedMode: LocalGuardMode,
    commandStartId: Int,
) = startLocalGuardInternal(
    requestedMode = requestedMode,
    commandStartId = commandStartId,
    preserveDisconnectingState = true,
)

private suspend fun FoxholeVpnService.startLocalGuardInternal(
    requestedMode: LocalGuardMode,
    commandStartId: Int,
    preserveDisconnectingState: Boolean,
) {
    val persistedSettings = container.settingsRepository.current()
    val settings =
        if (preserveDisconnectingState) {
            persistedSettings.runtimeSettingsAfterCleanProfileDisconnect()
        } else {
            persistedSettings
        }
    val desiredMode = settings.localGuardModeOrNull()

    val mode =
        when (resolveLocalGuardStart(desiredMode = desiredMode, requestedMode = requestedMode)) {
            LocalGuardStartResolution.START_REQUESTED -> requestedMode
            LocalGuardStartResolution.START_DESIRED -> {
                container.diagnosticsLogger.recordStructured(
                    "connection",
                    "local guard start retargeted",
                    "requested=${requestedMode.name.lowercase()}",
                    "desired=${desiredMode?.name?.lowercase().orEmpty()}",
                )
                checkNotNull(desiredMode)
            }
            LocalGuardStartResolution.STOP -> {
                disconnect(commandStartId = commandStartId, suppressLocalGuard = true)
                return
            }
        }
    if (
        handleLocalGuardPreflight(
            mode = mode,
            commandStartId = commandStartId,
        )
    ) {
        return
    }
    if (
        !preserveDisconnectingState &&
        shouldDeferLocalGuardStartForActiveProfileRuntime(
            snapshot = FoxholeVpnRuntimeBridge.snapshot.value,

            activeProfileSessionPresent = (activeSession?.profileId ?: 0L) > 0L,

            activeProfileVpnNetworkPresent = false,
        )
    ) {
        container.diagnosticsLogger.record(
            "connection",
            "local guard start deferred: active profile runtime mode=${mode.name.lowercase()}",
        )
        updateNotification()
        return
    }
    if (isSameLocalGuardRuntimeActive(mode) && isLocalGuardRuntimeCurrent(mode)) {
        runtimeNetworkActivityLoggingSuspended = false
        container.diagnosticsLogger.record(
            "connection",
            "local guard already active mode=${mode.name.lowercase()}",
        )
        updateNotification()
    } else {
        startNewLocalGuard(mode, commandStartId, settings, preserveDisconnectingState)
    }
}

private suspend fun FoxholeVpnService.startNewLocalGuard(
    mode: LocalGuardMode,
    commandStartId: Int,
    settings: Settings,
    preserveDisconnectingState: Boolean,
) {
    val transitionGeneration = beginRuntimeTransition("local_guard:${mode.name.lowercase()}")
    val tunnelHandover = retireActiveTunnelForLocalGuardHandover(mode)
    if (!tunnelHandover.ready) {
        fail(getString(R.string.error_runtime_start_failed), commandStartId)
        return
    }
    var replacedVpnNetworkHandle = tunnelHandover.previousVpnNetworkHandle
    var replacedVpnInterfaceName = tunnelHandover.previousVpnInterfaceName
    stopTrafficUpdates()
    stopAppTrafficStatsUpdates()
    stopGeoRefresh()
    stopNotificationHealthMonitoring()
    cancelScheduledAutoReconnect(resetAttempts = true)
    invalidateValidationEpoch("local_guard_start")
    if (activeLocalGuardMode != null) {
        val localGuardVpnNetwork = currentVpnNetworkOrNull()
        val localGuardVpnNetworkHandle = activeVpnNetworkHandle ?: localGuardVpnNetwork?.networkHandle
        val localGuardVpnInterfaceName =
            localGuardVpnNetwork?.let(connectivityManager::getLinkProperties)?.interfaceName
        container.diagnosticsLogger.record(
            "connection",
            "local guard restarting mode=${activeLocalGuardMode?.name?.lowercase().orEmpty()}",
        )

        if (!retireRuntimeForInterfaceHandover("local_guard_restart")) {
            fail(getString(R.string.error_runtime_start_failed), commandStartId)
            return
        }
        releaseRuntimeWakeLock()
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        localGuardVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
        replacedVpnNetworkHandle = localGuardVpnNetworkHandle
        replacedVpnInterfaceName = localGuardVpnInterfaceName
        runtimeNetworkActivityLoggingSuspended = false
    }
    val handover = establishNextInterfaceThenStopRetiredRuntimesWithProof(reason = "local_guard_start") {
        establishLocalGuardSession(
            mode = mode,
            commandStartId = commandStartId,
            settings = settings,
            transitionGeneration = transitionGeneration,
            replacedVpnNetworkHandle = replacedVpnNetworkHandle,
            replacedVpnInterfaceName = replacedVpnInterfaceName,
            preserveDisconnectingState = preserveDisconnectingState,
        )
    }
    val pending = handover.value ?: return
    if (!handover.retiredTunClosed) {
        fail(getString(R.string.error_vpn_teardown_pending), commandStartId)
        return
    }
    completeLocalGuardActivation(pending)
}

@Suppress("ReturnCount")
private suspend fun FoxholeVpnService.establishLocalGuardSession(
    mode: LocalGuardMode,
    commandStartId: Int,
    settings: Settings,
    transitionGeneration: Long,
    replacedVpnNetworkHandle: Long?,
    replacedVpnInterfaceName: String?,
    preserveDisconnectingState: Boolean,
): PendingLocalGuardActivation? {
    val session =
        runCatchingUnlessCancelled {
            buildLocalGuardSession(mode = mode, settings = settings)
        }.getOrElse { error ->
            container.i2pdManager.markCarrierUnavailable()
            container.diagnosticsLogger.recordFailure(
                "connection",
                "local guard session build failed: ${diagnosticFailureLabel(error)}",
            )
            fail(
                userFacingErrorMessage(error, R.string.error_runtime_start_failed),
                commandStartId,
            )
            return null
        }
    rememberI2pRelayNetworkClass()
    val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val analysisStatus = getString(R.string.notification_status_analysis)
    val analysisMessage =
        previousSnapshot.message
            .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus }
    activeSession = null
    activeLocalGuardMode = mode
    runtimeNetworkActivityLoggingSuspended = false

    renewBridgeWriter(TrafficMode.TUNNEL)
    bridgeWriter.clearTransientState(clearIpInfo = false)
    bridgeWriter.update(localGuardStartingSnapshot(mode, analysisMessage, preserveDisconnectingState))
    registerNetworkCallbackIfNeeded()
    registerDefaultNetworkCallbackIfNeeded()
    acquireRuntimeWakeLock()
    startNotificationHealthMonitoring()
    startChildProcessWatchdog()
    if (!isI2pEndpointLeaseCurrent(session)) {
        container.i2pdManager.markCarrierUnavailable()
        container.diagnosticsLogger.record(
            "i2pd",
            "local guard start refused: assembled I2P endpoint lease is stale",
        )
        fail(getString(R.string.status_child_component_degraded, "I2P"), commandStartId)
        return null
    }
    val result = startRuntimeWithHealthMetrics(session = session, owner = "local_guard")
    if (result.nativeForceStopOutcomeOrNull() != null) {
        return null
    }
    if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_start_result")) {
        return null
    }
    if (result.isSuccess) {
        registerVpnNetworkCallbackIfNeeded()
        val localGuardVpnNetwork = awaitLocalGuardVpnNetworkReady(
            mode,
            replacedVpnNetworkHandle,
            replacedVpnInterfaceName,
        )
        if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_network_ready")) {
            return null
        }
        if (localGuardVpnNetwork == null) {
            container.i2pdManager.markCarrierUnavailable()
            activeLocalGuardMode = null

            scheduleLocalGuardHeal(mode, "start_network_unavailable")
            fail(getString(R.string.error_local_guard_network_unavailable), commandStartId)
            return null
        }
        return PendingLocalGuardActivation(
            mode = mode,
            settings = settings,
            session = session,
            vpnNetwork = localGuardVpnNetwork,
            analysisMessage = analysisMessage,
            transitionGeneration = transitionGeneration,
        )
    } else {
        container.i2pdManager.markCarrierUnavailable()
        activeLocalGuardMode = null
        stopNotificationHealthMonitoring()
        releaseRuntimeWakeLock()
        val error = result.exceptionOrNull()
        container.diagnosticsLogger.recordFailure(
            "connection",
            "local guard start failed: ${diagnosticFailureLabel(error)}",
        )

        scheduleLocalGuardHeal(mode, "start_runtime_failed")
        fail(
            userFacingErrorMessage(error, R.string.error_runtime_start_failed),
            commandStartId
        )
        return null
    }
}

private fun FoxholeVpnService.completeLocalGuardActivation(pending: PendingLocalGuardActivation) {
    if (!isCurrentRuntimeTransition(pending.transitionGeneration, "local_guard_handoff_complete")) {
        return
    }
    updateActiveVpnUnderlyingNetwork(currentUpstreamNetworkOrNull())
    if (pending.mode == LocalGuardMode.DNS) {
        DnsRuntimeStats.reset()
        startRuntimeConnectionStatsUpdates(container.settingsRepository.settings.value)

        startDnsGuardWindowUpdates()
        bridgeWriter.updateTraffic(TrafficSnapshot())
    } else {
        trafficSampler.start()
        startTrafficUpdates()
        startAppTrafficStatsUpdates()
    }
    bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTED,
            trafficMode = TrafficMode.TUNNEL,
            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            profileName = pending.mode.runtimeProfileName(),
            protocolHint = com.foxhole.core.model.ProtocolHint.LOCAL_GUARD,
            message = pending.analysisMessage,
            isSmartStartConnection = pending.analysisMessage != null,
        ),
    )
    startGeoRefresh()
    cancelLocalGuardHeal(resetAttempts = true)
    container.diagnosticsLogger.record(
        "connection",
        "local guard started mode=${pending.mode.name.lowercase()}",
    )
    RuntimeResumeStateStore.markLocalGuardRuntime(this, pending.mode)

    container.connectionController.markRuntimeApplied(
        pending.session,
        pending.transitionGeneration,
        pending.mode,
    )
    startI2pdReadinessProbe(
        settings = pending.settings,
        session = pending.session,
        vpnNetwork = pending.vpnNetwork,
    )
    updateNotification()
}

private fun localGuardStartingSnapshot(
    mode: LocalGuardMode,
    analysisMessage: String?,
    preserveDisconnectingState: Boolean,
): ConnectionSnapshot =
    ConnectionSnapshot(
        state = if (preserveDisconnectingState) ConnectionState.DISCONNECTING else ConnectionState.CONNECTING,
        teardownPhase = if (preserveDisconnectingState) {
            com.foxhole.core.model.RuntimeTeardownPhase.ANDROID_TUNNEL
        } else {
            null
        },
        trafficMode = TrafficMode.TUNNEL,
        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
        profileName = mode.runtimeProfileName(),
        protocolHint = com.foxhole.core.model.ProtocolHint.LOCAL_GUARD,
        message = analysisMessage,
        isSmartStartConnection = analysisMessage != null,
    )

@Suppress("ReturnCount")
private suspend fun FoxholeVpnService.awaitLocalGuardVpnNetworkReady(
    mode: LocalGuardMode,
    replacedVpnNetworkHandle: Long?,
    replacedVpnInterfaceName: String?,
): Network? {
    val initialNetwork =
        awaitVpnNetworkOrNull(
            timeoutMs = FoxholeVpnService.LOCAL_GUARD_VPN_NETWORK_WAIT_TIMEOUT_MS,
            excludedHandle = replacedVpnNetworkHandle,
            excludedInterfaceName = replacedVpnInterfaceName,
        )
    if (initialNetwork == null) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "local guard vpn network wait timeout",
            "mode=${mode.name.lowercase()}",
        )
        delay(FoxholeVpnService.LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS)
        return null
    }
    activeVpnNetworkHandle = initialNetwork.networkHandle
    if (isVpnNetworkValidated(initialNetwork)) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "local guard vpn network validated",
            "mode=${mode.name.lowercase()}",
            "handle=${initialNetwork.networkHandle}",
        )
        return initialNetwork
    }

    var latestNetwork: Network = initialNetwork
    val deadline = SystemClock.elapsedRealtime() + FoxholeVpnService.LOCAL_GUARD_NETWORK_VALIDATION_TIMEOUT_MS
    while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadline) {
        currentVpnNetworkOrNull(
            excludedHandle = replacedVpnNetworkHandle,
            excludedInterfaceName = replacedVpnInterfaceName,
        )?.let { network ->
            latestNetwork = network
            activeVpnNetworkHandle = network.networkHandle
            if (isVpnNetworkValidated(network)) {
                container.diagnosticsLogger.recordStructured(
                    "connection",
                    "local guard vpn network validated",
                    "mode=${mode.name.lowercase()}",
                    "handle=${network.networkHandle}",
                )
                return network
            }
        }
        delay(FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS)
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "local guard vpn network validation timeout",
        "mode=${mode.name.lowercase()}",
        "handle=${latestNetwork.networkHandle}",
        "settle_ms=${FoxholeVpnService.LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS}",
    )
    delay(FoxholeVpnService.LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS)
    return latestNetwork.takeIf { probeLocalGuardFallbackReachability(mode, it) }
}

private suspend fun FoxholeVpnService.probeLocalGuardFallbackReachability(
    mode: LocalGuardMode,
    network: Network,
): Boolean {
    val httpResult =
        runCatchingUnlessCancelled {
            container.ipInfoRepository.probe(
                endpoint = DNS_INDEPENDENT_IP_INFO_ENDPOINT,
                callTimeoutMs = FoxholeVpnService.LOCAL_GUARD_CONNECTIVITY_PROBE_TIMEOUT_MS,
                network = network,
                resolverNetwork = network,
            )
        }
    val dnsResult =
        runCatchingUnlessCancelled {
            withContext(Dispatchers.IO) {
                network.getAllByName(FoxholeVpnService.LOCAL_GUARD_CONNECTIVITY_DNS_PROBE_HOST).isNotEmpty()
            }
        }
    val reachable = httpResult.isSuccess && dnsResult.getOrDefault(false)
    container.diagnosticsLogger.recordStructured(
        "connection",
        if (reachable) {
            "local guard reachability fallback passed"
        } else {
            "local guard reachability fallback failed"
        },
        "mode=${mode.name.lowercase()}",
        "handle=${network.networkHandle}",
        "http=${httpResult.isSuccess}",
        "dns=${dnsResult.getOrDefault(false)}",
        httpResult.exceptionOrNull()?.let { "http_error=${it.javaClass.simpleName}" },
        dnsResult.exceptionOrNull()?.let { "dns_error=${it.javaClass.simpleName}" },
    )
    return reachable
}

private suspend fun FoxholeVpnService.handleLocalGuardPreflight(
    mode: LocalGuardMode,
    commandStartId: Int,
): Boolean =
    when {
        !hasVpnPermission() -> {
            container.diagnosticsLogger.record("connection", "local guard skipped: missing vpn permission")
            stopService(commandStartId)
            true
        }

        shouldBlockSystemDnsLocalGuard(mode) -> {
            container.diagnosticsLogger.record(
                "dns",
                "system dns replacement skipped: android private dns active",
            )
            container.settingsRepository.updateDnsReplaceSystemDns(false)
            disconnect(
                message = getString(R.string.error_system_dns_private_dns_conflict),
                commandStartId = commandStartId,
                suppressLocalGuard = true,
            )
            true
        }
        else -> false
    }

private fun FoxholeVpnService.shouldBlockSystemDnsLocalGuard(mode: LocalGuardMode): Boolean =
    mode == LocalGuardMode.DNS &&
        !PrivateDnsSettings.current(this).isSupportedForSystemDnsProtection()

internal fun FoxholeVpnService.isSystemVpnLockdownActive(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled

internal suspend fun FoxholeVpnService.buildLocalGuardSession(
    mode: LocalGuardMode,
    settings: Settings,
): VpnSession {
    val baselineSettings = container.settingsRepository.ensureNewAppQuarantineBaseline(settings)
    val activePreset = container.routingRepository.currentPresetForRuntime()
    val privateDnsState = PrivateDnsSettings.currentState(this)
    val dnsFilterRuntimePaths = prepareLocalGuardDnsFilterRuntimePaths(baselineSettings)
    val runtimeSettings = baselineSettings.disableUnverifiedLocalGuardDnsFiltering(dnsFilterRuntimePaths)

    val dnsGuardFullCapture = mode == LocalGuardMode.DNS && isSystemVpnLockdownActive()
    if (dnsGuardFullCapture) {
        container.diagnosticsLogger.record(
            "dns",
            "dns guard full capture: system vpn lockdown active",
        )
    }
    val i2pCapableTun = mode != LocalGuardMode.DNS || dnsGuardFullCapture
    requireI2pPrivateDnsCompatibility(
        i2pActive = runtimeSettings.i2pRuntimeActive() && i2pCapableTun,
        privateDnsMode = PrivateDnsSettings.current(this),
    )
    val knownApplications =
        if (runtimeSettings.expert.newAppQuarantineEnabled) {
            runtimeSettings.expert.quarantineKnownApplications
        } else {
            emptyList()
        }
    val i2pEndpoint =
        startLocalGuardI2pEndpointOrNull(runtimeSettings, mode, dnsGuardFullCapture)
    val session = VpnSession(
        profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
        profileName = mode.runtimeProfileName(),
        protocolHint = com.foxhole.core.model.ProtocolHint.LOCAL_GUARD,
        configJson =
        container.runtimeConfigAssembler.assembleLocalGuard(
            settings = runtimeSettings,
            mode = mode,
            dnsFilterRuntimePaths = dnsFilterRuntimePaths,
            dnsGuardFullCapture = dnsGuardFullCapture,
            i2pSocksPort = i2pEndpoint?.socksPort,
        ),
        correlationId = "local-guard-${System.currentTimeMillis()}",

        forceFakeIpDns = localGuardUsesFullCapture(mode, dnsGuardFullCapture),
        quarantineNewApps = runtimeSettings.expert.newAppQuarantineEnabled,
        knownApplications = knownApplications,
        runtimeConfigFingerprint =
        container.runtimeConfigAssembler.localGuardRuntimeFingerprint(
            settings = runtimeSettings,
            mode = mode,
            activePreset = activePreset,
            privateDnsState = privateDnsState,
        ),
        quarantinePolicyRevision = runtimeSettings.expert.quarantinePolicyRevision,
        i2pEndpointGeneration = i2pEndpoint?.generation,
    )
    return session.copy(
        foxCoreConfig =
        FoxCoreConfigTranslator().translate(
            session = session,
            dnsRuleSetBootstrap = dnsFilterRuntimePaths?.foxCoreBootstrap,
        ),
    )
}

private suspend fun FoxholeVpnService.startLocalGuardI2pEndpointOrNull(
    settings: Settings,
    mode: LocalGuardMode,
    dnsGuardFullCapture: Boolean,
): I2pdEndpoints? {
    val i2pCapableTun = mode != LocalGuardMode.DNS || dnsGuardFullCapture
    if (!settings.i2pRuntimeActive() || !i2pCapableTun) {
        container.i2pdManager.stop()
        return null
    }
    return runCatching { container.i2pdManager.ensureStarted(settings.i2p) }
        .onFailure { error ->
            if (error is CancellationException) throw error
            container.diagnosticsLogger.recordFailure(
                "i2pd",
                "local guard i2pd start failed: ${diagnosticFailureLabel(error)}",
            )
        }.getOrThrow()
}

private suspend fun FoxholeVpnService.prepareLocalGuardDnsFilterRuntimePaths(settings: Settings): DnsFilterRuntimePaths? {
    if (!settings.dns.filteringEnabled) {
        return null
    }
    return container.dnsFilterAssetInstaller.prepareVerifiedOrNull()
        ?: run {
            container.diagnosticsLogger.record("dns", "dns rule-set runtime disabled: verified filter unavailable")
            null
        }
}

private fun Settings.disableUnverifiedLocalGuardDnsFiltering(
    dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
): Settings =
    if (dns.filteringEnabled && dnsFilterRuntimePaths == null) {
        copy(dns = dns.disableUnverifiedRuleSetRuntimeDns())
    } else {
        this
    }
