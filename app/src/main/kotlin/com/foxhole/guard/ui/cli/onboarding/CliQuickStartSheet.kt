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

/**
 * The quick start, shown exactly once directly after the wizard and before the beta notice.
 *
 * The gate needs no hydration guard, unlike the wizard's: it reads
 * `onboardingCompleted && !quickStartShown`, which is false on the pre-hydration defaults (the fast
 * UI store mirrors dashboard flags only, never these), so it cannot flash before stored settings
 * arrive. Dismissing writes the flag; the beta notice gate then becomes eligible.
 */
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

/** The sheet itself, free of the view model so the layout can be composed on its own. */
@Composable
internal fun CliQuickStartSheetContent(onDismiss: () -> Unit) {
    val colors = LocalCliColors.current
    val body = stringResource(R.string.cli_help_start_body)
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_help_start_title),
        icon = R.drawable.pix_power,
        // The body owns vertical scrolling. Letting ModalBottomSheet consume the same fast upward
        // fling can bounce between its full-height anchor and the inner scroll indefinitely.
        sheetGesturesEnabled = false,
        // The finish button rides the sheet's pinned footer: the reading passages scroll under it,
        // so the only way out of first-run stays on screen however long the text is. The height cap
        // and the scroll around the passages now belong to CliBottomSheet.
        footer = {
            Spacer(modifier = Modifier.height(CliSpacing.md))
            // Sheet palette: the single confirming action is the filled ok button, full width.
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
