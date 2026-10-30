package com.foxhole.guard.ui.cli.logs

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.cli.settings.SentinelAppDetailSheet
import com.foxhole.guard.ui.cli.settings.SentinelObservedAppRow

internal const val CLI_SENTINEL_INSTALL_PREVIEW_TAG = "cli_sentinel_install_preview"
internal const val CLI_SENTINEL_INSTALL_FULL_LIST_TAG = "cli_sentinel_install_full_list"

@Composable
internal fun CliRecentInstallationsPreview(installedApps: List<InstalledAppOption>) {
    val full = remember(installedApps) {
        sentinelInstallations(installedApps.filterNot(InstalledAppOption::isSystemApp))
    }
    val preview = remember(installedApps) { sentinelInstallationPreview(installedApps) }
    var open by rememberSaveable { mutableStateOf(false) }
    Spacer(Modifier.height(CliSpacing.sm))
    CliPanel(
        title = stringResource(R.string.cli_sentinel_recent_installations),
        icon = R.drawable.lin_apps,
        modifier = Modifier.fillMaxWidth().testTag(CLI_SENTINEL_INSTALL_PREVIEW_TAG)
            .cliPressable(onClick = { open = true }),
    ) {
        if (preview.isEmpty()) {
            Text(
                text = stringResource(R.string.cli_sentinel_installations_empty),
                style = CliType.small,
                color = LocalCliColors.current.dim,
            )
        } else {
            preview.forEachIndexed { index, app ->
                if (index > 0) CliRowDivider()
                SentinelObservedAppRow(app.packageName, app, onSelect = { open = true })
            }
        }
        if (full.size > preview.size) {
            Spacer(Modifier.height(CliSpacing.xs))
            Text(
                text = stringResource(R.string.cli_sentinel_view_all_installations, full.size),
                style = CliType.small,
                color = LocalCliColors.current.dim,
            )
        }
    }
    if (open) CliInstalledApplicationsSheet(full, onDismiss = { open = false })
}

@Composable
internal fun CliInstalledApplicationsSheet(apps: List<InstalledAppOption>, onDismiss: () -> Unit) {
    val sorted = remember(apps) { sentinelInstallations(apps) }
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    var detailPackage by rememberSaveable { mutableStateOf<String?>(null) }
    sorted.firstOrNull { it.packageName == detailPackage }?.let { app ->
        SentinelAppDetailSheet(app, onDismiss = { detailPackage = null })
    }
    CliBottomSheet(
        title = stringResource(R.string.cli_sentinel_installations),
        icon = R.drawable.lin_apps,
        contentScrollEnabled = false,
        onDismiss = onDismiss,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = listMaxHeight)
                .testTag(CLI_SENTINEL_INSTALL_FULL_LIST_TAG),
        ) {
            items(sorted, key = InstalledAppOption::packageName) { app ->
                SentinelObservedAppRow(app.packageName, app, onSelect = { detailPackage = app.packageName })
                CliRowDivider()
            }
        }
    }
}
