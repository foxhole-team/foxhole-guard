package com.foxhole.guard.ui.cli.home

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.LiveModeSwitchKind
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.confirmDisableTorForUdpProtocol
import com.foxhole.guard.ui.confirmKeepTorOnDeviceAndStartVpn
import com.foxhole.guard.ui.confirmLiveModeSwitch
import com.foxhole.guard.ui.confirmMoveTorIntoVpn
import com.foxhole.guard.ui.confirmStartTorBesideUdpVpn
import com.foxhole.guard.ui.confirmSwitchProfileWhileConnected
import com.foxhole.guard.ui.confirmSwitchProtocolWhileConnected
import com.foxhole.guard.ui.dismissTorTransitionPrompt
import com.foxhole.guard.ui.onEnableDirectTorQuickStart

/**
 * The shared yes/no confirm modal for every [TorTransitionPrompt]. It rises from the bottom as the
 * shared [CliBottomSheet], where a swipe or the scrim cancels, rather than wedging a panel into the
 * home layout.
 * The answers are the sheet-wide [CliSheetActionsRow]: full-width buttons, dashed err cancel,
 * filled ok confirm(s). Both the home and the profiles screen render this sheet from
 * `home.torTransitionPrompt`, so a switch confirmed from either surface looks identical.
 */
@Composable
internal fun CliTorPromptPanel(
    viewModel: HomeViewModel,
    prompt: TorTransitionPrompt,
    onLiveModeSwitchConfirmed: (RoutingModePreset) -> Unit = {},
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = viewModel::dismissTorTransitionPrompt,
        title = stringResource(promptTitleRes(prompt)),
        icon = R.drawable.pix_tor,
    ) {
        Text(text = torPromptQuestion(prompt), style = CliType.body, color = colors.fg)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliSheetActionsRow(
            onCancel = viewModel::dismissTorTransitionPrompt,
            actions = torPromptActions(
                viewModel = viewModel,
                prompt = prompt,
                onLiveModeSwitchConfirmed = onLiveModeSwitchConfirmed,
            ),
        )
    }
}

private fun promptTitleRes(prompt: TorTransitionPrompt): Int = when (prompt) {
    is TorTransitionPrompt.SwitchProfileWhileConnected,
    is TorTransitionPrompt.SwitchProtocolWhileConnected,
    -> R.string.cli_prof_switch_title
    else -> R.string.cli_home_tor_title
}

@Composable
private fun torPromptQuestion(prompt: TorTransitionPrompt): String = when (prompt) {
    is TorTransitionPrompt.DisableTorForUdpProtocol ->
        stringResource(R.string.cli_home_torprompt_disable_udp, prompt.protocolName)
    is TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive ->
        stringResource(R.string.cli_home_torprompt_vpn_over_tor, prompt.profileName)
    is TorTransitionPrompt.UdpVpnProtocolNotSupported ->
        stringResource(R.string.cli_home_torprompt_udp_unsupported, prompt.protocolName ?: "UDP")
    TorTransitionPrompt.StartTorFromDeviceWithoutVpn ->
        stringResource(R.string.cli_home_torprompt_direct)
    TorTransitionPrompt.StartTorBesideUdpVpn ->
        stringResource(R.string.cli_home_torprompt_beside)
    is TorTransitionPrompt.SwitchProfileWhileConnected ->
        stringResource(R.string.cli_prof_switch_profile_q, prompt.profileName)
    is TorTransitionPrompt.SwitchProtocolWhileConnected ->
        stringResource(R.string.cli_prof_switch_protocol_q, prompt.protocolName)
    is TorTransitionPrompt.LiveModeSwitch ->
        when (prompt.kind) {
            LiveModeSwitchKind.ATTACH_TOR ->
                stringResource(R.string.cli_home_torprompt_attach_tor, prompt.secondsLeft)
            LiveModeSwitchKind.DETACH_TOR ->
                stringResource(R.string.cli_home_torprompt_detach_tor, prompt.secondsLeft)
            LiveModeSwitchKind.TOR_STOPS_VPN ->
                stringResource(R.string.cli_home_torprompt_tor_stops_vpn, prompt.secondsLeft)
        }
}

/** The confirm option(s) of a prompt; the UDP-unsupported notice has none — cancel only. */
@Composable
private fun torPromptActions(
    viewModel: HomeViewModel,
    prompt: TorTransitionPrompt,
    onLiveModeSwitchConfirmed: (RoutingModePreset) -> Unit,
): List<CliSheetAction> = when (prompt) {
    is TorTransitionPrompt.DisableTorForUdpProtocol ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_torprompt_yes_no_tor),
                onClick = { viewModel.confirmDisableTorForUdpProtocol(prompt) },
            ),
        )
    is TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_torprompt_tor_into_vpn),
                onClick = { viewModel.confirmMoveTorIntoVpn(prompt) },
            ),
            CliSheetAction(
                label = stringResource(R.string.cli_home_torprompt_tor_beside),
                onClick = { viewModel.confirmKeepTorOnDeviceAndStartVpn(prompt) },
            ),
        )
    is TorTransitionPrompt.UdpVpnProtocolNotSupported -> emptyList()
    TorTransitionPrompt.StartTorFromDeviceWithoutVpn ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_torprompt_yes_direct),
                onClick = { viewModel.onEnableDirectTorQuickStart() },
            ),
        )
    TorTransitionPrompt.StartTorBesideUdpVpn ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_torprompt_yes_beside),
                onClick = { viewModel.confirmStartTorBesideUdpVpn() },
            ),
        )
    is TorTransitionPrompt.SwitchProfileWhileConnected ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_common_yes_confirm),
                onClick = { viewModel.confirmSwitchProfileWhileConnected(prompt) },
            ),
        )
    is TorTransitionPrompt.SwitchProtocolWhileConnected ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_common_yes_confirm),
                onClick = { viewModel.confirmSwitchProtocolWhileConnected(prompt) },
            ),
        )
    is TorTransitionPrompt.LiveModeSwitch ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_common_yes_confirm),
                onClick = {
                    onLiveModeSwitchConfirmed(prompt.target)
                    viewModel.confirmLiveModeSwitch(prompt)
                },
            ),
        )
}
