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
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliToggleRow

@Composable
internal fun CliUpdateNoticeSheet(viewModel: HomeViewModel) {
    val settings by viewModel.container.settingsRepository.settings.collectAsStateWithLifecycle()
    val visible by viewModel.alphaNoticeVisible.collectAsStateWithLifecycle()
    if (!visible || !settings.ui.onboardingCompleted) return
    CliUpdateNoticeSheetContent(
        monochromeEnabled = settings.ui.monochromeEnabled,
        onDismiss = viewModel::dismissAlphaNotice,
    )
}

@Composable
internal fun CliUpdateNoticeSheetContent(
    monochromeEnabled: Boolean = false,
    onDismiss: (Boolean) -> Unit,
) {
    var monochrome by rememberSaveable { mutableStateOf(monochromeEnabled) }
    val body = stringResource(R.string.cli_update_notice_body)
    val items = remember(body) { updateNoticeItems(body) }
    CliBottomSheet(
        onDismiss = { onDismiss(monochrome) },
        title = stringResource(R.string.cli_update_notice_title),
        icon = R.drawable.lin_update,
    ) {
        CliIconTextItems(items = items, framed = true)
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliPanel(modifier = Modifier.fillMaxWidth()) {
            CliToggleRow(
                label = stringResource(R.string.cli_cfg_monochrome),
                checked = monochrome,
                onToggle = { monochrome = it },
                icon = R.drawable.lin_star,
            )
        }
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
                icon = UPDATE_NOTICE_ICONS.getOrElse(index) { R.drawable.lin_info },
            )
        }
        .toList()

private val UPDATE_NOTICE_ICONS = listOf(
    R.drawable.lin_star,
    R.drawable.lin_map,
    R.drawable.lin_shield,
)
