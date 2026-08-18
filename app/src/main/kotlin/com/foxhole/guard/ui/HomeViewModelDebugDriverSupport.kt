package com.foxhole.guard.ui

import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.guard.BuildConfig
import com.foxhole.guard.core.settings.updatePrivacyRouteScope
import com.foxhole.guard.core.settings.updatePrivacyRouteSelectedPackages
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun HomeViewModel.applyBenchmarkIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG) {
        return
    }
    intent?.getStringExtra("foxhole_debug_import_raw")?.takeIf(String::isNotBlank)?.let { raw ->
        importRaw(raw)
    }
    if (intent?.hasExtra("foxhole_debug_tor_bypass") == true) {
        onPrivacyRouteBypassVpnTunnelConfigured(intent.getBooleanExtra("foxhole_debug_tor_bypass", false))
    }
    intent?.getStringExtra("foxhole_debug_connect")?.takeIf(String::isNotBlank)?.let(::debugDriveConnect)
    intent?.getStringExtra("foxhole_debug_tor_scope")?.takeIf(String::isNotBlank)?.let { scope ->
        viewModelScope.launch {
            if (scope.equals("all", ignoreCase = true)) {
                container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.ALL_APPS)
            } else if (scope.startsWith("apps:", ignoreCase = true)) {
                container.settingsRepository.updatePrivacyRouteSelectedPackages(
                    scope.substringAfter(':').split(',').map(String::trim).filter(String::isNotBlank),
                )
                container.settingsRepository.updatePrivacyRouteScope(PrivacyRouteScope.SELECTED_APPS)
            }
        }
    }
    if (intent?.hasExtra("foxhole_debug_tor_permit") == true) {
        onTorRoutePermittedChanged(intent.getBooleanExtra("foxhole_debug_tor_permit", false))
    }
    if (intent?.hasExtra("foxhole_debug_i2p_enable") == true) {
        onI2pEnabledChanged(intent.getBooleanExtra("foxhole_debug_i2p_enable", false))
    }
    if (intent?.hasExtra("foxhole_debug_i2p_engage") == true) {
        applyI2pEngagement(intent.getBooleanExtra("foxhole_debug_i2p_engage", false))
    }
    if (intent?.getBooleanExtra("foxhole_debug_dump_state", false) == true) {
        debugDumpControlState()
    }
    when (intent?.getStringExtra(TrafficMapBenchmarkStress.IntentExtra)) {
        TrafficMapBenchmarkStress.MaxLoadMode ->
            benchmarkTrafficMapUiStateMutable.value =
                TrafficMapBenchmarkStress.buildState(container.trafficMapRepository)
    }
}

private fun HomeViewModel.debugDumpControlState() {
    viewModelScope.launch {
        val settings = container.settingsRepository.current()
        val snapshot = container.connectionController.snapshot.value
        val applied = container.connectionController.appliedRuntimeSignature.value
        val current = runCatching { container.connectionController.currentRuntimeFingerprint() }.getOrNull()
        container.diagnosticsLogger.recordStructured(
            "debug",
            "control state dump",
            "mode=${settings.privacyRoute.mode}",
            "scope=${settings.privacyRoute.scope}",
            "bypass=${settings.privacyRoute.bypassVpnTunnel}",
            "traffic_mode=${settings.traffic.mode}",
            "snapshot=${snapshot.state}/${snapshot.profileId}",
            "tor_op=${controlUiState.value.torOperation.kind}",
            "reconnect_required=${runtimeReconnectRequiredMutable.value}",
            "show_tor_controls=${settings.ui.showTorDashboardControls}",
            "show_tor_quick=${settings.ui.showTorQuickLaunch}",
            "ui=${settings.ui}",
            "applied_fp=$applied",
            "current_fp=$current",
        )
    }
}

private fun HomeViewModel.debugDriveConnect(kind: String) {
    when (kind.lowercase()) {
        "disconnect" -> container.connectionController.disconnect(suppressLocalGuard = false)
        "tor_off", "route_off", "direct" ->
            viewModelScope.launch { onPrivacyRouteModeConfigured(PrivacyRouteMode.OFF) }
        "tor_only", "tor" ->
            viewModelScope.launch {
                onPrivacyRouteScopeConfigured(PrivacyRouteScope.ALL_APPS)
                onPrivacyRouteModeConfigured(PrivacyRouteMode.TOR_OVER_VPN)
                delay(1200)
                requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
            }
        "local_guard", "local", "guard" ->
            viewModelScope.launch { syncLocalGuardWithPermissionRequest() }
        "active", "profile" ->
            uiState.value.activeProfile?.let { requestManualConnectPermissionOrConnect(it.id) }
        else -> kind.toLongOrNull()?.let { requestManualConnectPermissionOrConnect(it) }
    }
}
