package com.foxhole.guard.runtime

import com.foxhole.core.model.DnsRuntimeStats
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.core.model.TunnelAppTrafficStats
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeUpdatePolicy
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.traffic.RuntimeAuditEvent
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import com.foxhole.guard.traffic.RuntimeNetworkActivityContext
import com.foxhole.guard.traffic.toTrafficMapConnectionSamples
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * App-traffic telemetry sampling for [FoxholeVpnService]. Extracted from the service body (which is
 * being split by responsibility on the road to beta) as extension functions so the periodic
 * per-app usage sampling lives next to its own start/stop lifecycle rather than inside the already
 * large service class.
 */
internal fun FoxholeVpnService.startAppTrafficStatsUpdates() {
    stopAppTrafficStatsUpdates()
    val settings = container.settingsRepository.settings.value
    if (!appTrafficStatsRuntimeEnabled(settings)) {
        return
    }
    // fireImmediately mirrors the old "record then delay" loop (first sample at t=0); the runtime
    // toggle is re-checked each fire and unregisters the task, matching the old loop's break.
    // A throwing recordSnapshot is caught and logged by the ticker's onError.
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

/**
 * Persisted I2P accounting — the statistics screen's I2P section outlives the session, so someone
 * has to move the two running counters into the store.
 *
 * The pass reads i2pd's cumulative received, sent, and transit totals rather than watching bytes go
 * by. That includes bootstrap and tunnel maintenance, so a connected router can no longer remain at
 * zero merely because no eepsite flow has crossed FoxCore's application lane yet.
 */
internal fun FoxholeVpnService.startI2pTrafficStatsUpdates() {
    stopI2pTrafficStatsUpdates()
    // No cursor reset here: the teardown below already recorded the previous session's tail and
    // reset in one ordered step. Doing it again from a second coroutine would race that one.
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_I2P_TRAFFIC,
        // Prime the cumulative cursors at session start. With the first pass delayed by 30 s,
        // `sample()` treated everything already moved as a pre-consent gap and discarded it; a
        // short I2P session therefore stayed at zero forever, while a long one appeared frozen for
        // its first minute. The immediate pass sees the freshly reset session counter and records
        // no bytes, then the first timed pass can persist the whole first interval.
        fireImmediately = true,
        intervalMs = { FoxholeVpnService.I2P_TRAFFIC_SAMPLE_INTERVAL_MS },
        runOn = Dispatchers.IO,
    ) {
        // Keep the cumulative cursor warm for the whole statistics-consent window, including while
        // I2P is paused. The router counter stays stable while i2pd is down. If
        // we reset the cursor on every paused tick, the first sample after a dashboard I2P toggle
        // merely primed at an already non-zero value and discarded that entire first interval.
        if (i2pTrafficStatsRuntimeEnabled(container.settingsRepository.settings.value)) {
            container.i2pTrafficRepository.sample()
        } else {
            // Collection consent is off: drop the cursor so opting back in cannot bank the gap.
            container.i2pTrafficRepository.resetSampleCursors()
        }
    }
}

internal fun FoxholeVpnService.stopI2pTrafficStatsUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_I2P_TRAFFIC)
}

/** Persisted I2P counters follow statistics consent; router engagement only changes the counter. */
internal fun i2pTrafficStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled

/**
 * Best-effort tail sample. The cursor intentionally survives VPN/local-guard handovers because i2pd
 * can survive them too; a real router restart is handled by the cumulative-counter restart rule.
 */
private fun FoxholeVpnService.recordFinalI2pTrafficSample() {
    // Sample even if the router was just paused: its console may still expose the final cumulative
    // reading while teardown is progressing.
    if (!i2pTrafficStatsRuntimeEnabled(container.settingsRepository.settings.value)) {
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
        // The geoip tables load lazily on the first lookup — a multi-second parse behind a lock.
        // dnsServerCountryProvider below is invoked from INSIDE the traffic-map state flow, where a
        // blocking call stalls the transform and freezes every later map emission (a connected
        // tunnel then kept showing standby because the availability update queued behind the
        // parse). Warm the tables off the flow and keep the provider itself non-blocking.
        scope.launch(Dispatchers.Default) { runtimeCountryResolver.warmUp() }
        trafficMapCountryTrackingJob =
            container.trafficMapRepository.startDestinationCountryTrackingFromSamples(
                scope = scope,
                connectionSamples =
                runtimeConnectionSnapshots.map { snapshot ->
                    snapshot.toTrafficMapConnectionSamples(
                        maxConnections = FoxholeVpnService.MAX_TRAFFIC_MAP_RUNTIME_CONNECTIONS,
                        countryCodeForDestination = runtimeCountryResolver::countryCodeForDestination,
                        ownPackageName = packageName,
                        dnsServerHost = { container.settingsRepository.settings.value.dns.server },
                        // Firewall/journal local guard has no tunnel: `direct` is the real device
                        // egress there, so its connections must populate the map instead of being
                        // dropped by the split-tunnel direct filter.
                        includeDirectOutbound = { activeLocalGuardMode != null },
                    )
                },
                // Session tunnel totals so the map's VPN node always matches the traffic widget.
                sessionTrafficBytesProvider = {
                    FoxholeVpnRuntimeBridge.traffic.value.let { traffic ->
                        traffic.rxTotalBytes + traffic.txTotalBytes
                    }
                },
                // The DNS node shows from the first second of the session: country of the
                // configured resolver until real DNS egress samples take over. Runs inside the map
                // state flow, so it never waits on the geoip parse — until the warm-up above lands
                // the node simply has no country yet, and the next map tick fills it in.
                dnsServerCountryProvider = {
                    container.settingsRepository.settings.value.dns.server
                        .takeIf(String::isNotBlank)
                        ?.let(runtimeCountryResolver::countryCodeForDestinationIfLoaded)
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

/**
 * The journal row for one core audit event, or null for the two kinds journalled as grouped
 * counts above ([RuntimeAuditEvent.Blocked] and [RuntimeAuditEvent.DnsBlocked]) — those arrive by
 * the hundred and one row per packet would drown the journal.
 */
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
        // Never grouped and never filtered on count: cutting an app off the network is a single
        // auditable request, and a request that cut nothing is exactly the case the journal has to
        // be able to show.
        is RuntimeAuditEvent.FlowsRevoked ->
            GuardEvent(
                GuardEventType.CORE_FLOWS_REVOKED,
                // Attributed when the scope IS a package, so the row lands under the app the user
                // blocked rather than only in the free-text detail.
                packageName = event.scope.takeIf { event.target == REVOKE_TARGET_PACKAGE },
                detail = "target=${event.target} scope=${event.scope ?: "none"} count=${event.count}",
            )
    }

/** The revocation kind whose scope is a package name (see `RevokeTarget.Package`). */
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

/**
 * DNS-replacement guard has no traffic widget (its TUN carries DNS only, and the UI deliberately
 * publishes an empty TrafficSnapshot), so it never ran the traffic job - and the traffic job is
 * what drains DnsRuntimeStats into persisted windows. Blocked-query counters therefore piled up
 * unread and the dashboard showed no DNS blocks at all in this mode. This job closes that hole:
 * it aggregates windows (which drains the counters) WITHOUT publishing traffic to the UI.
 */
internal fun FoxholeVpnService.startDnsGuardWindowUpdates() {
    stopDnsGuardWindowUpdates()
    trafficSampler.start()
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_DNS_GUARD_WINDOW,
        fireImmediately = false,
        intervalMs = { RuntimeUpdatePolicy.trafficUpdateIntervalMs(highFrequencyUiActive = false) },
    ) {
        recordAnomalyTrafficWindow(trafficSampler.sample())
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
