@file:Suppress("TooManyFunctions")

package com.foxhole.beta.ui

import android.app.Application
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
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.vpn.FoxholeVpnRuntimeBridge
import com.foxhole.beta.vpn.FoxholeVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale

internal fun HomeViewModel.importPresetTextInternal(
    raw: String,
    source: RoutingPresetSource,
) {
    viewModelScope.launch {
        runCatching { container.routingRepository.importPresetDocument(raw, source) }
            .onSuccess { emitSuccess(getApplication<Application>().getString(R.string.routing_preset_imported)) }
            .onFailure {
                emitError(
                    it.message ?: getApplication<Application>().getString(R.string.routing_preset_import_failed)
                )
            }
    }
}

internal fun HomeViewModel.refreshIpInfoInternal() {
    val snapshot = container.connectionController.snapshot.value
    if (snapshot.shouldRefreshDashboardConnectionMetrics()) {
        scheduleActiveProfileLatencyRefresh(
            showLoading = true,
            refreshImmediately = true,
            clearSelectedMetrics = true,
        )
    }
    startIpInfoRefresh(
        reportFailures = snapshot.shouldReportManualDashboardIpRefreshFailures(),
        showLoading = true,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.FULL,
        minimumLoadingDurationMs = HomeViewModel.MANUAL_IP_REFRESH_MIN_LOADING_MS,
        reason = IpInfoRefreshReason.MANUAL,
    )
}

internal fun HomeViewModel.refreshIpInfoSilentlyInternal() {
    startIpInfoRefresh(
        reportFailures = false,
        showLoading = false,
        clearExistingIp = false,
        fetchMode = IpInfoFetchMode.FULL,
        minimumLoadingDurationMs = 0L,
        reason = IpInfoRefreshReason.FOREGROUND,
    )
}

@Suppress("CyclomaticComplexMethod", "TooGenericExceptionCaught")
internal fun HomeViewModel.refreshIpInfoInternalInternal(
    reportFailures: Boolean,
    showLoading: Boolean,
    clearExistingIp: Boolean,
    fetchMode: IpInfoFetchMode,
    minimumLoadingDurationMs: Long = 0L,
    reason: IpInfoRefreshReason = IpInfoRefreshReason.FOREGROUND,
    onPublished: (suspend (IpInfo) -> Unit)? = null,
) {
    val refreshToken = invalidateIpInfoRefreshes()
    ipInfoRefreshJob =
        viewModelScope.launch {
            val target = ipInfoRefreshTargetForSnapshot(container.connectionController.snapshot.value)
            activeIpInfoRefreshReason = reason
            var publishedInfo = false
            container.diagnosticsLogger.record(
                "ip",
                "dashboard refresh started id=$refreshToken reason=${reason.name.lowercase()} mode=${fetchMode.name.lowercase()} target=${target.name.lowercase()} showLoading=$showLoading clearExistingIp=$clearExistingIp",
            )
            if (showLoading) {
                ipInfoLoadingMutable.value = true
            }
            val loadingStartedAtMs = if (showLoading) SystemClock.elapsedRealtime() else 0L
            if (shouldClearExistingIpForRefresh(reason = reason, clearExistingIp = clearExistingIp)) {
                FoxholeVpnRuntimeBridge.updateIpInfo(null)
            }
            try {
                val info = refreshIpInfoForReason(fetchMode = fetchMode, reason = reason)
                if (ipInfoRefreshToken == refreshToken) {
                    val currentTarget = ipInfoRefreshTargetForSnapshot(container.connectionController.snapshot.value)
                    if (!shouldPublishDashboardIpRefresh(target, currentTarget, reason)) {
                        container.diagnosticsLogger.record(
                            "ip",
                            "dashboard refresh ignored stale target id=$refreshToken reason=${reason.name.lowercase()} started=${target.name.lowercase()} current=${currentTarget.name.lowercase()}",
                        )
                        return@launch
                    }
                    if (reason == IpInfoRefreshReason.TOR_ROUTE) {
                        publishTorIpInfoFromDashboardRefresh(info)
                    } else {
                        if (container.connectionController.snapshot.value.shouldPublishDeviceIpInfoFromDashboardRefresh()) {
                            FoxholeVpnRuntimeBridge.updateDeviceIpInfo(info)
                        }
                        FoxholeVpnRuntimeBridge.updateIpInfo(info)
                        publishTorIpInfoFromDashboardRefresh(info)
                    }
                    publishedInfo = true
                    container.diagnosticsLogger.record(
                        "ip",
                        "geo refreshed id=$refreshToken reason=${reason.name.lowercase()} target=${target.name.lowercase()}",
                    )
                    onPublished?.invoke(info)
                }
            } catch (cancelled: CancellationException) {
                container.diagnosticsLogger.record(
                    "ip",
                    "dashboard refresh cancelled id=$refreshToken reason=${reason.name.lowercase()}",
                )
                throw cancelled
            } catch (error: Throwable) {
                container.diagnosticsLogger.record(
                    "ip",
                    "geo refresh failed: ${error.javaClass.simpleName}: ${error.message.orEmpty()}",
                )
                if (reportFailures) {
                    emitError(getApplication<Application>().getString(R.string.ip_info_failed))
                }
            } finally {
                if (showLoading && ipInfoRefreshToken == refreshToken) {
                    val elapsedLoadingMs = SystemClock.elapsedRealtime() - loadingStartedAtMs
                    val remainingLoadingMs = minimumLoadingDurationMs - elapsedLoadingMs
                    if (remainingLoadingMs > 0L) {
                        runCatching { delay(remainingLoadingMs) }
                    }
                }
                if (showLoading && ipInfoRefreshToken == refreshToken) {
                    ipInfoLoadingMutable.value = false
                }
                if (
                    reason == IpInfoRefreshReason.POST_CONNECT &&
                    !publishedInfo &&
                    ipInfoRefreshToken == refreshToken
                ) {
                    container.diagnosticsLogger.record(
                        "latency",
                        "post-connect latency scheduled after ip refresh finished without publication",
                    )
                    schedulePostConnectLatencyRefreshAfterIp(reason)
                }
                container.diagnosticsLogger.record(
                    "ip",
                    "dashboard refresh finished id=$refreshToken reason=${reason.name.lowercase()} loading=${ipInfoLoadingMutable.value}",
                )
                if (ipInfoRefreshToken == refreshToken) {
                    ipInfoRefreshJob = null
                    activeIpInfoRefreshReason = null
                }
            }
        }
}

private suspend fun HomeViewModel.refreshIpInfoForReason(
    fetchMode: IpInfoFetchMode,
    reason: IpInfoRefreshReason,
): com.foxhole.beta.core.model.IpInfo {
    val attempts = ipInfoRefreshAttemptsForReason(reason)
    val retryDelayMs = ipInfoRefreshRetryDelayMsForReason(reason)
    var lastError: Throwable? = null
    repeat(attempts) { attemptIndex ->
        val infoResult = runCatching { container.connectionController.refreshIpInfo(fetchMode = fetchMode) }
        val info = infoResult.getOrNull()
        infoResult.exceptionOrNull()?.let { error ->
            if (error is CancellationException) {
                throw error
            }
            lastError = error
        }
        if (info != null && (reason != IpInfoRefreshReason.TOR_ROUTE || canAcceptTorRouteIpRefresh(info))) {
            return info
        }
        if (reason == IpInfoRefreshReason.TOR_ROUTE && info != null) {
            lastError = IllegalStateException("tor route ip not ready")
        }
        if (attempts > 1) {
            container.diagnosticsLogger.record(
                "ip",
                "${reason.name.lowercase()} ip refresh attempt ${attemptIndex + 1}/$attempts not ready: ${lastError?.javaClass?.simpleName.orEmpty()}",
            )
        }
        if (attemptIndex < attempts - 1) {
            delay(retryDelayMs)
        }
    }
    lastError?.let { throw it }
    error("ip refresh failed")
}

internal fun ipInfoRefreshAttemptsForReason(reason: IpInfoRefreshReason): Int =
    when (reason) {
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
        -> HomeViewModel.CONNECTED_IP_REFRESH_ATTEMPTS
        IpInfoRefreshReason.TOR_ROUTE -> HomeViewModel.TOR_IP_REFRESH_ATTEMPTS
        IpInfoRefreshReason.MANUAL,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> 1
    }

internal fun ipInfoRefreshRetryDelayMsForReason(reason: IpInfoRefreshReason): Long =
    when (reason) {
        IpInfoRefreshReason.POST_CONNECT,
        IpInfoRefreshReason.RESTORED_VPN,
        IpInfoRefreshReason.NETWORK_CHANGE,
        -> HomeViewModel.CONNECTED_IP_REFRESH_RETRY_DELAY_MS
        IpInfoRefreshReason.TOR_ROUTE -> HomeViewModel.TOR_IP_REFRESH_RETRY_DELAY_MS
        IpInfoRefreshReason.MANUAL,
        IpInfoRefreshReason.FOREGROUND,
        IpInfoRefreshReason.POST_UPDATE,
        -> 0L
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
            RoutingRuleAction.BLOCK -> "Foxhole blocked site"
            RoutingRuleAction.PROXY,
            RoutingRuleAction.DIRECT,
            -> "Foxhole selected site"
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
        putExtra(
            Intent.EXTRA_SUBJECT,
            getApplication<Application>().getString(R.string.export_diagnostics_share_subject)
        )
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
        setDashboardConnectionMetricsLoading(true)
        container.connectionController.disconnect(suppressLocalGuard = true)
        waitForRuntimeDisconnect()
        connectNow(profileId)
        true
    }.onFailure {
        setDashboardConnectionMetricsLoading(false)
        if (it is CancellationException) {
            container.diagnosticsLogger.record("connection", "profile reconnect cancelled")
        } else {
            emitError(runtimeConnectionFailureMessage(it))
        }
    }.getOrDefault(false)
}

internal fun HomeViewModel.updateRuntimeSettingAndMaybeReconnectInternal(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val reconnectProfileId =
            activeRuntimeProfileIdForReload()
                ?.takeIf {
                    container.connectionController.snapshot.value.state in HomeViewModel.ACTIVE_CONNECTION_STATES
                }
        if (reconnectProfileId != null) {
            markRuntimeReloadPending()
        }
        runCatching {
            updateAction()
            if (reconnectProfileId != null) {
                setDashboardConnectionMetricsLoading(true)
                container.connectionController.disconnect(suppressLocalGuard = true)
                waitForRuntimeDisconnect()
                connectNow(reconnectProfileId)
            }
        }.onFailure {
            setDashboardConnectionMetricsLoading(false)
            clearRuntimeReloadPending()
            if (it is CancellationException) {
                container.diagnosticsLogger.record("connection", "runtime setting reconnect cancelled")
            } else {
                emitError(runtimeConnectionFailureMessage(it))
            }
        }
    }
}

internal fun HomeViewModel.updateRuntimeSettingAndMaybeReloadInternal(
    updateAction: suspend () -> Unit,
) {
    viewModelScope.launch {
        val shouldSuppressReconnectWarning =
            activeRuntimeProfileIdForReload() != null &&
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
    val targetProfileId = activeRuntimeProfileIdForReload() ?: return false
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
        scheduleDashboardRefreshAfterRuntimeReload()
        return true
    }
    return false
}

internal fun HomeViewModel.activeRuntimeProfileIdForReload(): Long? {
    val snapshot = container.connectionController.snapshot.value
    return resolveActiveRuntimeProfileIdForReload(
        snapshot = snapshot,
        activeProfileId = uiState.value.activeProfile?.id,
    )
}

internal fun resolveActiveRuntimeProfileIdForReload(
    snapshot: com.foxhole.beta.core.model.ConnectionSnapshot,
    activeProfileId: Long?,
): Long? =
    when {
        snapshot.state !in HomeViewModel.ACTIVE_CONNECTION_STATES -> null
        snapshot.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID -> FoxholeVpnService.TOR_ONLY_PROFILE_ID
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID -> null
        snapshot.profileId == activeProfileId -> activeProfileId
        else -> null
    }

internal fun HomeViewModel.connectInternal(
    profileId: Long,
    protocolOptionId: String? = null,
) {
    setDashboardConnectionMetricsLoading(true)
    viewModelScope.launch {
        runCatching {
            if (
                profileId != FoxholeVpnService.TOR_ONLY_PROFILE_ID &&
                uiState.value.activeProfile?.id != profileId
            ) {
                container.connectionController.setActiveProfile(profileId)
                val updated = container.profileRepository.getProfile(profileId)?.copy(isActive = true)
                startupActiveProfileMutable.value = updated
            }
            connectNow(profileId, protocolOptionId = protocolOptionId)
        }
            .onFailure {
                setDashboardConnectionMetricsLoading(false)
                if (it is CancellationException) {
                    container.diagnosticsLogger.record("connection", "connect cancelled")
                } else {
                    emitError(runtimeConnectionFailureMessage(it))
                }
            }
    }
}

private suspend fun HomeViewModel.waitForRuntimeDisconnect() {
    val stopped =
        withTimeoutOrNull(RUNTIME_RECONNECT_DISCONNECT_TIMEOUT_MS) {
            container.connectionController.snapshot.first { snapshot ->
                snapshot.state == ConnectionState.IDLE || snapshot.state == ConnectionState.ERROR
            }
            true
        } == true
    if (!stopped) {
        error(getApplication<Application>().getString(R.string.error_runtime_stopped))
    }
}

internal fun HomeViewModel.runtimeConnectionFailureMessage(error: Throwable): String {
    val app = getApplication<Application>()
    val message = error.message.orEmpty()
    return when {
        message.contains("Unexpected JSON token", ignoreCase = true) ||
            message.contains("stored profile config is not valid JSON", ignoreCase = true) ||
            message.contains("profile has no resolved config", ignoreCase = true) ->
            app.getString(R.string.profile_config_invalid_reimport)

        else -> localizedVpnRuntimeMessage(message, app) ?: error.message ?: app.getString(R.string.error_runtime_missing)
    }
}

private fun localizedVpnRuntimeMessage(
    message: String,
    app: Application,
): String? {
    val normalized = message.lowercase(Locale.US)
    return when {
        normalized.contains("reality verification failed") ->
            app.getString(R.string.vpn_error_reality_verification_failed)

        normalized.contains("certificate verify failed") ||
            normalized.contains("certpath") ||
            normalized.contains("certificate") && normalized.contains("verify") ->
            app.getString(R.string.vpn_error_certificate_verify_failed)

        normalized.contains("timeout") ||
            normalized.contains("timed out") ||
            normalized.contains("connection refused") ||
            normalized.contains("network is unreachable") ->
            app.getString(R.string.vpn_error_server_timeout)

        else -> null
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

private const val RUNTIME_RECONNECT_DISCONNECT_TIMEOUT_MS = 12_000L

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
    supersedeIpInfoRefreshesForConnect()
    requestNotificationPermission.tryEmit(Unit)
    if (profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID) {
        container.connectionController.connectTorOnly(statusMessage = statusMessage)
        return
    }
    warnIfTorRouteCannotRunForProfile(profileId, protocolOptionId)
    container.connectionController.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
    )
}

private fun HomeViewModel.supersedeIpInfoRefreshesForConnect() {
    if (shouldSupersedeIpRefreshForConnect(activeIpInfoRefreshReason)) {
        pendingPostConnectIpRefresh = true
        container.diagnosticsLogger.record(
            "ip",
            "dashboard refresh superseded by connect reason=${activeIpInfoRefreshReason?.name?.lowercase().orEmpty()}",
        )
    }
    invalidateIpInfoRefreshes()
}

private suspend fun HomeViewModel.warnIfTorRouteCannotRunForProfile(
    profileId: Long,
    protocolOptionId: String?,
) {
    val settings = container.settingsRepository.current()
    if (settings.privacyRoute.mode != PrivacyRouteMode.TOR_OVER_VPN || settings.privacyRoute.bypassVpnTunnel) {
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
    activeIpInfoRefreshReason = null
    ipInfoLoadingMutable.value = false
    ipInfoRefreshToken += 1
    return ipInfoRefreshToken
}

internal fun HomeViewModel.scheduleConnectedIpRefreshInternal(
    reason: IpInfoRefreshReason = IpInfoRefreshReason.POST_CONNECT,
    clearExistingIp: Boolean = false,
    showLoading: Boolean = false,
    minimumLoadingDurationMs: Long = 0L,
) {
    connectedIpRefreshJob?.cancel()
    connectedIpRefreshJob =
        viewModelScope.launch {
            connectedIpRefreshStartDelayMs(reason).takeIf { it > 0L }?.let { delay(it) }
            if (container.connectionController.snapshot.value.state != ConnectionState.CONNECTED) {
                return@launch
            }
            if (ipInfoRefreshJob != null) {
                container.diagnosticsLogger.record(
                    "ip",
                    "post-connect refresh replacing active refresh reason=${activeIpInfoRefreshReason?.name?.lowercase().orEmpty()}",
                )
                invalidateIpInfoRefreshes()
            }
            pendingPostConnectIpRefresh = false
            val snapshot = container.connectionController.snapshot.value
            val currentIpInfo = container.connectionController.ipInfo.value
            val fetchMode =
                if (
                    shouldUseFullDashboardIpRefresh(
                        reason = reason,
                        snapshot = snapshot,
                        currentIpInfo = currentIpInfo,
                    )
                ) {
                    IpInfoFetchMode.FULL
                } else {
                    ipInfoFetchModeForRefreshReason(reason)
                }
            val effectiveShowLoading =
                showLoading ||
                    shouldShowDashboardIpRefreshLoading(
                        reason = reason,
                        snapshot = snapshot,
                        currentIpInfo = currentIpInfo,
                    )
            val effectiveMinimumLoadingDurationMs =
                if (effectiveShowLoading && minimumLoadingDurationMs <= 0L) {
                    HomeViewModel.AUTO_IP_REFRESH_MIN_LOADING_MS
                } else {
                    minimumLoadingDurationMs
                }
            startIpInfoRefresh(
                reportFailures = false,
                showLoading = effectiveShowLoading,
                clearExistingIp = clearExistingIp,
                fetchMode = fetchMode,
                minimumLoadingDurationMs = effectiveMinimumLoadingDurationMs,
                reason = reason,
                onPublished = {
                    schedulePostConnectLatencyRefreshAfterIp(reason)
                },
            )
        }
}

internal fun connectedIpRefreshStartDelayMs(reason: IpInfoRefreshReason): Long =
    when (reason) {
        IpInfoRefreshReason.NETWORK_CHANGE -> 0L
        else -> HomeViewModel.CONNECTED_IP_REFRESH_DELAY_MS
    }

private fun HomeViewModel.schedulePostConnectLatencyRefreshAfterIp(reason: IpInfoRefreshReason) {
    if (reason != IpInfoRefreshReason.POST_CONNECT) {
        return
    }
    postConnectLatencyRefreshJob?.cancel()
    postConnectLatencyRefreshJob =
        viewModelScope.launch {
            delay(HomeViewModel.POST_CONNECT_LATENCY_AFTER_IP_DELAY_MS)
            postConnectLatencyRefreshJob = null
            if (
                container.connectionController.snapshot.value.state == ConnectionState.CONNECTED &&
                !autoConnectUiStateMutable.value.running &&
                container.connectionController.snapshot.value.shouldRefreshDashboardConnectionMetrics()
            ) {
                scheduleActiveProfileLatencyRefresh(showLoading = false, refreshImmediately = true)
            }
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

internal fun HomeViewModel.loadInstalledAppsInternal(force: Boolean = false) {
    if (!force && (installedAppsLoadedMutable.value || installedAppsLoadingMutable.value)) {
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
                                label = applicationInfo.loadLabel(
                                    packageManager
                                )?.toString().orEmpty().ifBlank { packageName },
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
            container.settingsRepository.recordInstalledAppInventory(installed)
        } catch (error: RuntimeException) {
            container.diagnosticsLogger.record(
                "apps",
                "installed app visibility query failed: ${error.message.orEmpty()}",
            )
            installedAppsMutable.value = emptyList()
            installedAppsLoadedMutable.value = false
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
            scheduleForegroundDashboardRefreshIfStale()
        }
    } else {
        clearProfileLatencyRefresh()
    }
}
