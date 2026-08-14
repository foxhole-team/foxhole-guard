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

// Independent I2P (i2pd) toggle support. The change alters the assembled runtime config (adds/drops
// the .i2p outbound + fakeip routing), so a live runtime reloads; idle applies on the next connect.
// With "allow outside tunnel" on, i2p also raises/drops the firewall guard as its routing surface
// (see Settings.i2pRaisesLocalGuard), so re-sync the guard too.
internal fun HomeViewModel.onI2pEnabledChanged(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateI2pEnabled(value)
    }
    viewModelScope.launch {
        syncLocalGuardWithPermissionRequest()
    }
}

// Dashboard/window pause: flips runtime engagement only, leaving the persisted permission (and its
// quick-access pill) in place. With atomic application off, both directions wait behind the shared
// current→future sheet. Returns true only when this call applied immediately.
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

/** Confirmation lands here directly so it cannot raise the same sheet a second time. */
internal fun HomeViewModel.applyI2pEngagement(value: Boolean) {
    updateRuntimeSettingAndMaybeReload {
        container.settingsRepository.updateI2pEngaged(value)
    }
    viewModelScope.launch {
        syncLocalGuardWithPermissionRequest()
    }
}

/**
 * True while the i2pd router is actually up. Every I2P option below is a START-ONLY i2pd field, so
 * changing one under a live router means tearing that router down — which is exactly what the
 * settings screen asks about first.
 */
internal fun HomeViewModel.i2pRouterLive(): Boolean =
    container.connectionController.i2pPhase.value.phase != I2pNetworkPhase.OFFLINE

/**
 * The shared I2P settings-change path. The edit is ALWAYS persisted; [restartNow] only decides
 * whether the running router is rebuilt at once or on its next start. With no router running there
 * is nothing to restart, so the change simply lands and says so in green.
 */
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

// Every start-only I2P field now travels the shared path: the screen asks "restart now?" whenever
// the router is live and answers through [applyI2pSettingChangeInternal]; with the router idle the
// change simply lands.
internal fun HomeViewModel.onI2pAllowOutsideTunnelChanged(
    value: Boolean,
    restartNow: Boolean = true,
) = applyI2pSettingChangeInternal(restartNow) {
    container.settingsRepository.updateI2pAllowOutsideTunnel(value)
}

/** Future carrier policy only: changing it must not restart an already running I2P router. */
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

// Addressbook edits are part of the runtime fingerprint (settings.i2p is serialized wholesale), so
// a live runtime reloads and the session factory restarts i2pd with a regenerated hosts.txt.
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
