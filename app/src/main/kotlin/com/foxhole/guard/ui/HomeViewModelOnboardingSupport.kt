package com.foxhole.guard.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.core.settings.completeOnboarding
import com.foxhole.guard.core.settings.markDnsFiltersUpdated
import com.foxhole.guard.core.settings.updateAnomalyEnabled
import com.foxhole.guard.core.settings.updateComponentAutoUpdate
import com.foxhole.guard.core.settings.updateDnsSettings
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pRelayTransitTraffic
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesUseFoxholeSource
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.core.settings.updateTrafficMapEnabled
import com.foxhole.guard.runtime.DnsFilterUpdateStatus
import com.foxhole.guard.runtime.GeoIpUpdateStatus
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.runtime.RemoteUpdatePhase
import com.foxhole.guard.runtime.ThreatIntelUpdateStatus
import com.foxhole.guard.runtime.TorBridgeUpdateStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class OnboardingDownload {
    GEOIP,
    TLS_FINGERPRINTS,
    DNS_FILTER,
    TOR_BRIDGES,
    THREAT_INTEL,
}

@Immutable
data class OnboardingDownloadState(
    val item: OnboardingDownload,
    val phase: RemoteUpdatePhase?,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val done: Boolean = false,
    val failed: Boolean = false,
) {
    val downloadFraction: Float
        get() =
            if (totalBytes <= 0L) {
                0f
            } else {
                (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            }
}

@Immutable
data class OnboardingProgress(
    val running: Boolean = false,
    val finished: Boolean = false,
    val items: List<OnboardingDownloadState> = emptyList(),
) {
    val fraction: Float
        get() =
            if (items.isEmpty()) {
                0f
            } else {
                items.sumOf { state ->
                    when {
                        state.done || state.failed -> 1.0
                        state.phase == RemoteUpdatePhase.VERIFYING -> 1.0
                        state.phase == RemoteUpdatePhase.DOWNLOADING -> state.downloadFraction.toDouble()
                        else -> 0.0
                    }
                }.div(items.size).toFloat().coerceIn(0f, 1f)
            }
}

internal val HomeViewModel.onboardingProgress: StateFlow<OnboardingProgress>
    get() = onboardingProgressMutable.asStateFlow()

internal fun HomeViewModel.onOnboardingFinished(
    torEnabled: Boolean,
    torBridges: Boolean,
    bridgesFoxholeMirror: Boolean,
    sentinelEnabled: Boolean,
    i2pEnabled: Boolean,
    i2pRelay: Boolean,
    dnsFilterEnabled: Boolean,
    autoUpdate: Boolean,
) {
    viewModelScope.launch {
        val repository = container.settingsRepository
        if (torEnabled) {
            repository.updatePrivacyRoutePermitted(true)
            repository.updatePrivacyRouteBridgesEnabled(
                onboardingTorBridgesCanEnable(
                    requested = torBridges,
                    useFoxholeSource = bridgesFoxholeMirror,
                    progress = onboardingProgressMutable.value,
                ),
            )
            if (torBridges) {
                repository.updatePrivacyRouteBridgesUseFoxholeSource(bridgesFoxholeMirror)
            }
        }
        if (i2pEnabled) {
            repository.updateI2pEnabled(true)
            repository.updateI2pRelayTransitTraffic(i2pRelay)
        }
        if (onboardingDnsFilterCanEnable(dnsFilterEnabled, onboardingProgressMutable.value)) {
            repository.updateDnsSettings(repository.current().dns.copy(filteringEnabled = true))
        } else {
            repository.updateDnsSettings(repository.current().dns.copy(filteringEnabled = false))
        }
        repository.updateTrafficMapEnabled(onboardingGeoIpCanEnable(onboardingProgressMutable.value))
        repository.updateAnomalyEnabled(
            onboardingSentinelCanEnable(
                requested = sentinelEnabled,
                progress = onboardingProgressMutable.value,
            ),
        )
        repository.updateComponentAutoUpdate(autoUpdate)
        repository.completeOnboarding()
    }
}

internal fun HomeViewModel.onOnboardingSkipped() {
    viewModelScope.launch {
        container.settingsRepository.completeOnboarding()
    }
}

internal fun HomeViewModel.onOnboardingDownload(
    dnsFilter: Boolean,
    torBridges: Boolean,
    geoIp: Boolean,
    threatIntel: Boolean,
    tlsFingerprints: Boolean,
) {
    if (onboardingProgressMutable.value.running) {
        return
    }
    val planned = onboardingDownloadPlan(geoIp, tlsFingerprints, dnsFilter, torBridges, threatIntel)
    if (planned.isEmpty()) {
        onboardingProgressMutable.value = OnboardingProgress(finished = true)
        return
    }
    onboardingProgressMutable.value =
        OnboardingProgress(
            running = true,
            items = planned.map { item -> OnboardingDownloadState(item = item, phase = null) },
        )
    viewModelScope.launch {
        planned.forEach { item ->
            val failed = runCatching {
                when (item) {
                    OnboardingDownload.DNS_FILTER -> {
                        val status = container.dnsFilterUpdateRepository.refreshNow(
                            requireAutoEnabled = false,
                            dnsSettingsOverride = container.settingsRepository.current().dns.copy(
                                filteringEnabled = true,
                            ),
                            onPhase = { phase -> onboardingPhase(item, phase) },
                            onProgress = { progress -> onboardingDownloadProgress(item, progress) },
                        ).status
                        val verified =
                            dnsFilterDownloadCanEnable(status) &&
                                container.dnsFilterAssetInstaller.prepareVerifiedOrNull() != null
                        if (verified && status == DnsFilterUpdateStatus.UP_TO_DATE) {
                            container.settingsRepository.markDnsFiltersUpdated()
                        }
                        !verified
                    }

                    OnboardingDownload.TOR_BRIDGES ->
                        container.torBridgeUpdateRepository.refreshNow(
                            useFoxholeSourceOverride = true,
                            onPhase = { phase -> onboardingPhase(item, phase) },
                            onProgress = { progress -> onboardingDownloadProgress(item, progress) },
                        ).status == TorBridgeUpdateStatus.FAILED

                    OnboardingDownload.GEOIP ->
                        container.geoIpUpdateRepository.refreshNow(
                            onPhase = { phase -> onboardingPhase(item, phase) },
                            onProgress = { progress -> onboardingDownloadProgress(item, progress) },
                        ).let { result ->
                            !onboardingGeoIpDownloadVerified(
                                status = result.status,
                                hasInstalledDatabase = container.geoIpUpdateRepository.hasDownloadedDatabase(),
                            )
                        }

                    OnboardingDownload.THREAT_INTEL ->
                        container.threatIntelUpdateRepository.refreshNow(
                            onPhase = { phase -> onboardingPhase(item, phase) },
                            onProgress = { progress -> onboardingDownloadProgress(item, progress) },
                        ).status != ThreatIntelUpdateStatus.UPDATED

                    OnboardingDownload.TLS_FINGERPRINTS ->
                        tlsFingerprintDownloadFailed(
                            container.tlsFingerprintUpdateRepository.refreshNow(
                                onPhase = { phase -> onboardingPhase(item, phase) },
                                onProgress = { progress -> onboardingDownloadProgress(item, progress) },
                            ).status,
                        )
                }
            }.getOrElse { true }
            onboardingProgressMutable.value = onboardingProgressMutable.value.mark(
                item = item,
                failed = failed,
            )
        }
        onboardingProgressMutable.value =
            onboardingProgressMutable.value.copy(running = false, finished = true)
    }
}

internal fun onboardingGeoIpCanEnable(progress: OnboardingProgress): Boolean {
    val geoDownload = progress.items.firstOrNull { state -> state.item == OnboardingDownload.GEOIP }
    return geoDownload?.let { state -> state.done && !state.failed } == true
}

internal fun onboardingGeoIpDownloadVerified(
    status: GeoIpUpdateStatus,
    hasInstalledDatabase: Boolean,
): Boolean =
    hasInstalledDatabase &&
        (status == GeoIpUpdateStatus.UPDATED || status == GeoIpUpdateStatus.UP_TO_DATE)

internal fun onboardingDnsFilterCanEnable(
    requested: Boolean,
    progress: OnboardingProgress,
): Boolean {
    if (!requested) return false
    val dnsDownload = progress.items.firstOrNull { state -> state.item == OnboardingDownload.DNS_FILTER }
    return dnsDownload?.let { state -> state.done && !state.failed } == true
}

internal fun onboardingTorBridgesCanEnable(
    requested: Boolean,
    useFoxholeSource: Boolean,
    progress: OnboardingProgress,
): Boolean =
    datasetActivationAllowed(
        requested = requested,
        feature = DatasetActivationFeature.TOR_BRIDGES,
        source =
        if (useFoxholeSource) {
            DatasetActivationSource.FOXHOLE_DB
        } else {
            DatasetActivationSource.TOR_PROJECT
        },
        verifiedDataset = progress.hasVerified(OnboardingDownload.TOR_BRIDGES),
    )

internal fun onboardingSentinelCanEnable(
    requested: Boolean,
    progress: OnboardingProgress,
): Boolean =
    datasetActivationAllowed(
        requested = requested,
        feature = DatasetActivationFeature.FOXHOLE_SENTINEL,
        source = DatasetActivationSource.FOXHOLE_DB,
        verifiedDataset = progress.hasVerified(OnboardingDownload.THREAT_INTEL),
    )

private fun OnboardingProgress.hasVerified(item: OnboardingDownload): Boolean =
    items.firstOrNull { state -> state.item == item }
        ?.let { state -> state.done && !state.failed } == true

internal fun onboardingDownloadPlan(
    geoIp: Boolean,
    tlsFingerprints: Boolean,
    dnsFilter: Boolean,
    torBridges: Boolean,
    threatIntel: Boolean,
): List<OnboardingDownload> = buildList {
    if (geoIp) add(OnboardingDownload.GEOIP)
    if (tlsFingerprints) add(OnboardingDownload.TLS_FINGERPRINTS)
    if (dnsFilter) add(OnboardingDownload.DNS_FILTER)
    if (torBridges) add(OnboardingDownload.TOR_BRIDGES)
    if (threatIntel) add(OnboardingDownload.THREAT_INTEL)
}

private fun HomeViewModel.onboardingPhase(item: OnboardingDownload, phase: RemoteUpdatePhase) {
    onboardingProgressMutable.value = onboardingProgressMutable.value.copy(
        items = onboardingProgressMutable.value.items.map { state ->
            if (state.item == item) {
                state.copy(
                    phase = phase,
                    downloadedBytes = if (phase == RemoteUpdatePhase.DOWNLOADING) state.downloadedBytes else 0L,
                    totalBytes = if (phase == RemoteUpdatePhase.DOWNLOADING) state.totalBytes else 0L,
                )
            } else {
                state
            }
        },
    )
}

private fun HomeViewModel.onboardingDownloadProgress(
    item: OnboardingDownload,
    progress: RemoteDownloadProgress,
) {
    onboardingProgressMutable.value = onboardingProgressMutable.value.copy(
        items = onboardingProgressMutable.value.items.map { state ->
            if (state.item == item) {
                state.copy(
                    phase = RemoteUpdatePhase.DOWNLOADING,
                    downloadedBytes = progress.downloadedBytes,
                    totalBytes = progress.totalBytes,
                )
            } else {
                state
            }
        },
    )
}

private fun OnboardingProgress.mark(item: OnboardingDownload, failed: Boolean): OnboardingProgress =
    copy(
        items = items.map { state ->
            if (state.item == item) {
                state.copy(
                    done = !failed,
                    failed = failed,
                    phase = null,
                    downloadedBytes = 0L,
                    totalBytes = 0L,
                )
            } else {
                state
            }
        },
    )
