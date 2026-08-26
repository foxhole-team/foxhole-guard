package com.foxhole.guard.ui

import androidx.lifecycle.viewModelScope
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.AppliedTorRoute
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.applyRoutingModePreset
import com.foxhole.guard.runtime.FoxholeVpnService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun HomeViewModel.onVpnTorStopRequested(): Boolean {
    val appliedRoute = container.connectionController.snapshot.value.appliedVpnTorRouteOrNull() ?: return false
    dismissPendingRoutingScenario()
    dismissTorTransitionPrompt()
    torTransitionPromptMutable.value = TorTransitionPrompt.VpnTorStop(scope = appliedRoute.scope)
    return true
}

internal fun HomeViewModel.confirmVpnTorStopTor(prompt: TorTransitionPrompt.VpnTorStop) {
    if (!consumeAppliedVpnTorPrompt(prompt, prompt.scope)) return
    disableTorOnActiveVpn(prompt.scope)
}

internal fun HomeViewModel.confirmVpnTorStopAll(prompt: TorTransitionPrompt.VpnTorStop) {
    if (!consumeAppliedVpnTorPrompt(prompt, prompt.scope)) return
    cancelAutoConnect(clearUiOnly = true)
    clearTorOperation()
    torIpInfoMutable.value = null
    container.connectionController.disconnect(suppressLocalGuard = false)
}

internal fun HomeViewModel.confirmVpnTorModeChoice(
    prompt: TorTransitionPrompt.VpnTorModeChoice,
    target: RoutingModePreset,
) {
    if (target !in setOf(RoutingModePreset.TOR, RoutingModePreset.VPN) ||
        !consumeAppliedVpnTorPrompt(prompt, prompt.scope)
    ) {
        return
    }
    when (target) {
        RoutingModePreset.TOR -> transitionAppliedVpnTorToTorOnly(prompt.scope)
        RoutingModePreset.VPN -> disableTorOnActiveVpn(prompt.scope)
        else -> Unit
    }
}

private fun HomeViewModel.disableTorOnActiveVpn(scope: PrivacyRouteScope) {
    clearTorOperation()
    torIpInfoMutable.value = null
    onRoutingModePresetSelected(preset = RoutingModePreset.VPN, scope = scope)
}

private fun HomeViewModel.consumeAppliedVpnTorPrompt(
    prompt: TorTransitionPrompt,
    scope: PrivacyRouteScope,
): Boolean {
    if (torTransitionPromptMutable.value != prompt) return false
    dismissTorTransitionPrompt()
    return container.connectionController.snapshot.value.appliedVpnTorRouteOrNull()?.scope == scope
}

internal fun shouldOfferVpnTorStopChoice(snapshot: ConnectionSnapshot): Boolean =
    snapshot.appliedVpnTorRouteOrNull() != null

internal fun shouldOfferVpnTorModeChoice(
    target: RoutingModePreset,
    snapshot: ConnectionSnapshot,
): Boolean = target == RoutingModePreset.TOR && snapshot.appliedVpnTorRouteOrNull() != null

internal fun ConnectionSnapshot.appliedVpnTorRouteOrNull(): AppliedTorRoute? =
    appliedTorRoute?.takeIf {
        val runtimeProfileId = profileId
        state in ACTIVE_CONNECTION_STATES && torActive && runtimeProfileId != null && runtimeProfileId > 0L
    }

private fun HomeViewModel.transitionAppliedVpnTorToTorOnly(scope: PrivacyRouteScope) {
    val ticket = runtimeSettingUpdates.reserve()
    viewModelScope.launch {
        try {
            ticket.awaitTurn()
            val appliedScope = container.connectionController.snapshot.value.appliedVpnTorRouteOrNull()?.scope
            if (appliedScope != scope || !routingModePresetSelectable(RoutingModePreset.TOR, scope)) return@launch
            clearTorOperation()
            torIpInfoMutable.value = null
            runAuthoritativeRuntimeSettingUpdateThen(
                updateAction = {
                    container.settingsRepository.applyRoutingModePreset(RoutingModePreset.TOR, scope)
                    emitRoutingScenarioSelected(R.string.cli_st_tor)
                },
                applyAction = {
                    container.connectionController.disconnect(
                        suppressLocalGuard = true,
                        userInitiated = false,
                    )
                    true
                },
                followUpAction = {
                    if (awaitConfirmedModeHandoffIdle()) {
                        markTorOperation(HomeTorOperationKind.CONNECTING)
                        connectNow(FoxholeVpnService.TOR_ONLY_PROFILE_ID)
                    }
                },
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            emitError(runtimeConnectionFailureMessage(error))
        } finally {
            ticket.complete()
        }
    }
}
