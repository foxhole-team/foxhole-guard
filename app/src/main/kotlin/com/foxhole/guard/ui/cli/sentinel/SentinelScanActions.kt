package com.foxhole.guard.ui.cli.sentinel

import androidx.compose.material3.Text
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.scanner.SentinelScanControlState
import com.foxhole.guard.core.sentinel.scanner.SentinelScanStage
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.settings.CliSentinelScanProgress
import com.foxhole.guard.ui.cli.settings.scanStageResource
import com.foxhole.guard.ui.cli.CliType

@Composable
internal fun SentinelScanActions(
    apps: List<InstalledAppOption>,
    scan: SentinelScanControlState,
    enabled: Boolean,
    available: Boolean,
    onPackage: (String) -> Unit,
    onBaseline: () -> Unit,
    onCancel: () -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = apps.firstOrNull { it.packageName == selected }
    val running = scan.stage == SentinelScanStage.RUNNING
    var choosing by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(180))) {
    CliActionRow(label = stringResource(R.string.sentinel_static_select_app),
        onTap = { choosing = true }, value = current?.let {
            sentinelAppVisibleLabel(it, stringResource(R.string.sentinel_static_current_app_unknown))
        } ?: "-", icon = R.drawable.lin_apps, enabled = !running)
    current?.let { SentinelAppIdentity(it, it.packageName) }
    if (choosing) CliSentinelAppPicker(apps, selected, onSelect = { selected = it }, onDismiss = { choosing = false })
    CliActionRow(stringResource(R.string.sentinel_static_run),
        onTap = { current?.let { onPackage(it.packageName) } }, enabled = enabled && available && current != null && !running)
    CliActionRow(stringResource(R.string.sentinel_static_baseline), onTap = onBaseline, enabled = enabled && available && !running)
    if (running) CliActionRow(stringResource(R.string.sentinel_static_cancel), onTap = onCancel)
    Text(stringResource(scan.stage.scanStageResource()), style = CliType.small)
    CliSentinelScanProgress(scan, apps)
    }
}
