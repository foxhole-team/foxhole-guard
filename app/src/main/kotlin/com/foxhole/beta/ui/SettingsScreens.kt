package com.foxhole.beta.ui

import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.foxhole.beta.R
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_STEP_SECONDS
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeChoiceCard
import com.foxhole.beta.ui.FoxholeLazyScaffold
import com.foxhole.beta.ui.FoxholePreferenceCard
import com.foxhole.beta.ui.UsageTotalsCard
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

@Composable
fun SettingsHomeScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    onOpenTraffic: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenSmartStart: () -> Unit,
    onOpenApplication: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenExpert: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onUnlockExpertSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repositoryOpenFailed = stringResource(R.string.open_repository_failed)
    val supportChannelOpenFailed = stringResource(R.string.support_channel_open_failed)
    var unlockDialogVisible by rememberSaveable { mutableStateOf(false) }
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    val expertVisible = state.settings.ui.showExpertSettings
    val onRepositoryClick = {
        if (!openFoxholeRepository(context)) {
            scope.launch {
                snackbarHostState.showBanner(
                    repositoryOpenFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }
    val onSupportBotClick = {
        if (!openTelegramChannel(context)) {
            scope.launch {
                snackbarHostState.showBanner(
                    supportChannelOpenFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }
    val onFooterClick = {
        if (!unlockDialogVisible) {
            if (expertVisible) {
                versionTapCount = 0
            } else {
                val nextTapCount = versionTapCount + 1
                versionTapCount = nextTapCount
                if (nextTapCount >= 5) {
                    unlockDialogVisible = true
                }
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        settingsHomeNavigationItems(
            hasSmartProfile = state.hasSmartProfile,
            expertVisible = expertVisible,
            onOpenTraffic = onOpenTraffic,
            onOpenRouting = onOpenRouting,
            onOpenRoutingApps = onOpenRoutingApps,
            onOpenRoutingSites = onOpenRoutingSites,
            onOpenSmartStart = onOpenSmartStart,
            onOpenApplication = onOpenApplication,
            onOpenHelp = onOpenHelp,
            onOpenExpert = onOpenExpert,
            onOpenDiagnostics = onOpenDiagnostics,
        )
        settingsHomeFooterItem(
            appVersion = state.appVersion,
            onRepositoryClick = onRepositoryClick,
            onSupportBotClick = onSupportBotClick,
            onClick = onFooterClick,
        )
    }

    if (unlockDialogVisible) {
        ConfirmDialog(
            title = stringResource(R.string.expert_unlock_confirm_title),
            body = stringResource(R.string.expert_unlock_confirm_body),
            confirmLabel = stringResource(R.string.enable_label),
            icon = Icons.Outlined.Shield,
            dismissLabel = stringResource(R.string.cancel),
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            onDismiss = {
                unlockDialogVisible = false
                versionTapCount = 0
            },
            onConfirm = {
                onUnlockExpertSettings()
                unlockDialogVisible = false
                versionTapCount = 0
            },
        )
    }
}

private fun LazyListScope.settingsHomeNavigationItems(
    hasSmartProfile: Boolean,
    expertVisible: Boolean,
    onOpenTraffic: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenSmartStart: () -> Unit,
    onOpenApplication: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenExpert: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    item {
        SettingsNavigationGroup {
            if (hasSmartProfile) {
                SettingsGroupedNavigationRow(
                    modifier = Modifier.testTag("settings_smart_start_action"),
                    icon = Icons.Outlined.Speed,
                    title = stringResource(R.string.smart_start_settings_title),
                    summary = stringResource(R.string.smart_start_settings_summary),
                    summaryMaxLines = 3,
                    onClick = onOpenSmartStart,
                )
                SettingsGroupDivider()
            }
            SettingsGroupedNavigationRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.traffic_settings),
                summary = stringResource(R.string.settings_home_network_summary),
                onClick = onOpenTraffic,
            )
        }
    }
    item {
        SettingsRoutingNavigationGroup(
            onOpenRouting = onOpenRouting,
            onOpenRoutingApps = onOpenRoutingApps,
            onOpenRoutingSites = onOpenRoutingSites,
        )
    }
    item {
        SettingsNavigationGroup {
            SettingsGroupedNavigationRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.app_settings),
                summary = stringResource(R.string.settings_home_application_summary),
                onClick = onOpenApplication,
            )
            SettingsGroupDivider()
            SettingsGroupedNavigationRow(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(R.string.help_title),
                summary = stringResource(R.string.settings_home_help_summary),
                onClick = onOpenHelp,
            )
            SettingsGroupDivider()
            SettingsGroupedNavigationRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.diagnostics_and_usage),
                summary = stringResource(R.string.settings_home_diagnostics_summary),
                onClick = onOpenDiagnostics,
            )
        }
    }
    if (expertVisible) {
        item {
            SettingsNavigationRow(
                modifier = Modifier.testTag("settings_expert_action"),
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.expert_settings),
                summary = stringResource(R.string.settings_home_advanced_summary),
                onClick = onOpenExpert,
            )
        }
    }
}

private fun LazyListScope.settingsHomeFooterItem(
    appVersion: String,
    onRepositoryClick: () -> Unit,
    onSupportBotClick: () -> Unit,
    onClick: () -> Unit,
) {
    item {
        SettingsFooterVersionText(
            text = stringResource(R.string.settings_footer_version, appVersion),
            summary = stringResource(R.string.settings_home_version_summary_hidden),
            onRepositoryClick = onRepositoryClick,
            onSupportBotClick = onSupportBotClick,
            onClick = onClick,
        )
    }
}

@Composable
private fun SettingsRoutingNavigationGroup(
    onOpenRouting: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
) {
    SettingsNavigationGroup {
        SettingsGroupedNavigationRow(
            icon = Icons.Outlined.AccountTree,
            title = stringResource(R.string.traffic_rules),
            summary = stringResource(R.string.settings_home_routing_summary),
            onClick = onOpenRouting,
        )
        SettingsGroupDivider()
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_routing_apps_action"),
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.routing_apps_title),
            summary = stringResource(R.string.settings_home_apps_summary),
            onClick = onOpenRoutingApps,
        )
        SettingsGroupDivider()
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_routing_sites_action"),
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.routing_sites_title),
            summary = stringResource(R.string.settings_home_sites_summary),
            onClick = onOpenRoutingSites,
        )
    }
}

@Composable
private fun SettingsNavigationGroup(content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .foxholeMenuShadow(shape = shape)
                .clip(shape),
        shape = shape,
        color = LocalFoxholeUiPalette.current.cardContainerColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content,
        )
    }
}

@Composable
private fun SettingsGroupedNavigationRow(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    summary: String,
    summaryMaxLines: Int = 1,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(7.dp).size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = summary.trimEnd().removeSuffix("."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        thickness = 2.dp,
        color = Color.Transparent,
    )
}

@Composable
fun SmartStartSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSmartStartProtocolSelectionTimeoutChanged: (Int) -> Unit,
    onSmartStartRefreshSelectionTimeoutChanged: (Int) -> Unit,
    onSmartStartTransportPrioritySelected: (SmartStartTransportPriority) -> Unit,
) {
    var protocolTimeoutExpanded by rememberSaveable { mutableStateOf(false) }
    var refreshTimeoutExpanded by rememberSaveable { mutableStateOf(false) }
    var transportPriorityExpanded by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.smart_start_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        if (state.hasSmartProfile) {
            item {
                SettingsControlGroup {
                    DropdownSettingRow(
                        title = stringResource(R.string.smart_start_protocol_timeout_title),
                        value = smartStartTimeoutLabel(state.settings.connection.smartStartProtocolSelectionTimeoutSeconds),
                        expanded = protocolTimeoutExpanded,
                        onExpandedChange = { protocolTimeoutExpanded = it },
                        values = smartStartTimeoutOptions(SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS),
                        selected = state.settings.connection.smartStartProtocolSelectionTimeoutSeconds,
                        label = { smartStartTimeoutLabel(it) },
                        onSelect = onSmartStartProtocolSelectionTimeoutChanged,
                        summary = stringResource(R.string.smart_start_protocol_timeout_summary),
                        leadingIcon = Icons.Outlined.Speed,
                        optionIcon = { Icons.Outlined.Speed },
                        summaryMaxLines = 3,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.smart_start_refresh_timeout_title),
                        value = smartStartTimeoutLabel(state.settings.connection.smartStartRefreshSelectionTimeoutSeconds),
                        expanded = refreshTimeoutExpanded,
                        onExpandedChange = { refreshTimeoutExpanded = it },
                        values = smartStartTimeoutOptions(SMART_START_REFRESH_TIMEOUT_MIN_SECONDS),
                        selected = state.settings.connection.smartStartRefreshSelectionTimeoutSeconds,
                        label = { smartStartTimeoutLabel(it) },
                        onSelect = onSmartStartRefreshSelectionTimeoutChanged,
                        summary = stringResource(R.string.smart_start_refresh_timeout_summary),
                        leadingIcon = Icons.Outlined.Refresh,
                        optionIcon = { Icons.Outlined.Refresh },
                        summaryMaxLines = 3,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.smart_start_transport_priority_title),
                        value = smartStartTransportPriorityLabel(state.settings.connection.smartStartTransportPriority),
                        expanded = transportPriorityExpanded,
                        onExpandedChange = { transportPriorityExpanded = it },
                        values = SmartStartTransportPriority.entries,
                        selected = state.settings.connection.smartStartTransportPriority,
                        label = { smartStartTransportPriorityLabel(it) },
                        onSelect = onSmartStartTransportPrioritySelected,
                        summary = stringResource(R.string.smart_start_transport_priority_summary),
                        leadingIcon = Icons.Outlined.SwapVert,
                        optionIcon = ::smartStartTransportPriorityIcon,
                        summaryMaxLines = 3,
                        grouped = true,
                    )
                }
            }
        }
    }
}

private fun smartStartTimeoutOptions(minSeconds: Int): List<Int> =
    generateSequence(minSeconds) { value -> value + SMART_START_TIMEOUT_STEP_SECONDS }
        .takeWhile { value -> value <= SMART_START_TIMEOUT_MAX_SECONDS }
        .toList()

@Composable
private fun smartStartTimeoutLabel(seconds: Int): String =
    pluralStringResource(R.plurals.smart_start_timeout_seconds_value, seconds, seconds)

@Composable
private fun smartStartTransportPriorityLabel(value: SmartStartTransportPriority): String =
    stringResource(
        when (value) {
            SmartStartTransportPriority.ALL -> R.string.smart_start_transport_priority_all
            SmartStartTransportPriority.UDP -> R.string.smart_start_transport_priority_udp
            SmartStartTransportPriority.TCP -> R.string.smart_start_transport_priority_tcp
        },
    )

private fun smartStartTransportPriorityIcon(value: SmartStartTransportPriority): ImageVector =
    when (value) {
        SmartStartTransportPriority.ALL -> Icons.Outlined.SwapVert
        SmartStartTransportPriority.UDP -> Icons.Outlined.Speed
        SmartStartTransportPriority.TCP -> Icons.Outlined.Public
    }

private fun openFoxholeRepository(context: Context): Boolean {
    val intent =
        Intent(Intent.ACTION_VIEW, FOXHOLE_REPOSITORY_URL.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

private const val FOXHOLE_REPOSITORY_URL = "https://github.com/foxhole-repo/foxhole-app"
private val TELEGRAM_PACKAGE_CANDIDATES =
    listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    )

internal fun installedTelegramPackage(packageManager: PackageManager): String? =
    TELEGRAM_PACKAGE_CANDIDATES.firstOrNull { packageName ->
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
        }.isSuccess
    }

private const val FOXHOLE_TELEGRAM_CHANNEL = "foxhole_repo"

private fun supportChannelBrowserUri(): Uri = "https://t.me/$FOXHOLE_TELEGRAM_CHANNEL".toUri()

private fun supportChannelTelegramUri(): Uri = "tg://resolve?domain=$FOXHOLE_TELEGRAM_CHANNEL".toUri()

private fun openTelegramChannel(context: Context): Boolean {
    val browserIntent =
        Intent(Intent.ACTION_VIEW, supportChannelBrowserUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
    val telegramPackage = installedTelegramPackage(context.packageManager)
    if (telegramPackage != null) {
        val telegramIntent =
            Intent(Intent.ACTION_VIEW, supportChannelTelegramUri())
                .setPackage(telegramPackage)
                .addCategory(Intent.CATEGORY_BROWSABLE)
        try {
            context.startActivity(telegramIntent)
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return try {
        context.startActivity(browserIntent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "UNUSED_PARAMETER")
@Composable
fun TrafficSettingsScreen(
    title: String,
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onAcknowledgeUnsafeWarning: () -> Unit,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onLatencyProbeMethodSelected: (LatencyProbeMethod) -> Unit,
    onTunStackSelected: (TunStack) -> Unit,
    onLocalProxyAuthEnabledChanged: (Boolean) -> Unit,
    onLocalProxyAuthChanged: (LocalAuthSettings) -> Unit,
    onLanProxyAuthEnabledChanged: (Boolean) -> Unit,
    onLanProxyAuthChanged: (LocalAuthSettings) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onProxySurfaceModeSelected: (ProxySurfaceMode) -> Unit,
    onLanProxySurfaceModeSelected: (ProxySurfaceMode) -> Unit,
    onSocksSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onHttpSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onMixedSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onMtuChanged: (Int) -> Unit,
    onPreferIpv6Changed: (Boolean) -> Unit,
    onDomainStrategySelected: (DomainStrategy) -> Unit,
    onBypassLanChanged: (Boolean) -> Unit,
    onAutoRefreshSubscriptionsChanged: (Boolean) -> Unit,
    onSubscriptionRefreshIntervalSelected: (SubscriptionRefreshInterval) -> Unit,
    onIpInfoEndpointChanged: (String) -> Unit,
) {
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var latencyProbeMethodMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var tunStackMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var proxySurfaceModeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var lanProxySurfaceModeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var proxyPortDialog by rememberSaveable { mutableStateOf(false) }
    var lanProxyPortDialog by rememberSaveable { mutableStateOf(false) }
    var mtuDialog by rememberSaveable { mutableStateOf(false) }
    var domainMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var subscriptionRefreshIntervalMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var endpointDialog by rememberSaveable { mutableStateOf(false) }
    val wifiLanAddress by rememberWifiLanAddress()
    val selectedModeOption = currentHomeModeOption(state.settings)
    val pingHttpLabel = stringResource(R.string.latency_probe_method_http)
    val pingIcmpLabel = stringResource(R.string.latency_probe_method_icmp)
    val pingTcpLabel = stringResource(R.string.latency_probe_method_tcp)
    val latencyProbeMethodLabel: (LatencyProbeMethod) -> String = { value ->
        when (value) {
            LatencyProbeMethod.HTTP -> pingHttpLabel
            LatencyProbeMethod.ICMP -> pingIcmpLabel
            LatencyProbeMethod.TCP -> pingTcpLabel
        }
    }
    fun updateSurfacePort(
        mode: ProxySurfaceMode,
        port: Int,
    ) {
        val surface = state.settings.expert.localSurfaces.surfaceFor(mode).copy(port = port)
        when (mode) {
            ProxySurfaceMode.SOCKS5 -> onSocksSurfaceChanged(surface)
            ProxySurfaceMode.HTTP -> onHttpSurfaceChanged(surface)
            ProxySurfaceMode.ALL -> onMixedSurfaceChanged(surface)
        }
    }
    SettingsScaffold(
        title = title,
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = trafficInfoBody(state),
            )
        }
        item {
            SettingsControlGroup {
                DropdownSettingRow(
                    title = stringResource(R.string.traffic_mode),
                    value = homeModeMenuLabel(selectedModeOption),
                    expanded = modeMenuExpanded,
                    onExpandedChange = { modeMenuExpanded = it },
                    values = HomeModeOption.entries,
                    selected = selectedModeOption,
                    label = { homeModeMenuLabel(it) },
                    onSelect = { option ->
                        applyHomeModeSelection(
                            mode = option,
                            onTrafficModeSelected = onTrafficModeSelected,
                            onPerAppRoutingModeSelected = onPerAppRoutingModeSelected,
                            selectedPackages = state.settings.expert.selectedPackages,
                            currentPerAppRoutingMode = state.settings.expert.perAppRoutingMode,
                        )
                    },
                    leadingIcon = homeModeOptionIcon(selectedModeOption),
                    optionIcon = ::homeModeOptionIcon,
                    grouped = true,
                )
                if (state.settings.traffic.mode == TrafficMode.TUNNEL) {
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.tun_stack),
                        value = tunStackLabel(state.settings.traffic.tunStack),
                        expanded = tunStackMenuExpanded,
                        onExpandedChange = { tunStackMenuExpanded = it },
                        values = TunStack.entries,
                        selected = state.settings.traffic.tunStack,
                        label = { tunStackLabel(it) },
                        onSelect = onTunStackSelected,
                        leadingIcon = Icons.Outlined.Shield,
                        optionIcon = ::tunStackIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.mtu),
                        value = state.settings.traffic.mtu.toString(),
                        leadingIcon = Icons.Outlined.Tune,
                        onClick = { mtuDialog = true },
                        grouped = true,
                    )
                }
                if (state.settings.traffic.mode == TrafficMode.PROXY) {
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.proxy_surface_mode_title),
                        value = proxySurfaceModeLabel(state.settings.expert.localSurfaces.proxyMode),
                        expanded = proxySurfaceModeMenuExpanded,
                        onExpandedChange = { proxySurfaceModeMenuExpanded = it },
                        values = ProxySurfaceMode.entries,
                        selected = state.settings.expert.localSurfaces.proxyMode,
                        label = { proxySurfaceModeLabel(it) },
                        onSelect = onProxySurfaceModeSelected,
                        leadingIcon = Icons.Outlined.SwapVert,
                        optionIcon = ::proxySurfaceModeIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.proxy_auth_title),
                        checked = state.settings.expert.localSurfaces.auth.enabled,
                        leadingIcon = Icons.Outlined.Shield,
                        summary = stringResource(R.string.proxy_auth_summary),
                        onCheckedChange = onLocalProxyAuthEnabledChanged,
                        grouped = true,
                    )
                    if (state.settings.expert.localSurfaces.auth.enabled) {
                        SettingsControlGroupDivider()
                        LocalProxyAuthEditor(
                            auth = state.settings.expert.localSurfaces.auth,
                            onAuthChanged = onLocalProxyAuthChanged,
                            grouped = true,
                        )
                    }
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.proxy_surface_endpoint_title),
                        value = proxySurfaceEndpointSummary(state.settings.expert.localSurfaces.proxyMode, state.settings.expert.localSurfaces),
                        leadingIcon = Icons.Outlined.Public,
                        onClick = null,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.port),
                        value = state.settings.expert.localSurfaces.surfaceFor(state.settings.expert.localSurfaces.proxyMode).port.toString(),
                        leadingIcon = Icons.Outlined.Router,
                        onClick = { proxyPortDialog = true },
                        grouped = true,
                    )
                }
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.proxy_lan_access_title),
                    checked = state.settings.expert.localSurfaces.allowLanAccess,
                    leadingIcon = Icons.Outlined.Router,
                    onCheckedChange = { enabled ->
                        if (!enabled || wifiLanAddress != null) {
                            onLocalProxyLanAccessChanged(enabled)
                        }
                    },
                    summary =
                        proxyLanAccessSummary(
                            allowLanAccess = state.settings.expert.localSurfaces.allowLanAccess,
                            wifiLanAddress = wifiLanAddress,
                        ),
                    enabled = wifiLanAddress != null || state.settings.expert.localSurfaces.allowLanAccess,
                    grouped = true,
                )
                if (state.settings.expert.localSurfaces.allowLanAccess) {
                    SettingsControlGroupDivider()
                    DropdownSettingRow(
                        title = stringResource(R.string.proxy_surface_mode_title),
                        value = proxySurfaceModeLabel(state.settings.expert.localSurfaces.lanProxyMode),
                        expanded = lanProxySurfaceModeMenuExpanded,
                        onExpandedChange = { lanProxySurfaceModeMenuExpanded = it },
                        values = ProxySurfaceMode.entries,
                        selected = state.settings.expert.localSurfaces.lanProxyMode,
                        label = { proxySurfaceModeLabel(it) },
                        onSelect = onLanProxySurfaceModeSelected,
                        leadingIcon = Icons.Outlined.SwapVert,
                        optionIcon = ::proxySurfaceModeIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.lan_proxy_auth_title),
                        checked = state.settings.expert.localSurfaces.lanAuth.enabled,
                        leadingIcon = Icons.Outlined.Shield,
                        summary = stringResource(R.string.lan_proxy_auth_summary),
                        onCheckedChange = onLanProxyAuthEnabledChanged,
                        grouped = true,
                    )
                    if (state.settings.expert.localSurfaces.lanAuth.enabled) {
                        SettingsControlGroupDivider()
                        LocalProxyAuthEditor(
                            auth = state.settings.expert.localSurfaces.lanAuth,
                            onAuthChanged = onLanProxyAuthChanged,
                            grouped = true,
                        )
                    }
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.proxy_surface_endpoint_title),
                        value = lanProxySurfaceEndpointSummary(
                            mode = state.settings.expert.localSurfaces.lanProxyMode,
                            surfaces = state.settings.expert.localSurfaces,
                            wifiLanAddress = wifiLanAddress,
                        ),
                        leadingIcon = Icons.Outlined.Public,
                        onClick = null,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.port),
                        value = state.settings.expert.localSurfaces.surfaceFor(state.settings.expert.localSurfaces.lanProxyMode).port.toString(),
                        leadingIcon = Icons.Outlined.Router,
                        onClick = { lanProxyPortDialog = true },
                        grouped = true,
                    )
                }
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.prefer_ipv6_routes),
                    checked = state.settings.traffic.preferIpv6,
                    leadingIcon = Icons.Outlined.Public,
                    onCheckedChange = onPreferIpv6Changed,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.domain_strategy),
                    value = domainStrategyLabel(state.settings.traffic.domainStrategy),
                    expanded = domainMenuExpanded,
                    onExpandedChange = { domainMenuExpanded = it },
                    values = DomainStrategy.entries,
                    selected = state.settings.traffic.domainStrategy,
                    label = { domainStrategyLabel(it) },
                    onSelect = onDomainStrategySelected,
                    leadingIcon = Icons.Outlined.AccountTree,
                    optionIcon = ::domainStrategyIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.bypass_lan),
                    checked = state.settings.expert.bypassLan,
                    summary = stringResource(R.string.bypass_lan_summary),
                    leadingIcon = Icons.Outlined.Router,
                    onCheckedChange = onBypassLanChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.auto_refresh_subscriptions_title),
                    checked = state.settings.connection.autoRefreshSubscriptions,
                    leadingIcon = Icons.Outlined.Refresh,
                    summary = stringResource(R.string.auto_refresh_subscriptions_summary),
                    onCheckedChange = onAutoRefreshSubscriptionsChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.auto_refresh_subscriptions_interval_title),
                    value = subscriptionRefreshIntervalLabel(state.settings.connection.subscriptionRefreshInterval),
                    expanded = subscriptionRefreshIntervalMenuExpanded,
                    onExpandedChange = { subscriptionRefreshIntervalMenuExpanded = it },
                    values = SubscriptionRefreshInterval.entries,
                    selected = state.settings.connection.subscriptionRefreshInterval,
                    label = { subscriptionRefreshIntervalLabel(it) },
                    onSelect = onSubscriptionRefreshIntervalSelected,
                    leadingIcon = Icons.Outlined.Refresh,
                    optionIcon = { Icons.Outlined.Refresh },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.latency_probe_method_title),
                    value = latencyProbeMethodLabel(state.settings.connection.latencyProbeMethod),
                    expanded = latencyProbeMethodMenuExpanded,
                    onExpandedChange = { latencyProbeMethodMenuExpanded = it },
                    values = LatencyProbeMethod.entries,
                    selected = state.settings.connection.latencyProbeMethod,
                    label = { latencyProbeMethodLabel(it) },
                    onSelect = onLatencyProbeMethodSelected,
                    summary = stringResource(R.string.latency_probe_method_summary),
                    summaryMaxLines = 5,
                    leadingIcon = Icons.Outlined.Speed,
                    optionIcon = ::latencyProbeMethodIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.ip_info_endpoint),
                    value = state.settings.connection.ipInfoEndpoint,
                    leadingIcon = Icons.Outlined.Public,
                    onClick = { endpointDialog = true },
                    grouped = true,
                )
            }
        }
    }

    if (mtuDialog) {
        IntValueDialog(
            title = stringResource(R.string.mtu),
            icon = Icons.Outlined.Tune,
            initialValue = state.settings.traffic.mtu,
            onDismiss = { mtuDialog = false },
            onConfirm = onMtuChanged,
        )
    }

    if (proxyPortDialog) {
        val mode = state.settings.expert.localSurfaces.proxyMode
        IntValueDialog(
            title = stringResource(R.string.port),
            icon = Icons.Outlined.Router,
            initialValue = state.settings.expert.localSurfaces.surfaceFor(mode).port,
            onDismiss = { proxyPortDialog = false },
            onConfirm = { port -> updateSurfacePort(mode, port) },
        )
    }

    if (lanProxyPortDialog) {
        val mode = state.settings.expert.localSurfaces.lanProxyMode
        IntValueDialog(
            title = stringResource(R.string.port),
            icon = Icons.Outlined.Router,
            initialValue = state.settings.expert.localSurfaces.surfaceFor(mode).port,
            onDismiss = { lanProxyPortDialog = false },
            onConfirm = { port -> updateSurfacePort(mode, port) },
        )
    }

    if (endpointDialog) {
        TextValueDialog(
            title = stringResource(R.string.ip_info_endpoint),
            icon = Icons.Outlined.Public,
            initialValue = state.settings.connection.ipInfoEndpoint,
            singleLine = true,
            onDismiss = { endpointDialog = false },
            onConfirm = onIpInfoEndpointChanged,
        )
    }

}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@Composable
fun ApplicationSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onLocaleSelected: (AppLocale) -> Unit,
    onAutoReconnectChanged: (Boolean) -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onBlockScreenshotsChanged: (Boolean) -> Unit,
) {
    val systemThemeLabel = stringResource(R.string.theme_mode_system)
    val darkThemeLabel = stringResource(R.string.theme_mode_dark)
    val lightThemeLabel = stringResource(R.string.theme_mode_light)
    val systemLocaleLabel = stringResource(R.string.language_system)
    val russianLocaleLabel = stringResource(R.string.language_russian)
    val englishLocaleLabel = stringResource(R.string.language_english)
    val themeModeLabel: (ThemeMode) -> String = { value ->
        when (value) {
            ThemeMode.SYSTEM -> systemThemeLabel
            ThemeMode.DARK -> darkThemeLabel
            ThemeMode.LIGHT -> lightThemeLabel
        }
    }
    val localeLabel: (AppLocale) -> String = { value ->
        when (value) {
            AppLocale.SYSTEM -> systemLocaleLabel
            AppLocale.RU -> russianLocaleLabel
            AppLocale.EN -> englishLocaleLabel
        }
    }
    var themeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var localeMenuExpanded by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.app_settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            SettingsControlGroup {
                DropdownSettingRow(
                    title = stringResource(R.string.theme),
                    value = themeModeLabel(state.settings.ui.themeMode),
                    expanded = themeMenuExpanded,
                    onExpandedChange = { themeMenuExpanded = it },
                    values = ThemeMode.entries,
                    selected = state.settings.ui.themeMode,
                    label = { themeModeLabel(it) },
                    onSelect = onThemeSelected,
                    leadingIcon = Icons.Outlined.Tune,
                    optionIcon = ::themeModeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.language),
                    value = localeLabel(state.settings.ui.locale),
                    expanded = localeMenuExpanded,
                    onExpandedChange = { localeMenuExpanded = it },
                    values = AppLocale.entries,
                    selected = state.settings.ui.locale,
                    label = { localeLabel(it) },
                    onSelect = onLocaleSelected,
                    leadingIcon = Icons.Outlined.Public,
                    optionIcon = ::localeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.auto_reconnect),
                    checked = state.settings.connection.autoReconnect,
                    leadingIcon = Icons.Outlined.Refresh,
                    onCheckedChange = onAutoReconnectChanged,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.auto_start_on_boot),
                    checked = state.settings.connection.autoStartOnBoot,
                    leadingIcon = Icons.Outlined.PhoneAndroid,
                    onCheckedChange = onAutoStartChanged,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.block_screenshots_title),
                    checked = state.settings.expert.blockScreenshots,
                    summary = stringResource(R.string.block_screenshots_summary),
                    leadingIcon = Icons.Outlined.Shield,
                    onCheckedChange = onBlockScreenshotsChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
            }
        }
    }
}

private fun trafficModeIcon(value: TrafficMode): ImageVector =
    when (value) {
        TrafficMode.TUNNEL -> Icons.Outlined.Shield
        TrafficMode.PROXY -> Icons.Outlined.SwapVert
    }

@Composable
private fun proxySurfaceModeLabel(value: ProxySurfaceMode): String =
    when (value) {
        ProxySurfaceMode.SOCKS5 -> stringResource(R.string.socks_inbound)
        ProxySurfaceMode.HTTP -> stringResource(R.string.http_inbound)
        ProxySurfaceMode.ALL -> stringResource(R.string.proxy_surface_mode_all)
    }

private fun proxySurfaceModeIcon(value: ProxySurfaceMode): ImageVector =
    when (value) {
        ProxySurfaceMode.SOCKS5 -> Icons.Outlined.Shield
        ProxySurfaceMode.HTTP -> Icons.Outlined.Public
        ProxySurfaceMode.ALL -> Icons.Outlined.Apps
    }

@Suppress("UNUSED_PARAMETER")
@Composable
private fun proxySurfaceEndpointSummary(
    mode: ProxySurfaceMode,
    surfaces: LocalSurfaceSettings,
): String {
    return stringResource(R.string.proxy_surface_loopback_endpoint)
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun lanProxySurfaceEndpointSummary(
    mode: ProxySurfaceMode,
    surfaces: LocalSurfaceSettings,
    wifiLanAddress: String?,
): String {
    return if (wifiLanAddress == null) {
        stringResource(R.string.proxy_surface_lan_waiting_for_wifi)
    } else {
        stringResource(R.string.proxy_surface_lan_endpoint, wifiLanAddress)
    }
}

private fun LocalSurfaceSettings.surfaceFor(mode: ProxySurfaceMode): ProxyInboundSettings =
    when (mode) {
        ProxySurfaceMode.SOCKS5 -> socks
        ProxySurfaceMode.HTTP -> http
        ProxySurfaceMode.ALL -> mixed
    }

private fun latencyProbeMethodIcon(value: LatencyProbeMethod): ImageVector =
    when (value) {
        LatencyProbeMethod.HTTP -> Icons.Outlined.Public
        LatencyProbeMethod.ICMP -> Icons.Outlined.Speed
        LatencyProbeMethod.TCP -> Icons.Outlined.SwapVert
    }

private fun tunStackIcon(value: TunStack): ImageVector =
    when (value) {
        TunStack.SYSTEM -> Icons.Outlined.PhoneAndroid
        TunStack.GVISOR -> Icons.Outlined.Shield
    }

private fun domainStrategyIcon(value: DomainStrategy): ImageVector =
    when (value) {
        DomainStrategy.AS_IS -> Icons.Outlined.Tune
        DomainStrategy.PREFER_IPV4,
        DomainStrategy.IPV4_ONLY,
        -> Icons.Outlined.Public
        DomainStrategy.PREFER_IPV6,
        DomainStrategy.IPV6_ONLY,
        -> Icons.Outlined.AccountTree
    }

@Composable
private fun subscriptionRefreshIntervalLabel(value: SubscriptionRefreshInterval): String =
    stringResource(
        when (value) {
            SubscriptionRefreshInterval.HOURS_6 -> R.string.auto_refresh_subscriptions_interval_6h
            SubscriptionRefreshInterval.HOURS_12 -> R.string.auto_refresh_subscriptions_interval_12h
            SubscriptionRefreshInterval.HOURS_24 -> R.string.auto_refresh_subscriptions_interval_24h
        },
    )

private fun themeModeIcon(value: ThemeMode): ImageVector =
    when (value) {
        ThemeMode.SYSTEM -> Icons.Outlined.BrightnessAuto
        ThemeMode.DARK -> Icons.Outlined.DarkMode
        ThemeMode.LIGHT -> Icons.Outlined.LightMode
    }

private fun localeIcon(value: AppLocale): ImageVector =
    when (value) {
        AppLocale.SYSTEM -> Icons.Outlined.PhoneAndroid
        AppLocale.RU,
        AppLocale.EN,
        -> Icons.Outlined.Language
    }

@Composable
fun HelpScreen(
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
) {
    val topics =
        listOf(
            HelpTopic(
                icon = Icons.Outlined.RocketLaunch,
                title = stringResource(R.string.help_quick_start_title),
                body = stringResource(R.string.help_quick_start_body),
            ),
            HelpTopic(
                icon = Icons.Outlined.VpnKey,
                title = stringResource(R.string.help_profiles_subscriptions_title),
                body = stringResource(R.string.help_profiles_subscriptions_body),
            ),
            HelpTopic(
                icon = Icons.Outlined.Speed,
                title = stringResource(R.string.auto_connect),
                body = stringResource(R.string.help_smart_start_full_body),
            ),
            HelpTopic(
                icon = Icons.Outlined.QueryStats,
                title = stringResource(R.string.help_protocol_statuses_title),
                body = stringResource(R.string.help_protocol_statuses_body),
                content = HelpTopicContent.PROTOCOL_STATUSES,
            ),
            HelpTopic(
                icon = Icons.Outlined.Shield,
                title = stringResource(R.string.help_connection_modes_title),
                body = stringResource(R.string.help_connection_modes_body),
                content = HelpTopicContent.CONNECTION_MODES,
            ),
            HelpTopic(
                icon = Icons.AutoMirrored.Outlined.AltRoute,
                title = stringResource(R.string.traffic_rules),
                body = stringResource(R.string.help_routing_full_body),
            ),
            HelpTopic(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.expert_settings),
                body = stringResource(R.string.help_expert_full_body),
            ),
        )
    SettingsScaffold(
        title = stringResource(R.string.help_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        items(
            items = topics,
            key = HelpTopic::title,
        ) { topic ->
            FoxholeCard {
                when (topic.content) {
                    HelpTopicContent.TEXT ->
                        HelpSection(
                            icon = topic.icon,
                            title = topic.title,
                            body = topic.body,
                        )
                    HelpTopicContent.PROTOCOL_STATUSES ->
                        HelpSectionContent(
                            icon = topic.icon,
                            title = topic.title,
                        ) {
                            HelpProtocolStatusesContent()
                        }
                    HelpTopicContent.CONNECTION_MODES ->
                        HelpSectionContent(
                            icon = topic.icon,
                            title = topic.title,
                        ) {
                            HelpConnectionModesContent()
                        }
                }
            }
        }
    }
}

private data class HelpTopic(
    val icon: ImageVector,
    val title: String,
    val body: String,
    val content: HelpTopicContent = HelpTopicContent.TEXT,
)

private enum class HelpTopicContent {
    TEXT,
    PROTOCOL_STATUSES,
    CONNECTION_MODES,
}

@Composable
private fun HelpProtocolStatusesContent() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        helpProtocolStatusItems().forEach { item ->
            HelpProtocolStatusRow(item)
        }
    }
}

@Composable
private fun HelpProtocolStatusRow(item: HelpProtocolStatusItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SmartProfileMetricPill(
            text = stringResource(item.labelRes),
            tone = item.tone,
            compact = false,
        )
        Text(
            text = stringResource(item.summaryRes),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun helpProtocolStatusItems(): List<HelpProtocolStatusItem> =
    listOf(
        HelpProtocolStatusItem(
            labelRes = R.string.latency_quality_fast,
            summaryRes = R.string.help_protocol_fast_summary,
            tone = SmartProfileMetricTone.POSITIVE,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.latency_quality_normal,
            summaryRes = R.string.help_protocol_normal_summary,
            tone = SmartProfileMetricTone.NEUTRAL,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.latency_quality_slow,
            summaryRes = R.string.help_protocol_slow_summary,
            tone = SmartProfileMetricTone.WARNING,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.latency_quality_very_slow,
            summaryRes = R.string.help_protocol_very_slow_summary,
            tone = SmartProfileMetricTone.VERY_SLOW,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.latency_pill_down,
            summaryRes = R.string.help_protocol_down_summary,
            tone = SmartProfileMetricTone.DANGER,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.latency_pill_unavailable,
            summaryRes = R.string.help_protocol_unavailable_summary,
            tone = SmartProfileMetricTone.NEUTRAL,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.smart_start_protocol_status_no_data,
            summaryRes = R.string.help_protocol_no_data_summary,
            tone = SmartProfileMetricTone.NEUTRAL,
        ),
        HelpProtocolStatusItem(
            labelRes = R.string.smart_start_protocol_status_disabled,
            summaryRes = R.string.help_protocol_disabled_summary,
            tone = SmartProfileMetricTone.NEUTRAL,
        ),
    )

private data class HelpProtocolStatusItem(
    val labelRes: Int,
    val summaryRes: Int,
    val tone: SmartProfileMetricTone,
)

@Composable
private fun HelpConnectionModesContent() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HelpConnectionModeRow(
            icon = Icons.Outlined.VpnKey,
            title = stringResource(R.string.traffic_mode_tunnel),
            body = stringResource(R.string.help_connection_tunnel_body),
        )
        HelpConnectionModeRow(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.home_mode_split_tunnel),
            body = stringResource(R.string.help_connection_split_tunnel_body),
        )
        HelpConnectionModeRow(
            icon = Icons.Outlined.Public,
            title = stringResource(R.string.traffic_mode_proxy),
            body = stringResource(R.string.help_connection_proxy_body),
        )
        HelpConnectionModeRow(
            icon = Icons.Outlined.Router,
            title = stringResource(R.string.proxy_lan_access_title),
            body = stringResource(R.string.help_connection_lan_proxy_body),
        )
    }
}

@Composable
private fun HelpConnectionModeRow(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(8.dp).size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
