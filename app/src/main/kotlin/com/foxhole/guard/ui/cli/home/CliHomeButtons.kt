package com.foxhole.guard.ui.cli.home

import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.ConnectModeSwitchRequestResult
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliMotion
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliKeyValue
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.dismissTorTransitionPrompt
import com.foxhole.guard.ui.onConnectModeSwitchRequested
import com.foxhole.guard.ui.onI2pEngagedChanged
import com.foxhole.guard.ui.onVpnTorStopRequested
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
        atomicModePromptPending ||
            home.torTransitionPrompt is TorTransitionPrompt.LiveModeSwitch ||
            home.torTransitionPrompt is TorTransitionPrompt.VpnTorModeChoice
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
    CliMorphingActionRow(
        row = row,
        order = PRIMARY_BUTTON_ORDER,
        label = "homePrimaryActions",
    ) { button, buttonModifier, interactive ->
        when (button) {
            CliHomeButton.MAIN ->
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
                    enabled = interactive && !subscriptionRefreshInProgress,
                    dimWhenDisabled = false,
                    modifier = buttonModifier.testTag(CLI_HOME_PRIMARY_ACTION_TAG),
                )
            CliHomeButton.MODE ->
                CliButton(
                    label = shownMode.label,
                    icon = if (shownMode == CliConnectMode.VPN) R.drawable.lin_shield else R.drawable.lin_tor,
                    color = if (shownMode == CliConnectMode.VPN) colors.accent else colors.tor,
                    modifier = buttonModifier
                        .testTag(CLI_HOME_MODE_ACTION_TAG)
                        .semantics { stateDescription = shownMode.label },
                    enabled = interactive,
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
            else -> Unit
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
        if (connected && !busy && viewModel.onVpnTorStopRequested()) return
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

internal data class CliMainButtonAction(
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
            iconRes = R.drawable.lin_update,
        )
    } else {
        this
    }

internal fun mainButtonAction(
    connected: Boolean,
    busy: Boolean,
): CliMainButtonAction = when {
    busy -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_cancel,
        command = CliCommands.CANCEL,
        filled = false,
        tone = CliMainActionTone.STOP,
        iconRes = R.drawable.lin_cross,
    )
    connected -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_disconnect,
        command = CliCommands.STOP,
        filled = false,
        tone = CliMainActionTone.STOP,
        iconRes = R.drawable.lin_power,
    )
    else -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_connect,
        command = null,
        filled = false,
        tone = CliMainActionTone.START,
        iconRes = R.drawable.lin_power,
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
        icon = R.drawable.lin_shield,
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
    val statusButton: @Composable (Modifier, Boolean) -> Unit = { buttonModifier, interactive ->
        CliButton(
            label = statusLabel,
            icon = R.drawable.lin_status,
            color = colors.accent,
            enabled = interactive && home.settingsHydrated,
            onClick = onStatus,
            onLongClick = statusHold,
            modifier = buttonModifier,
        )
    }
    val i2pEngaged = home.settings.i2p.enabled && home.settings.i2p.engaged
    val i2pButton: @Composable (Modifier, Boolean) -> Unit = { buttonModifier, interactive ->
        CliButton(
            label = if (home.settings.i2p.relayTransitTraffic) "I2P -R" else "I2P",
            icon = R.drawable.lin_globe,
            color = if (i2pEngaged) colors.i2p else colors.dim,
            enabled = interactive,
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
    CliMorphingActionRow(
        row = row,
        order = SECONDARY_BUTTON_ORDER,
        label = "homeSecondaryActions",
    ) { button, buttonModifier, interactive ->
        when (button) {
            CliHomeButton.RESTART ->
                CliButton(
                    label = stringResource(R.string.cli_home_btn_restart),
                    icon = R.drawable.lin_restart,
                    color = colors.accent,
                    enabled = interactive,
                    onClick = {
                        terminal.command(CliCommands.RESTART)
                        viewModel.onRestartActiveProfile()
                    },
                    modifier = buttonModifier,
                )
            CliHomeButton.STATUS -> statusButton(buttonModifier, interactive)
            CliHomeButton.I2P -> i2pButton(buttonModifier, interactive)
            else -> Unit
        }
    }
}

@Composable
private fun CliMorphingActionRow(
    row: CliHomeButtonRow,
    order: List<CliHomeButton>,
    label: String,
    content: @Composable (button: CliHomeButton, modifier: Modifier, interactive: Boolean) -> Unit,
) {
    val transition = updateTransition(targetState = row, label = label)
    Row(modifier = Modifier.fillMaxWidth()) {
        order.forEachIndexed { index, button ->
            val composed = button in transition.currentState || button in transition.targetState
            if (!composed) return@forEachIndexed
            key(button) {
                val weight by transition.animateFloat(
                    transitionSpec = { CliMotion.settle() },
                    label = "$label-${button.name}-weight",
                ) { state ->
                    if (button in state) VISIBLE_BUTTON_WEIGHT else HIDDEN_BUTTON_WEIGHT
                }
                val alpha by transition.animateFloat(
                    transitionSpec = { CliMotion.standard() },
                    label = "$label-${button.name}-alpha",
                ) { state ->
                    if (button in state) 1f else 0f
                }
                val hasLeadingGap = index > 0 &&
                    order.take(index).any { previous -> previous in transition.currentState || previous in transition.targetState }
                if (hasLeadingGap) {
                    val gap by transition.animateDp(
                        transitionSpec = { CliMotion.settle() },
                        label = "$label-${button.name}-gap",
                    ) { state ->
                        if (button in state && order.take(index).any { it in state }) {
                            CliSpacing.sm
                        } else {
                            HIDDEN_BUTTON_GAP
                        }
                    }
                    Spacer(modifier = Modifier.width(gap))
                }
                Box(
                    modifier = Modifier
                        .weight(weight)
                        .alpha(alpha)
                        .clipToBounds(),
                ) {
                    content(
                        button,
                        Modifier.fillMaxWidth(),
                        button in transition.targetState,
                    )
                }
            }
        }
    }
}

private val PRIMARY_BUTTON_ORDER = listOf(CliHomeButton.MAIN, CliHomeButton.MODE)
private val SECONDARY_BUTTON_ORDER =
    listOf(CliHomeButton.RESTART, CliHomeButton.STATUS, CliHomeButton.I2P)
private const val VISIBLE_BUTTON_WEIGHT = 1f
private const val HIDDEN_BUTTON_WEIGHT = 0.001f
private val HIDDEN_BUTTON_GAP = 0.dp
