package com.foxhole.beta.ui

import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BrightnessAuto
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.foxhole.beta.R
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.model.AppLocale
import com.foxhole.beta.core.model.ClashApiSettings
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.DiagnosticsRetention
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.LatencyProbeMethod
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.RoutingCatalog
import com.foxhole.beta.core.model.RoutingPreset
import com.foxhole.beta.core.model.RoutingPresetOverrideMode
import com.foxhole.beta.core.model.RoutingPresetSource
import com.foxhole.beta.core.model.RoutingRule
import com.foxhole.beta.core.model.RoutingRuleAction
import com.foxhole.beta.core.model.SubscriptionRefreshInterval
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.settings.effectiveSupportBotHandle
import com.foxhole.beta.core.settings.supportBotUsername
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeChoiceCard
import com.foxhole.beta.ui.FoxholeLazyScaffold
import com.foxhole.beta.ui.FoxholePreferenceCard
import com.foxhole.beta.ui.FoxholeValuePill
import com.foxhole.beta.ui.UsageTotalsCard
import com.foxhole.beta.vpn.FoxholeTileService
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.io.File

@Composable
fun SettingsHomeScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: (() -> Unit)?,
    onOpenTraffic: () -> Unit,
    onOpenRouting: () -> Unit,
    onOpenRoutingApps: () -> Unit,
    onOpenRoutingSites: () -> Unit,
    onOpenApplication: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenExpert: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onShowExpertSettingsChanged: (Boolean) -> Unit,
    onUnlockExpertSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repositoryOpenFailed = stringResource(R.string.open_repository_failed)
    val supportChannelOpenFailed = stringResource(R.string.support_channel_open_failed)
    var unlockDialogVisible by rememberSaveable { mutableStateOf(false) }
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    val expertVisible = state.settings.ui.showExpertSettings
    val expertUnlocked = state.settings.expert.unlockedAt != null

    SettingsScaffold(
        title = stringResource(R.string.settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Tune,
                title = stringResource(R.string.traffic_settings),
                summary = stringResource(R.string.settings_home_network_summary),
                onClick = onOpenTraffic,
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.AccountTree,
                title = stringResource(R.string.traffic_rules),
                summary = stringResource(R.string.settings_home_routing_summary),
                onClick = onOpenRouting,
            )
        }
        item {
            SettingsNavigationRow(
                modifier = Modifier.testTag("settings_routing_apps_action"),
                icon = Icons.Outlined.Apps,
                title = stringResource(R.string.routing_apps_title),
                summary = stringResource(R.string.settings_home_apps_summary),
                onClick = onOpenRoutingApps,
            )
        }
        item {
            SettingsNavigationRow(
                modifier = Modifier.testTag("settings_routing_sites_action"),
                icon = Icons.Outlined.Public,
                title = stringResource(R.string.routing_sites_title),
                summary = stringResource(R.string.settings_home_sites_summary),
                onClick = onOpenRoutingSites,
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.app_settings),
                summary = stringResource(R.string.settings_home_application_summary),
                onClick = onOpenApplication,
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = stringResource(R.string.help_title),
                summary = stringResource(R.string.settings_home_help_summary),
                onClick = onOpenHelp,
            )
        }
        if (BuildConfig.DEBUG) {
            item {
                SettingsNavigationRow(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.about_app_title),
                    summary = stringResource(R.string.settings_home_about_summary),
                    onClick = onOpenAbout,
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
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.diagnostics_and_usage),
                summary = stringResource(R.string.settings_home_diagnostics_summary),
                onClick = onOpenDiagnostics,
            )
        }
        item {
            SettingsFooterVersionText(
                text = stringResource(R.string.settings_footer_version, state.appVersion),
                summary = stringResource(R.string.settings_home_version_summary_hidden),
                onRepositoryClick = {
                    if (!openFoxholeRepository(context)) {
                        scope.launch {
                            snackbarHostState.showSnackbar(repositoryOpenFailed)
                        }
                    }
                },
                onSupportBotClick = {
                    if (!openTelegramChannel(context)) {
                        scope.launch {
                            snackbarHostState.showBanner(
                                supportChannelOpenFailed,
                                FoxholeBannerTone.ERROR,
                            )
                        }
                    }
                },
                onClick = {
                    if (unlockDialogVisible) {
                        return@SettingsFooterVersionText
                    }
                    if (expertVisible || expertUnlocked) {
                        versionTapCount = 0
                        return@SettingsFooterVersionText
                    }
                    val nextTapCount = versionTapCount + 1
                    versionTapCount = nextTapCount
                    if (nextTapCount >= 5) {
                        unlockDialogVisible = true
                    }
                },
            )
        }
    }

    if (unlockDialogVisible) {
        ConfirmDialog(
            title = stringResource(R.string.expert_unlock_confirm_title),
            body = stringResource(R.string.expert_unlock_confirm_body),
            confirmLabel = stringResource(R.string.yes_label),
            icon = Icons.Outlined.Shield,
            dismissLabel = stringResource(R.string.no_label),
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

private const val FOXHOLE_REPOSITORY_URL = "https://github.com/foxhole-app/foxhole"
private val TELEGRAM_PACKAGE_CANDIDATES =
    listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
    )

@Composable
private fun SupportBotIcon(
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(38.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_telegram_mark),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun installedTelegramPackage(packageManager: PackageManager): String? =
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

private const val FOXHOLE_TELEGRAM_CHANNEL = "foxhole_app"

private fun supportBotBrowserUri(handle: String): Uri = "https://t.me/${supportBotUsername(handle)}".toUri()

private fun supportBotTelegramUri(handle: String): Uri = "tg://resolve?domain=${supportBotUsername(handle)}".toUri()

private fun supportChannelBrowserUri(): Uri = "https://t.me/$FOXHOLE_TELEGRAM_CHANNEL".toUri()

private fun supportChannelTelegramUri(): Uri = "tg://resolve?domain=$FOXHOLE_TELEGRAM_CHANNEL".toUri()

private fun openSupportBot(context: Context, handle: String): Boolean {
    val browserIntent =
        Intent(Intent.ACTION_VIEW, supportBotBrowserUri(handle))
            .addCategory(Intent.CATEGORY_BROWSABLE)
    val telegramPackage = installedTelegramPackage(context.packageManager)
    if (telegramPackage != null) {
        val telegramIntent =
            Intent(Intent.ACTION_VIEW, supportBotTelegramUri(handle))
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

private fun createTelegramDiagnosticsShareIntent(
    packageName: String,
    baseIntent: Intent,
    context: Context,
    handle: String,
): Intent =
    Intent(baseIntent).apply {
        `package` = packageName
        putExtra(
            Intent.EXTRA_TEXT,
            context.getString(
                R.string.support_bot_share_text,
                handle,
                supportBotBrowserUri(handle).toString(),
            ),
        )
    }

@Composable
private fun SupportBotDiagnosticsCard(
    handle: String,
    onSendLog: () -> Unit,
    onConfigureBot: () -> Unit,
) {
    FoxholeCard(
        modifier = Modifier.testTag("diagnostics_support_bot_card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SupportBotIcon()
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = stringResource(R.string.send_log_to_bot),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.support_bot_diagnostics_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FoxholeValuePill(value = handle)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = onSendLog,
            ) {
                Text(stringResource(R.string.send_log_to_bot))
            }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = onConfigureBot,
            ) {
                Text(stringResource(R.string.support_bot_settings_button))
            }
        }
    }
}

@Composable
private fun SupportBotHandleDialog(
    currentHandle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by rememberSaveable(currentHandle) { mutableStateOf(currentHandle) }
    val normalized = com.foxhole.beta.core.settings.normalizeSupportBotHandle(value)
    val showError = value.isNotBlank() && normalized == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.support_bot_settings_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.support_bot_settings_summary))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.trim() },
                    label = { Text(stringResource(R.string.support_bot_handle_label)) },
                    singleLine = true,
                    isError = showError,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Text(
                        text = stringResource(R.string.support_bot_invalid_handle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { normalized?.let(onConfirm) },
                enabled = normalized != null,
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
fun TrafficSettingsScreen(
    title: String,
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onLatencyProbeMethodSelected: (LatencyProbeMethod) -> Unit,
    onTunStackSelected: (TunStack) -> Unit,
    onLocalProxyAuthEnabledChanged: (Boolean) -> Unit,
    onLocalProxyAuthChanged: (LocalAuthSettings) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onSocksSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onHttpSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onMixedSurfaceChanged: (ProxyInboundSettings) -> Unit,
    onMtuChanged: (Int) -> Unit,
    onPreferIpv6Changed: (Boolean) -> Unit,
    onDomainStrategySelected: (DomainStrategy) -> Unit,
    onAutoRefreshSubscriptionsChanged: (Boolean) -> Unit,
    onSubscriptionRefreshIntervalSelected: (SubscriptionRefreshInterval) -> Unit,
) {
    var modeMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var latencyProbeMethodMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var tunStackMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var socksDialog by rememberSaveable { mutableStateOf(false) }
    var httpDialog by rememberSaveable { mutableStateOf(false) }
    var mixedDialog by rememberSaveable { mutableStateOf(false) }
    var mtuDialog by rememberSaveable { mutableStateOf(false) }
    var domainMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var subscriptionRefreshIntervalMenuExpanded by rememberSaveable { mutableStateOf(false) }
    val wifiLanAddress by rememberWifiLanAddress()
    val tunnelModeLabel = stringResource(R.string.traffic_mode_tunnel)
    val proxyModeLabel = stringResource(R.string.traffic_mode_proxy)
    val trafficModeLabel: (TrafficMode) -> String = { value ->
        when (value) {
            TrafficMode.TUNNEL -> tunnelModeLabel
            TrafficMode.PROXY -> proxyModeLabel
        }
    }
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
            DropdownSettingRow(
                title = stringResource(R.string.traffic_mode),
                value = trafficModeLabel(state.settings.traffic.mode),
                expanded = modeMenuExpanded,
                onExpandedChange = { modeMenuExpanded = it },
                values = TrafficMode.entries,
                selected = state.settings.traffic.mode,
                label = { trafficModeLabel(it) },
                onSelect = onTrafficModeSelected,
                leadingIcon = Icons.Outlined.Tune,
                optionIcon = ::trafficModeIcon,
            )
        }
        if (state.settings.traffic.mode == TrafficMode.TUNNEL) {
            item {
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
                )
            }
            item {
                SettingValueRow(
                    title = stringResource(R.string.mtu),
                    value = state.settings.traffic.mtu.toString(),
                    leadingIcon = Icons.Outlined.Tune,
                    onClick = { mtuDialog = true },
                )
            }
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.proxy_lan_access_title),
                checked = state.settings.expert.localSurfaces.allowLanAccess,
                leadingIcon = Icons.Outlined.Public,
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
            )
        }
        if (state.settings.traffic.mode == TrafficMode.PROXY) {
            item {
                SettingSwitchRow(
                    title = stringResource(R.string.proxy_auth_title),
                    checked = state.settings.expert.localSurfaces.auth.enabled,
                    leadingIcon = Icons.Outlined.Shield,
                    summary = stringResource(R.string.proxy_auth_summary),
                    onCheckedChange = onLocalProxyAuthEnabledChanged,
                )
            }
            item {
                LocalProxyAuthEditor(
                    auth = state.settings.expert.localSurfaces.auth,
                    onAuthChanged = onLocalProxyAuthChanged,
                )
            }
            item {
                SettingValueRow(
                    title = stringResource(R.string.http_inbound),
                    value = surfaceSummary(state.settings.expert.localSurfaces.http, state.settings.expert.localSurfaces.auth.enabled),
                    leadingIcon = Icons.Outlined.Public,
                    onClick = { httpDialog = true },
                )
            }
            item {
                SettingValueRow(
                    title = stringResource(R.string.socks_inbound),
                    value = surfaceSummary(state.settings.expert.localSurfaces.socks, state.settings.expert.localSurfaces.auth.enabled),
                    leadingIcon = Icons.Outlined.Shield,
                    onClick = { socksDialog = true },
                )
            }
            item {
                SettingValueRow(
                    title = stringResource(R.string.mixed_inbound),
                    value = surfaceSummary(state.settings.expert.localSurfaces.mixed, state.settings.expert.localSurfaces.auth.enabled),
                    leadingIcon = Icons.Outlined.Apps,
                    onClick = { mixedDialog = true },
                )
            }
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.prefer_ipv6_routes),
                checked = state.settings.traffic.preferIpv6,
                leadingIcon = Icons.Outlined.Public,
                onCheckedChange = onPreferIpv6Changed,
            )
        }
        item {
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
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.auto_refresh_subscriptions_title),
                checked = state.settings.connection.autoRefreshSubscriptions,
                leadingIcon = Icons.Outlined.Refresh,
                summary = stringResource(R.string.auto_refresh_subscriptions_summary),
                onCheckedChange = onAutoRefreshSubscriptionsChanged,
                summaryMaxLines = 3,
            )
        }
        item {
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
            )
        }
        item {
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
            )
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

    if (socksDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.socks_inbound),
            initialValue = state.settings.expert.localSurfaces.socks,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { socksDialog = false },
            onConfirm = onSocksSurfaceChanged,
        )
    }

    if (httpDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.http_inbound),
            initialValue = state.settings.expert.localSurfaces.http,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { httpDialog = false },
            onConfirm = onHttpSurfaceChanged,
        )
    }

    if (mixedDialog) {
        ProxySurfaceDialog(
            title = stringResource(R.string.mixed_inbound),
            initialValue = state.settings.expert.localSurfaces.mixed,
            auth = state.settings.expert.localSurfaces.auth,
            lanAccessEnabled = state.settings.expert.localSurfaces.allowLanAccess,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { mixedDialog = false },
            onConfirm = onMixedSurfaceChanged,
        )
    }
}

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
    onIpInfoEndpointChanged: (String) -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val systemThemeLabel = stringResource(R.string.theme_mode_system)
    val darkThemeLabel = stringResource(R.string.theme_mode_dark)
    val lightThemeLabel = stringResource(R.string.theme_mode_light)
    val systemLocaleLabel = stringResource(R.string.language_system)
    val russianLocaleLabel = stringResource(R.string.language_russian)
    val englishLocaleLabel = stringResource(R.string.language_english)
    val quickSettingsTileUnavailable = stringResource(R.string.quick_settings_tile_unavailable)
    val quickSettingsTileAdded = stringResource(R.string.quick_settings_tile_added)
    val quickSettingsTileAlreadyAdded = stringResource(R.string.quick_settings_tile_already_added)
    val quickSettingsTileNotAdded = stringResource(R.string.quick_settings_tile_not_added)
    val appName = stringResource(R.string.app_name)
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
    var endpointDialog by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.app_settings),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
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
            )
        }
        item {
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
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.auto_reconnect),
                checked = state.settings.connection.autoReconnect,
                leadingIcon = Icons.Outlined.Refresh,
                onCheckedChange = onAutoReconnectChanged,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.auto_start_on_boot),
                checked = state.settings.connection.autoStartOnBoot,
                leadingIcon = Icons.Outlined.PhoneAndroid,
                onCheckedChange = onAutoStartChanged,
            )
        }
        item {
            SettingSwitchRow(
                title = stringResource(R.string.block_screenshots_title),
                checked = state.settings.expert.blockScreenshots,
                summary = stringResource(R.string.block_screenshots_summary),
                leadingIcon = Icons.Outlined.Shield,
                onCheckedChange = onBlockScreenshotsChanged,
                summaryMaxLines = 3,
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.ip_info_endpoint),
                value = state.settings.connection.ipInfoEndpoint,
                leadingIcon = Icons.Outlined.Public,
                onClick = { endpointDialog = true },
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.PhoneAndroid,
                title = stringResource(R.string.quick_settings_tile),
                summary = stringResource(R.string.quick_settings_tile_summary),
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.36f),
                leadingIconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                leadingIconTint = MaterialTheme.colorScheme.primary,
                summaryMaxLines = 2,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val statusBarManager = context.getSystemService(StatusBarManager::class.java)
                        if (statusBarManager == null) {
                            coroutineScope.launch {
                                snackbarHostState.showBanner(
                                    quickSettingsTileUnavailable,
                                    FoxholeBannerTone.INFO,
                                )
                            }
                        } else {
                            statusBarManager.requestAddTileService(
                                ComponentName(context, FoxholeTileService::class.java),
                                appName,
                                Icon.createWithResource(context, R.drawable.foxhole_logo_bitmap),
                                context.mainExecutor,
                            ) { result ->
                                val message =
                                    when (result) {
                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                                            quickSettingsTileAdded
                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                                            quickSettingsTileAlreadyAdded
                                        else -> quickSettingsTileNotAdded
                                    }
                                val tone =
                                    when (result) {
                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> FoxholeBannerTone.SUCCESS
                                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> FoxholeBannerTone.INFO
                                        else -> FoxholeBannerTone.ERROR
                                    }
                                coroutineScope.launch {
                                    snackbarHostState.showBanner(message, tone)
                                }
                            }
                        }
                    } else {
                        coroutineScope.launch {
                            snackbarHostState.showBanner(
                                quickSettingsTileUnavailable,
                                FoxholeBannerTone.INFO,
                            )
                        }
                    }
                },
            )
        }
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

private fun trafficModeIcon(value: TrafficMode): ImageVector =
    when (value) {
        TrafficMode.TUNNEL -> Icons.Outlined.Shield
        TrafficMode.PROXY -> Icons.Outlined.SwapVert
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
    SettingsScaffold(
        title = stringResource(R.string.help_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            FoxholeCard {
                HelpSection(
                    icon = Icons.Outlined.Speed,
                    title = stringResource(R.string.auto_connect),
                    body = stringResource(R.string.help_smart_start_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.traffic_mode_tunnel),
                    body = stringResource(R.string.help_tunnel_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.Apps,
                    title = stringResource(R.string.home_mode_split_tunnel),
                    body = stringResource(R.string.help_split_tunnel_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.Public,
                    title = stringResource(R.string.traffic_mode_proxy),
                    body = stringResource(R.string.help_proxy_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(R.string.proxy_lan_access_title),
                    body = stringResource(R.string.help_lan_proxy_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.PhoneAndroid,
                    title = stringResource(R.string.profile),
                    body = stringResource(R.string.help_profiles_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.AccountTree,
                    title = stringResource(R.string.traffic_rules),
                    body = stringResource(R.string.help_routing_body),
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
                )
                HelpSection(
                    icon = Icons.Outlined.Info,
                    title = stringResource(R.string.diagnostics_and_usage),
                    body = stringResource(R.string.help_logs_body),
                )
            }
        }
        item {
            FoxholeCard {
                HelpSection(
                    icon = Icons.Outlined.Shield,
                    title = stringResource(R.string.expert_settings),
                    body = stringResource(R.string.help_expert_body),
                )
            }
        }
    }
}

@Composable
fun AboutScreen(
    state: AboutRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
) {
    SettingsScaffold(
        title = stringResource(R.string.about_app_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.about_app_title),
                body = stringResource(R.string.settings_home_about_summary),
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.app_version),
                value = state.appVersion,
                leadingIcon = Icons.Outlined.PhoneAndroid,
                onClick = null,
            )
        }
        item {
            SettingValueRow(
                title = stringResource(R.string.core_version),
                value = "sing-box ${state.coreVersion}",
                leadingIcon = Icons.Outlined.Tune,
                onClick = null,
            )
        }
    }
}

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onCreateDiagnosticsArchive: () -> File,
    onShareDiagnosticsArchive: (File) -> Intent,
    onSupportBotHandleChanged: (String?) -> Unit,
    onClearUsage: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val supportBotHandle = effectiveSupportBotHandle(state.settings.ui.supportBotHandleOverride)
    val supportBotOpenFailed = stringResource(R.string.support_bot_open_failed)
    val supportBotBrowserFallback = stringResource(R.string.support_bot_browser_fallback)
    val supportBotSaved = stringResource(R.string.support_bot_saved)
    val diagnosticsArchiveSaved = stringResource(R.string.diagnostics_archive_saved)
    val diagnosticsArchiveSaveFailed = stringResource(R.string.diagnostics_archive_save_failed)
    val exportDiagnosticsTitle = stringResource(R.string.export_diagnostics)
    var liveLogsVisible by rememberSaveable { mutableStateOf(false) }
    var sendLogToBotVisible by rememberSaveable { mutableStateOf(false) }
    var supportBotSettingsVisible by rememberSaveable { mutableStateOf(false) }
    var pendingArchiveFile by remember { mutableStateOf<File?>(null) }
    val archiveSaver =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri ->
            val archive = pendingArchiveFile
            pendingArchiveFile = null
            if (uri == null || archive == null) {
                return@rememberLauncherForActivityResult
            }
            coroutineScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { output ->
                            archive.inputStream().use { input -> input.copyTo(output) }
                        } ?: error("openOutputStream returned null")
                        }
                }.onSuccess {
                    snackbarHostState.showBanner(
                        diagnosticsArchiveSaved,
                        FoxholeBannerTone.SUCCESS,
                    )
                }.onFailure {
                    snackbarHostState.showBanner(
                        it.message ?: diagnosticsArchiveSaveFailed,
                        FoxholeBannerTone.ERROR,
                    )
                }
            }
        }

    fun exportArchive(share: Boolean) {
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { onCreateDiagnosticsArchive() }
            }.onSuccess { archive ->
                if (share) {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(
                                onShareDiagnosticsArchive(archive),
                                exportDiagnosticsTitle,
                            ),
                        )
                    }.onFailure {
                        snackbarHostState.showBanner(
                            it.message ?: diagnosticsArchiveSaveFailed,
                            FoxholeBannerTone.ERROR,
                        )
                    }
                } else {
                    pendingArchiveFile = archive
                    archiveSaver.launch(archive.name)
                }
            }.onFailure {
                snackbarHostState.showBanner(
                    it.message ?: diagnosticsArchiveSaveFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }

    fun sendArchiveToSupportBot() {
        val telegramPackage = installedTelegramPackage(context.packageManager)
        if (telegramPackage == null) {
            val opened = openSupportBot(context, supportBotHandle)
            coroutineScope.launch {
                snackbarHostState.showBanner(
                    if (opened) {
                        supportBotBrowserFallback
                    } else {
                        supportBotOpenFailed
                    },
                    if (opened) FoxholeBannerTone.INFO else FoxholeBannerTone.ERROR,
                )
            }
            return
        }
        coroutineScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { onCreateDiagnosticsArchive() }
            }.onSuccess { archive ->
                val shareIntent =
                    createTelegramDiagnosticsShareIntent(
                        packageName = telegramPackage,
                        baseIntent = onShareDiagnosticsArchive(archive),
                        context = context,
                        handle = supportBotHandle,
                    )
                runCatching { context.startActivity(shareIntent) }
                    .onFailure {
                        val opened = openSupportBot(context, supportBotHandle)
                        snackbarHostState.showBanner(
                            if (opened) {
                                supportBotBrowserFallback
                            } else {
                                supportBotOpenFailed
                            },
                            if (opened) FoxholeBannerTone.INFO else FoxholeBannerTone.ERROR,
                        )
                    }
            }.onFailure {
                snackbarHostState.showBanner(
                    it.message ?: diagnosticsArchiveSaveFailed,
                    FoxholeBannerTone.ERROR,
                )
            }
        }
    }

    SettingsScaffold(
        title = stringResource(R.string.diagnostics_and_usage),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
    ) {
        item {
            InfoBlock(
                title = stringResource(R.string.information_title),
                body = stringResource(R.string.diagnostics_info_body),
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.FileUpload,
                title = stringResource(R.string.export_diagnostics),
                summary = stringResource(R.string.export_diagnostics_summary),
                onClick = { exportArchive(share = true) },
            )
        }
        item {
            SettingsNavigationRow(
                icon = Icons.Outlined.Info,
                title = stringResource(R.string.logs_title),
                summary = stringResource(R.string.logs_summary),
                onClick = { liveLogsVisible = true },
            )
        }
        item {
            SupportBotDiagnosticsCard(
                handle = supportBotHandle,
                onSendLog = { sendLogToBotVisible = true },
                onConfigureBot = { supportBotSettingsVisible = true },
            )
        }
        item {
            UsageTotalsCard(
                state = state,
                onClear = onClearUsage,
            )
        }
    }

    if (liveLogsVisible) {
        LiveLogsDialog(
            entries = state.diagnosticEntries,
            networkActivityLoggingEnabled = state.settings.expert.networkActivityLogging,
            retention = state.settings.expert.diagnosticsRetention,
            onDismiss = { liveLogsVisible = false },
            onShareArchive = { exportArchive(share = true) },
            onSaveArchive = { exportArchive(share = false) },
        )
    }

    if (sendLogToBotVisible) {
        ConfirmDialog(
            title = stringResource(R.string.send_log_to_bot),
            body = stringResource(R.string.send_log_to_bot_confirm_body),
            confirmLabel = stringResource(R.string.send_log_to_bot),
            icon = Icons.Outlined.FileUpload,
            onDismiss = { sendLogToBotVisible = false },
            onConfirm = {
                sendLogToBotVisible = false
                sendArchiveToSupportBot()
            },
        )
    }

    if (supportBotSettingsVisible) {
        SupportBotHandleDialog(
            currentHandle = supportBotHandle,
            onDismiss = { supportBotSettingsVisible = false },
            onConfirm = { handle ->
                supportBotSettingsVisible = false
                onSupportBotHandleChanged(handle)
                coroutineScope.launch {
                    snackbarHostState.showBanner(
                        supportBotSaved,
                        FoxholeBannerTone.SUCCESS,
                    )
                }
            },
        )
    }
}
