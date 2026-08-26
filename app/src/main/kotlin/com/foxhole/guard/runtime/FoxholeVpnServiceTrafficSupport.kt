package com.foxhole.guard.runtime

import android.os.SystemClock
import com.foxhole.core.model.DnsRuntimeStats
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.core.model.TunnelAppTrafficStats
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeUpdatePolicy
import com.foxhole.guard.core.sentinel.anomaly.sentinelTrafficWindowCollectionEnabled
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.traffic.RuntimeAuditEvent
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import com.foxhole.guard.traffic.RuntimeNetworkActivityContext
import com.foxhole.guard.traffic.TrafficMapRuntimeSnapshotSamples
import com.foxhole.guard.traffic.toTrafficMapConnectionSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

internal fun FoxholeVpnService.startAppTrafficStatsUpdates() {
    stopAppTrafficStatsUpdates()
    val settings = container.settingsRepository.settings.value
    if (!appTrafficStatsRuntimeEnabled(settings)) {
        return
    }

    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_APP_TRAFFIC,
        fireImmediately = true,
        intervalMs = { FoxholeVpnService.APP_TRAFFIC_SAMPLE_INTERVAL_MS },
    ) {
        if (!appTrafficStatsRuntimeEnabled(container.settingsRepository.settings.value)) {
            sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_APP_TRAFFIC)
            return@register
        }
        appTrafficStatsRecorder.recordSnapshot()
    }
}

internal fun FoxholeVpnService.stopAppTrafficStatsUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_APP_TRAFFIC)
}

internal fun FoxholeVpnService.startI2pTrafficStatsUpdates() {
    stopI2pTrafficStatsUpdates()
    if (!i2pTrafficSamplingPossible(container.settingsRepository.settings.value)) {
        scope.launch(Dispatchers.IO) { container.i2pTrafficRepository.resetSampleCursors() }
        return
    }

    val startedAtMs = SystemClock.elapsedRealtime()
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_I2P_TRAFFIC,

        fireImmediately = true,
        intervalMs = {
            i2pTrafficSampleIntervalMs(SystemClock.elapsedRealtime() - startedAtMs)
        },
        runOn = Dispatchers.IO,
    ) {
        val settings = container.settingsRepository.settings.value
        if (!i2pTrafficSamplingPossible(settings)) {
            container.i2pTrafficRepository.resetSampleCursors()
            stopI2pTrafficStatsUpdates()
            return@register
        }

        container.i2pTrafficRepository.sample()
    }
}

internal fun FoxholeVpnService.stopI2pTrafficStatsUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_I2P_TRAFFIC)
}

internal fun i2pTrafficSamplingPossible(settings: Settings): Boolean =
    settings.i2p.enabled && i2pTrafficStatsRuntimeEnabled(settings)

internal fun i2pTrafficSampleIntervalMs(sessionElapsedMs: Long): Long =
    if (sessionElapsedMs < FoxholeVpnService.I2P_TRAFFIC_SAMPLE_WARMUP_MS) {
        FoxholeVpnService.I2P_TRAFFIC_SAMPLE_INTERVAL_MS
    } else {
        FoxholeVpnService.I2P_TRAFFIC_SAMPLE_STEADY_INTERVAL_MS
    }

internal fun i2pTrafficStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled

private fun FoxholeVpnService.recordFinalI2pTrafficSample() {
    if (!i2pTrafficSamplingPossible(container.settingsRepository.settings.value)) {
        return
    }
    scope.launch(Dispatchers.IO) {
        container.i2pTrafficRepository.sample()
    }
}

internal fun FoxholeVpnService.startTrafficUpdates() {
    stopTrafficUpdates()
    DnsRuntimeStats.reset()
    TunnelAppTrafficStats.reset()
    TorTrafficStats.reset()
    I2pTrafficStats.reset()
    val settings = container.settingsRepository.settings.value
    val runtimeConnectionSnapshots =
        if (runtimeConnectionObserverNeeded(settings)) {
            runtimeConnectionSnapshots(settings)
        } else {
            null
        }
    if (destinationCountryTrackingRuntimeEnabled(settings) && runtimeConnectionSnapshots != null) {
        scope.launch(Dispatchers.Default) { runtimeCountryResolver.warmUp() }
        trafficMapCountryTrackingJob =
            container.trafficMapRepository.startDestinationCountryTrackingFromSamples(
                scope = scope,
                connectionSamples = runtimeConnectionSnapshots.map { snapshot ->
                    TrafficMapRuntimeSnapshotSamples(
                        generation = snapshot.generation,
                        samples = snapshot.toTrafficMapConnectionSamples(
                            maxConnections = FoxholeVpnService.MAX_TRAFFIC_MAP_RUNTIME_CONNECTIONS,
                            countryCodeForDestination = runtimeCountryResolver::countryCodeForDestination,
                            ownPackageName = packageName,

                            includeDirectOutbound = { activeLocalGuardMode != null },
                        ),
                    )
                },

                sessionTrafficBytesProvider = {
                    FoxholeVpnRuntimeBridge.traffic.value.let { traffic ->
                        traffic.rxTotalBytes + traffic.txTotalBytes
                    }
                },
            )
    }
    startRuntimeConnectionStatsUpdates(settings, runtimeConnectionSnapshots)
    startI2pTrafficStatsUpdates()
    startLanProxyUpdates()
    bridgeWriter.updateTraffic(trafficSampler.sample(resetRateBaseline = true))
    immediateTrafficSampleJob =
        scope.launch(Dispatchers.Default) {
            FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.collect {
                if (activeSession != null || activeLocalGuardMode != null) {
                    bridgeWriter.updateTraffic(trafficSampler.sample(resetRateBaseline = true))
                }
            }
        }
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_TRAFFIC,
        fireImmediately = false,
        intervalMs = {
            RuntimeUpdatePolicy.trafficUpdateIntervalMs(
                highFrequencyUiActive = FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value,
            )
        },
    ) {
        val sample = trafficSampler.sample()
        bridgeWriter.updateTraffic(sample)
        recordAnomalyTrafficWindow(sample)
    }
}

internal fun FoxholeVpnService.startRuntimeConnectionStatsUpdates(
    settings: Settings,
    runtimeConnectionSnapshots: SharedFlow<RuntimeConnectionSnapshot>? = null,
) {
    dnsRuntimeStatsJob?.cancel()
    dnsRuntimeStatsJob = null
    if (!runtimeConnectionStatsEnabled(settings)) {
        return
    }
    val snapshots = runtimeConnectionSnapshots ?: runtimeConnectionSnapshots(settings)
    dnsRuntimeStatsJob =
        dnsRuntimeStatsTracker.start(
            scope = scope,
            runtimeConnectionSnapshots = snapshots,
            enabled = {
                runtimeConnectionStatsEnabled(container.settingsRepository.settings.value)
            },
            networkActivityEnabled = {
                networkActivityStatsRuntimeEnabled(container.settingsRepository.settings.value)
            },
            tunnelAppTrafficEnabled = {
                appTrafficStatsRuntimeEnabled(container.settingsRepository.settings.value)
            },
            torOnlyRuntimeActive = {
                activeSession?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID
            },
            networkActivityContext = {
                RuntimeNetworkActivityContext(
                    profileId = activeSession?.profileId ?: LOCAL_GUARD_PROFILE_ID.takeIf { activeLocalGuardMode != null },
                    sessionId = activeSession?.correlationId,
                )
            },
            countryCodeForDestination = runtimeCountryResolver::countryCodeForDestination,
            onNetworkActivityEvents = { events ->
                scope.launch(Dispatchers.IO) {
                    container.anomalyRepository.recordNetworkActivityEvents(events)
                }
            },
            onRuntimeAuditEvents = { events, droppedAudit, droppedTraffic ->
                scope.launch(Dispatchers.IO) {
                    journalRuntimeAuditEvents(events, droppedAudit, droppedTraffic)
                }
            },
        )
}

private fun FoxholeVpnService.journalRuntimeAuditEvents(
    events: List<RuntimeAuditEvent>,
    droppedAudit: Long,
    droppedTraffic: Long,
) {
    events.filterIsInstance<RuntimeAuditEvent.Blocked>()
        .groupBy { event -> Triple(event.reason, event.transport, event.packageName) }
        .forEach { (key, blocked) ->
            container.securityComponents.journalEvent(
                GuardEvent(
                    type = GuardEventType.CORE_FLOW_BLOCKED,
                    packageName = key.third,
                    uid = blocked.firstOrNull()?.uid,
                    detail = "reason=${key.first} transport=${key.second} count=${blocked.size}",
                ),
            )
        }
    events.filterIsInstance<RuntimeAuditEvent.DnsBlocked>()
        .groupBy { event -> Triple(event.reason, event.category?.name, event.packageName) }
        .forEach { (key, blocked) ->
            container.securityComponents.journalEvent(
                GuardEvent(
                    type = GuardEventType.CORE_DNS_BLOCKED,
                    packageName = key.third,
                    detail = "reason=${key.first ?: "unknown"} category=${key.second ?: "none"} count=${blocked.size}",
                ),
            )
        }
    events.mapNotNull(::ungroupedRuntimeAuditGuardEvent)
        .forEach(container.securityComponents::journalEvent)
    if (droppedAudit > 0L || droppedTraffic > 0L) {
        container.securityComponents.journalEvent(
            GuardEvent(
                type = GuardEventType.CORE_EVENT_GAP,
                detail = "audit_dropped=$droppedAudit traffic_dropped=$droppedTraffic",
            ),
        )
    }
}

internal fun ungroupedRuntimeAuditGuardEvent(event: RuntimeAuditEvent): GuardEvent? =
    when (event) {
        is RuntimeAuditEvent.Blocked,
        is RuntimeAuditEvent.DnsBlocked -> null
        is RuntimeAuditEvent.ConfigApplied ->
            GuardEvent(
                GuardEventType.CORE_CONFIG_APPLIED,
                detail = "previous=${event.previousRevision} revision=${event.revision}",
            )
        is RuntimeAuditEvent.ConfirmationRequired ->
            GuardEvent(
                GuardEventType.CORE_CONFIRMATION_REQUIRED,
                detail = "interruption=${event.interruption} token=${event.token}",
            )
        is RuntimeAuditEvent.ConfirmationExpired ->
            GuardEvent(
                GuardEventType.CORE_CONFIRMATION_EXPIRED,
                detail = "interruption=${event.interruption} token=${event.token}",
            )
        is RuntimeAuditEvent.OutboundUnavailable ->
            GuardEvent(
                GuardEventType.CORE_OUTBOUND_UNAVAILABLE,
                detail = "id=${event.id} kind=${event.kind} reason=${event.reason} attempts=${event.attempts}",
            )
        is RuntimeAuditEvent.OutboundRestored ->
            GuardEvent(
                GuardEventType.CORE_OUTBOUND_RESTORED,
                detail = "id=${event.id} kind=${event.kind} attempts=${event.attempts}",
            )

        is RuntimeAuditEvent.FlowsRevoked ->
            GuardEvent(
                GuardEventType.CORE_FLOWS_REVOKED,

                packageName = event.scope.takeIf { event.target == REVOKE_TARGET_PACKAGE },
                detail = "target=${event.target} scope=${event.scope ?: "none"} count=${event.count}",
            )
    }

private const val REVOKE_TARGET_PACKAGE = "package"

private fun FoxholeVpnService.runtimeConnectionStatsEnabled(settings: Settings): Boolean =
    dnsRuntimeStatsRuntimeEnabled(settings) ||
        networkActivityStatsRuntimeEnabled(settings) ||
        appTrafficStatsRuntimeEnabled(settings) ||
        laneTrafficStatsRuntimeEnabled(settings)

private fun FoxholeVpnService.runtimeConnectionSnapshots(settings: Settings): SharedFlow<RuntimeConnectionSnapshot> =
    runtimeConnectionSnapshotFlow
        ?: runtimeConnectionObserver
            .connectionSnapshots(
                runtimeAvailable = flowOf(runtimeConnectionObserverNeeded(settings)),
                includeProcessInfo = {
                    val current = container.settingsRepository.settings.value
                    shouldIncludeRuntimeProcessInfo(
                        networkActivityEnabled = networkActivityStatsRuntimeEnabled(current),
                        appTrafficEnabled = appTrafficStatsRuntimeEnabled(current),
                    )
                },
            )
            .shareIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = FoxholeVpnService.RUNTIME_CONNECTION_OBSERVER_STOP_MS),
                replay = 1,
            )
            .also { sharedFlow -> runtimeConnectionSnapshotFlow = sharedFlow }

internal fun shouldIncludeRuntimeProcessInfo(
    networkActivityEnabled: Boolean,
    appTrafficEnabled: Boolean,
): Boolean = networkActivityEnabled || appTrafficEnabled

internal fun FoxholeVpnService.startDnsGuardWindowUpdates() {
    stopDnsGuardWindowUpdates()
    trafficSampler.start()
    if (!sentinelTrafficWindowCollectionEnabled(container.settingsRepository.settings.value)) {
        return
    }
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_DNS_GUARD_WINDOW,
        fireImmediately = false,
        intervalMs = { RuntimeUpdatePolicy.trafficUpdateIntervalMs(highFrequencyUiActive = false) },
    ) {
        recordAnomalyTrafficWindow(trafficSampler.sample())
        if (!sentinelTrafficWindowCollectionEnabled(container.settingsRepository.settings.value)) {
            stopDnsGuardWindowUpdates()
        }
    }
}

internal fun FoxholeVpnService.stopDnsGuardWindowUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_DNS_GUARD_WINDOW)
    anomalyTrafficAggregator.reset()
}

internal fun FoxholeVpnService.stopTrafficUpdates() {
    recordFinalI2pTrafficSample()
    stopI2pTrafficStatsUpdates()
    immediateTrafficSampleJob?.cancel()
    immediateTrafficSampleJob = null
    stopDnsGuardWindowUpdates()
    stopLanProxyUpdates()
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_TRAFFIC)
    trafficMapCountryTrackingJob?.cancel()
    trafficMapCountryTrackingJob = null
    dnsRuntimeStatsJob?.cancel()
    dnsRuntimeStatsJob = null
    runtimeConnectionSnapshotFlow = null
    container.trafficMapRepository.clearDestinationCountryBytes()
    anomalyTrafficAggregator.reset()
    DnsRuntimeStats.reset()
    TorTrafficStats.reset()
    I2pTrafficStats.reset()
}
