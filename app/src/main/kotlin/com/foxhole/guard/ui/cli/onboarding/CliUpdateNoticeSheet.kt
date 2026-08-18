package com.foxhole.guard.ui.cli.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.onRetroThemeRestored

@Composable
internal fun CliUpdateNoticeSheet(viewModel: HomeViewModel) {
    val settings by viewModel.container.settingsRepository.settings.collectAsStateWithLifecycle()
    val visible by viewModel.alphaNoticeVisible.collectAsStateWithLifecycle()
    if (!visible || !settings.ui.onboardingCompleted) return
    CliUpdateNoticeSheetContent(
        retroInitiallyChecked = settings.ui.visualStyle == VisualStyle.PIXEL,
        onDismiss = { retro ->
            viewModel.onRetroThemeRestored(retro)
            viewModel.dismissAlphaNotice()
        },
    )
}

@Composable
internal fun CliUpdateNoticeSheetContent(
    retroInitiallyChecked: Boolean,
    onDismiss: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    var retro by rememberSaveable(retroInitiallyChecked) { mutableStateOf(retroInitiallyChecked) }
    val body = stringResource(R.string.cli_update_notice_body)
    val items = remember(body) { updateNoticeItems(body) }
    CliBottomSheet(
        onDismiss = { onDismiss(retro) },
        title = stringResource(R.string.cli_update_notice_title),
        icon = R.drawable.pix_update,
    ) {
        CliIconTextItems(items = items, framed = true)
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliPanel(modifier = Modifier.fillMaxWidth()) {
            CliToggleRow(
                label = stringResource(R.string.cli_update_notice_retro),
                checked = retro,
                onToggle = { retro = it },
                note = stringResource(R.string.cli_update_notice_retro_note),
                icon = R.drawable.pix_edit,
            )
        }
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliButton(
            label = stringResource(R.string.cli_wizard_finish),
            filled = true,
            color = colors.ok,
            onClick = { onDismiss(retro) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

internal fun updateNoticeItems(body: String): List<CliQuickStartItem> =
    body.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapIndexed { index, text ->
            CliQuickStartItem(
                text = text,
                icon = UPDATE_NOTICE_ICONS.getOrElse(index) { R.drawable.pix_info },
            )
        }
        .toList()

private val UPDATE_NOTICE_ICONS = listOf(
    R.drawable.pix_edit,
    R.drawable.pix_power,
    R.drawable.pix_settings,
    R.drawable.pix_import,
    R.drawable.pix_update,
)
