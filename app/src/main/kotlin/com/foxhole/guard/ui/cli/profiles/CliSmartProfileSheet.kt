package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliLabelText
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.refreshSmartProfileMetrics
import com.foxhole.guard.ui.refreshSmartProfileMetricsInVpnMode
import com.foxhole.guard.ui.setSmartProfileProtocolEnabled

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CliSmartProfileSheet(
    viewModel: HomeViewModel,
    state: ProfilesRouteUiState,
    profile: Profile,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        modifier = modifier,
        title = stringResource(R.string.cli_prof_smart_sheet_title),
        icon = R.drawable.pix_profiles,
        trailing = { CliContextHelpButton(bodyRes = R.string.cli_help_smart_body) },
    ) {
        Text(
            text = cliLabelText(profile.name),
            style = CliType.body,
            color = colors.fg,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = SMART_PROFILE_NAME_MARQUEE_DELAY_MS,
                    repeatDelayMillis = SMART_PROFILE_NAME_MARQUEE_REPEAT_MS,
                ),
        )
        Spacer(modifier = Modifier.height(CliSpacing.xs))
        CliProtocolDropdown(
            viewModel = viewModel,
            state = state,
            profile = profile,
            onOptionSelected = onDismiss,
            onOptionEnabledToggle = { optionId, enabled ->
                viewModel.setSmartProfileProtocolEnabled(profile.id, optionId, enabled)
            },
        )
        Spacer(modifier = Modifier.height(CliSpacing.md))
        val refreshing = profile.id in state.smartProfileMetricsRefreshingProfileIds
        Row(horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm)) {
            CliButton(
                label = stringResource(R.string.cli_prof_smart_close),
                color = colors.err,
                dashed = true,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            CliSmartTestButton(
                viewModel = viewModel,
                profileId = profile.id,
                testing = refreshing,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private const val SMART_PROFILE_NAME_MARQUEE_DELAY_MS = 700
private const val SMART_PROFILE_NAME_MARQUEE_REPEAT_MS = 900

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
        filled = true,
        color = colors.accent,
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
            icon = R.drawable.pix_shield,
            confirmLabel = stringResource(R.string.cli_prof_test_warning_confirm),
            onConfirm = {
                confirmVpnOnlyTest = false
                viewModel.refreshSmartProfileMetricsInVpnMode(profileId)
            },
            onDismiss = { confirmVpnOnlyTest = false },
        )
    }
}

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
