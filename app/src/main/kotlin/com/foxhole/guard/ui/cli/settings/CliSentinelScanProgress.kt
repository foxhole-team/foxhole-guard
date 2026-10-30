package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors

internal const val CLI_SENTINEL_SCAN_PROGRESS_TAG = "cli_sentinel_scan_progress"
internal const val CLI_SENTINEL_SCAN_CURRENT_APP_TAG = "cli_sentinel_scan_current_app"

internal fun sentinelScanProgressPercent(scan: SentinelScanControlState): Int? {
    if (scan.stage != SentinelScanStage.RUNNING || scan.requestedPackages <= 0) return null
    val completed = scan.completedPackages.coerceIn(0, scan.requestedPackages)
    return ((completed.toLong() * 100L) / scan.requestedPackages).toInt()
}

@Composable
internal fun CliSentinelScanProgress(
    scan: SentinelScanControlState,
    installedApps: List<InstalledAppOption>,
) {
    if (scan.stage != SentinelScanStage.RUNNING) return
    val colors = LocalCliColors.current
    val percent = sentinelScanProgressPercent(scan)
    val currentPackage = scan.packageName?.takeIf(String::isNotBlank)
    val currentLabel =
        installedApps.firstOrNull { it.packageName == currentPackage }?.label?.takeIf(String::isNotBlank)
            ?: currentPackage ?: stringResource(R.string.sentinel_static_current_app_unknown)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CliSpacing.xs),
    ) {
        val progressModifier = Modifier.fillMaxWidth().testTag(CLI_SENTINEL_SCAN_PROGRESS_TAG)
        if (percent == null) {
            LinearProgressIndicator(
                modifier = progressModifier,
                color = colors.accent,
                trackColor = colors.panelAlt,
            )
        } else {
            val completed = scan.completedPackages.coerceIn(0, scan.requestedPackages)
            LinearProgressIndicator(
                progress = { completed.toFloat() / scan.requestedPackages },
                modifier = progressModifier,
                color = colors.accent,
                trackColor = colors.panelAlt,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
        ) {
            Text(
                text = currentLabel,
                modifier = Modifier.weight(1f).testTag(CLI_SENTINEL_SCAN_CURRENT_APP_TAG),
                style = CliType.small,
                color = colors.fg,
            )
            if (percent != null) {
                Text(
                    text = stringResource(R.string.sentinel_static_progress_percent, percent),
                    style = CliType.small,
                    color = colors.dim,
                )
            }
        }
        Text(
            text = if (percent == null) {
                stringResource(R.string.sentinel_static_progress_unknown)
            } else {
                stringResource(
                    R.string.sentinel_static_progress_count,
                    scan.completedPackages.coerceIn(0, scan.requestedPackages),
                    scan.requestedPackages,
                )
            },
            style = CliType.small,
            color = colors.dim,
        )
    }
}
