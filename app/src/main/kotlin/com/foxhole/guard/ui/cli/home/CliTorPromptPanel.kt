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
import com.foxhole.guard.ui.confirmVpnTorModeChoice
import com.foxhole.guard.ui.confirmVpnTorStopAll
import com.foxhole.guard.ui.confirmVpnTorStopTor
import com.foxhole.guard.ui.dismissTorTransitionPrompt
import com.foxhole.guard.ui.onEnableDirectTorQuickStart

@Composable
internal fun CliTorPromptPanel(
    viewModel: HomeViewModel,
    prompt: TorTransitionPrompt,
    onLiveModeSwitchConfirmed: (RoutingModePreset) -> Unit = {},
) {
    val colors = LocalCliColors.current
    val secondsLeft = (prompt as? TorTransitionPrompt.LiveModeSwitch)?.secondsLeft
    CliBottomSheet(
        onDismiss = viewModel::dismissTorTransitionPrompt,
        title = stringResource(promptTitleRes(prompt)),
        icon = R.drawable.lin_tor,
        trailing = secondsLeft?.let { seconds -> { CliTorPromptCountdown(seconds) } },
        closeLabel = if (prompt.isVpnTorChoice()) {
            stringResource(R.string.cli_common_no_cancel)
        } else {
            null
        },
    ) {
        torPromptQuestion(prompt)?.let { question ->
            Text(text = question, style = CliType.body, color = colors.fg)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
        CliSheetActionsRow(
            actions = torPromptActions(
                viewModel = viewModel,
                prompt = prompt,
                onLiveModeSwitchConfirmed = onLiveModeSwitchConfirmed,
            ).map { action -> action.copy(dismissAfterClick = true) },
            horizontal = prompt.isVpnTorChoice(),
        )
    }
}

@Composable
private fun CliTorPromptCountdown(secondsLeft: Int) {
    Text(
        text = "$secondsLeft ${stringResource(R.string.cli_uptime_seconds_short)}",
        style = CliType.small,
        color = LocalCliColors.current.warn,
        maxLines = 1,
    )
}

private fun promptTitleRes(prompt: TorTransitionPrompt): Int = when (prompt) {
    is TorTransitionPrompt.VpnTorStop -> R.string.cli_home_vpn_tor_stop_title
    is TorTransitionPrompt.VpnTorModeChoice -> R.string.cli_home_vpn_tor_mode_title
    is TorTransitionPrompt.SwitchProfileWhileConnected,
    is TorTransitionPrompt.SwitchProtocolWhileConnected,
    -> R.string.cli_prof_switch_title
    else -> R.string.cli_home_tor_title
}

@Composable
private fun torPromptQuestion(prompt: TorTransitionPrompt): String? = when (prompt) {
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
    is TorTransitionPrompt.VpnTorStop,
    is TorTransitionPrompt.VpnTorModeChoice,
    -> null
    is TorTransitionPrompt.SwitchProfileWhileConnected ->
        stringResource(R.string.cli_prof_switch_profile_q, prompt.profileName)
    is TorTransitionPrompt.SwitchProtocolWhileConnected ->
        stringResource(R.string.cli_prof_switch_protocol_q, prompt.protocolName)
    is TorTransitionPrompt.LiveModeSwitch ->
        when (prompt.kind) {
            LiveModeSwitchKind.ATTACH_TOR ->
                stringResource(R.string.cli_home_torprompt_attach_tor)
            LiveModeSwitchKind.DETACH_TOR ->
                stringResource(R.string.cli_home_torprompt_detach_tor)
            LiveModeSwitchKind.TOR_STOPS_VPN ->
                stringResource(R.string.cli_home_torprompt_tor_stops_vpn)
        }
}

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
    is TorTransitionPrompt.VpnTorStop ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_vpn_tor_stop_tor),
                onClick = { viewModel.confirmVpnTorStopTor(prompt) },
            ),
            CliSheetAction(
                label = stringResource(R.string.cli_home_vpn_tor_stop_all),
                onClick = { viewModel.confirmVpnTorStopAll(prompt) },
                tone = com.foxhole.guard.ui.cli.components.CliSheetActionTone.DESTRUCTIVE,
            ),
        )
    is TorTransitionPrompt.VpnTorModeChoice ->
        listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_home_vpn_tor_mode_tor),
                onClick = {
                    viewModel.confirmVpnTorModeChoice(prompt, RoutingModePreset.TOR)
                },
            ),
            CliSheetAction(
                label = stringResource(R.string.cli_home_vpn_tor_mode_vpn),
                onClick = {
                    viewModel.confirmVpnTorModeChoice(prompt, RoutingModePreset.VPN)
                },
                tone = com.foxhole.guard.ui.cli.components.CliSheetActionTone.ACCENT,
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

private fun TorTransitionPrompt.isVpnTorChoice(): Boolean =
    this is TorTransitionPrompt.VpnTorStop || this is TorTransitionPrompt.VpnTorModeChoice
