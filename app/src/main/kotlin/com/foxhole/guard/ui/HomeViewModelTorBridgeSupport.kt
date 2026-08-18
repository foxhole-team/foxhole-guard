package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.guard.TOR_BRIDGE_UPDATE_INTERVAL_HOURS
import com.foxhole.guard.applyTorBridgeUpdateSchedule
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgeTransport
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesAutoUpdate
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesUseFoxholeSource
import com.foxhole.guard.core.settings.updatePrivacyRoutePermitted
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.runtime.TorBridgeUpdateStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

internal fun HomeViewModel.onTorBridgesEnabledChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteBridgesEnabled(value)
    }
}

internal fun HomeViewModel.onTorBridgeTransportSelected(value: TorBridgeTransport) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updatePrivacyRouteBridgeTransport(value)
    }
}

internal fun HomeViewModel.onTorBridgesAutoUpdateChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updatePrivacyRouteBridgesAutoUpdate(value)
        getApplication<Application>().applyTorBridgeUpdateSchedule(
            enabled = value && container.settingsRepository.current().connection.componentUpdateCheckEnabled,
        )
    }
}

internal fun HomeViewModel.onTorBridgesUseFoxholeSourceChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updatePrivacyRouteBridgesUseFoxholeSource(value)
    }
}

internal val HomeViewModel.torBridgeDownloadProgress: StateFlow<RemoteDownloadProgress?>
    get() = componentUpdates.torBridgeDownloadProgressMutable.asStateFlow()

internal fun HomeViewModel.onTorBridgeManualRefresh(useFoxholeSourceOverride: Boolean? = null) {
    if (torBridgeRefreshInProgressMutable.value) {
        return
    }
    torBridgeUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
    componentUpdates.torBridgeDownloadProgressMutable.value = null
    torBridgeManualRefreshJob =
        viewModelScope.launch {
            torBridgeRefreshInProgressMutable.value = true
            val terminalPhase =
                try {
                    runTorBridgeManualRefreshRequest(useFoxholeSourceOverride)
                } finally {
                    torBridgeRefreshInProgressMutable.value = false
                }
            if (terminalPhase == FoxholeUpdatePhase.FAILED) {
                torBridgeUpdatePhaseMutable.value = FoxholeUpdatePhase.FAILED
            } else {
                settleUpdatePhase({ phase -> torBridgeUpdatePhaseMutable.value = phase }, terminalPhase)
            }
        }
}

private suspend fun HomeViewModel.runTorBridgeManualRefreshRequest(
    useFoxholeSourceOverride: Boolean?,
): FoxholeUpdatePhase =
    runCatching {
        container.torBridgeUpdateRepository.refreshNow(
            useFoxholeSourceOverride = useFoxholeSourceOverride,
            onPhase = { phase -> torBridgeUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase() },
            onProgress = { progress -> componentUpdates.torBridgeDownloadProgressMutable.value = progress },
        )
    }.fold(
        onSuccess = { result ->
            when (result.status) {
                TorBridgeUpdateStatus.UPDATED -> FoxholeUpdatePhase.DONE
                TorBridgeUpdateStatus.UP_TO_DATE -> FoxholeUpdatePhase.NO_UPDATE
                TorBridgeUpdateStatus.FAILED -> FoxholeUpdatePhase.FAILED
            }
        },
        onFailure = { error ->
            if (error is CancellationException) throw error
            container.diagnosticsLogger.recordFailure(
                "tor",
                "bridge list refresh failed error=${error.javaClass.simpleName}",
            )
            FoxholeUpdatePhase.FAILED
        },
    )

internal fun HomeViewModel.onTorBridgeManualRefreshCancel() {
    torBridgeManualRefreshJob?.cancel()
    torBridgeManualRefreshJob = null
    torBridgeRefreshInProgressMutable.value = false
    torBridgeUpdatePhaseMutable.value = FoxholeUpdatePhase.IDLE
    componentUpdates.torBridgeDownloadProgressMutable.value = null
}

internal fun HomeViewModel.onTorPermissionWithBridgeChoice(
    useBridges: Boolean,
    source: DatasetActivationSource,
) {
    viewModelScope.launch {
        val repository = container.settingsRepository
        repository.updatePrivacyRoutePermitted(true)
        repository.updatePrivacyRouteBridgesUseFoxholeSource(source == DatasetActivationSource.FOXHOLE_DB)
        repository.updatePrivacyRouteBridgesEnabled(useBridges)
    }
}

internal fun torBridgeRefreshRequired(
    settings: PrivacyRouteSettings,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (!settings.permitted || !settings.bridgesEnabled) return false
    if (settings.bridgesLastUpdateSuccess != true) return true
    val checkedAt = settings.bridgesCheckedAt ?: return true
    val intervalMs = TimeUnit.HOURS.toMillis(TOR_BRIDGE_UPDATE_INTERVAL_HOURS)
    return checkedAt <= nowMs - intervalMs
}

internal data class TorBridgePersistenceMarker(
    val checkedAt: Long?,
    val updatedAt: Long?,
    val lastUpdateSuccess: Boolean?,
)

internal fun PrivacyRouteSettings.torBridgePersistenceMarker(): TorBridgePersistenceMarker =
    TorBridgePersistenceMarker(
        checkedAt = bridgesCheckedAt,
        updatedAt = bridgesUpdatedAt,
        lastUpdateSuccess = bridgesLastUpdateSuccess,
    )

internal fun torBridgeVerifiedSuccess(
    phase: FoxholeUpdatePhase,
    settings: PrivacyRouteSettings,
    baseline: TorBridgePersistenceMarker,
): Boolean =
    settings.torBridgePersistenceMarker() != baseline &&
        settings.bridgesLastUpdateSuccess == true &&
        settings.bridgesCheckedAt != null &&
        when (phase) {
            FoxholeUpdatePhase.DONE -> settings.bridgesUpdatedAt != null
            FoxholeUpdatePhase.NO_UPDATE -> true
            else -> false
        }
