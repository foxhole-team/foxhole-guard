package com.foxhole.beta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Map
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.NetworkRulesSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.ProxySurfaceMode
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.SMART_START_PROTOCOL_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_REFRESH_TIMEOUT_MIN_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_MAX_SECONDS
import com.foxhole.beta.core.model.SMART_START_TIMEOUT_STEP_SECONDS
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.SmartStartTransportPriority
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.settings.SMART_START_SUBSCRIPTION_RETRY_ATTEMPT_OPTIONS
import com.foxhole.beta.core.settings.SMART_START_SUBSCRIPTION_RETRY_DELAY_OPTIONS
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeChoiceCard
import com.foxhole.beta.ui.FoxholeLazyScaffold
import com.foxhole.beta.ui.FoxholePreferenceCard
import com.foxhole.beta.ui.UsageTotalsCard
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
@Composable
fun SettingsHomeScreen(
    expertVisible: Boolean,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    onOpenTraffic: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenNetworkRules: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPrivacyRoute: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenSmartStart: () -> Unit,
    onOpenApplication: () -> Unit,
    onOpenExpert: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenStatistics: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    DebugRecompositionCounter("SettingsHomeScreen")
    val initialStartupStage =
        remember {
            initialSettingsHomeStartupStage(SettingsHomeStartupCompositionWarmState.markEntered())
        }
    var startupStage by rememberSaveable { mutableStateOf(initialStartupStage) }
    LaunchedEffect(Unit) {
        while (startupStage < SETTINGS_HOME_STARTUP_STAGE_ALL) {
            delay(SETTINGS_HOME_STARTUP_STAGE_DELAY_MS)
            startupStage += 1
        }
    }
    SettingsScaffold(
        title = stringResource(R.string.settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        settingsHomeNavigationItems(
            expertVisible = expertVisible,
            onOpenTraffic = onOpenTraffic,
            onOpenDns = onOpenDns,
            onOpenNetworkRules = onOpenNetworkRules,
            onOpenSecurity = onOpenSecurity,
            onOpenPrivacyRoute = onOpenPrivacyRoute,
            onOpenRoutingApps = onOpenRoutingApps,
            onOpenRoutingSites = onOpenRoutingSites,
            onOpenSmartStart = onOpenSmartStart,
            onOpenApplication = onOpenApplication,
            onOpenAbout = onOpenAbout,
            onOpenExpert = onOpenExpert,
            onOpenDiagnostics = onOpenDiagnostics,
            onOpenStatistics = onOpenStatistics,
            startupStage = startupStage,
        )
    }
}

private fun LazyListScope.settingsHomeNavigationItems(
    expertVisible: Boolean,
    onOpenTraffic: () -> Unit,
    onOpenDns: () -> Unit,
    onOpenNetworkRules: () -> Unit,
    onOpenSecurity: () -> Unit,
    onOpenPrivacyRoute: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenSmartStart: () -> Unit,
    onOpenApplication: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenExpert: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenStatistics: () -> Unit,
    startupStage: Int,
) {
    item {
        SettingsNavigationGroup(name = "SettingsGroup:network") {
            SettingsGroupedNavigationRow(
                modifier = Modifier.testTag("settings_smart_start_action"),
                icon = FoxholeIcons.SmartStart,
                title = stringResource(R.string.smart_start_settings_title),
                summary = stringResource(R.string.smart_start_settings_summary),
                summaryMaxLines = 3,
                onClick = onOpenSmartStart,
            )
            SettingsGroupDivider()
            SettingsGroupedNavigationRow(
                modifier = Modifier.testTag("settings_traffic_action"),
                icon = FoxholeIcons.Traffic,
                title = stringResource(R.string.traffic_settings),
                summary = stringResource(R.string.settings_home_network_summary),
                onClick = onOpenTraffic,
            )
            SettingsGroupDivider()
            SettingsGroupedNavigationRow(
                modifier = Modifier.testTag("settings_dns_action"),
                icon = FoxholeIcons.Dns,
                title = stringResource(R.string.dns_settings_title),
                summary = stringResource(R.string.settings_home_dns_summary),
                onClick = onOpenDns,
            )
            SettingsGroupDivider()
            SettingsGroupedNavigationRow(
                modifier = Modifier.testTag("settings_network_rules_action"),
                icon = FoxholeIcons.NetworkRules,
                title = stringResource(R.string.network_rules_settings_title),
                summary = stringResource(R.string.network_rules_settings_summary),
                summaryMaxLines = Int.MAX_VALUE,
                onClick = onOpenNetworkRules,
            )
        }
    }
    if (shouldComposeSettingsHomeSecurityGroup(startupStage)) {
        item {
            SettingsSecurityRoutingNavigationGroup(
                onOpenSecurity = onOpenSecurity,
                onOpenRoutingApps = onOpenRoutingApps,
                onOpenRoutingSites = onOpenRoutingSites,
                onOpenPrivacyRoute = onOpenPrivacyRoute,
            )
        }
    }
    if (shouldComposeSettingsHomeAppGroup(startupStage)) {
        item {
            SettingsNavigationGroup(name = "SettingsGroup:app") {
                SettingsGroupedNavigationRow(
                    modifier = Modifier.testTag("settings_application_action"),
                    icon = FoxholeIcons.Application,
                    title = stringResource(R.string.app_settings),
                    summary = stringResource(R.string.settings_home_application_summary),
                    onClick = onOpenApplication,
                )
                if (expertVisible) {
                    SettingsGroupDivider()
                    SettingsGroupedNavigationRow(
                        modifier = Modifier.testTag("settings_expert_action"),
                        icon = FoxholeIcons.Expert,
                        title = stringResource(R.string.expert_settings),
                        summary = stringResource(R.string.settings_home_advanced_summary),
                        onClick = onOpenExpert,
                    )
                }
                SettingsGroupDivider()
                SettingsGroupedNavigationRow(
                    modifier = Modifier.testTag("settings_diagnostics_action"),
                    icon = FoxholeIcons.Diagnostics,
                    title = stringResource(R.string.diagnostics_and_usage),
                    summary = stringResource(R.string.settings_home_diagnostics_summary),
                    onClick = onOpenDiagnostics,
                )
                SettingsGroupDivider()
                SettingsGroupedNavigationRow(
                    modifier = Modifier.testTag("settings_statistics_action"),
                    icon = FoxholeIcons.Statistics,
                    title = stringResource(R.string.statistics_title),
                    summary = stringResource(R.string.settings_home_statistics_summary),
                    onClick = onOpenStatistics,
                )
                SettingsGroupDivider()
                SettingsGroupedNavigationRow(
                    modifier = Modifier.testTag("settings_about_action"),
                    icon = FoxholeIcons.About,
                    title = stringResource(R.string.about_settings_title),
                    summary = stringResource(R.string.about_settings_summary),
                    summaryMaxLines = Int.MAX_VALUE,
                    onClick = onOpenAbout,
                )
            }
        }
    }
}

internal fun initialSettingsHomeStartupStage(settingsAlreadyWarm: Boolean): Int =
    if (settingsAlreadyWarm) SETTINGS_HOME_STARTUP_STAGE_ALL else SETTINGS_HOME_STARTUP_STAGE_INITIAL

internal fun shouldComposeSettingsHomeSecurityGroup(startupStage: Int): Boolean =
    startupStage >= SETTINGS_HOME_STARTUP_STAGE_SECURITY

internal fun shouldComposeSettingsHomeAppGroup(startupStage: Int): Boolean =
    startupStage >= SETTINGS_HOME_STARTUP_STAGE_APP

private object SettingsHomeStartupCompositionWarmState {
    private var entered = false

    fun markEntered(): Boolean {
        val alreadyWarm = entered
        entered = true
        return alreadyWarm
    }
}

private const val SETTINGS_HOME_STARTUP_STAGE_INITIAL = 0
private const val SETTINGS_HOME_STARTUP_STAGE_SECURITY = 1
private const val SETTINGS_HOME_STARTUP_STAGE_APP = 2
private const val SETTINGS_HOME_STARTUP_STAGE_ALL = SETTINGS_HOME_STARTUP_STAGE_APP
private const val SETTINGS_HOME_STARTUP_STAGE_DELAY_MS = 16L

@Composable
private fun SettingsSecurityRoutingNavigationGroup(
    onOpenSecurity: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenPrivacyRoute: () -> Unit,
) {
    val torRouteIcon = ImageVector.vectorResource(FoxholeIcons.Drawables.TorRoute)
    SettingsNavigationGroup(name = "SettingsGroup:security-routing") {
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_security_action"),
            icon = FoxholeIcons.Security,
            title = stringResource(R.string.security_settings_title),
            summary = stringResource(R.string.security_settings_summary),
            titleTrailingContent = {
                BetaBadge(modifier = Modifier.offset(y = (-3).dp))
            },
            onClick = onOpenSecurity,
        )
        SettingsGroupDivider()
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_routing_sites_action"),
            icon = FoxholeIcons.RoutingSites,
            title = stringResource(R.string.routing_sites_title),
            summary = stringResource(R.string.settings_home_sites_summary),
            onClick = onOpenRoutingSites,
        )
        SettingsGroupDivider()
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_routing_apps_action"),
            icon = FoxholeIcons.RoutingApps,
            title = stringResource(R.string.routing_apps_title),
            summary = stringResource(R.string.settings_home_apps_summary),
            onClick = onOpenRoutingApps,
        )
        SettingsGroupDivider()
        SettingsGroupedNavigationRow(
            modifier = Modifier.testTag("settings_privacy_route_action"),
            icon = torRouteIcon,
            title = stringResource(R.string.privacy_route_title),
            summary = stringResource(R.string.privacy_route_summary),
            summaryMaxLines = Int.MAX_VALUE,
            onClick = onOpenPrivacyRoute,
        )
    }
}

@Composable
private fun SettingsNavigationGroup(
    name: String = "SettingsGroup",
    content: @Composable ColumnScope.() -> Unit,
) {
    DebugRecompositionCounter(name)
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

@Suppress("UNUSED_PARAMETER")
@Composable
private fun SettingsGroupedNavigationRow(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    summary: String,
    summaryMaxLines: Int = 3,
    titleTrailingContent: (@Composable RowScope.() -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.52f
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .then(modifier)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.padding(4.dp).size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                titleTrailingContent?.invoke(this)
            }
            Text(
                text = summary.trimEnd().removeSuffix("."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                maxLines = summaryMaxLines,
                overflow = TextOverflow.Clip,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
        )
    }
}

@Composable
private fun SettingsGroupDivider() {
    HorizontalDivider(
        modifier = Modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.36f),
    )
}

@Suppress("LongMethod")
@Composable
fun SmartStartSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onSmartStartEnabledChanged: (Boolean) -> Unit,
    onSmartStartProtocolSelectionTimeoutChanged: (Int) -> Unit,
    onSmartStartRefreshSelectionTimeoutChanged: (Int) -> Unit,
    onSmartStartTransportPrioritySelected: (SmartStartTransportPriority) -> Unit,
    onAutoReconnectChanged: (Boolean) -> Unit,
    onSmartStartV2RayTunSubscriptionsEnabledChanged: (Boolean) -> Unit,
    onSmartStartFailoverEnabledChanged: (Boolean) -> Unit,
    onSmartStartSubscriptionRetryAttemptsChanged: (Int) -> Unit,
    onSmartStartSubscriptionRetryDelaySecondsChanged: (Int) -> Unit,
    onClearSmartStartData: () -> Unit,
) {
    var protocolTimeoutExpanded by rememberSaveable { mutableStateOf(false) }
    var refreshTimeoutExpanded by rememberSaveable { mutableStateOf(false) }
    var transportPriorityExpanded by rememberSaveable { mutableStateOf(false) }
    var retryAttemptsExpanded by rememberSaveable { mutableStateOf(false) }
    var retryDelayExpanded by rememberSaveable { mutableStateOf(false) }
    var clearSmartStartConfirmVisible by rememberSaveable { mutableStateOf(false) }
    val smartStartEnabled = state.settings.connection.smartStartEnabled
    val smartStartSettingsEnabled = smartStartEnabled && state.hasSmartProfile
    val subscriptionRefreshEnabled = state.hasSubscriptionProfile
    val subscriptionRetrySettingsEnabled =
        smartStartEnabled && subscriptionRefreshEnabled && state.settings.connection.smartStartV2RayTunSubscriptionsEnabled
    val supportedConfigurationsAvailable = state.hasSmartProfile || subscriptionRefreshEnabled

    SettingsScaffold(
        title = stringResource(R.string.smart_start_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.auto_connect),
                body = stringResource(R.string.help_smart_start_full_body),
                icon = FoxholeIcons.SmartStart,
            ) {
                Text(
                    text = stringResource(R.string.help_protocol_statuses_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                HelpProtocolStatusesContent()
            }
        },
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.auto_reconnect),
                    checked = state.settings.connection.autoReconnect,
                    summary = stringResource(R.string.auto_reconnect_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    onCheckedChange = onAutoReconnectChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.smart_start_enable_title),
                    checked = state.settings.connection.smartStartEnabled,
                    summary = stringResource(R.string.smart_start_enable_summary),
                    leadingIcon = FoxholeIcons.SmartStart,
                    onCheckedChange = onSmartStartEnabledChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
            }
        }
        if (!smartStartEnabled || !supportedConfigurationsAvailable) {
            item {
                Text(
                    text =
                        stringResource(
                            if (smartStartEnabled) {
                                R.string.smart_start_settings_unavailable_hint
                            } else {
                                R.string.smart_start_disabled_hint
                            },
                        ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.smart_start_v2raytun_subscriptions_title),
                    checked =
                        smartStartEnabled &&
                            subscriptionRefreshEnabled &&
                            state.settings.connection.smartStartV2RayTunSubscriptionsEnabled,
                    enabled = smartStartEnabled && subscriptionRefreshEnabled,
                    onCheckedChange = onSmartStartV2RayTunSubscriptionsEnabledChanged,
                    summary = stringResource(R.string.smart_start_v2raytun_subscriptions_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.smart_start_subscription_retry_attempts_title),
                    value = smartStartRetryAttemptsLabel(state.settings.connection.smartStartSubscriptionRetryAttempts),
                    expanded = retryAttemptsExpanded,
                    onExpandedChange = { retryAttemptsExpanded = it },
                    values = SMART_START_SUBSCRIPTION_RETRY_ATTEMPT_OPTIONS,
                    selected = state.settings.connection.smartStartSubscriptionRetryAttempts,
                    label = { smartStartRetryAttemptsLabel(it) },
                    onSelect = onSmartStartSubscriptionRetryAttemptsChanged,
                    summary = stringResource(R.string.smart_start_subscription_retry_attempts_summary),
                    leadingIcon = FoxholeIcons.AutoMode,
                    optionIcon = { FoxholeIcons.AutoMode },
                    enabled = subscriptionRetrySettingsEnabled,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.smart_start_subscription_retry_delay_title),
                    value = smartStartTimeoutLabel(state.settings.connection.smartStartSubscriptionRetryDelaySeconds),
                    expanded = retryDelayExpanded,
                    onExpandedChange = { retryDelayExpanded = it },
                    values = SMART_START_SUBSCRIPTION_RETRY_DELAY_OPTIONS,
                    selected = state.settings.connection.smartStartSubscriptionRetryDelaySeconds,
                    label = { smartStartTimeoutLabel(it) },
                    onSelect = onSmartStartSubscriptionRetryDelaySecondsChanged,
                    summary = stringResource(R.string.smart_start_subscription_retry_delay_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    optionIcon = { Icons.Outlined.Refresh },
                    enabled = subscriptionRetrySettingsEnabled,
                    summaryMaxLines = 3,
                    grouped = true,
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.smart_start_failover_title),
                    checked =
                        smartStartSettingsEnabled &&
                            state.settings.connection.smartStartFailoverEnabled,
                    enabled = smartStartSettingsEnabled,
                    onCheckedChange = onSmartStartFailoverEnabledChanged,
                    summary = stringResource(R.string.smart_start_failover_summary),
                    leadingIcon = FoxholeIcons.Traffic,
                    summaryMaxLines = 4,
                    grouped = true,
                )
                SettingsControlGroupDivider()
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
                    leadingIcon = FoxholeIcons.Latency,
                    optionIcon = { FoxholeIcons.Latency },
                    enabled = smartStartSettingsEnabled,
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
                    enabled = smartStartSettingsEnabled,
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
                    leadingIcon = FoxholeIcons.Traffic,
                    optionIcon = ::smartStartTransportPriorityIcon,
                    enabled = smartStartSettingsEnabled,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingsGroupedNavigationRow(
                    icon = Icons.Outlined.Delete,
                    title = stringResource(R.string.smart_start_clear_data_title),
                    summary = stringResource(R.string.smart_start_clear_data_summary),
                    summaryMaxLines = 3,
                    enabled = smartStartEnabled && supportedConfigurationsAvailable,
                    onClick = { clearSmartStartConfirmVisible = true },
                )
            }
        }
    }
    if (clearSmartStartConfirmVisible) {
        ConfirmDialog(
            title = stringResource(R.string.smart_start_clear_confirm_title),
            body = stringResource(R.string.smart_start_clear_confirm_body),
            confirmLabel = stringResource(R.string.yes_label),
            icon = Icons.Outlined.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            dismissLabel = stringResource(R.string.no_label),
            onDismiss = { clearSmartStartConfirmVisible = false },
            onConfirm = {
                clearSmartStartConfirmVisible = false
                onClearSmartStartData()
            },
        )
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
private fun smartStartRetryAttemptsLabel(attempts: Int): String =
    pluralStringResource(R.plurals.smart_start_retry_attempts_value, attempts, attempts)

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
        SmartStartTransportPriority.ALL -> FoxholeIcons.Traffic
        SmartStartTransportPriority.UDP -> FoxholeIcons.Latency
        SmartStartTransportPriority.TCP -> FoxholeIcons.Network
    }

@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList", "UNUSED_PARAMETER")
@Composable
fun TrafficSettingsScreen(
    title: String,
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onAcknowledgeUnsafeWarning: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onOpenRoutingApps: () -> Unit,
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
    var subscriptionRefreshIntervalMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var endpointDialog by rememberSaveable { mutableStateOf(false) }
    val wifiLanAddress by rememberWifiLanAddress(
        enabled = state.settings.expert.localSurfaces.allowLanAccess,
    )
    val selectedModeOption = currentHomeModeOption(state.settings)
    val pingHttpLabel = stringResource(R.string.latency_probe_method_http)
    val pingIcmpLabel = stringResource(R.string.latency_probe_method_icmp)
    val pingTcpLabel = stringResource(R.string.latency_probe_method_tcp)
    val subscriptionRefreshAvailable = state.hasSubscriptionProfile
    val subscriptionRefreshEnabled =
        subscriptionRefreshAvailable && state.settings.connection.autoRefreshSubscriptions
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
        tag = "traffic_settings_screen",
    ) {
        item {
            SettingsControlGroup {
                SettingsNavigationRow(
                    icon = FoxholeIcons.Security,
                    title = stringResource(R.string.kill_switch_title),
                    summary = stringResource(R.string.kill_switch_summary),
                    summaryMaxLines = 4,
                    grouped = true,
                    onClick = onOpenSystemVpnSettings,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.traffic_mode),
                    value = homeModeMenuLabel(selectedModeOption),
                    expanded = modeMenuExpanded,
                    onExpandedChange = { modeMenuExpanded = it },
                    values = HomeModeOption.entries,
                    selected = selectedModeOption,
                    label = { homeModeMenuLabel(it) },
                    onSelect = { option ->
                        if (option == HomeModeOption.SPLIT && state.settings.expert.selectedPackages.isEmpty()) {
                            onOpenRoutingApps()
                        } else {
                            applyHomeModeSelection(
                                mode = option,
                                onTrafficModeSelected = onTrafficModeSelected,
                                onPerAppRoutingModeSelected = onPerAppRoutingModeSelected,
                                selectedPackages = state.settings.expert.selectedPackages,
                                currentPerAppRoutingMode = state.settings.expert.perAppRoutingMode,
                            )
                        }
                    },
                    infoBody = stringResource(R.string.traffic_mode_help_body),
                    summaryMaxLines = 6,
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
                        leadingIcon = FoxholeIcons.Security,
                        optionIcon = ::tunStackIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.mtu),
                        value = state.settings.traffic.mtu.toString(),
                        leadingIcon = FoxholeIcons.Expert,
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
                        leadingIcon = FoxholeIcons.ProxySurface,
                        optionIcon = ::proxySurfaceModeIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.proxy_auth_title),
                        checked = state.settings.expert.localSurfaces.auth.enabled,
                        leadingIcon = FoxholeIcons.Auth,
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
                        leadingIcon = FoxholeIcons.ProxyEndpoint,
                        onClick = null,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.port),
                        value = state.settings.expert.localSurfaces.surfaceFor(state.settings.expert.localSurfaces.proxyMode).port.toString(),
                        leadingIcon = FoxholeIcons.NetworkRules,
                        onClick = { proxyPortDialog = true },
                        grouped = true,
                    )
                }
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.proxy_lan_access_title),
                    checked = state.settings.expert.localSurfaces.allowLanAccess,
                    leadingIcon = FoxholeIcons.Lan,
                    onCheckedChange = { enabled ->
                        if (!enabled || wifiLanAddress != null) {
                            onLocalProxyLanAccessChanged(enabled)
                        }
                    },
                    summary =
                        proxyLanAccessSummary(
                            wifiLanAddress = wifiLanAddress,
                            port =
                                state.settings.expert.localSurfaces
                                    .surfaceFor(state.settings.expert.localSurfaces.lanProxyMode)
                                    .port,
                        ),
                    summaryColor =
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(
                            alpha = if (state.settings.expert.localSurfaces.allowLanAccess) 1f else 0.54f,
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
                        leadingIcon = FoxholeIcons.ProxySurface,
                        optionIcon = ::proxySurfaceModeIcon,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingSwitchRow(
                        title = stringResource(R.string.lan_proxy_auth_title),
                        checked = state.settings.expert.localSurfaces.lanAuth.enabled,
                        leadingIcon = FoxholeIcons.Auth,
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
                        leadingIcon = FoxholeIcons.Lan,
                        onClick = null,
                        grouped = true,
                    )
                    SettingsControlGroupDivider()
                    SettingValueRow(
                        title = stringResource(R.string.port),
                        value = state.settings.expert.localSurfaces.surfaceFor(state.settings.expert.localSurfaces.lanProxyMode).port.toString(),
                        leadingIcon = FoxholeIcons.Lan,
                        onClick = { lanProxyPortDialog = true },
                        grouped = true,
                    )
                }
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.prefer_ipv6_routes),
                    checked = state.settings.traffic.preferIpv6,
                    leadingIcon = FoxholeIcons.IpStrategy,
                    onCheckedChange = onPreferIpv6Changed,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.bypass_lan),
                    checked = state.settings.expert.bypassLan,
                    summary = stringResource(R.string.bypass_lan_summary),
                    leadingIcon = FoxholeIcons.Lan,
                    onCheckedChange = onBypassLanChanged,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.auto_refresh_subscriptions_title),
                    checked = subscriptionRefreshEnabled,
                    enabled = subscriptionRefreshAvailable,
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
                    enabled = subscriptionRefreshEnabled,
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
                    leadingIcon = FoxholeIcons.Latency,
                    optionIcon = ::latencyProbeMethodIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.ip_info_endpoint),
                    value = state.settings.connection.ipInfoEndpoint,
                    leadingIcon = FoxholeIcons.Network,
                    onClick = { endpointDialog = true },
                    grouped = true,
                )
            }
        }
    }

    if (mtuDialog) {
        IntValueDialog(
            title = stringResource(R.string.mtu),
            icon = FoxholeIcons.Expert,
            initialValue = state.settings.traffic.mtu,
            onDismiss = { mtuDialog = false },
            onConfirm = onMtuChanged,
        )
    }

    if (proxyPortDialog) {
        val mode = state.settings.expert.localSurfaces.proxyMode
        IntValueDialog(
            title = stringResource(R.string.port),
            icon = FoxholeIcons.NetworkRules,
            initialValue = state.settings.expert.localSurfaces.surfaceFor(mode).port,
            onDismiss = { proxyPortDialog = false },
            onConfirm = { port -> updateSurfacePort(mode, port) },
        )
    }

    if (lanProxyPortDialog) {
        val mode = state.settings.expert.localSurfaces.lanProxyMode
        IntValueDialog(
            title = stringResource(R.string.port),
            icon = FoxholeIcons.Lan,
            initialValue = state.settings.expert.localSurfaces.surfaceFor(mode).port,
            onDismiss = { lanProxyPortDialog = false },
            onConfirm = { port -> updateSurfacePort(mode, port) },
        )
    }

    if (endpointDialog) {
        TextValueDialog(
            title = stringResource(R.string.ip_info_endpoint),
            icon = FoxholeIcons.Network,
            initialValue = state.settings.connection.ipInfoEndpoint,
            singleLine = true,
            onDismiss = { endpointDialog = false },
            onConfirm = onIpInfoEndpointChanged,
        )
    }

}

@Composable
fun NetworkRulesSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onNetworkRulesChanged: (NetworkRulesSettings) -> Unit,
) {
    val networkRules = state.settings.networkRules
    SettingsScaffold(
        title = stringResource(R.string.network_rules_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.network_rules_wifi_title),
                    checked = networkRules.wifiRulesEnabled,
                    summary = stringResource(R.string.network_rules_wifi_summary),
                    leadingIcon = FoxholeIcons.Wifi,
                    onCheckedChange = { enabled ->
                        onNetworkRulesChanged(networkRules.copy(wifiRulesEnabled = enabled))
                    },
                    titleMaxLines = 2,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                NetworkRulesWifiProfileRows(
                    networkRules = networkRules,
                    profiles = state.profiles,
                    activeProfileId = state.activeProfile?.id,
                    onNetworkRulesChanged = onNetworkRulesChanged,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.network_rules_cellular_title),
                    checked = networkRules.cellularRulesEnabled,
                    summary = stringResource(R.string.network_rules_cellular_summary),
                    leadingIcon = FoxholeIcons.Cellular,
                    onCheckedChange = { enabled ->
                        onNetworkRulesChanged(networkRules.copy(cellularRulesEnabled = enabled))
                    },
                    titleMaxLines = 2,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.network_rules_skip_subscription_refresh_mobile_title),
                    checked = networkRules.skipSubscriptionRefreshOnCellular,
                    summary = stringResource(R.string.network_rules_skip_subscription_refresh_mobile_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    onCheckedChange = { enabled ->
                        onNetworkRulesChanged(networkRules.copy(skipSubscriptionRefreshOnCellular = enabled))
                    },
                    titleMaxLines = 2,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.network_rules_skip_speed_tests_mobile_title),
                    checked = networkRules.skipSpeedTestsOnCellular,
                    summary = stringResource(R.string.network_rules_skip_speed_tests_mobile_summary),
                    leadingIcon = FoxholeIcons.Latency,
                    onCheckedChange = { enabled ->
                        onNetworkRulesChanged(networkRules.copy(skipSpeedTestsOnCellular = enabled))
                    },
                    titleMaxLines = 2,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                NetworkRulesMobileProfileRows(
                    networkRules = networkRules,
                    profiles = state.profiles,
                    activeProfileId = state.activeProfile?.id,
                    onNetworkRulesChanged = onNetworkRulesChanged,
                )
            }
        }
    }
}

@Composable
private fun NetworkRulesMobileProfileRows(
    networkRules: NetworkRulesSettings,
    profiles: List<Profile>,
    activeProfileId: Long?,
    onNetworkRulesChanged: (NetworkRulesSettings) -> Unit,
) {
    val cellularProfileIds = listOf<Long?>(null) + profiles.map(Profile::id)
    val selectedCellularProfile =
        profiles.firstOrNull { profile -> profile.id == networkRules.cellularProfileId }
    val defaultCellularProfileId =
        remember(profiles, activeProfileId, networkRules.cellularProfileId) {
            networkRules.cellularProfileId
                ?: profiles.firstOrNull { profile -> profile.id != activeProfileId }?.id
                ?: profiles.firstOrNull()?.id
        }
    var cellularProfileMenuExpanded by rememberSaveable { mutableStateOf(false) }
    SettingSwitchRow(
        title = stringResource(R.string.network_rules_mobile_profile_title),
        checked = networkRules.useCellularProfile,
        enabled = profiles.isNotEmpty(),
        summary =
            if (profiles.isEmpty()) {
                stringResource(R.string.network_rules_mobile_profile_summary_empty)
            } else {
                stringResource(R.string.network_rules_mobile_profile_summary)
            },
        leadingIcon = FoxholeIcons.Cellular,
        onCheckedChange = { enabled ->
            onNetworkRulesChanged(
                networkRules.copy(
                    useCellularProfile = enabled,
                    cellularProfileId =
                        if (enabled) {
                            networkRules.cellularProfileId ?: defaultCellularProfileId
                        } else {
                            networkRules.cellularProfileId
                        },
                ),
            )
        },
        titleMaxLines = 2,
        summaryMaxLines = 4,
        grouped = true,
    )
    SettingsControlGroupDivider()
    DropdownSettingRow(
        title = stringResource(R.string.network_rules_mobile_profile_picker_title),
        value =
            selectedCellularProfile?.name
                ?: stringResource(R.string.network_rules_mobile_profile_value_unselected),
        expanded = cellularProfileMenuExpanded,
        onExpandedChange = { cellularProfileMenuExpanded = it },
        values = cellularProfileIds,
        selected = networkRules.cellularProfileId,
        label = { profileId ->
            profiles.profileNameOrDefault(
                profileId = profileId,
                fallback = stringResource(R.string.network_rules_mobile_profile_value_unselected),
            )
        },
        onSelect = { profileId ->
            onNetworkRulesChanged(networkRules.copy(cellularProfileId = profileId))
        },
        leadingIcon = FoxholeIcons.Cellular,
        optionIcon = { Icons.Outlined.VpnKey },
        enabled = networkRules.useCellularProfile && profiles.isNotEmpty(),
        grouped = true,
    )
}

@Suppress("LongParameterList")
@Composable
fun PrivacyRouteSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onPrivacyRouteModeSelected: (PrivacyRouteMode) -> Unit,
    onPrivacyRouteScopeSelected: (PrivacyRouteScope) -> Unit,
    onPrivacyRouteBypassVpnTunnelChanged: (Boolean) -> Unit,
    onOpenPrivacyRouteApps: () -> Unit,
    onPrivacyRouteSelectedPackagesChanged: (List<String>) -> Unit,
) {
    var privacyRouteScopeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val torRouteIcon = ImageVector.vectorResource(FoxholeIcons.Drawables.TorRoute)
    val selectedPackages = state.settings.privacyRoute.selectedPackages
    val selectedPackageSet = selectedPackages.toSet()
    val selectedApps =
        remember(state.installedApps, selectedPackages) {
            resolveSelectedApps(state.installedApps, selectedPackages)
        }
    SettingsScaffold(
        title = stringResource(R.string.privacy_route_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "privacy_route_settings_screen",
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.privacy_route_title),
                body = stringResource(R.string.privacy_route_info_body),
                icon = torRouteIcon,
            )
        },
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.privacy_route_title),
                    checked = state.settings.privacyRoute.enabled,
                    onCheckedChange = { enabled ->
                        onPrivacyRouteModeSelected(
                            if (enabled) {
                                PrivacyRouteMode.TOR_OVER_VPN
                            } else {
                                PrivacyRouteMode.OFF
                            },
                        )
                    },
                    summary = stringResource(R.string.privacy_route_summary),
                    leadingIcon = torRouteIcon,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.privacy_route_bypass_vpn_title),
                    checked = state.settings.privacyRoute.bypassVpnTunnel,
                    enabled = state.settings.privacyRoute.enabled,
                    onCheckedChange = onPrivacyRouteBypassVpnTunnelChanged,
                    summary = stringResource(R.string.privacy_route_bypass_vpn_summary),
                    leadingIcon = FoxholeIcons.Network,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.privacy_route_scope_title),
                    value = privacyRouteScopeLabel(state.settings.privacyRoute.scope),
                    expanded = privacyRouteScopeMenuExpanded,
                    onExpandedChange = { privacyRouteScopeMenuExpanded = it },
                    values = PrivacyRouteScope.entries,
                    selected = state.settings.privacyRoute.scope,
                    label = { privacyRouteScopeLabel(it) },
                    onSelect = onPrivacyRouteScopeSelected,
                    leadingIcon = FoxholeIcons.RoutingApps,
                    optionIcon = ::privacyRouteScopeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                if (state.settings.privacyRoute.scope == PrivacyRouteScope.ALL_APPS) {
                    InfoBlock(
                        title = stringResource(R.string.privacy_route_all_apps_warning_title),
                        body = stringResource(R.string.privacy_route_all_apps_warning_body),
                    )
                } else {
                    AppGridSectionContent(
                        title = stringResource(R.string.privacy_route_selected_apps_title),
                        subtitle = stringResource(R.string.privacy_route_selected_apps_summary),
                        leadingIcon = FoxholeIcons.RoutingApps,
                        apps = selectedApps,
                        emptyText = stringResource(R.string.privacy_route_selected_apps_empty),
                        headerActionLabel = stringResource(R.string.add_label),
                        headerActionTag = "privacy_route_apps_choose_action",
                        onHeaderAction = onOpenPrivacyRouteApps,
                        onRemove = { app ->
                            onPrivacyRouteSelectedPackagesChanged(selectedPackages.filterNot { it == app.packageName })
                        },
                        onDropPackage = { packageName, beforePackageName ->
                            if (packageName !in selectedPackageSet || beforePackageName != null) {
                                onPrivacyRouteSelectedPackagesChanged(
                                    insertPackageBefore(selectedPackages, packageName, beforePackageName),
                                )
                            }
                        },
                        modifier = Modifier.testTag("privacy_route_apps_section"),
                    )
                }
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod", "UnusedParameter")
@Composable
fun ApplicationSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onLocaleSelected: (AppLocale) -> Unit,
    onAutoStartChanged: (Boolean) -> Unit,
    onBlockScreenshotsChanged: (Boolean) -> Unit,
    onNetworkCardEnabledChanged: (Boolean) -> Unit,
    onTrafficCardEnabledChanged: (Boolean) -> Unit,
    onTrafficMapEnabledChanged: (Boolean) -> Unit,
    onEnableTrafficMapSupportSettings: () -> Unit,
    onShowExpertSettingsChanged: (Boolean) -> Unit,
    onShowFirewallStatusChanged: (Boolean) -> Unit,
    onShowTorQuickLaunchChanged: (Boolean) -> Unit,
    onSmartStartDashboardControlsEnabledChanged: (Boolean) -> Unit,
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
    var trafficMapSupportPromptVisible by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.app_settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "application_settings_screen",
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
                    leadingIcon = FoxholeIcons.Theme,
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
                    leadingIcon = FoxholeIcons.RoutingSites,
                    optionIcon = ::localeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.auto_start_on_boot),
                    checked = state.settings.connection.autoStartOnBoot,
                    leadingIcon = FoxholeIcons.AutoStart,
                    onCheckedChange = onAutoStartChanged,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    modifier = Modifier.testTag("settings_block_screenshots_toggle"),
                    title = stringResource(R.string.block_screenshots_title),
                    checked = state.settings.expert.blockScreenshots,
                    leadingIcon = FoxholeIcons.ScreenshotsBlocked,
                    onCheckedChange = onBlockScreenshotsChanged,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.show_advanced_settings_title),
                    checked = state.settings.ui.showExpertSettings,
                    summary = stringResource(R.string.show_advanced_settings_summary),
                    leadingIcon = FoxholeIcons.Expert,
                    onCheckedChange = onShowExpertSettingsChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.network_card_setting_title),
                    checked = state.settings.ui.networkCardEnabled,
                    summary = stringResource(R.string.network_card_setting_summary),
                    leadingIcon = FoxholeIcons.Network,
                    onCheckedChange = onNetworkCardEnabledChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.traffic_card_setting_title),
                    checked = state.settings.ui.trafficCardEnabled,
                    summary = stringResource(R.string.traffic_card_setting_summary),
                    leadingIcon = FoxholeIcons.Traffic,
                    onCheckedChange = onTrafficCardEnabledChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.traffic_map_setting_title),
                    checked = state.settings.ui.trafficMapEnabled,
                    summary = stringResource(R.string.traffic_map_setting_summary),
                    leadingIcon = Icons.Outlined.Map,
                    onCheckedChange = { enabled ->
                        onTrafficMapEnabledChanged(enabled)
                        if (enabled && state.settings.trafficMapSupportPromptNeeded()) {
                            trafficMapSupportPromptVisible = true
                        }
                    },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.show_tor_quick_launch_title),
                    checked = state.settings.ui.showTorQuickLaunch,
                    summary = stringResource(R.string.show_tor_quick_launch_summary),
                    leadingIcon = FoxholeIcons.SmartStart,
                    onCheckedChange = onShowTorQuickLaunchChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.show_firewall_status_title),
                    checked = state.settings.ui.showFirewallStatus,
                    summary = stringResource(R.string.show_firewall_status_summary),
                    leadingIcon = FoxholeIcons.Security,
                    onCheckedChange = onShowFirewallStatusChanged,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
            }
        }
    }

    if (trafficMapSupportPromptVisible) {
        ConfirmDialog(
            title = stringResource(R.string.traffic_map_support_prompt_title),
            body = stringResource(R.string.traffic_map_support_prompt_body),
            confirmLabel = stringResource(R.string.traffic_map_support_prompt_confirm),
            dismissLabel = stringResource(R.string.close),
            icon = Icons.Outlined.Map,
            onDismiss = { trafficMapSupportPromptVisible = false },
            onConfirm = {
                trafficMapSupportPromptVisible = false
                onEnableTrafficMapSupportSettings()
            },
        )
    }
}

private fun Settings.trafficMapSupportPromptNeeded(): Boolean =
    !expert.networkActivityLogging ||
        !statistics.enabled ||
        !statistics.countryTrafficEnabled

@Composable
private fun privacyRouteScopeLabel(value: PrivacyRouteScope): String =
    when (value) {
        PrivacyRouteScope.SELECTED_APPS -> stringResource(R.string.privacy_route_scope_selected_apps)
        PrivacyRouteScope.ALL_APPS -> stringResource(R.string.privacy_route_scope_all_apps)
    }

private fun privacyRouteScopeIcon(value: PrivacyRouteScope): ImageVector =
    when (value) {
        PrivacyRouteScope.SELECTED_APPS -> FoxholeIcons.RoutingApps
        PrivacyRouteScope.ALL_APPS -> FoxholeIcons.Apps
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
        ProxySurfaceMode.SOCKS5 -> FoxholeIcons.ProxySurface
        ProxySurfaceMode.HTTP -> FoxholeIcons.Network
        ProxySurfaceMode.ALL -> FoxholeIcons.Apps
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
        LatencyProbeMethod.HTTP -> FoxholeIcons.Network
        LatencyProbeMethod.ICMP -> FoxholeIcons.Latency
        LatencyProbeMethod.TCP -> FoxholeIcons.Traffic
    }

private fun tunStackIcon(value: TunStack): ImageVector =
    when (value) {
        TunStack.SYSTEM -> FoxholeIcons.Application
        TunStack.GVISOR -> FoxholeIcons.Security
    }

private fun domainStrategyIcon(value: DomainStrategy): ImageVector =
    when (value) {
        DomainStrategy.AS_IS -> FoxholeIcons.Expert
        DomainStrategy.PREFER_IPV4,
        DomainStrategy.IPV4_ONLY,
        -> FoxholeIcons.Network
        DomainStrategy.PREFER_IPV6,
        DomainStrategy.IPV6_ONLY,
        -> FoxholeIcons.IpStrategy
    }

@Composable
private fun subscriptionRefreshIntervalLabel(value: SubscriptionRefreshInterval): String =
    stringResource(
        when (value) {
            SubscriptionRefreshInterval.HOURS_1 -> R.string.auto_refresh_subscriptions_interval_1h
            SubscriptionRefreshInterval.HOURS_3 -> R.string.auto_refresh_subscriptions_interval_3h
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
        AppLocale.SYSTEM -> FoxholeIcons.Application
        AppLocale.RU,
        AppLocale.EN,
        -> FoxholeIcons.RoutingSites
    }

@Composable
fun HelpScreen(
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
) {
    val topics =
        listOf(
            HelpTopic(
                icon = FoxholeIcons.SmartStart,
                title = stringResource(R.string.help_quick_start_title),
                body = stringResource(R.string.help_quick_start_body),
            ),
            HelpTopic(
                icon = Icons.Outlined.VpnKey,
                title = stringResource(R.string.help_profiles_subscriptions_title),
                body = stringResource(R.string.help_profiles_subscriptions_body),
            ),
            HelpTopic(
                icon = FoxholeIcons.Latency,
                title = stringResource(R.string.auto_connect),
                body = stringResource(R.string.help_smart_start_full_body),
            ),
            HelpTopic(
                icon = FoxholeIcons.Statistics,
                title = stringResource(R.string.help_protocol_statuses_title),
                body = stringResource(R.string.help_protocol_statuses_body),
                content = HelpTopicContent.PROTOCOL_STATUSES,
            ),
            HelpTopic(
                icon = FoxholeIcons.Security,
                title = stringResource(R.string.help_connection_modes_title),
                body = stringResource(R.string.help_connection_modes_body),
                content = HelpTopicContent.CONNECTION_MODES,
            ),
            HelpTopic(
                icon = FoxholeIcons.Expert,
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
            title = stringResource(R.string.help_connection_split_proxy_title),
            body = stringResource(R.string.help_connection_split_proxy_body),
        )
        HelpConnectionModeRow(
            icon = Icons.Outlined.Apps,
            title = stringResource(R.string.help_connection_split_direct_title),
            body = stringResource(R.string.help_connection_split_direct_body),
        )
        HelpConnectionModeRow(
            icon = FoxholeIcons.ProxySurface,
            title = stringResource(R.string.traffic_mode_proxy),
            body = stringResource(R.string.help_connection_proxy_body),
        )
        HelpConnectionModeRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.information_title),
            body = stringResource(R.string.help_connection_auth_note),
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
