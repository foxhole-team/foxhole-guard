package com.foxhole.guard.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.Settings
import com.foxhole.core.network.NetworkFingerprint
import com.foxhole.core.network.isCellularOrMetered
import com.foxhole.core.network.scopedByNetworkRules
import com.foxhole.guard.R
import com.foxhole.guard.core.security.LockState
import com.foxhole.guard.runtime.FoxholeVpnService
import com.foxhole.guard.runtime.NetworkRuleCommandRequest
import com.foxhole.guard.runtime.NetworkRuleCommandRequests
import com.foxhole.guard.runtime.NetworkRuleRecommendationNotifier
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun HomeViewModel.currentNetworkFingerprintForSmartRules(): NetworkFingerprint? {
    val fingerprint = container.networkFingerprintProvider.currentFingerprint() ?: return null
    return fingerprint.scopedByNetworkRules(controlUiState.value.settings.networkRules)
}

internal fun HomeViewModel.shouldSkipSpeedTestsOnCurrentNetwork(): Boolean {
    val networkRules = controlUiState.value.settings.networkRules
    if (!networkRules.skipSpeedTestsOnCellular) {
        return false
    }
    return container.networkFingerprintProvider.currentFingerprint()?.isCellularOrMetered() == true
}

internal fun HomeViewModel.currentNetworkProfileOverride(state: HomeUiState = controlUiState.value): NetworkProfileOverride? {
    val networkRules = state.settings.networkRules
    val fingerprint = container.networkFingerprintProvider.currentFingerprint() ?: return null
    return when {
        fingerprint.transport == "wifi" &&
            networkRules.wifiRulesEnabled &&
            networkRules.useWifiProfile ->
            state.profiles.firstOrNull { profile -> profile.id == networkRules.wifiProfileId }?.let { profile ->
                val protocolOptionId =
                    profile.resolveNetworkRuleProtocolOptionId(networkRules.wifiProtocolOptionId)
                NetworkProfileOverride(
                    profile = profile,
                    protocolOptionId = protocolOptionId,
                )
            }
        fingerprint.isCellularOrMetered() &&
            networkRules.cellularRulesEnabled &&
            networkRules.useCellularProfile ->
            state.profiles.firstOrNull { profile -> profile.id == networkRules.cellularProfileId }?.let { profile ->
                val protocolOptionId =
                    profile.resolveNetworkRuleProtocolOptionId(networkRules.cellularProtocolOptionId)
                NetworkProfileOverride(
                    profile = profile,
                    protocolOptionId = protocolOptionId,
                )
            }
        else -> null
    }
}

@OptIn(FlowPreview::class)
internal fun HomeViewModel.startNetworkRulesWatchInternal() {
    val connectivityManager =
        getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
    viewModelScope.launch {
        container.settingsRepository.settings
            .map { settings -> networkRulesWatchNeeded(settings) }
            .distinctUntilChanged()
            .collectLatest { needed ->
                if (!needed) return@collectLatest
                observeNetworkTransitions(connectivityManager)
            }
    }
}

internal fun networkRulesWatchNeeded(settings: Settings): Boolean =
    settings.networkRules.wifiRulesEnabled || settings.networkRules.cellularRulesEnabled

@OptIn(FlowPreview::class)
private suspend fun HomeViewModel.observeNetworkTransitions(connectivityManager: ConnectivityManager) {
    run {
        callbackFlow {
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        trySend(Unit)
                    }

                    override fun onLost(network: Network) {
                        trySend(Unit)
                    }
                }
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
                .onFailure { close(it) }
            awaitClose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
        }
            .debounce(NETWORK_RULES_CHANGE_DEBOUNCE_MS)
            .collectLatest {
                evaluateNetworkRuleSwitchInternal()
            }
    }
}

private fun HomeViewModel.evaluateNetworkRuleSwitchInternal() {
    val state = controlUiState.value
    val fingerprint = currentNetworkFingerprintForSmartRules() ?: return
    val override = networkRuleOverrideToApply(state, fingerprint) ?: return
    val rules = state.settings.networkRules
    val autoConnect =
        if (fingerprint.transport == "wifi") rules.wifiAutoConnect else rules.cellularAutoConnect
    container.diagnosticsLogger.record(
        "network_rules",
        "network change transport=${fingerprint.transport} -> profile=${override.profile.id} auto=$autoConnect",
    )
    if (autoConnect) {
        requestManualConnectPermissionOrConnect(override.profile.id, override.protocolOptionId)
        return
    }
    recommendNetworkRuleProfileSwitch(override, fingerprint.transport)
}

private fun HomeViewModel.networkRuleOverrideToApply(
    state: HomeUiState,
    fingerprint: NetworkFingerprint,
): NetworkProfileOverride? {
    val override = currentNetworkProfileOverride(state)
    val handledKey = "${fingerprint.key}:${override?.profile?.id ?: "none"}"
    if (handledKey == lastNetworkRuleHandledKey) {
        return null
    }
    val isBaseline = lastNetworkRuleHandledKey == null
    lastNetworkRuleHandledKey = handledKey
    if (override == null || isBaseline) {
        return null
    }
    val connection = state.connection
    val specialRuntimeActive =
        connection.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
            connection.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
    return when {
        connection.state !in ACTIVE_CONNECTION_STATES -> override
        connection.profileId == override.profile.id -> null
        specialRuntimeActive -> null
        else -> override
    }
}

private fun HomeViewModel.recommendNetworkRuleProfileSwitch(
    override: NetworkProfileOverride,
    transport: String,
) {
    pendingNetworkRuleOverride = override
    val transportLabel =
        getApplication<Application>().getString(
            if (transport == "wifi") R.string.network_rules_transport_wifi else R.string.network_rules_transport_cellular,
        )
    if (dashboardVisible) {
        val durationMs = NETWORK_RULES_BANNER_DURATION_MS
        snackbars.tryEmit(
            FoxholeBannerEvent(
                message =
                getApplication<Application>().getString(
                    R.string.network_rule_banner_message,
                    transportLabel,
                    override.profile.name,
                ),
                tone = FoxholeBannerTone.INFO,
                actionLabel = getApplication<Application>().getString(R.string.network_rule_notification_switch),
                action = FoxholeBannerAction.ACCEPT_NETWORK_RULE_PROFILE,
                durationMillis = durationMs,
                expiresAtElapsedMs = SystemClock.elapsedRealtime() + durationMs,
            ),
        )
    } else {
        NetworkRuleRecommendationNotifier(getApplication()).notifyRecommendation(
            transportLabel = transportLabel,
            profileName = override.profile.name,
            profileId = override.profile.id,
            protocolOptionId = override.protocolOptionId,
        )
    }
}

internal fun HomeViewModel.onNetworkRuleProfileSwitchAccepted() {
    val override = pendingNetworkRuleOverride ?: return
    pendingNetworkRuleOverride = null
    requestManualConnectPermissionOrConnect(override.profile.id, override.protocolOptionId)
}

internal fun HomeViewModel.onNetworkRuleProfileSwitchDismissed() {
    pendingNetworkRuleOverride = null
}

internal fun HomeViewModel.applyNetworkRuleSwitchIntent(intent: Intent?) {
    if (intent?.action != NetworkRuleRecommendationNotifier.ACTION_APPLY_NETWORK_RULE) {
        return
    }
    val profileId = intent.getLongExtra(NetworkRuleRecommendationNotifier.EXTRA_PROFILE_ID, -1L)
    if (profileId <= 0L) {
        return
    }
    val protocolOptionId = intent.getStringExtra(NetworkRuleRecommendationNotifier.EXTRA_PROTOCOL_OPTION_ID)
    val token = intent.getStringExtra(NetworkRuleRecommendationNotifier.EXTRA_REQUEST_TOKEN) ?: return
    intent.removeExtra(NetworkRuleRecommendationNotifier.EXTRA_REQUEST_TOKEN)
    viewModelScope.launch {
        val admitted = withTimeoutOrNull(NetworkRuleCommandRequest.EXPIRY_MS) {
            appLockManager.lockState.first { it == LockState.UNLOCKED }
        } ?: return@launch
        if (admitted != LockState.UNLOCKED || !NetworkRuleCommandRequests.consume(
                getApplication(), token, profileId, protocolOptionId,
            )
        ) {
            return@launch
        }
        NetworkRuleRecommendationNotifier(getApplication()).cancel()
        requestManualConnectPermissionOrConnect(profileId, protocolOptionId)
    }
}

private const val NETWORK_RULES_CHANGE_DEBOUNCE_MS = 2_500L
private const val NETWORK_RULES_BANNER_DURATION_MS = 10_000L
