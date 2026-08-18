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

internal fun HomeViewModel.onSuppressProfileSwipeReconnectConfirmChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSuppressProfileSwipeReconnectConfirm(value)
    }
}

internal fun HomeViewModel.onSuppressTrafficClearConfirmChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateSuppressTrafficClearConfirm(value)
    }
}

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
    resetUsageTrackingInternal()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_TOTAL_RX, TRAFFIC_CHART_LANE_TOTAL_TX)
}

internal fun HomeViewModel.resetTorTraffic() {
    TorTrafficStats.reset()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_TOR_RX, TRAFFIC_CHART_LANE_TOR_TX)
}

internal fun HomeViewModel.resetI2pTraffic() {
    I2pTrafficStats.reset()
    trafficChartRecorder.clearLanePair(TRAFFIC_CHART_LANE_I2P_RX, TRAFFIC_CHART_LANE_I2P_TX)
}

internal fun HomeViewModel.clearI2pTrafficStatistics() {
    clearI2pTrafficStatisticsInternal()
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
