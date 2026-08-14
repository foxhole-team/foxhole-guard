package com.foxhole.guard.ui

import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.runtime.RemoteDownloadProgress
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Component-update state owned in one place: DNS filter, Tor bridge and GeoIP database refresh
 * flows plus their in-flight jobs. HomeViewModel exposes same-named aliases so the Support-file
 * call sites read unchanged; the fields narrow behind methods once the source-contract tests
 * stop pinning raw writes.
 */
internal class HomeComponentUpdatesState {
    val dnsFilterRefreshInProgressMutable = MutableStateFlow(false)
    val dnsFilterUpdateAvailableMutable = MutableStateFlow(false)
    val dnsFilterUpdatePhaseMutable = MutableStateFlow(FoxholeUpdatePhase.IDLE)
    val torBridgeRefreshInProgressMutable = MutableStateFlow(false)
    val torBridgeUpdatePhaseMutable = MutableStateFlow(FoxholeUpdatePhase.IDLE)
    val torBridgeDownloadProgressMutable = MutableStateFlow<RemoteDownloadProgress?>(null)
    val threatIntelUpdatePhaseMutable = MutableStateFlow(FoxholeUpdatePhase.IDLE)
    val threatIntelDownloadProgressMutable = MutableStateFlow<RemoteDownloadProgress?>(null)
    val geoIpDatabaseUiStateMutable = MutableStateFlow(GeoIpDatabaseUiState())
    val componentGeoIpUpdateAvailableMutable = MutableStateFlow(false)
    val catalogPresetPreviewsMutable =
        MutableStateFlow<Map<Long, List<RoutingRepository.RoutingCatalogPresetPreview>>>(
            emptyMap(),
        )
    var dnsFilterEnablePreflightJob: Job? = null
    var dnsFilterManualRefreshJob: Job? = null
    var geoIpDatabaseUpdateJob: Job? = null
    var torBridgeManualRefreshJob: Job? = null
    var threatIntelManualRefreshJob: Job? = null
    var appUpdateJob: Job? = null
}
