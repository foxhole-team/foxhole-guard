package com.foxhole.guard.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.TorBridgeTransport
import com.foxhole.guard.applyTorBridgeUpdateSchedule
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgeTransport
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesAutoUpdate
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesEnabled
import com.foxhole.guard.core.settings.updatePrivacyRouteBridgesUseFoxholeSource
import com.foxhole.guard.runtime.TorBridgeUpdateStatus
import kotlinx.coroutines.launch

// Tor bridge settings support (the "Bridges" group on the Tor-core screen). Extracted from
// HomeViewModel by domain, same pattern as the DNS-filter / GeoIP update support files.

// Bridge lines land in torrc-defaults at Tor start; a live direct-Tor runtime reloads so the
// change applies immediately, otherwise it applies on the next start.
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

internal fun HomeViewModel.onTorBridgeManualRefresh() {
    if (torBridgeRefreshInProgressMutable.value) {
        return
    }
    torBridgeUpdatePhaseMutable.value = FoxholeUpdatePhase.CHECKING
    torBridgeManualRefreshJob =
        viewModelScope.launch {
            torBridgeRefreshInProgressMutable.value = true
            val terminalPhase =
                try {
                    runTorBridgeManualRefreshRequest()
                } finally {
                    torBridgeRefreshInProgressMutable.value = false
                }
            settleUpdatePhase({ phase -> torBridgeUpdatePhaseMutable.value = phase }, terminalPhase)
        }
}

private suspend fun HomeViewModel.runTorBridgeManualRefreshRequest(): FoxholeUpdatePhase =
    runCatching {
        container.torBridgeUpdateRepository.refreshNow(
            onPhase = { phase -> torBridgeUpdatePhaseMutable.value = phase.toFoxholeUpdatePhase() },
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
            container.diagnosticsLogger.record(
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
}
