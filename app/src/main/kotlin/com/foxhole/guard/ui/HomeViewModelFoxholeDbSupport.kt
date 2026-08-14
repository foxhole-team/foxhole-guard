package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.Settings
import com.foxhole.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.runtime.ThreatIntelUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// FoxHole DB is one repository with four groups (dns lists, tor bridges, security lists, geo
// database); the app downloads only the enabled ones. This file is the UI's single view of that
// model: which groups are on, which need data, and the one "refresh everything enabled" action
// the updates screen and the "update required" sheets share.

internal val HomeViewModel.threatIntelUpdatePhase: StateFlow<FoxholeUpdatePhase>
    get() = componentUpdates.threatIntelUpdatePhaseMutable.asStateFlow()

internal val HomeViewModel.threatIntelDownloadProgress: StateFlow<RemoteDownloadProgress?>
    get() = componentUpdates.threatIntelDownloadProgressMutable.asStateFlow()

/** `generated_at` of the installed security lists, or null while only the bundled seed exists. */
internal fun HomeViewModel.threatIntelInstalledGeneratedAt(): String? =
    container.threatIntelUpdateRepository.installedGeneratedAt()

internal fun HomeViewModel.onThreatIntelManualRefresh() {
    if (componentUpdates.threatIntelManualRefreshJob?.isActive == true) {
        return
    }
    componentUpdates.threatIntelUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
    componentUpdates.threatIntelDownloadProgressMutable.value = null
    componentUpdates.threatIntelManualRefreshJob =
        viewModelScope.launch {
            val terminalPhase =
                try {
                    val result =
                        container.threatIntelUpdateRepository.refreshNow(
                            onPhase = { phase ->
                                componentUpdates.threatIntelUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase()
                            },
                            onProgress = { progress ->
                                componentUpdates.threatIntelDownloadProgressMutable.value = progress
                            },
                        )
                    when (result.status) {
                        ThreatIntelUpdateStatus.UPDATED -> FoxholeUpdatePhase.DONE
                        ThreatIntelUpdateStatus.SKIPPED,
                        ThreatIntelUpdateStatus.FAILED,
                        -> FoxholeUpdatePhase.FAILED
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: RuntimeException) {
                    container.diagnosticsLogger.recordFailure(
                        "sentinel",
                        "manual data refresh failed error=${error.javaClass.simpleName}",
                    )
                    FoxholeUpdatePhase.FAILED
                }
            if (terminalPhase == FoxholeUpdatePhase.FAILED) {
                componentUpdates.threatIntelUpdatePhaseMutable.value = terminalPhase
            } else {
                settleUpdatePhase(
                    { phase -> componentUpdates.threatIntelUpdatePhaseMutable.value = phase },
                    terminalPhase,
                )
            }
        }
}

internal fun HomeViewModel.onThreatIntelManualRefreshCancel() {
    componentUpdates.threatIntelManualRefreshJob?.cancel()
    componentUpdates.threatIntelManualRefreshJob = null
    componentUpdates.threatIntelUpdatePhaseMutable.value = FoxholeUpdatePhase.IDLE
    componentUpdates.threatIntelDownloadProgressMutable.value = null
}

/** DONE is only honest after the verified feed has replaced the baseline installation. */
internal fun threatIntelVerifiedSuccess(
    phase: FoxholeUpdatePhase,
    installedGeneratedAt: String?,
    baselineGeneratedAt: String?,
): Boolean =
    phase == FoxholeUpdatePhase.DONE &&
        installedGeneratedAt != null &&
        installedGeneratedAt != baselineGeneratedAt

/** Kicks every ENABLED group's refresh; each runs behind its own in-flight guard. */
internal fun HomeViewModel.onFoxholeDbRefreshAll(settings: Settings) {
    if (settings.dns.dnsRuleSetFilteringEnabled()) {
        onDnsFilterManualRefresh()
    }
    if (settings.privacyRoute.permitted && settings.privacyRoute.bridgesEnabled) {
        onTorBridgeManualRefresh()
    }
    if (settings.anomaly.enabled) {
        onThreatIntelManualRefresh()
    }
    // The geo group has no enable switch: the map and geo statistics simply need it.
    onGeoIpDatabaseUpdateRequested()
}

/** One group of the FoxHole DB status: enabled state plus whether its data is present/fresh. */
internal data class FoxholeDbGroupUi(
    val enabled: Boolean,
    // Null when the group never downloaded anything (bundled fallbacks excluded on purpose:
    // "требует обновления" is about the FoxHole DB feed, not about the seed shipped in the APK).
    val updatedAtMs: Long?,
    val updateAvailable: Boolean = false,
) {
    val needsData: Boolean get() = enabled && (updatedAtMs == null || updateAvailable)
}
