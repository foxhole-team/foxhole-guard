package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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

// Home-screen buttons and connection-mode logic: the only place that decides what a press does.

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
    val reconnectRequired = home.reconnectRequired
    val displayedMode = configuredConnectMode(home.settings)
    // The mode button is a cycler. On a live tunnel the confirm modal DEFERS the switch, so the
    // persisted mode does not move between taps — track the pending selection in the parent so each
    // tap advances the target (and re-aims the modal) instead of re-raising the same one. Parent
    // ownership also lets consent cancellation clear the UI and deferred route atomically.
    val modePromptPending =
        atomicModePromptPending || home.torTransitionPrompt is TorTransitionPrompt.LiveModeSwitch
    LaunchedEffect(modePromptPending, displayedMode) {
        resetPendingModeCycle(modePromptPending, onPendingModeCycleChanged)
    }
    // A pending target belongs to the confirmation sheet, not to the live control. Until confirm
    // the button must continue to describe the actually persisted/running mode.
    val shownMode = displayedMode
    val activeProfile = home.activeProfile
    val mainAction =
        mainButtonAction(
            connected = connected,
            busy = busy,
            reconnectRequired = reconnectRequired,
        )
    val visibleMainAction =
        mainAction.visibleDuringSubscriptionRefresh(subscriptionRefreshInProgress)
    val row = cliHomeButtonLayout(
        torModuleEnabled = home.settings.privacyRoute.permitted,
        i2pModuleEnabled = home.settings.i2p.enabled,
        connected = connected,
    ).primary
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        CliButton(
            label = stringResource(visibleMainAction.labelRes),
            icon = visibleMainAction.iconRes,
            filled = visibleMainAction.filled,
            color =
            when (mainAction.tone) {
                // START is green; stop and cancel are red.
                CliMainActionTone.START -> colors.ok
                CliMainActionTone.WARN -> colors.warn
                CliMainActionTone.STOP -> colors.err
            },
            onClick = {
                onInteraction()
                if (subscriptionRefreshInProgress) return@CliButton
                performPrimaryAction(
                    viewModel = viewModel,
                    terminal = terminal,
                    home = home,
                    connected = connected,
                    busy = busy,
                    action = mainAction,
                    displayedMode = displayedMode,
                )
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
            // A manual subscription refresh must preserve the primary action's full geometry and
            // readable state. Dimming the filled START button made it look like an empty hole while
            // the network call was in flight, especially in the dark palette.
            dimWhenDisabled = false,
            // No marching border while busy — the CANCEL label and red tone already say
            // "in progress", and an animated edge on the primary button read as flicker.
            modifier = Modifier
                .weight(row.share)
                .testTag(CLI_HOME_PRIMARY_ACTION_TAG),
        )
        // Nothing to cycle without the TOR module: the button is gone and START takes the row.
        if (CliHomeButton.MODE in row) {
            CliButton(
                label = shownMode.label,
                icon = if (shownMode == CliConnectMode.VPN) R.drawable.pix_shield else R.drawable.pix_tor,
                // Mode colour: VPN takes the accent, TOR and VPN+TOR the tor orange.
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
                            // The pending target remains transactional and invisible, but repeat
                            // taps can still re-aim the confirmation without mutating live state.
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
    // START prints the real command for the mode; stop/cancel print as-is.
    val command = action.command ?: startCommandFor(displayedMode, home.activeProfile)
    if (!connected && !busy) {
        val accepted = viewModel.startRoutingMode(
            preset = displayedMode.preset,
            scope = home.settings.privacyRoute.scope,
        )
        // A refused start already emits its actionable error. Do not manufacture a
        // command/progress row for work that never entered the runtime.
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

internal enum class CliMainActionTone { START, WARN, STOP }

private data class CliMainButtonAction(
    val labelRes: Int,
    // null: the click computes the printed command via startCommandFor.
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

// While reconnectRequired is set onToggleConnection RECONNECTS a connected tunnel (donor
// contract) - label and printed command must agree with the action.
private fun mainButtonAction(
    connected: Boolean,
    busy: Boolean,
    reconnectRequired: Boolean,
): CliMainButtonAction = when {
    // Cancel is red like stop — both tear the connection down.
    busy -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_cancel,
        command = CliCommands.CANCEL,
        filled = false,
        tone = CliMainActionTone.STOP,
        iconRes = R.drawable.pix_cross,
    )
    connected && reconnectRequired -> CliMainButtonAction(
        labelRes = R.string.cli_home_btn_reconnect,
        command = CliCommands.RECONNECT,
        filled = false,
        tone = CliMainActionTone.WARN,
        iconRes = R.drawable.pix_restart,
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
        // The command is computed in the click; a static value here would drift from it.
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

/**
 * The start command per mode, in [CliCommands] canon. `-t` is printed only for smart profiles,
 * carrying the same selected protocol token as the protocol fact, lower-cased.
 */
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

/** Mode token for the `mode` command: fixed, independent of locale and labels. */
private fun modeCommandFor(mode: CliConnectMode): String = modeCommandFor(mode.preset)

internal fun modeCommandFor(mode: RoutingModePreset): String = when (mode) {
    RoutingModePreset.VPN -> CliCommands.MODE_VPN
    RoutingModePreset.TOR -> CliCommands.MODE_TOR
    RoutingModePreset.VPN_TOR -> CliCommands.MODE_VPN_TOR
    RoutingModePreset.SPLIT_INCLUDE,
    RoutingModePreset.SPLIT_EXCLUDE,
    -> CliCommands.MODE_VPN
}

// Cycle order matches the mental model «vpn → vpn+tor → tor → vpn»: add Tor to the VPN, then drop
// the VPN keeping Tor, then back to plain VPN.
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

/**
 * One tap of the mode cycler. Advances the pending selection one step from wherever it currently
 * sits ([pending] ?: the running mode), and either applies it or raises/re-aims its confirmation.
 * A deferred request prints nothing until confirmation, so cancelling a sheet cannot leave a
 * command in the journal for a mode that was never applied.
 */
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
        // Paged back to the current mode: nothing to switch, so dismiss.
        if (modePromptPending) {
            viewModel.dismissTorTransitionPrompt()
            viewModel.dismissPendingRoutingScenario()
        }
        return null
    }
    // A refused switch leaves nothing pending. The preflight can decline a mode this profile
    // cannot build — and until this read the answer, the button went on displaying the mode that
    // had just been rejected: the label said one thing, the runtime carried another, and the next
    // tap cycled on from a state that never existed.
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

/**
 * The cycler's landing, and whether the switch was taken.
 *
 * There is no permission branch here any more: the button that calls this exists only while the Tor
 * module is on, so the permission is granted by construction — and a branch that cannot be taken is
 * a branch that stops being read.
 */
private fun applyConnectModeSwitch(
    viewModel: HomeViewModel,
    next: CliConnectMode,
    scope: com.foxhole.core.model.PrivacyRouteScope,
): ConnectModeSwitchRequestResult =
    // On a live tunnel this raises the confirm-with-countdown modal; idle/tor-neutral applies
    // straight. REJECTED is a refusal the user has already been told about in the terminal.
    viewModel.onConnectModeSwitchRequested(next.preset, scope)

/**
 * The protocol fact: a smart profile shows its selected option, plain ones the hint. The row is
 * constant (showing "—" without a profile) so the facts panel never changes height. Service and
 * undefined hints are not protocols and also render as "—".
 */
@Composable
internal fun CliProfileProtocolFact(profile: com.foxhole.core.model.Profile?) {
    val option = profile?.protocolOptions?.firstOrNull { it.id == profile.selectedProtocolOptionId }
        ?: profile?.protocolOptions?.firstOrNull { it.isSelected }
    val label = option?.displayName
        ?: profile?.protocolHint
            ?.takeIf { it != ProtocolHint.UNKNOWN && it != ProtocolHint.CUSTOM_CONFIG }
            ?.name
    CliKeyValue(
        key = stringResource(R.string.cli_home_key_protocol),
        value = label?.lowercase() ?: "—",
        icon = R.drawable.pix_shield,
    )
}

/** Secondary row: restart/reconnect, STATUS (prints the status block) and the I2P toggle. */
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
    // The extended report has no other affordance, so the buzz IS the acknowledgement: it fires
    // the moment the hold registers, before the journals are read.
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
            // `-R` while transit relaying is on: the router forwards other people's traffic, and
            // that has to be visible from the home screen, not only in settings.
            label = if (home.settings.i2p.relayTransitTraffic) "I2P -R" else "I2P",
            icon = R.drawable.pix_globe,
            // I2P owns pink everywhere: status, graph and the quick control.
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
    // Whatever is present divides the whole width between itself: a button missing from this row
    // is missing because it could do nothing, and the rest are not going to leave a gap for it.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        if (CliHomeButton.RESTART in row) {
            val reconnectRequired = home.reconnectRequired
            CliButton(
                label =
                stringResource(
                    if (reconnectRequired) {
                        R.string.cli_home_btn_reconnect
                    } else {
                        R.string.cli_home_btn_restart
                    },
                ),
                icon = R.drawable.pix_restart,
                color = if (reconnectRequired) colors.warn else colors.accent,
                onClick = {
                    terminal.command(if (reconnectRequired) CliCommands.RECONNECT else CliCommands.RESTART)
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
