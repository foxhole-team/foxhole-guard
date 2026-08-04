package com.foxhole.guard.ui.cli.profiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.Profile
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfilesRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliContextHelpButton
import com.foxhole.guard.ui.refreshSmartProfileMetrics
import com.foxhole.guard.ui.setSmartProfileProtocolEnabled

/**
 * Smart-profile management on the shared [CliBottomSheet] (profiles screen only). It slides up
 * carrying the available-protocol table ([CliProtocolDropdown]) whose S column doubles as the
 * per-protocol on/off toggle (N1), and a single bottom row of two buttons: `[close]` dismisses,
 * `[test]` re-probes the metrics — this sheet and the profile detail sheet are the only places
 * where TEST lives. Picking a protocol row switches to it and dismisses the sheet so any resulting
 * switch confirm (B3/P2) is visible anchored to the bottom of the screen. Only meaningful for smart
 * profiles (`protocolOptions.size > 1`); the caller gates it.
 */
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
                color = colors.dim,
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

/**
 * The one smart-profile TEST button. Its two homes — this sheet and the profile detail sheet —
 * must look and behave identically: filled accent, an ellipsis and a lock while measuring, and a
 * metrics restart on tap.
 */
@Composable
internal fun CliSmartTestButton(
    viewModel: HomeViewModel,
    profileId: Long,
    testing: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    CliButton(
        label = if (testing) {
            stringResource(R.string.cli_prof_test_button) + "…"
        } else {
            stringResource(R.string.cli_prof_test_button)
        },
        filled = true,
        color = colors.accent,
        enabled = !testing,
        onClick = { viewModel.refreshSmartProfileMetrics(profileId) },
        modifier = modifier,
    )
}
