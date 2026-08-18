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

internal val HomeViewModel.threatIntelUpdatePhase: StateFlow<FoxholeUpdatePhase>
    get() = componentUpdates.threatIntelUpdatePhaseMutable.asStateFlow()

internal val HomeViewModel.threatIntelDownloadProgress: StateFlow<RemoteDownloadProgress?>
    get() = componentUpdates.threatIntelDownloadProgressMutable.asStateFlow()

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

internal fun threatIntelVerifiedSuccess(
    phase: FoxholeUpdatePhase,
    installedGeneratedAt: String?,
    baselineGeneratedAt: String?,
): Boolean =
    phase == FoxholeUpdatePhase.DONE &&
        installedGeneratedAt != null &&
        installedGeneratedAt != baselineGeneratedAt

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
    onTlsFingerprintManualRefresh()
    onGeoIpDatabaseUpdateRequested()
}

internal data class FoxholeDbGroupUi(
    val enabled: Boolean,
    val updatedAtMs: Long?,
    val updateAvailable: Boolean = false,
) {
    val needsData: Boolean get() = enabled && (updatedAtMs == null || updateAvailable)
}
