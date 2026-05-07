package com.foxhole.beta.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.InstalledAppOption
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.vpn.FoxholeVpnRuntimeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal fun HomeViewModel.importPresetTextInternal(
    raw: String,
    source: RoutingPresetSource,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.importPresetDocument(raw, source) }
            .onSuccess { emitSuccess(getApplication<Application>().getString(R.string.routing_preset_imported)) }
            .onFailure { emitError(it.message ?: getApplication<Application>().getString(R.string.routing_preset_import_failed)) }
    }
}

internal fun HomeViewModel.refreshIpInfoInternal() {
    startIpInfoRefresh(
        reportFailures = true,
        showLoading = true,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.FULL,
        minimumLoadingDurationMs = HomeViewModel.MANUAL_IP_REFRESH_MIN_LOADING_MS,
    )
}

internal fun HomeViewModel.refreshIpInfoSilentlyInternal() {
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = false,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.FULL,
        minimumLoadingDurationMs = 0L,
    )
}

internal fun HomeViewModel.refreshIpInfoInternalInternal(
    reportFailures: Boolean,
    showLoading: Boolean,
    clearExistingIp: Boolean,
    fetchMode: IpInfoFetchMode,
    minimumLoadingDurationMs: Long = 0L,
) {
    val refreshToken = invalidateIpInfoRefreshes()
    ipInfoRefreshJob =
        viewModelScope.launch {
            container.diagnosticsLogger.record(
                "ip",
                "dashboard refresh started mode=${fetchMode.name.lowercase()} showLoading=$showLoading clearExistingIp=$clearExistingIp",
            )
            if (showLoading) {
                ipInfoLoadingMutable.value = true
            }
            val loadingStartedAtMs = if (showLoading) SystemClock.elapsedRealtime() else 0L
            if (clearExistingIp) {
                FoxholeVpnRuntimeBridge.updateIpInfo(null)
            }
            try {
                runCatching { container.connectionController.refreshIpInfo(fetchMode = fetchMode) }
                    .onSuccess { info ->
                        if (ipInfoRefreshToken == refreshToken) {
                            FoxholeVpnRuntimeBridge.updateIpInfo(info)
                            container.diagnosticsLogger.record("ip", "geo refreshed")
                        }
                    }
                    .onFailure {
                        container.diagnosticsLogger.record(
                            "ip",
                            "geo refresh failed: ${it.javaClass.simpleName}: ${it.message.orEmpty()}",
                        )
                        if (reportFailures) {
                            emitError(getApplication<Application>().getString(R.string.ip_info_failed))
                        }
                    }
            } finally {
                if (showLoading && ipInfoRefreshToken == refreshToken) {
                    val elapsedLoadingMs = SystemClock.elapsedRealtime() - loadingStartedAtMs
                    val remainingLoadingMs = minimumLoadingDurationMs - elapsedLoadingMs
                    if (remainingLoadingMs > 0L) {
                        delay(remainingLoadingMs)
                    }
                }
                if (showLoading && ipInfoRefreshToken == refreshToken) {
                    ipInfoLoadingMutable.value = false
                }
                container.diagnosticsLogger.record(
                    "ip",
                    "dashboard refresh finished loading=${ipInfoLoadingMutable.value}",
                )
                if (ipInfoRefreshToken == refreshToken) {
                    ipInfoRefreshJob = null
                }
            }
        }
}

internal suspend fun HomeViewModel.getResolvedConfigInternal(
    profileId: Long,
    protocolOptionIdOverride: String? = null,
): String = container.profileRepository.getResolvedConfig(profileId, protocolOptionIdOverride)

internal suspend fun HomeViewModel.updateResolvedConfigInternal(
    profileId: Long,
    editedJson: String,
    reconnectAfterSave: Boolean = false,
    protocolOptionIdOverride: String? = null,
): Boolean =
    runCatching {
        container.profileRepository.updateResolvedConfig(profileId, editedJson, protocolOptionIdOverride)
    }.onSuccess {
        val reconnected = reconnectProfileIfRequested(profileId, reconnectAfterSave)
        val message =
            if (reconnected) {
                getApplication<Application>().getString(R.string.profile_config_saved_reconnecting)
            } else if (uiState.value.activeProfile?.id == profileId &&
                uiState.value.connection.state in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.RECONNECTING)
            ) {
                getApplication<Application>().getString(R.string.reconnect_required)
            } else {
                getApplication<Application>().getString(R.string.profile_config_saved)
            }
        if (message == getApplication<Application>().getString(R.string.reconnect_required)) {
            emitInfo(message)
        } else {
            emitSuccess(message)
        }
    }.onFailure {
        emitError(it.message ?: getApplication<Application>().getString(R.string.profile_config_save_failed))
    }.isSuccess

internal fun HomeViewModel.saveSiteRuleInternal(
    ruleId: Long?,
    domains: List<String>,
    action: RoutingRuleAction,
) {
    viewModelScope.launch {
        runCatching {
            val normalizedTokens =
                domains
                    .mapNotNull(::normalizedSiteMaskToken)
                    .distinct()
            require(normalizedTokens.isNotEmpty()) {
                getApplication<Application>().getString(R.string.site_exception_validation_error)
            }
            require(normalizedTokens.all { siteMaskValidationErrorRes(it) == null }) {
                getApplication<Application>().getString(R.string.site_exception_invalid_error)
            }
            val normalizedDomains = normalizedTokens.filterNot { it.startsWith(SITE_CIDR_PREFIX) }
            val normalizedIpCidrs =
                normalizedTokens
                    .filter { it.startsWith(SITE_CIDR_PREFIX) }
                    .map { it.removePrefix(SITE_CIDR_PREFIX) }
            val presetId =
                uiState.value.activePreset?.id ?: container.routingRepository.createPreset(
                    name = getApplication<Application>().getString(R.string.local_rules_preset_name),
                    activate = true,
                )
            container.routingRepository.upsertRule(
                presetId = presetId,
                ruleId = ruleId,
                name = siteRuleName(action, normalizedTokens.firstOrNull()),
                enabled = true,
                order = uiState.value.activePreset?.rules?.firstOrNull { it.id == ruleId }?.order,
                action = action,
                matchDomains = normalizedDomains,
                matchIpCidrs = normalizedIpCidrs,
                matchPorts = emptyList(),
                matchProtocols = emptyList(),
                matchNetworks = emptyList(),
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
            emitSuccess(getApplication<Application>().getString(R.string.routing_rule_saved))
        }.onFailure {
            emitError(it.message ?: getApplication<Application>().getString(R.string.routing_rule_save_failed))
        }
    }
}

internal fun HomeViewModel.onSiteRuleMovedInternal(
    ruleId: Long,
    action: RoutingRuleAction,
    ruleIdsInOrder: List<Long>,
) {
    viewModelScope.launch {
        runCatching {
            val rule = uiState.value.activePreset?.rules?.firstOrNull { it.id == ruleId }
                ?: error(getApplication<Application>().getString(R.string.routing_rule_save_failed))
            val firstToken = (rule.matchDomains + rule.matchIpCidrs.map { "$SITE_CIDR_PREFIX$it" }).firstOrNull()
            container.routingRepository.updateRuleActionAndOrder(
                ruleId = ruleId,
                name = siteRuleName(action, firstToken),
                action = action,
                ruleIdsInOrder = ruleIdsInOrder,
            )
        }.onSuccess {
            maybeReloadActiveRuntime()
        }.onFailure {
            emitError(it.message ?: getApplication<Application>().getString(R.string.routing_rule_save_failed))
        }
    }
}

private const val SITE_CIDR_PREFIX = "cidr:"

private fun HomeViewModel.siteRuleName(
    action: RoutingRuleAction,
    token: String?,
): String {
    val prefix =
        when (action) {
            RoutingRuleAction.BLOCK -> "FoxHole blocked site"
            RoutingRuleAction.PROXY,
            RoutingRuleAction.DIRECT,
            -> "FoxHole selected site"
        }
    return "$prefix: ${token ?: getApplication<Application>().getString(R.string.site_exception_default_name)}"
}

internal fun HomeViewModel.createDiagnosticsArchiveInternal(sanitize: Boolean = true): File =
    container.diagnosticsLogger.createExportFile(sanitize = sanitize)

internal fun HomeViewModel.exportDiagnosticsInternal(file: File = createDiagnosticsArchive()): Intent {
    val uri =
        FileProvider.getUriForFile(
            getApplication(),
            "${getApplication<Application>().packageName}.fileprovider",
            file,
        )
    return Intent(Intent.ACTION_SEND).apply {
        type = "application/gzip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, getApplication<Application>().getString(R.string.export_diagnostics_share_subject))
        putExtra(Intent.EXTRA_TEXT, getApplication<Application>().getString(R.string.export_diagnostics_share_text))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal suspend fun HomeViewModel.reconnectProfileIfRequestedInternal(
    profileId: Long,
    reconnectNow: Boolean,
): Boolean {
    val shouldReconnect =
        reconnectNow &&
            uiState.value.activeProfile?.id == profileId &&
            uiState.value.connection.state in HomeViewModel.ACTIVE_CONNECTION_STATES
    if (!shouldReconnect) {
        return false
    }

    return runCatching {
        dashboardConnectionMetricsLoadingMutable.value = true
        container.connectionController.disconnect()
        container.connectionController.snapshot.first { snapshot ->
            snapshot.state == ConnectionState.IDLE || snapshot.state == ConnectionState.ERROR
        }
        connectNow(profileId)
        true
    }.onFailure {
        dashboardConnectionMetricsLoadingMutable.value = false
        emitError(runtimeConnectionFailureMessage(it))
    }.getOrDefault(false)
}

internal fun HomeViewModel.updateRuntimeSettingAndMaybeReloadInternal(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val shouldSuppressReconnectWarning =
            uiState.value.activeProfile != null &&
                container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES
        if (shouldSuppressReconnectWarning) {
            markRuntimeReloadPending()
        }
        updateAction()
        val reloadRequested = maybeReloadActiveRuntime()
        if (shouldSuppressReconnectWarning && !reloadRequested) {
            clearRuntimeReloadPending()
        }
    }
}

internal suspend fun HomeViewModel.maybeReloadActiveRuntimeInternal(): Boolean {
    if (runtimeReconnectRequiredMutable.value) {
        return false
    }
    val targetProfileId = uiState.value.activeProfile?.id ?: return false
    val connectionState = container.connectionController.snapshot.value.state
    if (connectionState !in HomeViewModel.ACTIVE_CONNECTION_STATES) {
        return false
    }
    val appliedFingerprint = container.connectionController.appliedRuntimeSignature.value
    val currentFingerprint = container.connectionController.currentRuntimeFingerprint()
    if (appliedFingerprint == currentFingerprint) {
        return false
    }
    if (container.connectionController.reload(targetProfileId)) {
        return true
    }
    return false
}

internal fun HomeViewModel.connectInternal(profileId: Long) {
    dashboardConnectionMetricsLoadingMutable.value = true
    viewModelScope.launch {
        runCatching { connectNow(profileId) }
            .onFailure {
                dashboardConnectionMetricsLoadingMutable.value = false
                emitError(runtimeConnectionFailureMessage(it))
            }
    }
}

private fun HomeViewModel.runtimeConnectionFailureMessage(error: Throwable): String {
    val app = getApplication<Application>()
    val message = error.message.orEmpty()
    return when {
        message.contains("Unexpected JSON token", ignoreCase = true) ||
            message.contains("stored profile config is not valid JSON", ignoreCase = true) ||
            message.contains("profile has no resolved config", ignoreCase = true) ->
            app.getString(R.string.profile_config_invalid_reimport)

        else -> error.message ?: app.getString(R.string.error_runtime_missing)
    }
}

internal fun HomeViewModel.infoBannerInternal(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.INFO,
    )

internal fun HomeViewModel.errorBannerInternal(stringRes: Int): FoxholeBannerEvent =
    FoxholeBannerEvent(
        message = getApplication<Application>().getString(stringRes),
        tone = FoxholeBannerTone.ERROR,
    )

internal suspend fun HomeViewModel.emitInfoInternal(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.INFO,
        ),
    )
}

internal suspend fun HomeViewModel.emitSuccessInternal(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.SUCCESS,
        ),
    )
}

internal suspend fun HomeViewModel.emitErrorInternal(message: String) {
    snackbars.emit(
        FoxholeBannerEvent(
            message = message,
            tone = FoxholeBannerTone.ERROR,
        ),
    )
}

internal suspend fun HomeViewModel.connectNowInternal(
    profileId: Long,
    protocolOptionId: String? = null,
    statusMessage: String? = null,
    isSmartStartConnection: Boolean = false,
    previousVpnNetworkHandle: Long? = null,
) {
    invalidateIpInfoRefreshes()
    warnIfTorRouteCannotRunForProfile(profileId, protocolOptionId)
    container.connectionController.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

private suspend fun HomeViewModel.warnIfTorRouteCannotRunForProfile(
    profileId: Long,
    protocolOptionId: String?,
) {
    val settings = container.settingsRepository.current()
    if (settings.privacyRoute.mode != PrivacyRouteMode.TOR_OVER_VPN) {
        return
    }
    val profile = container.profileRepository.getProfile(profileId) ?: return
    val protocolHint = profile.runtimeProtocolHint(protocolOptionId)
    if (protocolHint.isUdpTransport()) {
        snackbars.emit(errorBanner(R.string.privacy_route_udp_warning))
    }
}

private fun Profile.runtimeProtocolHint(protocolOptionId: String?) =
    (
        protocolOptionId
            ?.takeIf(String::isNotBlank)
            ?.let { requestedId -> protocolOptions.firstOrNull { option -> option.id == requestedId } }
            ?: selectedProtocolOptionId
                ?.takeIf(String::isNotBlank)
                ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
            ?: protocolOptions.firstOrNull()
    )?.protocolHint ?: protocolHint

internal fun HomeViewModel.invalidateIpInfoRefreshesInternal(): Long {
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob = null
    ipInfoRefreshJob?.cancel()
    ipInfoRefreshJob = null
    ipInfoLoadingMutable.value = false
    ipInfoRefreshToken += 1
    return ipInfoRefreshToken
}

internal fun HomeViewModel.scheduleConnectedIpRefreshInternal() {
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob =
        viewModelScope.launch {
            delay(HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS)
            if (
                container.connectionController.snapshot.value.state != ConnectionState.CONNECTED ||
                ipInfoRefreshJob != null
            ) {
                return@launch
            }
            startIpInfoRefresh(
                reportFailures = true,
                showLoading = false,
                clearExistingIp = false,
                fetchMode = IpInfoFetchMode.FULL,
                minimumLoadingDurationMs = 0L,
            )
        }
}

internal fun HomeViewModel.markRuntimeReloadPendingInternal() {
    runtimeReloadPendingJob?.cancel()
    runtimeReloadPendingMutable.value = true
    runtimeReloadPendingJob =
        viewModelScope.launch {
            delay(HomeViewModel.RUNTIME_RELOAD_PENDING_TIMEOUT_MS)
            runtimeReloadPendingMutable.value = false
            runtimeReloadPendingJob = null
        }
}

internal fun HomeViewModel.clearRuntimeReloadPendingInternal() {
    runtimeReloadPendingJob?.cancel()
    runtimeReloadPendingJob = null
    runtimeReloadPendingMutable.value = false
}

internal fun HomeViewModel.markRuntimeReconnectRequiredInternal() {
    runtimeReconnectRequiredMutable.value = true
}

internal fun HomeViewModel.clearRuntimeReconnectRequiredInternal() {
    runtimeReconnectRequiredMutable.value = false
}

internal fun HomeViewModel.loadInstalledAppsInternal() {
    if (installedAppsLoadedMutable.value || installedAppsLoadingMutable.value) {
        return
    }
    viewModelScope.launch {
        installedAppsLoadingMutable.value = true
        try {
            val installed =
                withContext(Dispatchers.IO) {
                    val packageManager = getApplication<Application>().packageManager
                    val installedApplications =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            packageManager.getInstalledApplications(
                                PackageManager.ApplicationInfoFlags.of(0),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            packageManager.getInstalledApplications(0)
                        }
                    installedApplications
                        .asSequence()
                        .filter { applicationInfo ->
                            packageManager.getLaunchIntentForPackage(applicationInfo.packageName) != null ||
                                applicationInfo.enabled
                        }
                        .associateBy(ApplicationInfo::packageName)
                        .values
                        .filterNot { it.packageName == getApplication<Application>().packageName }
                        .map { applicationInfo ->
                            val packageName = applicationInfo.packageName
                            val flags = applicationInfo.flags
                            val isSystemApp =
                                flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
                                    flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                            InstalledAppOption(
                                packageName = packageName,
                                label = applicationInfo.loadLabel(packageManager)?.toString().orEmpty().ifBlank { packageName },
                                isSystemApp = isSystemApp,
                            )
                        }.sortedWith(
                            compareBy<InstalledAppOption>(
                                { it.isSystemApp },
                                { it.label.lowercase() },
                                { it.packageName.lowercase() },
                            ),
                        )
            }
            installedAppsMutable.value = installed
            installedAppsLoadedMutable.value = true
        } catch (error: RuntimeException) {
            container.diagnosticsLogger.record(
                "apps",
                "installed app visibility query failed: ${error.message.orEmpty()}",
            )
            installedAppsMutable.value = emptyList()
            installedAppsLoadedMutable.value = true
        } finally {
            installedAppsLoadingMutable.value = false
        }
    }
}

internal fun HomeViewModel.ensureInstalledAppsLoadedInternal() {
    loadInstalledApps()
}

internal fun HomeViewModel.onTrafficUiVisibilityChangedInternal(visible: Boolean) {
    FoxholeVpnRuntimeBridge.setHighFrequencyTrafficUpdates(visible)
    if (visible) {
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        val connectionState = container.connectionController.snapshot.value.state
        if (connectionState == ConnectionState.CONNECTED && !autoConnectUiStateMutable.value.running) {
            scheduleActiveProfileLatencyRefresh()
            scheduleConnectedIpRefresh()
        } else if (
            connectionState in setOf(ConnectionState.IDLE, ConnectionState.ERROR) &&
            ipInfoRefreshJob == null &&
            !ipInfoLoadingMutable.value
        ) {
            startIpInfoRefresh(
                reportFailures = false,
                showLoading = true,
                clearExistingIp = false,
                fetchMode = IpInfoFetchMode.ENTRY_QUICK,
                minimumLoadingDurationMs = 0L,
            )
        }
    } else {
        clearProfileLatencyRefresh()
    }
}
