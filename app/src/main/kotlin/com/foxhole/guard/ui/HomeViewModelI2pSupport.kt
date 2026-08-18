package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.I2pAddressBookEntry
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.I2pTransitBandwidth
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.removeI2pAddressBookEntry
import com.foxhole.guard.core.settings.updateI2pAllowOutsideTunnel
import com.foxhole.guard.core.settings.updateI2pAllowRelayOnCellular
import com.foxhole.guard.core.settings.updateI2pAutoReconnectAfterVpnDisconnect
import com.foxhole.guard.core.settings.updateI2pEnabled
import com.foxhole.guard.core.settings.updateI2pEngaged
import com.foxhole.guard.core.settings.updateI2pRelayTransitTraffic
import com.foxhole.guard.core.settings.updateI2pTransitBandwidth
import com.foxhole.guard.core.settings.updateI2pTransitTunnelsLimit
import com.foxhole.guard.core.settings.upsertI2pAddressBookEntry
import kotlinx.coroutines.launch

internal fun HomeViewModel.onI2pEnabledChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateI2pEnabled(value)
        if (value) container.settingsRepository.updateI2pEngaged(false)
    }
    viewModelScope.launch {
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.onI2pEngagedChanged(value: Boolean): Boolean {
    val settings = container.settingsRepository.settings.value
    val current = settings.i2p.enabled && settings.i2p.engaged
    if (current == value) return true
    if (requiresApplyConfirmation(AtomicApplyScope.MODE, settings.connection.atomicConnection)) {
        pendingRoutingScenarioConfirmationMutable.value =
            PendingRoutingScenarioChange.I2pRelay(
                currentEnabled = current,
                targetEnabled = value,
            )
        return false
    }
    applyI2pEngagement(value)
    return true
}

internal fun HomeViewModel.applyI2pEngagement(value: Boolean) {
    val insideRunningSession = value && controlUiState.value.connection.isPrimaryConnectionRuntime()
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateI2pEngaged(value)
    }
    viewModelScope.launch {
        if (insideRunningSession) {
            snackbars.tryEmit(infoBanner(R.string.cli_i2p_start_in_session))
        }
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.disengageI2pForDisconnect() {
    if (!container.settingsRepository.settings.value.i2p.engaged) return
    viewModelScope.launch {
        container.settingsRepository.updateI2pEngaged(false)
    }
}

internal fun HomeViewModel.i2pRouterLive(): Boolean =
    container.connectionController.i2pPhase.value.phase != I2pNetworkPhase.OFFLINE

internal fun HomeViewModel.applyI2pSettingChangeInternal(
    restartNow: Boolean,
    update: suspend () -> Unit,
) {
    if (restartNow) {
        updateRuntimeSettingAndMaybeReload { update() }
        viewModelScope.launch { syncLocalGuardWithPermissionRequest() }
        return
    }
    val routerLive = i2pRouterLive()
    viewModelScope.launch {
        update()
        snackbars.tryEmit(
            if (routerLive) {
                warningBanner(R.string.i2p_settings_saved_deferred)
            } else {
                successBanner(R.string.i2p_settings_saved)
            },
        )
        syncLocalGuardWithPermissionRequest()
    }
}

internal fun HomeViewModel.onI2pAllowOutsideTunnelChanged(
    value: Boolean,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pAllowOutsideTunnel(value)
}

internal fun HomeViewModel.onI2pAutoReconnectChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateI2pAutoReconnectAfterVpnDisconnect(value)
    }
}

internal fun HomeViewModel.onI2pRelayTransitTrafficChanged(
    value: Boolean,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pRelayTransitTraffic(value)
}

internal fun HomeViewModel.onI2pAllowRelayOnCellularChanged(
    value: Boolean,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pAllowRelayOnCellular(value)
}

internal fun HomeViewModel.onI2pTransitBandwidthSelected(
    value: I2pTransitBandwidth,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pTransitBandwidth(value)
}

internal fun HomeViewModel.onI2pTransitTunnelsLimitSelected(
    value: Int,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pTransitTunnelsLimit(value)
}

internal fun HomeViewModel.onI2pAddressBookEntrySaved(
    originalHost: String?,
    entry: I2pAddressBookEntry,
) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.upsertI2pAddressBookEntry(originalHost, entry)
    }
}

internal fun HomeViewModel.onI2pAddressBookEntryDeleted(host: String) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.removeI2pAddressBookEntry(host)
    }
}
