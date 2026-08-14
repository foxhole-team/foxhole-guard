package com.foxhole.guard.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.WebAppRoute
import com.foxhole.core.model.networkUp
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.data.WebAppPreview
import com.foxhole.guard.core.settings.updateShowFirewallStatus
import com.foxhole.guard.core.settings.updateWebAppsDockScreen
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.core.settings.updateWebAppsIsolation
import com.foxhole.guard.core.settings.updateWebAppsPollIntervalMinutes
import com.foxhole.guard.core.settings.updateWebAppsPushService
import com.foxhole.guard.core.webapps.webAppRouteSatisfied
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// Web apps settings toggles. The push service is the only one that touches the runtime: enabling
// it raises the firewall, so it repeats the side effects of the direct firewall toggle and
// reconciles guard.

/** Single write point for the open frame: the watchdog mutes notifications for the visible app. */
private fun HomeViewModel.setOpenWebApp(app: WebAppEntity?) {
    openWebAppMutable.value = app
    getApplication<FoxholeApplication>().appGraph.webAppsWatchdog.foregroundWebAppId = app?.id
}

internal fun HomeViewModel.onWebAppsEnabledChanged(value: Boolean) {
    setOpenWebApp(null)
    val snapshot = container.connectionController.snapshot.value
    when {
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
            snapshot.state in ACTIVE_CONNECTION_STATES ->
            updateRuntimeSettingAndMaybeReconnect {
                container.settingsRepository.updateWebAppsEnabled(value)
            }
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID ->
            viewModelScope.launch {
                container.settingsRepository.updateWebAppsEnabled(value)
                syncLocalGuardWithPermissionRequest(forceRestart = true)
            }
        else ->
            updateRuntimeSettingAndMaybeReload {
                container.settingsRepository.updateWebAppsEnabled(value)
            }
    }
}

/** Called after the firewall consent form, which the UI shows while the firewall is off. */
internal fun HomeViewModel.onWebAppsPushServiceChanged(value: Boolean) {
    viewModelScope.launch {
        val firewallWasOff = !container.settingsRepository.current().expert.firewallEnabled
        container.settingsRepository.updateWebAppsPushService(value)
        if (value && firewallWasOff) {
            // Indirect firewall enablement gets the same wiring as the direct toggle.
            container.settingsRepository.updateShowFirewallStatus(true)
            applyTrafficMapSupportSettings()
        }
        if (value) {
            // The watchdog posts system notifications, so the permission is needed even when the
            // firewall is already on.
            requestNotificationPermission.tryEmit(Unit)
        }
        syncLocalGuardWithPermissionRequest()
        if (value && firewallWasOff) {
            refreshLocalGuardDashboardIpAfterSettingsChange()
        }
    }
}

/** Isolation is a UI-level gate over an unchanged runtime: a plain setting, no reconnect. */
internal fun HomeViewModel.onWebAppsIsolationChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateWebAppsIsolation(value)
    }
}

internal fun HomeViewModel.onWebAppsDockScreenChanged(value: Boolean) {
    viewModelScope.launch {
        container.settingsRepository.updateWebAppsDockScreen(value)
    }
}

internal fun HomeViewModel.onWebAppsPollIntervalChanged(value: Int) {
    viewModelScope.launch {
        container.settingsRepository.updateWebAppsPollIntervalMinutes(value)
    }
}

/** Add-web-app form: idle -> loading -> ready/error; ok resets to idle. */
internal sealed interface WebAppAddUiState {
    data object Idle : WebAppAddUiState

    data object Loading : WebAppAddUiState

    data class Ready(val preview: WebAppPreview) : WebAppAddUiState

    data class Error(val message: String) : WebAppAddUiState
}

internal fun HomeViewModel.previewWebApp(rawUrl: String) {
    webAppAddStateMutable.value = WebAppAddUiState.Loading
    viewModelScope.launch {
        val webApps = container.settingsRepository.current().webApps
        if (
            !webApps.enabled ||
            !webAppRouteSatisfied(
                route = WebAppRoute.DEFAULT,
                blockWithoutTunnel = webApps.isolationEnabled,
                snapshot = container.connectionController.snapshot.value,
                i2pReady = container.connectionController.i2pPhase.value.phase.networkUp,
            )
        ) {
            webAppAddStateMutable.value =
                WebAppAddUiState.Error(
                    getApplication<Application>().getString(R.string.cli_webapps_route_required),
                )
            return@launch
        }
        container.webAppsRepository
            .preview(rawUrl)
            .onSuccess { preview -> webAppAddStateMutable.value = WebAppAddUiState.Ready(preview) }
            .onFailure { error ->
                webAppAddStateMutable.value =
                    WebAppAddUiState.Error(error.message ?: "fetch failed")
            }
    }
}

internal fun HomeViewModel.confirmAddWebApp(name: String) {
    val ready = webAppAddStateMutable.value as? WebAppAddUiState.Ready ?: return
    viewModelScope.launch {
        container.webAppsRepository.add(
            WebAppPreview(
                url = ready.preview.url,
                name = name.trim().ifEmpty { ready.preview.name },
                iconBytes = ready.preview.iconBytes,
            ),
        )
        webAppAddStateMutable.value = WebAppAddUiState.Idle
    }
}

internal fun HomeViewModel.dismissWebAppAdd() {
    webAppAddStateMutable.value = WebAppAddUiState.Idle
}

internal fun HomeViewModel.renameWebApp(id: Long, name: String) {
    viewModelScope.launch {
        container.webAppsRepository.rename(id, name)
    }
}

internal fun HomeViewModel.setWebAppRoute(id: Long, route: WebAppRoute) {
    viewModelScope.launch {
        if (openWebAppMutable.value?.id == id) {
            setOpenWebApp(null)
        }
        val graph = getApplication<FoxholeApplication>().appGraph
        graph.webAppsWatchdog.withPollingPaused {
            container.webAppsRepository.setRoute(id, route)
        }
    }
}

internal fun HomeViewModel.removeWebApp(id: Long) {
    viewModelScope.launch {
        if (openWebAppMutable.value?.id == id) {
            setOpenWebApp(null)
        }
        val removed = container.webAppsRepository.webApp(id)
        container.webAppsRepository.remove(id)
        val graph = getApplication<FoxholeApplication>().appGraph
        graph.webAppsNotifier.cancel(id)
        // Removal drops the app's profile (or, pre-profiles, the site's cookies and storages), so
        // a deleted login does not linger. Per-app only: a removal must not wipe the other web
        // apps, so the no-capability WebView keeps its data until a consented full wipe.
        removed?.let { entity ->
            graph.webAppsWatchdog.withPollingPaused {
                graph.webAppsDataCleaner.clearApp(entity.id, entity.url)
            }
        }
    }
}

/** Opening the frame clears the app badge: its notifications count as read. */
internal fun HomeViewModel.openWebApp(id: Long) {
    viewModelScope.launch {
        val app = container.webAppsRepository.webApp(id) ?: return@launch
        val webApps = container.settingsRepository.current().webApps
        val route = app.webAppRoute()
        if (
            !webApps.enabled ||
            !webAppRouteSatisfied(
                route = route,
                blockWithoutTunnel = webApps.isolationEnabled,
                snapshot = container.connectionController.snapshot.value,
                i2pReady = container.connectionController.i2pPhase.value.phase.networkUp,
            )
        ) {
            emitError(
                getApplication<Application>().getString(R.string.cli_webapps_route_required),
            )
            return@launch
        }
        // The route is installed BEFORE the frame's WebView exists: a proxy override applied to a
        // live WebView leaves the first requests on whatever network the process had. A refusal
        // (no route the core can publish, an override the WebView provider will not take) closes
        // the door instead of opening an unrouted frame.
        val activation =
            getApplication<FoxholeApplication>().appGraph.webAppsWatchdog.acquireForegroundProxy(
                appId = app.id,
                route = route,
                blockWithoutTunnel = webApps.isolationEnabled,
            )
        if (!activation.applied) {
            emitError(
                getApplication<Application>().getString(R.string.cli_webapps_route_unavailable),
            )
            return@launch
        }
        webAppProxyCredentialsMutable.value = activation.credentials
        container.webAppsRepository.resetBadge(app.id)
        setOpenWebApp(app)
    }
}

internal fun HomeViewModel.closeWebApp() {
    setOpenWebApp(null)
}

/**
 * The frame's WebView is gone. Only now may the process-global override come down — and the
 * credentials it was holding go with it.
 */
internal fun HomeViewModel.onWebAppFrameReleased() {
    viewModelScope.launch {
        webAppProxyCredentialsMutable.value = null
        getApplication<FoxholeApplication>().appGraph.webAppsWatchdog.releaseForegroundProxy()
    }
}

/**
 * With isolation on, destroy the WebView before a stopped/reconnecting TUN can fall back to the
 * default network; with it off the frame survives tunnel churn — the current network is allowed.
 */
internal fun HomeViewModel.startWebAppRouteSupervision() {
    viewModelScope.launch {
        combine(
            openWebAppMutable,
            container.connectionController.snapshot,
            container.settingsRepository.settings.map { it.webApps },
            container.connectionController.i2pPhase,
        ) { app, snapshot, webApps, i2p ->
            app == null ||
                webAppRouteSatisfied(
                    route = app.webAppRoute(),
                    blockWithoutTunnel = webApps.isolationEnabled,
                    snapshot = snapshot,
                    i2pReady = i2p.phase.networkUp,
                )
        }
            .distinctUntilChanged()
            .collect { routeOk ->
                if (!routeOk) setOpenWebApp(null)
            }
    }
}

/** Notification or widget tap: the intent extra carrying the app id opens its frame. */
internal fun HomeViewModel.applyWebAppOpenIntent(intent: Intent?) {
    if (intent == null) {
        return
    }
    val id = intent.getLongExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID, -1L)
    if (id <= 0L) {
        return
    }
    // Re-delivery of the same intent on recreate must not reopen the frame.
    intent.removeExtra(CliMainActivity.EXTRA_OPEN_WEB_APP_ID)
    openWebApp(id)
}

// Web-app data wipe: the app's own profile natively when the WebView supports MULTI_PROFILE,
// per-site via DELETE_BROWSING_DATA otherwise, and with neither only the whole shared profile
// (the UI asks first). The watchdog pauses around a wipe so its hidden WebView is not holding
// the site — or the profile — open, and the badge resets — a logged-out site has nothing unread
// to count.

internal fun HomeViewModel.webAppPerAppClearSupported(): Boolean =
    getApplication<FoxholeApplication>().appGraph.webAppsDataCleaner.perAppClearSupported

internal fun HomeViewModel.clearWebAppData(id: Long) {
    viewModelScope.launch {
        val graph = getApplication<FoxholeApplication>().appGraph
        val app = container.webAppsRepository.webApp(id) ?: return@launch
        var clearedSite: String? = null
        graph.webAppsWatchdog.withPollingPaused {
            clearedSite = graph.webAppsDataCleaner.clearApp(app.id, app.url)
        }
        val cleared = clearedSite
        if (cleared != null) {
            container.webAppsRepository.resetBadge(id)
            graph.webAppsNotifier.cancel(id)
            emitSuccess(getApplication<Application>().getString(R.string.cli_webapps_cleared, cleared))
        } else {
            emitError(getApplication<Application>().getString(R.string.cli_webapps_clear_failed))
        }
    }
}

internal fun HomeViewModel.clearAllWebAppsData() {
    viewModelScope.launch {
        val graph = getApplication<FoxholeApplication>().appGraph
        val apps = container.webAppsRepository.listWebApps()
        var wiped = false
        graph.webAppsWatchdog.withPollingPaused {
            wiped = graph.webAppsDataCleaner.clearAll(apps.map { it.id })
        }
        if (wiped) {
            apps.forEach { app ->
                container.webAppsRepository.resetBadge(app.id)
                graph.webAppsNotifier.cancel(app.id)
            }
            emitSuccess(getApplication<Application>().getString(R.string.cli_webapps_cleared_all))
        } else {
            emitError(getApplication<Application>().getString(R.string.cli_webapps_clear_failed))
        }
    }
}

/** True when the system will not show web-app notifications: master toggle off or channel muted. */
internal fun HomeViewModel.webAppNotificationsBlocked(): Boolean =
    getApplication<FoxholeApplication>().appGraph.webAppsNotifier.deliveryBlocked()

/** Terminal note about an external link blocked in the frame: navigation is locked to the domain. */
internal fun HomeViewModel.notifyWebAppExternalBlocked(host: String) {
    viewModelScope.launch {
        emitInfo(getApplication<Application>().getString(R.string.cli_webapps_external_blocked, host))
    }
}

internal fun HomeViewModel.webAppIconFile(entity: WebAppEntity): java.io.File? =
    container.webAppsRepository.iconFile(entity)
