package com.foxhole.guard.ui

import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TrafficMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

internal class HomeConnectionFlowState(
    initialStartupProfile: Profile?,
) {
    val autoConnectUiStateMutable = MutableStateFlow(AutoConnectUiState())
    val reconnectInProgressMutable = MutableStateFlow(false)
    val runtimeReloadPendingMutable = MutableStateFlow(false)
    val runtimeReconnectRequiredMutable = MutableStateFlow(false)
    val profileReconnectPromptUntilMutable = MutableStateFlow(0L)
    val startupActiveProfileMutable = MutableStateFlow(initialStartupProfile)

    val pendingRoutingScenarioConfirmationMutable =
        MutableStateFlow<PendingRoutingScenarioChange?>(null)
    var autoConnectJob: Job? = null
    var reconnectJob: Job? = null
    var runtimeReloadPendingJob: Job? = null
    var routeModeRestartPromptJob: Job? = null
    var routeModeRestartBaseline: Pair<TrafficMode, PerAppRoutingMode>? = null
    var lastNetworkRuleHandledKey: String? = null
    var pendingNetworkRuleOverride: NetworkProfileOverride? = null
}
