package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.foxhole.core.model.ProtocolHint
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliKeyValue
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
    pendingModeCycle: CliConnectMode?,
    onPendingModeCycleChanged: (CliConnectMode?) -> Unit,
    onTorConsentNeeded: (CliConnectMode) -> Unit,
) {
    val colors = LocalCliColors.current
    val reconnectRequired = home.reconnectRequired
    val displayedMode = configuredConnectMode(home.settings)
    // The mode button is a cycler. On a live tunnel the confirm modal DEFERS the switch, so the
    // persisted mode does not move between taps — track the pending selection in the parent so each
    // tap advances the target (and re-aims the modal) instead of re-raising the same one. Parent
    // ownership also lets consent cancellation clear the UI and deferred route atomically.
    val modePromptPending = home.torTransitionPrompt is TorTransitionPrompt.LiveModeSwitch
    LaunchedEffect(modePromptPending, displayedMode) {
        if (!modePromptPending) onPendingModeCycleChanged(null)
    }
    val shownMode = pendingModeCycle ?: displayedMode
    val mainAction =
        mainButtonAction(
            connected = connected,
            busy = busy,
            reconnectRequired = reconnectRequired,
        )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        CliButton(
            label = stringResource(mainAction.labelRes),
            icon = mainAction.iconRes,
            filled = mainAction.filled,
            color =
            when (mainAction.tone) {
                // START is green; stop and cancel are red.
                CliMainActionTone.START -> colors.ok
                CliMainActionTone.WARN -> colors.warn
                CliMainActionTone.STOP -> colors.err
            },
            onClick = {
                // START prints the real command for the mode; stop/cancel print as-is.
                val command = mainAction.command
                    ?: startCommandFor(displayedMode, home.activeProfile)
                terminal.command(command)
                if (!connected && !busy) {
                    viewModel.startRoutingMode(
                        preset = displayedMode.preset,
                        scope = home.settings.privacyRoute.scope,
                    )
                } else {
                    viewModel.onToggleConnection()
                }
            },
            modifier = Modifier
                .weight(1f)
                .testTag(CLI_HOME_PRIMARY_ACTION_TAG),
        )
        CliButton(
            label = shownMode.label,
            icon = if (shownMode == CliConnectMode.VPN) R.drawable.pix_shield else R.drawable.pix_tor,
            // Mode colour: VPN takes the accent, TOR and VPN+TOR the tor orange.
            color = if (shownMode == CliConnectMode.VPN) colors.accent else colors.tor,
            modifier = Modifier
                .weight(1f)
                .testTag(CLI_HOME_MODE_ACTION_TAG)
                .semantics { stateDescription = shownMode.label },
            onClick = {
                onPendingModeCycleChanged(
                    cycleConnectMode(
                        viewModel = viewModel,
                        terminal = terminal,
                        home = home,
                        running = displayedMode,
                        pending = pendingModeCycle,
                        modePromptPending = modePromptPending,
                        onTorConsentNeeded = onTorConsentNeeded,
                    ),
                )
            },
        )
    }
}

private enum class CliMainActionTone { START, WARN, STOP }

private data class CliMainButtonAction(
    val labelRes: Int,
    // null: the click computes the printed command via startCommandFor.
    val command: String?,
    val filled: Boolean,
    val tone: CliMainActionTone,
    val iconRes: Int,
)

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
internal const val CLI_HOME_TOR_CONSENT_CONFIRM_TAG = "cli_home_tor_consent_confirm"
internal const val CLI_HOME_TOR_CONSENT_CANCEL_TAG = "cli_home_tor_consent_cancel"
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
private fun modeCommandFor(mode: CliConnectMode): String = when (mode) {
    CliConnectMode.VPN -> CliCommands.MODE_VPN
    CliConnectMode.TOR -> CliCommands.MODE_TOR
    CliConnectMode.VPN_TOR -> CliCommands.MODE_VPN_TOR
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
 * sits ([pending] ?: the running mode), prints the `mode …` command, and either raises/re-aims the
 * confirm modal for the new target or — when the cycle lands back on the running mode — drops the
 * pending modal (nothing to switch). Returns the new pending selection to store (null once back on
 * the running mode).
 */
private fun cycleConnectMode(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    running: CliConnectMode,
    pending: CliConnectMode?,
    modePromptPending: Boolean,
    onTorConsentNeeded: (CliConnectMode) -> Unit,
): CliConnectMode? {
    val next = nextConnectMode(pending ?: running)
    terminal.command(modeCommandFor(next))
    if (next == running) {
        // Paged back to the current mode: nothing to switch, so dismiss.
        if (modePromptPending) {
            viewModel.dismissTorTransitionPrompt()
        }
        return null
    }
    applyConnectModeSwitch(
        viewModel = viewModel,
        next = next,
        torPermitted = home.settings.privacyRoute.permitted,
        scope = home.settings.privacyRoute.scope,
        onTorConsentNeeded = onTorConsentNeeded,
    )
    return next
}

private fun applyConnectModeSwitch(
    viewModel: HomeViewModel,
    next: CliConnectMode,
    torPermitted: Boolean,
    scope: com.foxhole.core.model.PrivacyRouteScope,
    onTorConsentNeeded: (CliConnectMode) -> Unit,
) {
    if (next != CliConnectMode.VPN && !torPermitted) {
        onTorConsentNeeded(next)
        return
    }
    // On a live tunnel this raises the confirm-with-countdown modal; idle/tor-neutral applies straight.
    viewModel.onConnectModeSwitchRequested(next.preset, scope)
}

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
) {
    val colors = LocalCliColors.current
    val statusLabel = stringResource(R.string.cli_home_btn_status)
    val i2pVisible = home.settings.i2p.enabled
    val i2pEngaged = home.settings.i2p.enabled && home.settings.i2p.engaged
    val i2pButton: @Composable (Modifier) -> Unit = { buttonModifier ->
        CliButton(
            label = "I2P",
            icon = R.drawable.pix_globe,
            // I2P: grey when off, green when on.
            color = if (i2pEngaged) colors.ok else colors.dim,
            onClick = {
                val enable = !i2pEngaged
                terminal.command(CliCommands.i2p(enable))
                viewModel.onI2pEngagedChanged(enable)
            },
            modifier = buttonModifier,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        if (connected) {
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
                modifier = Modifier.weight(1f),
            )
            CliButton(
                label = statusLabel,
                icon = R.drawable.pix_status,
                color = colors.accent,
                onClick = onStatus,
                modifier = Modifier.weight(1f),
            )
            if (i2pVisible) {
                i2pButton(Modifier.weight(1f))
            }
        } else if (i2pVisible) {
            CliButton(
                label = statusLabel,
                icon = R.drawable.pix_status,
                color = colors.accent,
                onClick = onStatus,
                modifier = Modifier.weight(1f),
            )
            i2pButton(Modifier.weight(1f))
        } else {
            Spacer(modifier = Modifier.weight(0.5f))
            CliButton(
                label = statusLabel,
                icon = R.drawable.pix_status,
                color = colors.accent,
                onClick = onStatus,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.weight(0.5f))
        }
    }
}
