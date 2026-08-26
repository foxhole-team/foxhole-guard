package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.PerAppRoutingMode
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TrafficMode
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cancelSmartProfileMetricsRefresh
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.cli.components.CliIconTextItem
import com.foxhole.guard.ui.cli.components.CliIconTextItems
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPanelEdgeToEdgeContentPadding
import com.foxhole.guard.ui.cli.components.LocalCliBottomSheetDismissRequest
import com.foxhole.guard.ui.refreshSmartProfileMetrics
import com.foxhole.guard.ui.refreshSmartProfileMetricsInVpnMode
import com.foxhole.guard.ui.setSmartProfileProtocolEnabled

@Composable
internal fun CliSmartProfileSheet(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val refreshing = profile.id in state.smartProfileMetricsRefreshingProfileIds
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_prof_smart_sheet_title),
        icon = R.drawable.lin_profiles,
        trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_smart_body) },
        sheetGesturesEnabled = false,
        contentScrollEnabled = false,
        closeActionTag = CLI_SMART_SHEET_CLOSE_TAG,
        footerTrailing = {
            CliSmartTestButton(
                viewModel = viewModel,
                profileId = profile.id,
                testing = refreshing,
                modifier = Modifier
                    .weight(1f)
                    .testTag(CLI_SMART_SHEET_TEST_TAG),
            )
        },
    ) {
        val requestSheetDismiss = LocalCliBottomSheetDismissRequest.current
        CliIconTextItems(
            items = listOf(
                CliIconTextItem(
                    text = cliLabelText(profile.name),
                    icon = R.drawable.lin_profiles,
                ),
            ),
            framed = true,
            iconColor = colors.info,
            marquee = true,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPanel(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = CliPanelEdgeToEdgeContentPadding,
        ) {
            CliProtocolDropdown(
                viewModel = viewModel,
                state = state,
                profile = profile,
                onOptionSelected = requestSheetDismiss,
                onOptionEnabledToggle = { optionId, enabled ->
                    viewModel.setSmartProfileProtocolEnabled(profile.id, optionId, enabled)
                },
            )
        }
    }
}

@Composable
internal fun CliSmartTestButton(
    viewModel: HomeViewModel,
    profileId: Long,
    testing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    var confirmVpnOnlyTest by rememberSaveable { mutableStateOf(false) }
    CliButton(
        label = stringResource(
            if (testing) R.string.cli_prof_stop_test_button else R.string.cli_prof_test_button,
        ),
        color = colors.ok,
        onClick = {
            when (smartProfileTestAction(testing)) {
                SmartProfileTestAction.START -> {
                    if (
                        protocolTestRequiresVpnOnlyConfirmation(
                            privacyRouteEnabled = home.settings.privacyRoute.enabled,
                            trafficMode = home.settings.traffic.mode,
                            perAppRoutingMode = home.settings.expert.perAppRoutingMode,
                        )
                    ) {
                        confirmVpnOnlyTest = true
                    } else {
                        viewModel.refreshSmartProfileMetrics(profileId)
                    }
                }
                SmartProfileTestAction.STOP -> viewModel.cancelSmartProfileMetricsRefresh()
            }
        },
        modifier = modifier,
    )
    if (confirmVpnOnlyTest) {
        CliConfirmSheet(
            title = stringResource(R.string.cli_prof_test_warning_title),
            question = stringResource(R.string.cli_prof_test_warning_body),
            icon = R.drawable.lin_shield,
            confirmLabel = stringResource(R.string.cli_prof_test_warning_confirm),
            onConfirm = {
                confirmVpnOnlyTest = false
                viewModel.refreshSmartProfileMetricsInVpnMode(profileId)
            },
            onDismiss = { confirmVpnOnlyTest = false },
        )
    }
}

internal const val CLI_SMART_SHEET_TEST_TAG = "cli_smart_sheet_test"
internal const val CLI_SMART_SHEET_CLOSE_TAG = "cli_smart_sheet_close"

internal enum class SmartProfileTestAction { START, STOP }

internal fun smartProfileTestAction(testing: Boolean): SmartProfileTestAction =
    if (testing) SmartProfileTestAction.STOP else SmartProfileTestAction.START

internal fun protocolTestRequiresVpnOnlyConfirmation(
    privacyRouteEnabled: Boolean,
    trafficMode: TrafficMode,
    perAppRoutingMode: PerAppRoutingMode,
): Boolean =
    privacyRouteEnabled ||
        trafficMode != TrafficMode.TUNNEL ||
        perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL
