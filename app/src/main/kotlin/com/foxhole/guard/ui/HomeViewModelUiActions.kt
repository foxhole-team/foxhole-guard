package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pTrafficStats
import com.foxhole.core.model.TorTrafficStats
import com.foxhole.guard.core.settings.updateInterfaceSettingsExpanded
import com.foxhole.guard.core.settings.updateLayoutEditingEnabled
import com.foxhole.guard.core.settings.updateMapWidgetMapOnRight
import com.foxhole.guard.core.settings.updateSuppressFirewallEnableWarning
import com.foxhole.guard.core.settings.updateSuppressProfileSwipeReconnectConfirm
import com.foxhole.guard.core.settings.updateSuppressTrafficClearConfirm
import kotlinx.coroutines.launch

// UI-preference toggles, traffic-counter resets and the section-visibility hooks of the home
// view-model: extensions on the class — split from HomeViewModel.kt.
// "Don't show again" checkbox on the profile-card swipe-refresh confirmation.
internal fun HomeViewModel.onSuppressProfileSwipeReconnectConfirmChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSuppressProfileSwipeReconnectConfirm(value)
    }
}

// "Don't show again" checkbox on the traffic-widget swipe-clear confirmation.
internal fun HomeViewModel.onSuppressTrafficClearConfirmChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSuppressTrafficClearConfirm(value)
    }
}

// "Don't show again" checkbox on the firewall-enable warnings (settings + dashboard window).
internal fun HomeViewModel.onSuppressFirewallEnableWarningChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSuppressFirewallEnableWarning(value)
    }
}

internal fun HomeViewModel.onMapWidgetMapOnRightChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateMapWidgetMapOnRight(value)
    }
}

internal fun HomeViewModel.onInterfaceSettingsExpandedChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateInterfaceSettingsExpanded(value)
    }
}

internal fun HomeViewModel.onLayoutEditingEnabledChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateLayoutEditingEnabled(value)
    }
}

internal fun HomeViewModel.resetUsageTracking() {
    // The clear is already confirmed by the time this runs (the traffic widget's sheet, or
    // the user's opt-out).
    resetUsageTrackingInternal()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_TOTAL_RX, TRAFFIC_CHART_LANE_TOTAL_TX)
}

// The traffic widget's TOR page clears only its own lane counters (and its chart trace).
internal fun HomeViewModel.resetTorTraffic() {
    TorTrafficStats.reset()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_TOR_RX, TRAFFIC_CHART_LANE_TOR_TX)
}

// The traffic widget's I2P page mirrors the TOR clear for the i2p lane.
internal fun HomeViewModel.resetI2pTraffic() {
    I2pTrafficStats.reset()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_I2P_RX, TRAFFIC_CHART_LANE_I2P_TX)
}

internal fun HomeViewModel.clearDiagnosticsLocalData() = runConfirmedAction { clearDiagnosticsLocalDataInternal() }

internal fun HomeViewModel.clearNetworkActivityLocalData() =
    runConfirmedAction { clearNetworkActivityLocalDataInternal() }

internal fun HomeViewModel.clearTorLog() =
    runConfirmedAction { clearRuntimeLogTagInternal(tag = "tor", journalKey = "tor-log") }

internal fun HomeViewModel.clearI2pLog() =
    runConfirmedAction { clearRuntimeLogTagInternal(tag = "i2pd", journalKey = "i2p-log") }

internal fun HomeViewModel.onDashboardUiVisibilityChanged(visible: Boolean) {
    if (dashboardVisible == visible) {
        return
    }
    dashboardVisible = visible
    if (visible) {
        startPendingProfileReconnectPromptIfNeeded()
        viewModelScope.launch {
            syncLocalGuardWithPermissionRequest()
        }
        val connectionState = container.connectionController.snapshot.value.state
        if (connectionState == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running) {
            scheduleForegroundDashboardRefreshIfStale()
        }
    } else {
        trafficUiVisible = false
        syncHighFrequencyTrafficVisibility()
        if (!profilesUiVisible) {
            clearProfileLatencyRefresh()
        }
    }
}

// The profiles screen hosts the protocol-management window whose remembered metrics must stay
// live while a protocol is connected, so the connected-metrics refresh loop runs there too.
internal fun HomeViewModel.onProfilesUiVisibilityChanged(visible: Boolean) {
    if (profilesUiVisible == visible) {
        return
    }
    profilesUiVisible = visible
    if (visible) {
        val connectionState = container.connectionController.snapshot.value.state
        if (connectionState == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running) {
            scheduleActiveProfileLatencyRefresh(showLoading = false, refreshImmediately = true)
        }
    } else if (!dashboardVisible) {
        clearProfileLatencyRefresh()
    }
}

internal val HomeViewModel.connectionMetricsUiVisible: Boolean
    get() = dashboardVisible || profilesUiVisible

internal fun HomeViewModel.onTrafficUiVisibilityChanged(visible: Boolean) {
    trafficUiVisible = visible
    syncHighFrequencyTrafficVisibility()
}

internal fun HomeViewModel.updateAppPickerQuery(query: String) {
    appPickerQueryFlowMutable.value = query
}

internal fun HomeViewModel.clearAppPickerQuery() {
    appPickerQueryFlowMutable.value = ""
}
