package com.foxhole.beta.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.DnsFilterMode
import com.foxhole.beta.core.model.DnsSettings
import com.foxhole.beta.core.model.DomainStrategy
import com.foxhole.beta.core.model.SecureDnsMode
import com.foxhole.beta.core.network.normalizeDnsDomainRules

@Suppress("LongParameterList", "LongMethod")
@Composable
fun DnsSettingsScreen(
    state: SettingsRouteUiState,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onDnsSettingsChanged: (DnsSettings) -> Unit,
    onDomainStrategySelected: (DomainStrategy) -> Unit,
    onOpenDnsBypassApps: () -> Unit,
    onDnsDomainBypassRulesChanged: (List<String>) -> Unit,
    onDnsFilterManualRefresh: () -> Unit,
) {
    val dns = state.settings.dns
    var filterModeExpanded by rememberSaveable { mutableStateOf(false) }
    var secureModeExpanded by rememberSaveable { mutableStateOf(false) }
    var domainStrategyExpanded by rememberSaveable { mutableStateOf(false) }
    var serverDialog by rememberSaveable { mutableStateOf(false) }
    var domainBypassDialog by rememberSaveable { mutableStateOf(false) }
    var filterUpdateSourceDialog by rememberSaveable { mutableStateOf(false) }
    var autoUpdateConsent by rememberSaveable { mutableStateOf(false) }
    var autoUpdateWarning by rememberSaveable { mutableStateOf(false) }
    var refreshAfterEnablePrompt by rememberSaveable { mutableStateOf(false) }
    val refreshInProgress = state.dnsFilterRefreshInProgress

    fun updateDns(next: DnsSettings) {
        onDnsSettingsChanged(next)
    }

    SettingsScaffold(
        title = stringResource(R.string.dns_settings_title),
        snackbarHostState = snackbarHostState,
        onNavigateUp = onNavigateUp,
        tag = "dns_settings_screen",
    ) {
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.dns_protection_title),
                    checked = dns.filteringEnabled,
                    summary = stringResource(R.string.dns_protection_summary),
                    leadingIcon = Icons.Outlined.Security,
                    onCheckedChange = { enabled ->
                        updateDns(dns.copy(filteringEnabled = enabled))
                        if (enabled) {
                            refreshAfterEnablePrompt = true
                        }
                    },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.dns_filter_mode_title),
                    value = dnsFilterModeLabel(dns.filterMode),
                    expanded = filterModeExpanded,
                    onExpandedChange = { filterModeExpanded = it },
                    values = DnsFilterMode.entries,
                    selected = dns.filterMode,
                    label = { dnsFilterModeLabel(it) },
                    onSelect = { mode -> updateDns(dns.copy(filterMode = mode)) },
                    summary = dnsFilterModeSummary(dns.filterMode),
                    leadingIcon = Icons.Outlined.FilterAlt,
                    optionIcon = ::dnsFilterModeIcon,
                    enabled = dns.filteringEnabled,
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_block_ads_title),
                    checked = dns.blockAds,
                    summary = stringResource(R.string.dns_block_ads_summary),
                    leadingIcon = Icons.Outlined.FilterAlt,
                    enabled = dns.filteringEnabled,
                    onCheckedChange = { enabled -> updateDns(dns.copy(blockAds = enabled)) },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_block_trackers_title),
                    checked = dns.blockTrackers,
                    summary = stringResource(R.string.dns_block_trackers_summary),
                    leadingIcon = Icons.Outlined.Shield,
                    enabled = dns.filteringEnabled,
                    onCheckedChange = { enabled -> updateDns(dns.copy(blockTrackers = enabled)) },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_block_app_telemetry_title),
                    checked = dns.blockAppTelemetry,
                    summary = stringResource(R.string.dns_block_app_telemetry_summary),
                    leadingIcon = Icons.Outlined.WarningAmber,
                    enabled = dns.filteringEnabled,
                    onCheckedChange = { enabled -> updateDns(dns.copy(blockAppTelemetry = enabled)) },
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_block_malicious_title),
                    checked = dns.blockMaliciousDomains,
                    summary = stringResource(R.string.dns_block_malicious_summary),
                    leadingIcon = Icons.Outlined.Security,
                    enabled = dns.filteringEnabled,
                    onCheckedChange = { enabled -> updateDns(dns.copy(blockMaliciousDomains = enabled)) },
                    titleMaxLines = 2,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_auto_update_filters_title),
                    checked = dns.autoUpdateFilters,
                    summary = stringResource(R.string.dns_auto_update_filters_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    enabled = dns.filteringEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            autoUpdateWarning = true
                        } else {
                            autoUpdateConsent = true
                        }
                    },
                    summaryMaxLines = 3,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.dns_filter_update_source_title),
                    value = dnsFilterUpdateSourceLabel(dns.dnsFilterUpdateUrl),
                    summary = stringResource(R.string.dns_filter_update_source_summary),
                    leadingIcon = Icons.Outlined.Public,
                    onClick =
                        if (dns.filteringEnabled) {
                            { filterUpdateSourceDialog = true }
                        } else {
                            null
                        },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.dns_filter_list_status_title),
                    value = dnsFilterStatusLabel(dns.filtersUpdatedAt),
                    summary = stringResource(R.string.dns_filter_list_status_summary),
                    leadingIcon = Icons.Outlined.Refresh,
                    actionIcon = Icons.Outlined.Refresh,
                    onClick =
                        if (refreshInProgress) {
                            null
                        } else {
                            onDnsFilterManualRefresh
                        },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                if (refreshInProgress) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item {
            SettingsControlGroup {
                SettingSwitchRow(
                    title = stringResource(R.string.dns_through_vpn_title),
                    checked = dns.dnsThroughVpn,
                    summary = stringResource(R.string.dns_through_vpn_summary),
                    leadingIcon = Icons.Outlined.Shield,
                    onCheckedChange = { enabled -> updateDns(dns.copy(dnsThroughVpn = enabled)) },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_block_outside_tunnel_title),
                    checked = dns.blockOutsideTunnel,
                    summary = stringResource(R.string.dns_block_outside_tunnel_summary),
                    leadingIcon = Icons.Outlined.Security,
                    onCheckedChange = { enabled -> updateDns(dns.copy(blockOutsideTunnel = enabled)) },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingSwitchRow(
                    title = stringResource(R.string.dns_intercept_requests_title),
                    checked = dns.interceptDnsRequests,
                    summary = stringResource(R.string.dns_intercept_requests_summary),
                    leadingIcon = Icons.Outlined.Dns,
                    onCheckedChange = { enabled -> updateDns(dns.copy(interceptDnsRequests = enabled)) },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.dns_server_title),
                    value = dns.server,
                    leadingIcon = Icons.Outlined.Public,
                    onClick = { serverDialog = true },
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.dns_secure_mode_title),
                    value = secureDnsModeLabel(dns.secureMode),
                    expanded = secureModeExpanded,
                    onExpandedChange = { secureModeExpanded = it },
                    values = SecureDnsMode.entries,
                    selected = dns.secureMode,
                    label = { secureDnsModeLabel(it) },
                    onSelect = { mode -> updateDns(dns.copy(secureMode = mode)) },
                    leadingIcon = Icons.Outlined.Dns,
                    optionIcon = ::secureDnsModeIcon,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                DropdownSettingRow(
                    title = stringResource(R.string.domain_strategy),
                    value = domainStrategyLabel(state.settings.traffic.domainStrategy),
                    expanded = domainStrategyExpanded,
                    onExpandedChange = { domainStrategyExpanded = it },
                    values = DomainStrategy.entries,
                    selected = state.settings.traffic.domainStrategy,
                    label = { domainStrategyLabel(it) },
                    onSelect = onDomainStrategySelected,
                    leadingIcon = Icons.Outlined.AccountTree,
                    optionIcon = { Icons.Outlined.AccountTree },
                    grouped = true,
                )
            }
        }
        item {
            SettingsControlGroup {
                SettingValueRow(
                    title = stringResource(R.string.dns_per_app_bypass_title),
                    value = pluralStringResource(
                        R.plurals.dns_app_bypass_count,
                        dns.appBypassPackages.size,
                        dns.appBypassPackages.size,
                    ),
                    summary = stringResource(R.string.dns_per_app_bypass_summary),
                    leadingIcon = Icons.Outlined.Apps,
                    onClick = onOpenDnsBypassApps,
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
                SettingsControlGroupDivider()
                SettingValueRow(
                    title = stringResource(R.string.dns_domain_bypass_title),
                    value = pluralStringResource(
                        R.plurals.dns_domain_bypass_count,
                        dns.domainBypassRules.size,
                        dns.domainBypassRules.size,
                    ),
                    summary = stringResource(R.string.dns_domain_bypass_summary),
                    leadingIcon = Icons.Outlined.Language,
                    onClick = { domainBypassDialog = true },
                    summaryMaxLines = Int.MAX_VALUE,
                    grouped = true,
                )
            }
        }
    }

    if (serverDialog) {
        TextValueDialog(
            title = stringResource(R.string.dns_server_title),
            icon = Icons.Outlined.Public,
            initialValue = dns.server,
            singleLine = true,
            onDismiss = { serverDialog = false },
            onConfirm = { value -> updateDns(dns.copy(server = value)) },
        )
    }

    if (domainBypassDialog) {
        DnsDomainBypassDialog(
            initialRules = dns.domainBypassRules,
            onDismiss = { domainBypassDialog = false },
            onConfirm = onDnsDomainBypassRulesChanged,
        )
    }

    if (filterUpdateSourceDialog) {
        TextValueDialog(
            title = stringResource(R.string.dns_filter_update_source_title),
            icon = Icons.Outlined.Public,
            initialValue = dns.dnsFilterUpdateUrl,
            singleLine = true,
            onDismiss = { filterUpdateSourceDialog = false },
            onConfirm = { value -> updateDns(dns.copy(dnsFilterUpdateUrl = value)) },
        )
    }

    if (autoUpdateConsent) {
        ConfirmDialog(
            title = stringResource(R.string.dns_auto_update_consent_title),
            body = stringResource(R.string.dns_auto_update_consent_body),
            confirmLabel = stringResource(R.string.enable_label),
            dismissLabel = stringResource(R.string.close),
            icon = Icons.Outlined.Refresh,
            onDismiss = { autoUpdateConsent = false },
            onConfirm = {
                autoUpdateConsent = false
                updateDns(dns.copy(autoUpdateFilters = true))
            },
        )
    }

    if (refreshAfterEnablePrompt) {
        ConfirmDialog(
            title = stringResource(R.string.dns_filter_refresh_after_enable_title),
            body = stringResource(R.string.dns_filter_refresh_after_enable_body),
            confirmLabel = stringResource(R.string.refresh),
            dismissLabel = stringResource(R.string.close),
            icon = Icons.Outlined.Refresh,
            onDismiss = { refreshAfterEnablePrompt = false },
            onConfirm = {
                refreshAfterEnablePrompt = false
                onDnsFilterManualRefresh()
            },
        )
    }

    if (autoUpdateWarning) {
        ConfirmDialog(
            title = stringResource(R.string.dns_auto_update_warning_title),
            body = stringResource(R.string.dns_auto_update_warning_body),
            confirmLabel = stringResource(R.string.disable_label),
            dismissLabel = stringResource(R.string.close),
            icon = Icons.Outlined.WarningAmber,
            onDismiss = { autoUpdateWarning = false },
            onConfirm = {
                autoUpdateWarning = false
                updateDns(dns.copy(autoUpdateFilters = false))
            },
        )
    }
}

private fun dnsFilterUpdateSourceLabel(value: String): String =
    value
        .removePrefix("https://")
        .removePrefix("http://")
        .substringBefore('/')
        .ifBlank { value }

@Composable
private fun DnsDomainBypassDialog(
    initialRules: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var rawRules by rememberSaveable { mutableStateOf(initialRules.joinToString(separator = "\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { FoxholeDialogTitle(title = stringResource(R.string.dns_domain_bypass_title), icon = Icons.Outlined.Language) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.dns_domain_bypass_editor_summary),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = rawRules,
                    onValueChange = { rawRules = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    label = { Text(stringResource(R.string.site_mask_input_label)) },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            FoxholeDialogConfirmButton(
                onClick = {
                    onConfirm(normalizeDnsDomainRules(rawRules))
                    onDismiss()
                },
            )
        },
        dismissButton = {
            FoxholeDialogDismissButton(onClick = onDismiss)
        },
    )
}

@Composable
private fun dnsFilterModeLabel(value: DnsFilterMode): String =
    stringResource(
        when (value) {
            DnsFilterMode.COMPATIBILITY -> R.string.dns_filter_mode_compatibility
            DnsFilterMode.STRICT -> R.string.dns_filter_mode_strict
        },
    )

@Composable
private fun dnsFilterModeSummary(value: DnsFilterMode): String =
    stringResource(
        when (value) {
            DnsFilterMode.COMPATIBILITY -> R.string.dns_filter_mode_compatibility_summary
            DnsFilterMode.STRICT -> R.string.dns_filter_mode_strict_summary
        },
    )

private fun dnsFilterModeIcon(value: DnsFilterMode) =
    when (value) {
        DnsFilterMode.COMPATIBILITY -> Icons.Outlined.Shield
        DnsFilterMode.STRICT -> Icons.Outlined.WarningAmber
    }

@Composable
private fun secureDnsModeLabel(value: SecureDnsMode): String =
    stringResource(
        when (value) {
            SecureDnsMode.DOH -> R.string.dns_secure_mode_doh
            SecureDnsMode.DOT -> R.string.dns_secure_mode_dot
            SecureDnsMode.PLAIN -> R.string.dns_secure_mode_plain
        },
    )

private fun secureDnsModeIcon(value: SecureDnsMode) =
    when (value) {
        SecureDnsMode.DOH -> Icons.Outlined.Security
        SecureDnsMode.DOT -> Icons.Outlined.Shield
        SecureDnsMode.PLAIN -> Icons.Outlined.Public
    }

@Composable
private fun dnsFilterStatusLabel(updatedAt: Long?): String =
    if (updatedAt == null) {
        stringResource(R.string.dns_filter_status_never)
    } else {
        val elapsedMinutes = ((System.currentTimeMillis() - updatedAt).coerceAtLeast(0L) / 60_000L).coerceAtLeast(1L)
        when {
            elapsedMinutes < 60L -> {
                val minutes = elapsedMinutes.toInt()
                pluralStringResource(R.plurals.dns_filter_status_minutes, minutes, minutes)
            }
            elapsedMinutes < 24L * 60L -> {
                val hours = (elapsedMinutes / 60L).toInt().coerceIn(1, 23)
                pluralStringResource(R.plurals.dns_filter_status_hours, hours, hours)
            }
            else -> {
                val days = (elapsedMinutes / (24L * 60L)).toInt().coerceAtLeast(1)
                pluralStringResource(R.plurals.dns_filter_status_days, days, days)
            }
        }
    }
