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
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.LocalGuardStartResolution
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.i2pRuntimeActive
import com.foxhole.core.runtime.isSupportedForSystemDnsProtection
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.DNS_INDEPENDENT_IP_INFO_ENDPOINT
import com.foxhole.core.runtime.resolveLocalGuardStart
import com.foxhole.core.runtime.runtimeProfileName
import com.foxhole.core.runtime.shouldDeferLocalGuardStartForActiveProfileRuntime
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.updateDnsReplaceSystemDns
import com.foxhole.guard.userFacingErrorMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local-guard (DNS-filter / on-device protection) start-up and preflight for [FoxholeVpnService],
 * extracted from the service body in the Phase B split by responsibility.
 */

internal suspend fun FoxholeVpnService.startLocalGuard(
    requestedMode: LocalGuardMode,
    commandStartId: Int,
) {
    val settings = container.settingsRepository.current()
    val desiredMode = settings.localGuardModeOrNull()
    // The command was queued against older settings; the settings are the authority (see
    // resolveLocalGuardStart). Everything below runs on the mode they ask for, not the one the
    // intent carried — a stale command must never take down a guard that is still wanted.
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
        shouldDeferLocalGuardStartForActiveProfileRuntime(
            snapshot = FoxholeVpnRuntimeBridge.snapshot.value,
            // Only a real upstream VPN profile (id > 0) defers — its tunnel already enforces the
            // guard's blocking/DNS. A Tor-only session (id = -20) is a mode the firewall replaces:
            // treat it as "no profile session" so the guard starts and its handoff stops Tor.
            activeProfileSessionPresent = (activeSession?.profileId ?: 0L) > 0L,
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
        startNewLocalGuard(mode, commandStartId, settings)
    }
}

@Suppress("ReturnCount")
private suspend fun FoxholeVpnService.startNewLocalGuard(
    mode: LocalGuardMode,
    commandStartId: Int,
    settings: Settings,
) {
    val transitionGeneration = beginRuntimeTransition("local_guard:${mode.name.lowercase()}")
    stopActiveTunnelBeforeLocalGuard(mode)
    stopTrafficUpdates()
    stopAppTrafficStatsUpdates()
    stopGeoRefresh()
    stopNotificationHealthMonitoring()
    cancelScheduledAutoReconnect(resetAttempts = true)
    invalidateValidationEpoch("local_guard_start")
    if (activeLocalGuardMode != null) {
        container.diagnosticsLogger.record(
            "connection",
            "local guard restarting mode=${activeLocalGuardMode?.name?.lowercase().orEmpty()}",
        )
        stopRuntimeFailClosed(reason = "local_guard_restart")
        releaseRuntimeWakeLock()
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        runtimeNetworkActivityLoggingSuspended = false
    }
    FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = TrafficMode.TUNNEL)
    val session = buildLocalGuardSession(mode = mode, settings = settings)
    val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val analysisStatus = getString(R.string.notification_status_analysis)
    val analysisMessage =
        previousSnapshot.message
            .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus }
    activeSession = null
    activeLocalGuardMode = mode
    runtimeNetworkActivityLoggingSuspended = false
    // The guard is a new session and needs a new ownership claim, exactly like the profile connect
    // path takes one. This service reuses one writer instance across sessions, and any IDLE/ERROR
    // published on the way here — the stop half of a disconnect, a profile switch, a previous
    // guard — fences the old claim for good. Without renewing, every publish below is refused as
    // stale: measured on the bench Pixel as `bridge write rejected: stale mode=tunnel
    // incoming=connected` in the same millisecond the guard logged `local guard started`. The
    // firewall ran for real while the app never showed it as connected. The renewal was added to
    // the connect path when this class of bug was first found; the guard path was missed.
    renewBridgeWriter(TrafficMode.TUNNEL)
    bridgeWriter.clearTransientState(clearIpInfo = false)
    bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.CONNECTING,
            trafficMode = TrafficMode.TUNNEL,
            profileId = FoxholeVpnService.LOCAL_GUARD_PROFILE_ID,
            profileName = mode.runtimeProfileName(),
            protocolHint = com.foxhole.core.model.ProtocolHint.LOCAL_GUARD,
            message = analysisMessage,
            isSmartStartConnection = analysisMessage != null,
        ),
    )
    registerNetworkCallbackIfNeeded()
    registerDefaultNetworkCallbackIfNeeded()
    acquireRuntimeWakeLock()
    startNotificationHealthMonitoring()
    val result = startRuntimeWithHealthMetrics(session = session, owner = "local_guard")
    if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_start_result")) {
        return
    }
    if (result.isSuccess) {
        registerVpnNetworkCallbackIfNeeded()
        val localGuardVpnNetwork = awaitLocalGuardVpnNetworkReady(mode)
        if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_network_ready")) {
            return
        }
        if (localGuardVpnNetwork == null) {
            activeLocalGuardMode = null
            // Boot-without-network / transient connectivity: self-heal instead of a terminal ERROR
            // (the error disconnect below keeps this scheduled heal; see FoxholeVpnService.disconnect).
            scheduleLocalGuardHeal(mode, "start_network_unavailable")
            fail(getString(R.string.error_local_guard_network_unavailable), commandStartId)
            return
        }
        updateActiveVpnUnderlyingNetwork(currentUpstreamNetworkOrNull())
        if (mode == LocalGuardMode.DNS) {
            DnsRuntimeStats.reset()
            startRuntimeConnectionStatsUpdates(container.settingsRepository.settings.value)
            // Windows must still be aggregated here, or the blocked-query counters this guard
            // produces are never drained and the dashboard reports zero DNS blocks in DNS mode.
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
                profileName = mode.runtimeProfileName(),
                protocolHint = com.foxhole.core.model.ProtocolHint.LOCAL_GUARD,
                message = analysisMessage,
                isSmartStartConnection = analysisMessage != null,
            ),
        )
        startGeoRefresh()
        // Guard is healthy again: reset the self-heal budget so a later, independent outage gets a
        // fresh set of retries (this also lands after a successful heal-restart).
        cancelLocalGuardHeal(resetAttempts = true)
        container.diagnosticsLogger.record("connection", "local guard started mode=${mode.name.lowercase()}")
        RuntimeResumeStateStore.markLocalGuardRuntime(this, mode)
        // Without this every later syncLocalGuard sees runtime_current=false and restarts the
        // guard in a loop (each settings echo triggered a full restart of a healthy guard).
        // Must record the GUARD fingerprint (mode + persistent-blocking + activity-logging), not
        // the general one, or those toggles silently no-op on a live firewall.
        container.connectionController.markCurrentLocalGuardRuntimeApplied(mode)
        updateNotification()
    } else {
        activeLocalGuardMode = null
        stopNotificationHealthMonitoring()
        releaseRuntimeWakeLock()
        val error = result.exceptionOrNull()
        // Transient runtime start failure: schedule a bounded self-heal before the error teardown.
        scheduleLocalGuardHeal(mode, "start_runtime_failed")
        fail(
            userFacingErrorMessage(error, R.string.error_runtime_missing),
            commandStartId
        )
    }
}

@Suppress("ReturnCount")
private suspend fun FoxholeVpnService.awaitLocalGuardVpnNetworkReady(mode: LocalGuardMode): Network? {
    val initialNetwork = awaitVpnNetworkOrNull(FoxholeVpnService.LOCAL_GUARD_VPN_NETWORK_WAIT_TIMEOUT_MS)
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
        currentVpnNetworkOrNull()?.let { network ->
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

// The gates that can still refuse a guard the settings DO want. "Is a guard wanted at all, and
// which one" is resolved before this (resolveLocalGuardStart) and is deliberately not repeated
// here: that question has exactly one owner.
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

/** Whether the user enabled "block connections without VPN" for this app in system settings. */
private fun FoxholeVpnService.isSystemVpnLockdownActive(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled

internal suspend fun FoxholeVpnService.buildLocalGuardSession(
    mode: LocalGuardMode,
    settings: Settings,
): VpnSession {
    val dnsFilterRuntimePaths = prepareLocalGuardDnsFilterRuntimePaths(settings)
    val runtimeSettings = settings.disableUnverifiedLocalGuardDnsFiltering(dnsFilterRuntimePaths)
    // Under the system always-on-VPN lockdown the DNS guard cannot stay DNS-only: the system
    // blocks every packet the TUN does not route, so the guard captures the full device and
    // forwards non-DNS traffic direct instead.
    val dnsGuardFullCapture = mode == LocalGuardMode.DNS && isSystemVpnLockdownActive()
    if (dnsGuardFullCapture) {
        container.diagnosticsLogger.record(
            "dns",
            "dns guard full capture: system vpn lockdown active",
        )
    }
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
            i2pSocksPort = startLocalGuardI2pProxyPortOrNull(runtimeSettings, mode, dnsGuardFullCapture),
        ),
        correlationId = "local-guard-${System.currentTimeMillis()}",
    )
    // The same readiness probe the profile connect path runs. `I2pNetworkPhase.CONNECTED` has one
    // writer — the authenticated SOCKS probe inside `I2pdProcessManager.awaitReady()` — and this
    // path never called it, so I2P WITHOUT a VPN profile (which is the ordinary way to use it: the
    // transparent guard carries the `.i2p` diversion by itself) left the panel on "building
    // tunnels" forever, however healthy the router was. Launched on the service scope, never
    // awaited: I2P readiness is not a precondition of the guard, and a failed i2pd start does not
    // fail it either.
    startLocalGuardI2pdReadinessProbe(runtimeSettings)
    return session.copy(foxCoreConfig = FoxCoreConfigTranslator().translate(session))
}

private fun FoxholeVpnService.startLocalGuardI2pdReadinessProbe(settings: Settings) {
    if (!settings.i2pRuntimeActive()) {
        return
    }
    scope.launch(Dispatchers.IO) {
        container.i2pdManager.awaitReady(I2PD_READY_TIMEOUT_MS)
    }
}

/**
 * With no profile runtime, the transparent firewall guard carries the `.i2p` diversion so the I2P
 * button works from idle. The narrow DNS-only guard routes no TCP, so it only applies under full
 * capture. A failed i2pd start never fails the guard.
 */
private suspend fun FoxholeVpnService.startLocalGuardI2pProxyPortOrNull(
    settings: Settings,
    mode: LocalGuardMode,
    dnsGuardFullCapture: Boolean,
): Int? {
    val i2pCapableTun = mode != LocalGuardMode.DNS || dnsGuardFullCapture
    if (!settings.i2pRuntimeActive() || !i2pCapableTun) {
        // Mirrors ProfileSessionFactory.startI2pProxyPortOrNull: guard is rebuilt without the I2P
        // leg, but that alone does not stop the i2pd process. With the firewall on, disabling I2P
        // is a hot guard reload with no full teardown, so libi2pd.so stayed alive and the phase
        // stuck at BUILDING_TUNNELS forever. stop() is a safe no-op when nothing runs.
        container.i2pdManager.stop()
        return null
    }
    return runCatching { container.i2pdManager.ensureStarted(settings.i2p).socksPort }
        .onFailure { error ->
            container.diagnosticsLogger.record(
                "i2pd",
                "local guard i2pd start skipped: ${error.message ?: error.javaClass.simpleName}",
            )
        }.getOrNull()
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
