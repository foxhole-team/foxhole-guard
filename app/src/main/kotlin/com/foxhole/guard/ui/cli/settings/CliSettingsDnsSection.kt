package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.DnsSettings
import com.foxhole.core.model.DomainStrategy
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.ThreatListLevel
import com.foxhole.core.model.TrackerListLevel
import com.foxhole.guard.R
import com.foxhole.guard.runtime.RemoteDownloadProgress
import com.foxhole.guard.ui.DnsResolverPreset
import com.foxhole.guard.ui.FoxholeUpdatePhase
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliBottomSheet
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliSelectRow
import com.foxhole.guard.ui.cli.components.CliSheetAction
import com.foxhole.guard.ui.cli.components.CliSheetActionsRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.dnsResolverPresetFor
import com.foxhole.guard.ui.isRunning
import com.foxhole.guard.ui.onDnsDomainBypassRulesChanged
import com.foxhole.guard.ui.onDnsFilterEnablePreflightCancelled
import com.foxhole.guard.ui.onDnsReplaceSystemDnsChanged
import com.foxhole.guard.ui.onDnsSettingsChanged
import com.foxhole.guard.ui.onDomainStrategySelected
import com.foxhole.guard.ui.onSniffChanged

@Composable
internal fun CliDnsSection(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    domainStrategy: DomainStrategy,
    sniff: Boolean,
    refreshInProgress: Boolean,
    refreshPhase: FoxholeUpdatePhase,
    refreshProgress: RemoteDownloadProgress?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenAppBypass: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_section_dns),
        icon = R.drawable.lin_dns,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliDnsFilteringGroup(
            viewModel = viewModel,
            dns = dns,
            refreshInProgress = refreshInProgress,
            refreshPhase = refreshPhase,
            refreshProgress = refreshProgress,
        )
        CliRowDivider()
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_dns_secure),
            icon = R.drawable.lin_lock,
            value = dns.secureMode.name.lowercase(),
            options = SecureDnsMode.entries.map { mode ->
                CliDropdownOption(
                    id = mode.name,
                    label = mode.name.lowercase(),
                    detail = secureModeDetail(mode),
                )
            },
            selectedId = dns.secureMode.name,
            onSelect = { id ->
                viewModel.onDnsSettingsChanged(dns.copy(secureMode = SecureDnsMode.valueOf(id)))
            },
        )
        CliRowDivider()
        CliDnsServerRows(viewModel = viewModel, dns = dns)
        CliRowDivider()
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_domain_strategy),
            icon = R.drawable.lin_globe,
            value = domainStrategy.name.lowercase(),
            options = DomainStrategy.entries.map { strategy ->
                CliDropdownOption(id = strategy.name, label = strategy.name.lowercase())
            },
            selectedId = domainStrategy.name,
            onSelect = { id -> viewModel.onDomainStrategySelected(DomainStrategy.valueOf(id)) },
        )
        CliRowDivider()
        CliDnsResolverGroup(viewModel = viewModel, dns = dns, sniff = sniff)
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_replace_system),
            icon = R.drawable.lin_settings,
            checked = dns.replaceSystemDns,
            onToggle = viewModel::onDnsReplaceSystemDnsChanged,
            infoText = stringResource(R.string.cli_cfg_dns_replace_system_note),
        )
        CliSettingsAnimatedRows(visible = dnsReplaceSystemIpv6WarningVisible(dns)) {
            CliElbowLine(
                text = stringResource(R.string.cli_cfg_dns_replace_system_ipv6_warn),
                color = colors.warn,
            )
        }
        CliRowDivider()
        CliDnsBypassRows(viewModel = viewModel, dns = dns)
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_dns_app_bypass),
            icon = R.drawable.lin_apps,
            value = dns.appBypassPackages.size.toString(),
            onTap = onOpenAppBypass,
        )
    }
}

@Composable
private fun CliDnsFilteringGroup(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    refreshInProgress: Boolean,
    refreshPhase: FoxholeUpdatePhase,
    refreshProgress: RemoteDownloadProgress?,
) {
    val colors = LocalCliColors.current
    var enableConfirmationOpen by rememberSaveable { mutableStateOf(false) }
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_filter),
        icon = R.drawable.lin_dns,
        checked = dns.filteringEnabled,
        enabled = !refreshInProgress,
        onToggle = { requestedEnabled ->
            if (dnsFilteringEnableConfirmationRequired(dns.filteringEnabled, requestedEnabled)) {
                enableConfirmationOpen = true
            } else {
                enableConfirmationOpen = false
                viewModel.onDnsSettingsChanged(dns.copy(filteringEnabled = requestedEnabled))
            }
        },
    )
    if (enableConfirmationOpen) {
        val verifiedSuccess = dns.filteringEnabled &&
            (refreshPhase == FoxholeUpdatePhase.DONE || refreshPhase == FoxholeUpdatePhase.NO_UPDATE)
        CliDnsFilterEnableSheet(
            phase = refreshPhase,
            refreshInProgress = refreshInProgress,
            verifiedSuccess = verifiedSuccess,
            downloadProgress = refreshProgress,
            onConfirm = {
                viewModel.onDnsSettingsChanged(dns.copy(filteringEnabled = true))
            },
            onDismiss = {
                if (refreshInProgress) viewModel.onDnsFilterEnablePreflightCancelled()
                enableConfirmationOpen = false
            },
        )
    }
    CliSettingsAnimatedRows(visible = dns.filteringEnabled) {
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_block_ads),
            icon = R.drawable.lin_forbidden,
            checked = dns.blockAds,
            onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockAds = it)) },
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_block_trackers),
            icon = R.drawable.lin_incognito,
            checked = dns.blockTrackers,
            onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockTrackers = it)) },
        )
        CliSettingsAnimatedRows(visible = dnsTrackerLevelVisible(dns)) {
            CliRowDivider()
            CliDropdownRow(
                label = stringResource(R.string.cli_cfg_dns_tracker_level),
                icon = R.drawable.lin_up,
                value = dns.trackerListLevel.name.lowercase(),
                options = TrackerListLevel.entries.map { level ->
                    CliDropdownOption(id = level.name, label = level.name.lowercase())
                },
                selectedId = dns.trackerListLevel.name,
                onSelect = { id ->
                    viewModel.onDnsSettingsChanged(dns.copy(trackerListLevel = TrackerListLevel.valueOf(id)))
                },
                modifier = Modifier.padding(start = CliSpacing.md),
            )
        }
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_block_telemetry),
            icon = R.drawable.lin_stats,
            checked = dns.blockAppTelemetry,
            onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockAppTelemetry = it)) },
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_block_malicious),
            icon = R.drawable.lin_shield,
            checked = dns.blockMaliciousDomains,
            onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockMaliciousDomains = it)) },
        )
        CliSettingsAnimatedRows(visible = dnsThreatLevelVisible(dns)) {
            CliRowDivider()
            CliDropdownRow(
                label = stringResource(R.string.cli_cfg_dns_threat_level),
                icon = R.drawable.lin_up,
                value = dns.threatListLevel.name.lowercase(),
                options = ThreatListLevel.entries.map { level ->
                    CliDropdownOption(id = level.name, label = level.name.lowercase())
                },
                selectedId = dns.threatListLevel.name,
                onSelect = { id ->
                    viewModel.onDnsSettingsChanged(dns.copy(threatListLevel = ThreatListLevel.valueOf(id)))
                },
                modifier = Modifier.padding(start = CliSpacing.md),
            )
        }
        CliSettingsAnimatedRows(visible = dnsInterceptWarningVisible(dns)) {
            CliElbowLine(
                text = stringResource(R.string.cli_cfg_dns_intercept_warn),
                color = colors.warn,
            )
        }
    }
}

internal fun dnsFilteringEnableConfirmationRequired(
    currentlyEnabled: Boolean,
    requestedEnabled: Boolean,
): Boolean = !currentlyEnabled && requestedEnabled

@Composable
private fun CliDnsFilterEnableSheet(
    phase: FoxholeUpdatePhase,
    refreshInProgress: Boolean,
    verifiedSuccess: Boolean,
    downloadProgress: RemoteDownloadProgress?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliBottomSheet(
        title = stringResource(R.string.cli_dns_filter_enable_title),
        icon = R.drawable.lin_dns,
        onDismiss = onDismiss,
        autoDismissAfterMillis = DNS_FILTER_SUCCESS_AUTO_DISMISS_MS.takeIf { verifiedSuccess },
    ) {
        val runningPhase = phase.takeIf { it.isRunning } ?: FoxholeUpdatePhase.CHECKING
        when {
            refreshInProgress || phase.isRunning -> {
                CliVerifiedUpdateProgress(
                    phase = runningPhase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = false,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetActionsRow(actions = emptyList())
            }
            verifiedSuccess -> {
                CliVerifiedUpdateProgress(
                    phase = phase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = true,
                )
            }
            phase == FoxholeUpdatePhase.DONE || phase == FoxholeUpdatePhase.NO_UPDATE -> {
                CliVerifiedUpdateProgress(
                    phase = phase,
                    downloadProgress = downloadProgress,
                    verifiedSuccess = false,
                )
            }
            phase == FoxholeUpdatePhase.FAILED -> {
                Text(
                    text = stringResource(R.string.dns_filter_refresh_failed),
                    style = CliType.body,
                    color = colors.err,
                )
                Spacer(modifier = Modifier.height(CliSpacing.md))
                CliSheetActionsRow(
                    actions = listOf(
                        CliSheetAction(
                            label = stringResource(R.string.cli_tor_bridges_update_retry),
                            onClick = onConfirm,
                        ),
                    ),
                )
            }
            else -> CliDnsFilterEnablePrompt(onConfirm)
        }
    }
}

@Composable
private fun CliDnsFilterEnablePrompt(onConfirm: () -> Unit) {
    val colors = LocalCliColors.current
    Text(
        text = stringResource(R.string.cli_dns_filter_enable_body),
        style = CliType.body,
        color = colors.fg,
    )
    Spacer(modifier = Modifier.height(CliSpacing.md))
    CliSheetActionsRow(
        actions = listOf(
            CliSheetAction(
                label = stringResource(R.string.cli_common_yes_confirm),
                onClick = onConfirm,
            ),
        ),
    )
}

@Composable
private fun CliDnsResolverGroup(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    sniff: Boolean,
) {
    val colors = LocalCliColors.current
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_use_vpn_provider),
        icon = R.drawable.lin_link,
        checked = dns.useVpnProviderDns,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(useVpnProviderDns = it)) },
    )
    CliRowDivider()
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_through_vpn),
        icon = R.drawable.lin_shield,
        checked = dns.dnsThroughVpn,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(dnsThroughVpn = it)) },
    )
    CliSettingsAnimatedRows(visible = dnsLeakWarningVisible(dns)) {
        CliElbowLine(
            text = stringResource(R.string.cli_cfg_dns_leak_warn),
            color = colors.warn,
        )
    }
    CliRowDivider()
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_outside),
        icon = R.drawable.lin_forbidden,
        checked = dns.blockOutsideTunnel,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockOutsideTunnel = it)) },
    )
    CliRowDivider()
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_intercept),
        icon = R.drawable.lin_import,
        checked = dns.interceptDnsRequests,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(interceptDnsRequests = it)) },
    )
    CliRowDivider()
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_sniff),
        icon = R.drawable.lin_status,
        checked = sniff,
        onToggle = viewModel::onSniffChanged,
    )
}

@Composable
private fun CliDnsServerRows(
    viewModel: HomeViewModel,
    dns: DnsSettings,
) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var customServer by rememberSaveable { mutableStateOf("") }
    val preset = dnsResolverPresetFor(dns.server)
    CliDropdownRow(
        label = stringResource(R.string.cli_cfg_dns_server),
        icon = R.drawable.lin_dns,
        value = preset?.label?.lowercase() ?: dns.server,
        options = DnsResolverPreset.entries
            .filter { it != DnsResolverPreset.CUSTOM }
            .map { candidate ->
                CliDropdownOption(
                    id = candidate.name,
                    label = candidate.label.lowercase(),
                    detail = candidate.hostFor(dns.secureMode),
                )
            } + CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        selectedId = preset?.name ?: CLI_OPT_CUSTOM,
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM) {
                customOpen = true
            } else {
                customOpen = false
                DnsResolverPreset.entries.firstOrNull { it.name == id }?.let { candidate ->
                    viewModel.onDnsSettingsChanged(dns.copy(server = candidate.hostFor(dns.secureMode)))
                }
            }
        },
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            icon = R.drawable.lin_dns,
            prompt = "dns",
            value = customServer,
            onValueChange = { customServer = it.take(MAX_SERVER_LENGTH) },
            onSubmit = {
                if (customServer.isNotBlank()) {
                    viewModel.onDnsSettingsChanged(dns.copy(server = customServer.trim()))
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
        )
    }
}

@Composable
private fun CliDnsBypassRows(
    viewModel: HomeViewModel,
    dns: DnsSettings,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var newDomain by rememberSaveable { mutableStateOf("") }
    CliSelectRow(
        label = stringResource(R.string.cli_cfg_dns_bypass),
        icon = R.drawable.lin_link,
        value = dns.domainBypassRules.size.toString(),
        expanded = open,
        onExpandToggle = { open = !open },
    )
    CliSettingsAnimatedRows(visible = open) {
        dns.domainBypassRules.forEach { rule ->
            CliActionRow(
                label = rule,
                icon = R.drawable.lin_link,
                value = "✗",
                onTap = {
                    viewModel.onDnsDomainBypassRulesChanged(dns.domainBypassRules - rule)
                },
            )
            CliRowDivider()
        }
        CliActionRow(
            label = stringResource(R.string.cli_input_domain_add),
            icon = R.drawable.lin_add,
            onTap = { addOpen = true },
        )
    }
    if (addOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_domain_title),
            icon = R.drawable.lin_globe,
            prompt = "+",
            value = newDomain,
            onValueChange = { newDomain = it.take(MAX_SERVER_LENGTH) },
            onSubmit = {
                if (newDomain.isNotBlank()) {
                    viewModel.onDnsDomainBypassRulesChanged(dns.domainBypassRules + newDomain.trim())
                    newDomain = ""
                    addOpen = false
                }
            },
            onDismiss = { addOpen = false },
        )
    }
}

internal fun dnsTrackerLevelVisible(dns: DnsSettings): Boolean =
    dns.filteringEnabled && dns.blockTrackers

internal fun dnsThreatLevelVisible(dns: DnsSettings): Boolean =
    dns.filteringEnabled && dns.blockMaliciousDomains

internal fun dnsInterceptWarningVisible(dns: DnsSettings): Boolean =
    dns.filteringEnabled && !dns.interceptDnsRequests

internal fun dnsLeakWarningVisible(dns: DnsSettings): Boolean =
    !dns.useVpnProviderDns && !dns.dnsThroughVpn

internal fun dnsReplaceSystemIpv6WarningVisible(dns: DnsSettings): Boolean =
    dns.replaceSystemDns

private const val MAX_SERVER_LENGTH = 253
private const val DNS_FILTER_SUCCESS_AUTO_DISMISS_MS = 900L

private fun secureModeDetail(mode: SecureDnsMode): String = when (mode) {
    SecureDnsMode.DOH -> "dns-over-https"
    SecureDnsMode.DOT -> "dns-over-tls"
    SecureDnsMode.PLAIN -> "udp/53"
}
