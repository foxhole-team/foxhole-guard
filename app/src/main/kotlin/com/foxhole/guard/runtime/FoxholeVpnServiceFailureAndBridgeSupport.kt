package com.foxhole.guard.runtime

import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.DnsRuntimeStats
import com.foxhole.core.model.NotificationSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.isActiveRuntimeFor
import com.foxhole.core.runtime.isActiveRuntimeForAnotherMode
import com.foxhole.core.runtime.shouldPublishAppOwnedIpInfoForSnapshot
import com.foxhole.core.runtime.stoppedRuntimeSnapshot
import com.foxhole.core.sentinel.detection.TrafficAggregationContext
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.sentinelTrafficWindowCollectionEnabled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// The service surface for failures, anomaly windows and the network/notification bridge
// delegates: thin entry points the framework callbacks and sibling supports call into.
// Split from FoxholeVpnService.kt (same extension-file pattern as the other supports).
internal fun FoxholeVpnService.fail(
    message: String,
    commandStartId: Int? = null,
    reasonCode: AutoConnectReasonCode? = null,
) {
    container.diagnosticsLogger.record("connection", "runtime failure: $message")
    launchPriorityCommand(RuntimeCommandPriority.STOP, "fail_disconnect") {
        disconnect(message, commandStartId, reasonCode)
    }
}

internal suspend fun FoxholeVpnService.failClosedTeardown(
    commandStartId: Int,
    action: String?,
) {
    beginRuntimeTransition("fail_closed")
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    val session = activeSession
    val hadActiveRuntime =
        session != null ||
            activeLocalGuardMode != null ||
            snapshot.isActiveRuntimeFor(TrafficMode.TUNNEL)
    if (!hadActiveRuntime && snapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL)) {
        container.diagnosticsLogger.record(
            "connection",
            "runtime command ignored by inactive tunnel service while another mode is active",
        )
        removeForegroundNotification()
        stopService(commandStartId)
        return
    }
    val finalTraffic =
        if (session != null) {
            trafficSampler.sample()
        } else {
            TrafficSnapshot()
        }
    if (session != null) {
        persistProfileTraffic(session, finalTraffic)
    }
    container.diagnosticsLogger.recordStructured(
        "connection",
        "runtime command fail-closed teardown",
        action?.let { "action=$it" } ?: "action=null",
    )
    val previousVpnNetworkHandle = activeVpnNetworkHandle ?: currentVpnNetworkOrNull()?.networkHandle
    closeRuntimeSession(reason = "runtime_command_fail_closed")
    awaitStoppedVpnNetworkTeardown(
        previousVpnNetworkHandle = previousVpnNetworkHandle,
        reason = "runtime_command_fail_closed",
    )
    val failClosedMessage =
        if (action == FoxholeVpnService.ACTION_NATIVE_RUNTIME_STOP && hadActiveRuntime) {
            getString(R.string.error_runtime_stopped)
        } else {
            null
        }
    bridgeWriter.clearTransientState()
    bridgeWriter.update(
        stoppedRuntimeSnapshot(
            session = session,
            state = if (hadActiveRuntime) ConnectionState.ERROR else ConnectionState.IDLE,
            trafficMode = container.settingsRepository.current().traffic.mode,
            message = failClosedMessage,
        ),
    )
    removeForegroundNotification()
    stopService(commandStartId)
}

/**
 * Fail-closed publication after an unexpected stop. Returns false when another mode owns the
 * published snapshot (cross-mode handoff): the bridge rejects the stale writes centrally, and the
 * controller's applied-runtime record is left to the live mode — only the local session fields
 * reset in that case.
 */
internal fun FoxholeVpnService.publishUnexpectedRuntimeStopSnapshot(): Boolean {
    activeSession = null
    activeLocalGuardMode = null
    activeVpnNetworkHandle = null
    runtimeNetworkActivityLoggingSuspended = false
    if (!FoxholeVpnRuntimeBridge.snapshotOwnedByAnotherMode(TrafficMode.TUNNEL)) {
        container.connectionController.clearAppliedRuntime()
    }
    bridgeWriter.updateTraffic(trafficSampler.reset())
    bridgeWriter.clearTransientState()
    return bridgeWriter.update(
        ConnectionSnapshot(
            state = ConnectionState.ERROR,
            trafficMode = container.settingsRepository.settings.value.traffic.mode,
            message = getString(R.string.error_runtime_stopped),
        ),
    )
}

internal fun FoxholeVpnService.recordAnomalyTrafficWindow(sample: TrafficSnapshot) {
    val connection = FoxholeVpnRuntimeBridge.snapshot.value
    val settings = container.settingsRepository.settings.value
    if (!sentinelTrafficWindowCollectionEnabled(settings)) {
        // Keep both delta baselines bounded while collection is off. Without this reset, enabling
        // Sentinel later would turn the entire disabled interval into one artificial spike.
        anomalyTrafficAggregator.reset()
        DnsRuntimeStats.reset()
        return
    }
    val dnsDelta = DnsRuntimeStats.snapshot()
    val aggregationContext =
        TrafficAggregationContext(
            connection = connection,
            settings = settings,
            networkType = anomalyNetworkTypeProvider.current(),
            destinationCountries = container.trafficMapRepository.currentDestinationCountryBytes(),
            blockedDns = dnsDelta.blocked,
            allowedDns = dnsDelta.allowed,
            blockedDnsDomains = dnsDelta.blockedDomains,
            blockedDnsByCategory =
            dnsDelta.blockedByCategory.entries.associate { (category, count) -> category to count.toLong() },
            blockedDnsApps =
            dnsDelta.blockedByApp.entries.associate { (packageName, count) -> packageName to count.toLong() },
        )
    val window =
        anomalyTrafficAggregator.aggregate(
            snapshot = sample,
            context = aggregationContext,
        ) ?: return
    DnsRuntimeStats.drain()
    scope.launch(Dispatchers.IO) {
        val appWindows =
            if (appTrafficStatsRuntimeEnabled(settings)) {
                runCatching {
                    appTrafficStatsRecorder.sampleWindows(
                        maxCacheAgeMs = FoxholeVpnService.APP_TRAFFIC_SAMPLE_CACHE_MAX_AGE_MS,
                    )
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        runCatching {
            container.anomalyRepository.recordTrafficWindow(window, appWindows)
        }.onFailure {
            container.diagnosticsLogger.record("anomaly", "traffic window analysis failed")
        }
    }
}

internal fun FoxholeVpnService.refreshDefaultNetworkAvailability() = refreshDefaultNetworkAvailabilityInternal()

internal fun FoxholeVpnService.onDefaultNetworkCapabilitiesChanged(
    capabilities: NetworkCapabilities?,
    reason: String,
) = onDefaultNetworkCapabilitiesChangedInternal(capabilities, reason)

internal fun FoxholeVpnService.markNotificationConnectivityOffline() = markNotificationConnectivityOfflineInternal()

internal fun FoxholeVpnService.updateNotificationConnectivityHealth(
    state: ConnectivityHealthState,
    resetFailures: Boolean = false,
    force: Boolean = false,
) = updateNotificationConnectivityHealthInternal(state, resetFailures, force)

internal fun FoxholeVpnService.isUpstreamNetwork(network: Network): Boolean = isUpstreamNetworkInternal(network)

internal fun FoxholeVpnService.recordDefaultNetworkCapabilities(
    reason: String,
    capabilities: NetworkCapabilities?,
) = recordDefaultNetworkCapabilitiesInternal(reason, capabilities)

internal fun FoxholeVpnService.recordNetworkEvent(
    message: String,
    capabilities: NetworkCapabilities?,
) = recordNetworkEventInternal(message, capabilities)

internal fun FoxholeVpnService.describeNetworkCapabilities(capabilities: NetworkCapabilities?): String =
    describeNetworkCapabilitiesInternal(capabilities)

internal fun FoxholeVpnService.currentVpnNetwork(excludedHandle: Long? = null): Network =
    currentVpnNetworkInternal(excludedHandle)

internal fun FoxholeVpnService.currentVpnNetworkOrNull(excludedHandle: Long? = null): Network? =
    currentVpnNetworkOrNullInternal(excludedHandle)

internal fun FoxholeVpnService.currentUpstreamNetworkOrNull(excludedHandle: Long? = null): Network? =
    currentUpstreamNetworkOrNullInternal(excludedHandle)

internal fun FoxholeVpnService.shouldPublishAppOwnedIpInfo(): Boolean {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    return shouldPublishAppOwnedIpInfoForSnapshot(
        snapshot = snapshot,
        analysisStatus = getString(R.string.notification_status_analysis),
    )
}

internal fun FoxholeVpnService.shouldAcceptNewDeviceIpInfoFromAppOwnedRefresh(): Boolean = shouldPublishAppOwnedIpInfo()

internal fun FoxholeVpnService.isVpnNetworkValidated(network: Network): Boolean = isVpnNetworkValidatedInternal(network)

internal suspend fun FoxholeVpnService.awaitVpnNetworkOrNull(
    timeoutMs: Long,
    excludedHandle: Long? = null,
): Network? = awaitVpnNetworkOrNullInternal(timeoutMs, excludedHandle)

internal fun FoxholeVpnService.onConnectionStarted(
    session: VpnSession,
    trafficMode: TrafficMode,
) = onConnectionStartedInternal(session, trafficMode)

internal fun FoxholeVpnService.onTunnelValidated(
    session: VpnSession,
    vpnNetwork: Network,
) = onTunnelValidatedInternal(session, vpnNetwork)

internal fun FoxholeVpnService.currentNotificationSnapshot(): NotificationSnapshot = currentNotificationSnapshotInternal()

internal fun FoxholeVpnService.notificationCollapsedText(snapshot: NotificationSnapshot): String =
    notificationCollapsedTextInternal(snapshot)

internal fun FoxholeVpnService.notificationExpandedText(snapshot: NotificationSnapshot): String? =
    notificationExpandedTextInternal(snapshot)

internal fun FoxholeVpnService.notificationHealthText(snapshot: NotificationSnapshot): String? =
    notificationHealthTextInternal(snapshot)

internal suspend fun FoxholeVpnService.persistProfileTraffic(
    session: VpnSession,
    traffic: TrafficSnapshot,
) = persistProfileTrafficInternal(session, traffic)

internal fun FoxholeVpnService.notificationStateLabel(snapshot: NotificationSnapshot): String = notificationStateLabelInternal(
    snapshot
)
