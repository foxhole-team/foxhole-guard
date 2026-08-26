package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.markQuickStartShown
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliSheetHeaderIconRole
import kotlinx.coroutines.launch

@Composable
internal fun CliQuickStartSheet(viewModel: HomeViewModel) {
    val settings by viewModel.container.settingsRepository.settings.collectAsStateWithLifecycle()
    val ui = settings.ui
    if (!ui.onboardingCompleted || ui.quickStartShown) return
    CliQuickStartSheetContent(
        onDismiss = {
            viewModel.viewModelScope.launch {
                viewModel.container.settingsRepository.markQuickStartShown()
            }
        },
    )
}

@Composable
internal fun CliQuickStartSheetContent(onDismiss: () -> Unit) {
    val body = stringResource(R.string.cli_help_start_body)
    val smartBody = stringResource(R.string.cli_help_smart_body)
    val detailsBody = stringResource(R.string.cli_help_start_details)
    val colors = LocalCliColors.current
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_quick_start_title),
        icon = R.drawable.lin_power,
        headerIconRole = CliSheetHeaderIconRole.INFORMATION,
        sheetGesturesEnabled = false,
    ) {
        CliQuickStartItems(
            body = body,
            framed = true,
            iconColor = colors.info,
            smartBody = smartBody,
            detailsBody = detailsBody,
        )
    }
}
