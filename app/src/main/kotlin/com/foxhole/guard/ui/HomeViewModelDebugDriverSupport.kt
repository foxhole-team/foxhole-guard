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

/**
 * Debug-only adb intent drivers for [HomeViewModel] (profile import, Tor route placement, scripted
 * connect smoke, traffic-map stress). Extracted from the view-model body; compiled into release too
 * but every entry point no-ops unless BuildConfig.DEBUG.
 */

internal fun HomeViewModel.applyBenchmarkIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG) {
        return
    }
    // Debug-only helper for repeatable device testing: import a raw profile/subscription via
    //   adb shell am start -n <pkg>/com.foxhole.guard.MainActivity --es foxhole_debug_import_raw "<url-or-config>"
    intent?.getStringExtra("foxhole_debug_import_raw")?.takeIf(String::isNotBlank)?.let { raw ->
        // Bypass the add-profile confirmation sheet: this path exists for adb automation.
        importRaw(raw)
    }
    // Debug-only: set the Tor route placement for testing, e.g.
    //   adb shell am start -n <pkg>/...MainActivity --ez foxhole_debug_tor_bypass false
    if (intent?.hasExtra("foxhole_debug_tor_bypass") == true) {
        onPrivacyRouteBypassVpnTunnelConfigured(intent.getBooleanExtra("foxhole_debug_tor_bypass", false))
    }
    // Debug-only: drive a connect for repeatable, scriptable runtime smoke (no UI tap), e.g.
    //   adb ... --es foxhole_debug_connect tor_only      (Tor-only over the embedded Tor)
    //   adb ... --es foxhole_debug_connect local_guard   (local guard / DNS filter runtime)
    //   adb ... --es foxhole_debug_connect active         (the selected VPN profile)
    //   adb ... --es foxhole_debug_connect <profileId>    (a specific profile by id)
    //   adb ... --es foxhole_debug_connect disconnect
    intent?.getStringExtra("foxhole_debug_connect")?.takeIf(String::isNotBlank)?.let(::debugDriveConnect)
    // Debug-only: set the Tor route scope without touching the runtime, e.g.
    //   adb ... --es foxhole_debug_tor_scope all
    //   adb ... --es foxhole_debug_tor_scope apps:org.mozilla.firefox,com.example
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
    // Debug-only: permit/forbid the Tor route exactly like the settings switch, e.g.
    //   adb shell am start -n <pkg>/...MainActivity --ez foxhole_debug_tor_permit true
    if (intent?.hasExtra("foxhole_debug_tor_permit") == true) {
        onTorRoutePermittedChanged(intent.getBooleanExtra("foxhole_debug_tor_permit", false))
    }
    // Debug-only: enable/disable the i2p runtime exactly like the settings switch, e.g.
    //   adb shell am start -n <pkg>/...MainActivity --ez foxhole_debug_i2p_enable true
    if (intent?.hasExtra("foxhole_debug_i2p_enable") == true) {
        onI2pEnabledChanged(intent.getBooleanExtra("foxhole_debug_i2p_enable", false))
    }
    // Debug-only: pause/resume (engage) the i2p router without touching the persisted permission, e.g.
    //   adb shell am start -n <pkg>/...MainActivity --ez foxhole_debug_i2p_engage true
    if (intent?.hasExtra("foxhole_debug_i2p_engage") == true) {
        // Debug automation is not an interactive UI surface, so it applies explicitly instead of
        // parking behind a confirmation sheet that adb cannot answer.
        applyI2pEngagement(intent.getBooleanExtra("foxhole_debug_i2p_engage", false))
    }
    // Debug-only: dump the control-plane state to FoxholeDiag for device debugging, e.g.
    //   adb shell am start -n <pkg>/...MainActivity --ez foxhole_debug_dump_state true
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

// Debug-only connect driver for device smoke. Bypasses the UI toggle gates so a single adb
// intent can exercise each runtime path end-to-end (see applyBenchmarkIntent for usage).
private fun HomeViewModel.debugDriveConnect(kind: String) {
    when (kind.lowercase()) {
        "disconnect" -> container.connectionController.disconnect(suppressLocalGuard = false)
        "tor_off", "route_off", "direct" ->
            viewModelScope.launch { onPrivacyRouteModeConfigured(PrivacyRouteMode.OFF) }
        "tor_only", "tor" ->
            viewModelScope.launch {
                // Tor-only needs the privacy route enabled for all apps; persist, then start.
                onPrivacyRouteScopeConfigured(PrivacyRouteScope.ALL_APPS)
                onPrivacyRouteModeConfigured(PrivacyRouteMode.TOR_OVER_VPN)
                delay(1200)
                requestManualConnectPermissionOrConnect(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
            }
        "local_guard", "local", "guard" ->
            // The guard is started by syncing to the configured guard mode (firewall / system-DNS),
            // NOT by connecting the LOCAL_GUARD sentinel as a profile — that corrupts the active
            // profile and spins syncLocalGuard. It no-ops if no guard mode is enabled in settings.
            viewModelScope.launch { syncLocalGuardWithPermissionRequest() }
        "active", "profile" ->
            uiState.value.activeProfile?.let { requestManualConnectPermissionOrConnect(it.id) }
        else -> kind.toLongOrNull()?.let { requestManualConnectPermissionOrConnect(it) }
    }
}
