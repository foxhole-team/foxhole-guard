package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.foxhole.guard.R
import com.foxhole.guard.core.settings.markQuickStartShown
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
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
    val colors = LocalCliColors.current
    val body = stringResource(R.string.cli_help_start_body)
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_help_start_title),
        icon = R.drawable.pix_power,
        sheetGesturesEnabled = false,
        footer = {
            Spacer(modifier = Modifier.height(CliSpacing.md))
            CliButton(
                label = stringResource(R.string.cli_wizard_finish),
                filled = true,
                color = colors.ok,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        },
    ) {
        CliQuickStartItems(body = body, framed = true)
    }
}
