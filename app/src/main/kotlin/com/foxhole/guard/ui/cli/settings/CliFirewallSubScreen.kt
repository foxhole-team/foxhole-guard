package com.foxhole.guard.ui.cli.settings

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.labelRes
import com.foxhole.guard.guardian.enqueuePendingQuarantineAnalysis
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliBottomChromeClearance
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliAttentionPixel
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliCenteredEmptyNote
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliPixIcon
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionTone
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.components.cliMarchingBorder
import com.foxhole.guard.ui.cli.components.cliPressable
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.onNewAppQuarantineChanged
import com.foxhole.guard.ui.onQuarantinedAppResolved
import java.text.DateFormat
import java.util.Date

@Composable
internal fun CliFirewallSubScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val appState by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
    val settings = state.settings
    val pendingPackages = settings.expert.pendingQuarantinePackages
    val context = LocalContext.current
    LaunchedEffect(pendingPackages) {
        if (pendingPackages.isNotEmpty()) {
            viewModel.loadInstalledApps(force = true)
            context.enqueuePendingQuarantineAnalysis()
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CliSpacing.md),
    ) {
        CliScreenHeader(label = stringResource(R.string.cli_cfg_firewall), icon = R.drawable.pix_fire)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = LocalCliBottomChromeClearance.current),
        ) {
            CliKillSwitchPanel(viewModel = viewModel, settings = settings)
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliPanel(
                title = stringResource(R.string.cli_firewall_quarantine_title),
                icon = R.drawable.pix_forbidden,
                modifier = Modifier.fillMaxWidth(),
            ) {
                CliToggleRow(
                    label = stringResource(R.string.cli_anomaly_quarantine),
                    icon = R.drawable.pix_forbidden,
                    checked = settings.expert.newAppQuarantineEnabled,
                    onToggle = viewModel::onNewAppQuarantineChanged,
                    infoText = stringResource(R.string.cli_anomaly_quarantine_note),
                )
                if (!settings.expert.firewallEnabled) {
                    CliElbowLine(text = stringResource(R.string.cli_firewall_quarantine_needs_firewall))
                }
            }
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliQuarantineQueue(
                pendingPackages = pendingPackages,
                pendingDetails = settings.expert.pendingQuarantineAppDetails,
                installedApps = appState.installedApps,
                onResolve = viewModel::onQuarantinedAppResolved,
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
        }
    }
}

@Composable
private fun CliKillSwitchPanel(
    viewModel: HomeViewModel,
    settings: Settings,
) {
    val context = LocalContext.current
    CliPanel(
        title = stringResource(R.string.cli_firewall_killswitch_title),
        icon = R.drawable.pix_lock,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_firewall_killswitch_toggle),
            icon = R.drawable.pix_lock,
            checked = settings.expert.killSwitchEnabled,
            onToggle = viewModel::onKillSwitchEnabledChanged,
            infoText = stringResource(R.string.cli_firewall_killswitch_info),
        )
        CliElbowLine(text = stringResource(R.string.cli_firewall_killswitch_software))
        CliElbowLine(text = stringResource(R.string.cli_firewall_killswitch_system))
        CliActionRow(
            label = stringResource(R.string.cli_help_killswitch_link),
            icon = R.drawable.pix_settings,
            onTap = {
                runCatching {
                    context.startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
                }
            },
        )
    }
}

@Composable
private fun CliQuarantineQueue(
    pendingPackages: List<String>,
    pendingDetails: List<PendingQuarantineAppDetails>,
    installedApps: List<InstalledAppOption>,
    onResolve: (String, Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    val items = remember(pendingPackages, pendingDetails, installedApps) {
        quarantineQueueItems(pendingPackages, pendingDetails, installedApps)
    }
    var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedItem = items.firstOrNull { item -> item.packageName == selectedPackage }
    CliPanel(
        title = stringResource(R.string.cli_quarantine_title),
        icon = R.drawable.pix_forbidden,
        modifier = Modifier.fillMaxWidth(),
        attention = items.isNotEmpty(),
        attentionColor = colors.firewall,
    ) {
        if (items.isEmpty()) {
            CliCenteredEmptyNote(text = stringResource(R.string.cli_quarantine_empty))
        } else {
            CliQuarantineTableHeader()
            items.forEach { item ->
                CliQuarantineTableRow(item = item, onSelect = { selectedPackage = item.packageName })
            }
        }
    }
    selectedItem?.let { item ->
        CliQuarantineDecisionSheet(
            item = item,
            onDismiss = { selectedPackage = null },
            onResolve = { keepBlocked ->
                selectedPackage = null
                onResolve(item.packageName, keepBlocked)
            },
        )
    }
}

@Composable
private fun CliQuarantineTableHeader() {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(modifier = Modifier.width(28.dp + CliSpacing.sm))
        Text(
            text = stringResource(R.string.cli_quarantine_column_app),
            style = CliType.small,
            color = colors.faint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cli_quarantine_column_installed),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(66.dp),
        )
        Text(
            text = stringResource(R.string.cli_quarantine_column_action),
            style = CliType.small,
            color = colors.faint,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
        )
    }
}

@Composable
private fun CliQuarantineTableRow(
    item: CliQuarantineQueueItem,
    onSelect: () -> Unit,
) {
    val colors = LocalCliColors.current
    val date = remember(item.installTime) { shortDate(item.installTime) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 52.dp)
            .cliPressable(onClick = onSelect)
            .cliMarchingBorder(colors.firewall)
            .padding(horizontal = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CliQuarantineAppIcon(item = item)
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = CliType.body,
                color = colors.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.packageName,
                style = CliType.small,
                color = colors.faint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = date,
            style = CliType.small,
            color = colors.dim,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(66.dp),
        )
        Row(
            modifier = Modifier.width(72.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CliAttentionPixel(color = colors.firewall)
            Spacer(modifier = Modifier.width(CliSpacing.xs))
            CliChip(
                label = stringResource(R.string.cli_quarantine_decide),
                color = colors.firewall,
                onClick = onSelect,
            )
        }
    }
    Spacer(modifier = Modifier.height(CliSpacing.xs))
}

@Composable
private fun CliQuarantineAppIcon(item: CliQuarantineQueueItem) {
    val colors = LocalCliColors.current
    val icon = rememberCliAppIcon(
        packageName = item.packageName,
        versionCode = item.versionCode,
        lastUpdateTime = item.lastUpdateTime,
        bitmapSize = 28.dp,
    )
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(colors.panelAlt),
        contentAlignment = Alignment.Center,
    ) {
        if (icon == null) {
            CliPixIcon(
                id = R.drawable.pix_forbidden,
                contentDescription = null,
                size = 16.dp,
                tint = colors.firewall,
            )
        } else {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape),
            )
        }
    }
}

@Composable
private fun CliQuarantineDecisionSheet(
    item: CliQuarantineQueueItem,
    onDismiss: () -> Unit,
    onResolve: (Boolean) -> Unit,
) {
    val colors = LocalCliColors.current
    val signalLabels = mutableListOf<String>()
    for (signal in item.riskSignals) {
        signalLabels += stringResource(signal.labelRes())
    }
    val signals = if (!item.analysisComplete) {
        stringResource(R.string.cli_quarantine_analysis_pending)
    } else if (signalLabels.isEmpty()) {
        stringResource(R.string.installed_app_risk_signals_none)
    } else {
        signalLabels.joinToString(" · ")
    }
    val riskColor =
        if (!item.analysisComplete) {
            colors.firewall
        } else {
            when (item.riskLevel) {
                InstalledAppRiskLevel.LOW -> colors.ok
                InstalledAppRiskLevel.MEDIUM -> colors.warn
                InstalledAppRiskLevel.HIGH -> colors.err
            }
        }
    val riskLabel =
        if (item.analysisComplete) {
            stringResource(item.riskLevel.labelRes())
        } else {
            stringResource(R.string.cli_quarantine_analysis_pending)
        }
    CliBottomSheet(
        onDismiss = onDismiss,
        title = stringResource(R.string.cli_quarantine_confirm_title),
        icon = R.drawable.pix_forbidden,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CliQuarantineAppIcon(item = item)
            Spacer(modifier = Modifier.width(CliSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.label, style = CliType.body, color = colors.fg)
                Text(text = item.packageName, style = CliType.small, color = colors.faint)
            }
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliElbowLine(text = stringResource(R.string.cli_quarantine_pending_state), color = colors.firewall)
        CliElbowLine(
            text = stringResource(R.string.cli_quarantine_install_marker, longDate(item.installTime)),
        )
        CliElbowLine(
            text = stringResource(
                R.string.cli_quarantine_source_marker,
                item.installerPackageName ?: stringResource(R.string.installed_app_source_unknown),
            ),
        )
        CliElbowLine(
            text = stringResource(R.string.cli_quarantine_risk_marker, riskLabel),
            color = riskColor,
        )
        CliElbowLine(text = stringResource(R.string.cli_quarantine_signals_marker, signals))
        Spacer(modifier = Modifier.height(CliSpacing.md))
        CliSheetActionsRow(
            onCancel = onDismiss,
            actions = listOf(
                CliSheetAction(
                    label = stringResource(R.string.cli_quarantine_block),
                    onClick = { onResolve(true) },
                    tone = CliSheetActionTone.DESTRUCTIVE,
                ),
                CliSheetAction(
                    label = stringResource(R.string.cli_quarantine_allow),
                    onClick = { onResolve(false) },
                ),
            ),
        )
    }
}

internal data class CliQuarantineQueueItem(
    val packageName: String,
    val label: String,
    val versionCode: Long?,
    val lastUpdateTime: Long?,
    val installTime: Long?,
    val installerPackageName: String?,
    val riskLevel: InstalledAppRiskLevel,
    val riskSignals: List<InstalledAppRiskSignal>,
    val analysisComplete: Boolean,
)

internal fun quarantineQueueItems(
    pendingPackages: List<String>,
    pendingDetails: List<PendingQuarantineAppDetails>,
    installedApps: List<InstalledAppOption>,
): List<CliQuarantineQueueItem> {
    val appsByPackage = installedApps.associateBy(InstalledAppOption::packageName)
    val detailsByPackage = pendingDetails.associateBy(PendingQuarantineAppDetails::packageName)
    return pendingPackages
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .map { packageName ->
            val app = appsByPackage[packageName]
            val details = detailsByPackage[packageName]
            CliQuarantineQueueItem(
                packageName = packageName,
                label = details?.label?.ifBlank { packageName }
                    ?: app?.label?.ifBlank { packageName }
                    ?: packageName,
                versionCode = app?.versionCode,
                lastUpdateTime = app?.lastUpdateTime,
                installTime = details?.firstInstallTime ?: app?.firstInstallTime ?: details?.detectedAt,
                installerPackageName = details?.installerPackageName ?: app?.installerPackageName,
                riskLevel = details?.riskLevel ?: app?.riskLevel ?: InstalledAppRiskLevel.LOW,
                riskSignals = details?.riskSignals ?: app?.riskSignals.orEmpty(),
                analysisComplete = details?.analysisComplete == true,
            )
        }
}

private fun shortDate(timestamp: Long?): String =
    timestamp?.takeIf { value -> value > 0L }
        ?.let { value -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(value)) }
        ?: "—"

private fun longDate(timestamp: Long?): String =
    timestamp?.takeIf { value -> value > 0L }
        ?.let { value -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(value)) }
        ?: "—"
