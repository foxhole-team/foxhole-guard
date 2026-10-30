package com.foxhole.guard.ui.cli.onboarding

import android.os.Build
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.InterfaceMode
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.onInterfaceModeSelected
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.settings.CliInterfaceModeRow

@Composable
internal fun CliUpdateNoticeSheet(viewModel: HomeViewModel) {
    val settings by viewModel.container.settingsRepository.settings.collectAsStateWithLifecycle()
    val visible by viewModel.alphaNoticeVisible.collectAsStateWithLifecycle()
    val readOnly by viewModel.updateNoticeState.readOnlyVisible.collectAsStateWithLifecycle()
    val retryGeneration by viewModel.updateNoticeState.retryGeneration.collectAsStateWithLifecycle()
    if ((!visible && !readOnly) || !settings.ui.onboardingCompleted) return
    CliUpdateNoticeSheetContent(
        monochromeEnabled = settings.ui.monochromeEnabled,
        retryGeneration = retryGeneration,
        showInterfaceModeChoice = visible,
        interfaceMode = settings.ui.interfaceMode,
        onInterfaceModeChanged = viewModel::onInterfaceModeSelected,
        onDismiss = {
            if (visible) viewModel.dismissAlphaNotice(it) else viewModel.updateNoticeState.closeReadOnly()
        },
    )
}

@Composable
internal fun CliUpdateNoticeSheetContent(
    monochromeEnabled: Boolean = false,
    retryGeneration: Int = 0,
    showMonochromeChoice: Boolean = true,
    interfaceMode: InterfaceMode = InterfaceMode.USER,
    showInterfaceModeChoice: Boolean = showMonochromeChoice,
    onInterfaceModeChanged: (InterfaceMode) -> Unit = {},
    onDismiss: (Boolean) -> Unit,
) {
    val summaries = releaseNoteKeys(Build.VERSION.SDK_INT).map { stringResource(it) }
    val body = summaries.joinToString("\n")
    val items = remember(body) { updateNoticeItems(body) }
    key(retryGeneration) {
        CliBottomSheet(
            onDismiss = { onDismiss(monochromeEnabled) },
            title = stringResource(R.string.cli_release_notes_title),
            icon = R.drawable.lin_update,
        ) {
            CliIconTextItems(items = items, framed = true)
            Spacer(modifier = Modifier.height(CliSpacing.md))
            if (showInterfaceModeChoice) {
                CliPanel(modifier = Modifier.fillMaxWidth()) {
                    CliInterfaceModeRow(interfaceMode, onInterfaceModeChanged, showInfo = false)
                }
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

internal fun releaseNoteKeys(apiLevel: Int): List<Int> = listOf(
    R.string.cli_release_notes_runtime,
    if (apiLevel >= 29) R.string.cli_release_notes_sentinel_network else R.string.cli_release_notes_sentinel_apps,
    R.string.cli_release_notes_backup,
    R.string.cli_release_notes_core,
)

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
