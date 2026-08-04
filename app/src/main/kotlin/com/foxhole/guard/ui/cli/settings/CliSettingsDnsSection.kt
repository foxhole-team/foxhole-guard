package com.foxhole.guard.ui.cli.settings

import androidx.compose.foundation.layout.fillMaxWidth
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
import com.foxhole.guard.ui.DnsResolverPreset
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliElbowLine
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliSelectRow
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.dnsResolverPresetFor
import com.foxhole.guard.ui.onDnsDomainBypassRulesChanged
import com.foxhole.guard.ui.onDnsReplaceSystemDnsChanged
import com.foxhole.guard.ui.onDnsSettingsChanged
import com.foxhole.guard.ui.onDomainStrategySelected
import com.foxhole.guard.ui.onSniffChanged

/**
 * The dns panel. Enabling the filter goes through onDnsSettingsChanged, whose built-in
 * preflight downloads the rule set and reports progress via banners - those lines land in
 * the home terminal through the existing snackbar bridge, no extra progress UI here.
 */
@Composable
internal fun CliDnsSection(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    domainStrategy: DomainStrategy,
    sniff: Boolean,
    refreshInProgress: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenAppBypass: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_section_dns),
        icon = R.drawable.pix_dns,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliDnsFilteringGroup(viewModel = viewModel, dns = dns, refreshInProgress = refreshInProgress)
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_dns_secure),
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
        CliDnsServerRows(viewModel = viewModel, dns = dns)
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_domain_strategy),
            value = domainStrategy.name.lowercase(),
            options = DomainStrategy.entries.map { strategy ->
                CliDropdownOption(id = strategy.name, label = strategy.name.lowercase())
            },
            selectedId = domainStrategy.name,
            onSelect = { id -> viewModel.onDomainStrategySelected(DomainStrategy.valueOf(id)) },
        )
        CliDnsResolverGroup(viewModel = viewModel, dns = dns, sniff = sniff)
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_dns_replace_system),
            checked = dns.replaceSystemDns,
            onToggle = viewModel::onDnsReplaceSystemDnsChanged,
            note = stringResource(R.string.cli_cfg_dns_replace_system_note),
        )
        if (dnsReplaceSystemIpv6WarningVisible(dns)) {
            CliElbowLine(
                text = stringResource(R.string.cli_cfg_dns_replace_system_ipv6_warn),
                color = colors.warn,
            )
        }
        CliDnsBypassRows(viewModel = viewModel, dns = dns)
        CliActionRow(
            label = stringResource(R.string.cli_cfg_dns_app_bypass),
            value = dns.appBypassPackages.size.toString(),
            onTap = onOpenAppBypass,
        )
    }
}

/** Filter master + the per-category blocks, each level dropdown gated on its category. */
@Composable
private fun CliDnsFilteringGroup(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    refreshInProgress: Boolean,
) {
    val colors = LocalCliColors.current
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_filter),
        checked = dns.filteringEnabled,
        enabled = !refreshInProgress,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(filteringEnabled = it)) },
    )
    if (refreshInProgress) {
        Text(
            text = stringResource(R.string.cli_cfg_dns_filter_loading),
            style = CliType.small,
            color = colors.info,
        )
    }
    if (!dns.filteringEnabled) {
        return
    }
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_ads),
        checked = dns.blockAds,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockAds = it)) },
    )
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_trackers),
        checked = dns.blockTrackers,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockTrackers = it)) },
    )
    if (dnsTrackerLevelVisible(dns)) {
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_dns_tracker_level),
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
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_telemetry),
        checked = dns.blockAppTelemetry,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockAppTelemetry = it)) },
    )
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_malicious),
        checked = dns.blockMaliciousDomains,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockMaliciousDomains = it)) },
    )
    if (dnsThreatLevelVisible(dns)) {
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_dns_threat_level),
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
    if (dnsInterceptWarningVisible(dns)) {
        CliElbowLine(
            text = stringResource(R.string.cli_cfg_dns_intercept_warn),
            color = colors.warn,
        )
    }
}

/** Resolver placement + leak controls: how DNS reaches the network and what falls outside it. */
@Composable
private fun CliDnsResolverGroup(
    viewModel: HomeViewModel,
    dns: DnsSettings,
    sniff: Boolean,
) {
    val colors = LocalCliColors.current
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_use_vpn_provider),
        checked = dns.useVpnProviderDns,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(useVpnProviderDns = it)) },
    )
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_through_vpn),
        checked = dns.dnsThroughVpn,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(dnsThroughVpn = it)) },
    )
    if (dnsLeakWarningVisible(dns)) {
        CliElbowLine(
            text = stringResource(R.string.cli_cfg_dns_leak_warn),
            color = colors.warn,
        )
    }
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_block_outside),
        checked = dns.blockOutsideTunnel,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(blockOutsideTunnel = it)) },
    )
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_intercept),
        checked = dns.interceptDnsRequests,
        onToggle = { viewModel.onDnsSettingsChanged(dns.copy(interceptDnsRequests = it)) },
    )
    CliToggleRow(
        label = stringResource(R.string.cli_cfg_dns_sniff),
        checked = sniff,
        onToggle = viewModel::onSniffChanged,
    )
}

/** Resolver row: known presets re-address by secure mode; custom opens a free-form input. */
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
            prompt = "dns",
            value = customServer,
            onValueChange = { customServer = it.take(MAX_SERVER_LENGTH) },
            onSubmit = {
                if (customServer.isNotBlank()) {
                    // The repository normalizes schemes/blank input (normalizedDnsServer).
                    viewModel.onDnsSettingsChanged(dns.copy(server = customServer.trim()))
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
        )
    }
}

/**
 * Domain bypass rules: `✗ domain` rows to delete plus an input to add (repo normalizes).
 * Not a select - the trigger row just folds the rule list in and out inline.
 */
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
        value = dns.domainBypassRules.size.toString(),
        expanded = open,
        onExpandToggle = { open = !open },
    )
    if (open) {
        dns.domainBypassRules.forEach { rule ->
            CliActionRow(
                label = rule,
                value = "✗",
                onTap = {
                    viewModel.onDnsDomainBypassRulesChanged(dns.domainBypassRules - rule)
                },
            )
        }
        CliActionRow(
            label = stringResource(R.string.cli_input_domain_add),
            onTap = { addOpen = true },
        )
    }
    if (addOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_domain_title),
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

// Pure gating predicates for the DNS panel, extracted so the show/hide rules are unit-testable
// without composing the UI.
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

// Technical protocol wire names, identical in every locale.
private fun secureModeDetail(mode: SecureDnsMode): String = when (mode) {
    SecureDnsMode.DOH -> "dns-over-https"
    SecureDnsMode.DOT -> "dns-over-tls"
    SecureDnsMode.PLAIN -> "udp/53"
}
