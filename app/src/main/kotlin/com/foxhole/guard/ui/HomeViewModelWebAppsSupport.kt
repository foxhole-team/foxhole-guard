package com.foxhole.guard.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.R
import com.foxhole.guard.core.data.WebAppEntity
import com.foxhole.guard.core.data.WebAppPreview
import com.foxhole.guard.core.security.vault.WebAppCredentials
import com.foxhole.guard.core.settings.updateShowFirewallStatus
import com.foxhole.guard.core.settings.updateWebAppsDockScreen
import com.foxhole.guard.core.settings.updateWebAppsEnabled
import com.foxhole.guard.core.settings.updateWebAppsPollIntervalMinutes
import com.foxhole.guard.core.settings.updateWebAppsPushService
import com.foxhole.guard.core.webapps.isWebAppTunTransportReady
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.ui.cli.CliMainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Web apps settings toggles. The push service is the only one that touches the runtime: enabling
// it raises the firewall, so it repeats the side effects of the direct firewall toggle and
// reconciles guard.

internal fun HomeViewModel.onWebAppsEnabledChanged(value: Boolean) {
    openWebAppMutable.value = null
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
        if (
            !container.settingsRepository.current().webApps.enabled ||
            !container.connectionController.isConnectedRuntimeCurrent()
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

internal fun HomeViewModel.removeWebApp(id: Long) {
    viewModelScope.launch {
        if (openWebAppMutable.value?.id == id) {
            openWebAppMutable.value = null
        }
        container.webAppsRepository.remove(id)
    }
}

/** Opening the frame clears the app badge: its notifications count as read. */
internal fun HomeViewModel.openWebApp(id: Long) {
    viewModelScope.launch {
        val app = container.webAppsRepository.webApp(id) ?: return@launch
        if (
            !container.settingsRepository.current().webApps.enabled ||
            !container.connectionController.isConnectedRuntimeCurrent()
        ) {
            emitError(
                getApplication<Application>().getString(R.string.cli_webapps_route_required),
            )
            return@launch
        }
        container.webAppsRepository.resetBadge(app.id)
        openWebAppMutable.value = app
    }
}

internal fun HomeViewModel.closeWebApp() {
    openWebAppMutable.value = null
}

/** Destroy the WebView before a stopped/reconnecting TUN can fall back to the default network. */
internal fun HomeViewModel.startWebAppRouteSupervision() {
    viewModelScope.launch {
        container.connectionController.snapshot
            .map(::isWebAppTunTransportReady)
            .distinctUntilChanged()
            .collect { transportReady ->
                if (!transportReady) openWebAppMutable.value = null
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

// Web-app credentials live in the vault's AUTH section (AES-GCM, section key in Keystore). File
// IO stays off the main thread.

internal suspend fun HomeViewModel.loadWebAppCredentials(id: Long): WebAppCredentials? =
    withContext(Dispatchers.IO) {
        getApplication<FoxholeApplication>().appGraph.webAppCredentialsStore.load(id)
    }

/**
 * Returns true only if the credential actually reached the vault. The store throws (Keystore,
 * IO), and the earlier fire-and-forget killed the coroutine while the panel closed anyway — the
 * user believed the password was saved. Close the editor *only* on true.
 */
internal suspend fun HomeViewModel.saveWebAppCredentials(
    id: Long,
    login: String,
    password: String,
): Boolean {
    val saved =
        runCatching {
            withContext(Dispatchers.IO) {
                getApplication<FoxholeApplication>().appGraph.webAppCredentialsStore
                    .save(id, WebAppCredentials(login = login, password = password))
            }
        }.isSuccess
    if (saved) {
        emitSuccess(getApplication<Application>().getString(R.string.cli_webapps_creds_saved))
    } else {
        emitError(getApplication<Application>().getString(R.string.cli_webapps_creds_save_failed))
    }
    return saved
}

internal fun HomeViewModel.deleteWebAppCredentials(id: Long) {
    viewModelScope.launch(Dispatchers.IO) {
        getApplication<FoxholeApplication>().appGraph.webAppCredentialsStore.delete(id)
    }
}

/** Terminal note about an external link blocked in the frame: navigation is locked to the domain. */
internal fun HomeViewModel.notifyWebAppExternalBlocked(host: String) {
    viewModelScope.launch {
        emitInfo(getApplication<Application>().getString(R.string.cli_webapps_external_blocked, host))
    }
}

internal fun HomeViewModel.webAppIconFile(entity: WebAppEntity): java.io.File? =
    container.webAppsRepository.iconFile(entity)
