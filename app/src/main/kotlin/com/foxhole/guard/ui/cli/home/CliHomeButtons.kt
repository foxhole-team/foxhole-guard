package com.foxhole.guard.ui.cli.home

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.ConnectModeSwitchRequestResult
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.dismissTorTransitionPrompt
import com.foxhole.guard.ui.onConnectModeSwitchRequested
import com.foxhole.guard.ui.onI2pEngagedChanged
import com.foxhole.guard.ui.startRoutingMode

@Composable
internal fun CliPrimaryButtonsRow(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    busy: Boolean,
    subscriptionRefreshInProgress: Boolean,
    atomicModePromptPending: Boolean,
    pendingModeCycle: CliConnectMode?,
    onPendingModeCycleChanged: (CliConnectMode?) -> Unit,
    onInteraction: () -> Unit,
) {
    val colors = LocalCliColors.current
    val haptics = LocalHapticFeedback.current
    val displayedMode = configuredConnectMode(home.settings)
    val modePromptPending =
        atomicModePromptPending || home.torTransitionPrompt is TorTransitionPrompt.LiveModeSwitch
    LaunchedEffect(modePromptPending, displayedMode) {
        resetPendingModeCycle(modePromptPending, onPendingModeCycleChanged)
    }
    val shownMode = displayedMode
    val activeProfile = home.activeProfile
    val mainAction =
        mainButtonAction(
            connected = connected,
            busy = busy,
        )
    val visibleMainAction =
        mainAction.visibleDuringSubscriptionRefresh(subscriptionRefreshInProgress)
    val row = cliHomeButtonLayout(
        torModuleEnabled = home.settings.privacyRoute.permitted,
        i2pModuleEnabled = home.settings.i2p.enabled,
        connected = connected,
    ).primary
    val onPrimaryPressed = {
        performPrimaryAction(
            viewModel = viewModel,
            terminal = terminal,
            home = home,
            connected = connected,
            busy = busy,
            action = mainAction,
            displayedMode = displayedMode,
        )
    }
    val canRefreshSubscriptionOnStartHold =
        canRefreshSubscriptionOnStartHold(
            actionTone = mainAction.tone,
            connected = connected,
            busy = busy,
            subscriptionRefreshInProgress = subscriptionRefreshInProgress,
            mode = shownMode,
            profileSourceType = activeProfile?.sourceType,
        )
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        CliButton(
            label = stringResource(visibleMainAction.labelRes),
            icon = visibleMainAction.iconRes,
            filled = visibleMainAction.filled,
            color =
            when (mainAction.tone) {
                CliMainActionTone.START -> colors.ok
                CliMainActionTone.STOP -> colors.err
            },
            onClick = {
                onInteraction()
                if (subscriptionRefreshInProgress) return@CliButton
                onPrimaryPressed()
            },
            onLongClick =
            if (canRefreshSubscriptionOnStartHold && activeProfile != null) {
                {
                    onInteraction()
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    terminal.command(CliCommands.refreshSubscription(activeProfile.name))
                    viewModel.onRefreshProfile()
                }
            } else {
                null
            },
            enabled = !subscriptionRefreshInProgress,
            dimWhenDisabled = false,
            modifier = Modifier
                .weight(row.share)
                .testTag(CLI_HOME_PRIMARY_ACTION_TAG),
        )
        if (CliHomeButton.MODE in row) {
            CliButton(
                label = shownMode.label,
                icon = if (shownMode == CliConnectMode.VPN) R.drawable.pix_shield else R.drawable.pix_tor,
                color = if (shownMode == CliConnectMode.VPN) colors.accent else colors.tor,
                modifier = Modifier
                    .weight(row.share)
                    .testTag(CLI_HOME_MODE_ACTION_TAG)
                    .semantics { stateDescription = shownMode.label },
                onClick = {
                    onInteraction()
                    onPendingModeCycleChanged(
                        cycleConnectMode(
                            viewModel = viewModel,
                            terminal = terminal,
                            home = home,
                            running = displayedMode,
                            pending = pendingModeCycle,
                            modePromptPending = modePromptPending,
                        ),
                    )
                },
            )
        }
    }
}

private fun performPrimaryAction(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    busy: Boolean,
    action: CliMainButtonAction,
    displayedMode: CliConnectMode,
) {
    val command = action.command ?: startCommandFor(displayedMode, home.activeProfile)
    if (!connected && !busy) {
        val accepted = viewModel.startRoutingMode(
            preset = displayedMode.preset,
            scope = home.settings.privacyRoute.scope,
        )
        if (accepted) terminal.command(command)
    } else {
        terminal.command(command)
        viewModel.onToggleConnection()
    }
}

private fun resetPendingModeCycle(
    modePromptPending: Boolean,
    onPendingModeCycleChanged: (CliConnectMode?) -> Unit,
) {
    if (!modePromptPending) onPendingModeCycleChanged(null)
}

internal fun canRefreshSubscriptionOnStartHold(
    actionTone: CliMainActionTone,
    connected: Boolean,
    busy: Boolean,
    subscriptionRefreshInProgress: Boolean,
    mode: CliConnectMode,
    profileSourceType: ProfileSourceType?,
): Boolean =
    actionTone == CliMainActionTone.START &&
        !connected &&
        !busy &&
        !subscriptionRefreshInProgress &&
        mode != CliConnectMode.TOR &&
        profileSourceType == ProfileSourceType.SUBSCRIPTION_URL

internal enum class CliMainActionTone { START, STOP }

private data class CliMainButtonAction(
    val labelRes: Int,
    val command: String?,
    val filled: Boolean,
    val tone: CliMainActionTone,
    val iconRes: Int,
)

private fun CliMainButtonAction.visibleDuringSubscriptionRefresh(
    subscriptionRefreshInProgress: Boolean,
): CliMainButtonAction =
    if (subscriptionRefreshInProgress && tone == CliMainActionTone.START) {
        copy(
            labelRes = R.string.cli_home_btn_subscription_refreshing,
            iconRes = R.drawable.pix_update,
        )
    } else {
        this
    }

private fun mainButtonAction(
    connected: Boolean,
    busy: Boolean,
): CliMainButtonAction = when {
    busy -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_cancel,
        command = CliCommands.CANCEL,
        filled = false,
        tone = CliMainActionTone.STOP,
        iconRes = R.drawable.pix_cross,
    )
    connected -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_disconnect,
        command = CliCommands.STOP,
        filled = false,
        tone = CliMainActionTone.STOP,
        iconRes = R.drawable.pix_power,
    )
    else -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_connect,
        command = null,
        filled = true,
        tone = CliMainActionTone.START,
        iconRes = R.drawable.pix_power,
    )
}

internal const val CLI_HOME_FACTS_TAG = "cli_home_facts"
internal const val CLI_HOME_IP_TAG = "cli_home_ip"
internal const val CLI_HOME_TRAFFIC_TAG = "cli_home_traffic"
internal const val CLI_HOME_PRIMARY_ACTION_TAG = "cli_home_primary_action"
internal const val CLI_HOME_MODE_ACTION_TAG = "cli_home_mode_action"
internal const val CLI_HOME_TERMINAL_TAG = "cli_home_terminal"

private fun startCommandFor(mode: CliConnectMode, profile: com.foxhole.core.model.Profile?): String {
    val transport = profile?.let { p ->
        p.protocolOptions.firstOrNull { it.id == p.selectedProtocolOptionId }
            ?: p.protocolOptions.firstOrNull { it.isSelected }
    }?.displayName?.lowercase()
    return when (mode) {
        CliConnectMode.VPN -> CliCommands.startVpn(profile?.name, transport)
        CliConnectMode.TOR -> CliCommands.START_TOR
        CliConnectMode.VPN_TOR -> CliCommands.startVpnTor(profile?.name, transport)
    }
}

private fun modeCommandFor(mode: CliConnectMode): String = modeCommandFor(mode.preset)

internal fun modeCommandFor(mode: RoutingModePreset): String = when (mode) {
    RoutingModePreset.VPN -> CliCommands.MODE_VPN
    RoutingModePreset.TOR -> CliCommands.MODE_TOR
    RoutingModePreset.VPN_TOR -> CliCommands.MODE_VPN_TOR
    RoutingModePreset.SPLIT_INCLUDE,
    RoutingModePreset.SPLIT_EXCLUDE,
    -> CliCommands.MODE_VPN
}

private fun nextConnectMode(current: CliConnectMode): CliConnectMode =
    when (current) {
        CliConnectMode.VPN -> CliConnectMode.VPN_TOR
        CliConnectMode.VPN_TOR -> CliConnectMode.TOR
        CliConnectMode.TOR -> CliConnectMode.VPN
    }

private fun configuredConnectMode(settings: com.foxhole.core.model.Settings): CliConnectMode =
    when {
        settings.privacyRoute.enabled && settings.privacyRoute.bypassVpnTunnel -> CliConnectMode.TOR
        settings.privacyRoute.enabled -> CliConnectMode.VPN_TOR
        else -> CliConnectMode.VPN
    }

private fun cycleConnectMode(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    running: CliConnectMode,
    pending: CliConnectMode?,
    modePromptPending: Boolean,
): CliConnectMode? {
    val next = nextConnectMode(pending ?: running)
    if (next == running) {
        if (modePromptPending) {
            viewModel.dismissTorTransitionPrompt()
            viewModel.dismissPendingRoutingScenario()
        }
        return null
    }
    return when (
        applyConnectModeSwitch(
            viewModel = viewModel,
            next = next,
            scope = home.settings.privacyRoute.scope,
        )
    ) {
        ConnectModeSwitchRequestResult.REJECTED -> null
        ConnectModeSwitchRequestResult.DEFERRED -> next
        ConnectModeSwitchRequestResult.APPLIED -> {
            terminal.command(modeCommandFor(next))
            null
        }
    }
}

private fun applyConnectModeSwitch(
    viewModel: HomeViewModel,
    next: CliConnectMode,
    scope: com.foxhole.core.model.PrivacyRouteScope,
): ConnectModeSwitchRequestResult =
    viewModel.onConnectModeSwitchRequested(next.preset, scope)

/**
 * The transport protocol of the running option, never its name. A smart profile's
 * [com.foxhole.core.model.ProfileProtocolOption.displayName] is the free-form remark the
 * subscription shipped ("Amsterdam #3"), so reading it printed the profile name in a row
 * labelled "VPN protocol"; only [com.foxhole.core.model.ProtocolHint] names the protocol.
 */
internal fun cliProfileProtocolLabel(profile: com.foxhole.core.model.Profile?): String? {
    val option = profile?.protocolOptions?.firstOrNull { it.id == profile.selectedProtocolOptionId }
        ?: profile?.protocolOptions?.firstOrNull { it.isSelected }
    val hint = option?.protocolHint ?: profile?.protocolHint ?: return null
    return hint.takeIf { it != ProtocolHint.UNKNOWN && it != ProtocolHint.CUSTOM_CONFIG }?.name
}

@Composable
internal fun CliProfileProtocolFact(profile: com.foxhole.core.model.Profile?) {
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_protocol),
        value = cliProfileProtocolLabel(profile)?.lowercase() ?: "—",
        icon = R.drawable.pix_shield,
    )
}

@Composable
internal fun CliSecondaryButtonsRow(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    onStatus: () -> Unit,
    onStatusHold: () -> Unit,
) {
    val colors = LocalCliColors.current
    val statusLabel = stringResource(R.string.cli_home_btn_status)
    val haptics = LocalHapticFeedback.current
    val statusHold: () -> Unit = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onStatusHold()
    }
    val statusButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        CliButton(
            label = statusLabel,
            icon = R.drawable.pix_status,
            color = colors.accent,
            enabled = home.settingsHydrated,
            onClick = onStatus,
            onLongClick = statusHold,
            modifier = buttonModifier,
        )
    }
    val i2pEngaged = home.settings.i2p.enabled && home.settings.i2p.engaged
    val i2pButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        CliButton(
            label = if (home.settings.i2p.relayTransitTraffic) "I2P -R" else "I2P",
            icon = R.drawable.pix_globe,
            color = if (i2pEngaged) colors.i2p else colors.dim,
            onClick = {
                val enable = !i2pEngaged
                if (viewModel.onI2pEngagedChanged(enable)) {
                    terminal.command(CliCommands.i2p(enable))
                }
            },
            modifier = buttonModifier,
        )
    }
    val row = cliHomeButtonLayout(
        torModuleEnabled = home.settings.privacyRoute.permitted,
        i2pModuleEnabled = home.settings.i2p.enabled,
        connected = connected,
    ).secondary
    Row(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        if (CliHomeButton.RESTART in row) {
            CliButton(
                label = stringResource(R.string.cli_home_btn_restart),
                icon = R.drawable.pix_restart,
                color = colors.accent,
                onClick = {
                    terminal.command(CliCommands.RESTART)
                    viewModel.onRestartActiveProfile()
                },
                modifier = Modifier.weight(row.share),
            )
        }
        statusButton(Modifier.weight(row.share))
        if (CliHomeButton.I2P in row) {
            i2pButton(Modifier.weight(row.share))
        }
    }
}
