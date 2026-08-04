package com.foxhole.guard.ui.cli.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.SettingsRouteUiState
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTerminalPrefs
import com.foxhole.guard.ui.cli.CliType
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliStringSetSaver
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.logs.CliLogsScreen
import com.foxhole.guard.ui.onAutoReconnectChanged
import com.foxhole.guard.ui.onAutoStartChanged
import com.foxhole.guard.ui.onI2pEnabledChanged
import com.foxhole.guard.ui.onLatencyProbeMethodSelected
import com.foxhole.guard.ui.onLocaleSelected
import com.foxhole.guard.ui.onMtuChanged
import com.foxhole.guard.ui.onPreferIpv6Changed
import com.foxhole.guard.ui.onTorRoutePermittedChanged
import com.foxhole.guard.ui.onTunStackSelected
import com.foxhole.guard.ui.onWebAppsEnabledChanged
import com.foxhole.guard.ui.onWidgetAlphaPercentChanged
import com.foxhole.guard.ui.onWidgetBlackBackgroundChanged

/**
 * The `cfg` tab, regrouped after the classic SettingsHomeScreen: network / dns / security /
 * tor·i2p / application. Every group is a collapsible `── section ──` panel, collapsed by
 * default; the open set is a saveable key set, so it survives rotation and process death.
 * Selects float as anchored dropdown popups ([CliDropdownRow]); toggles apply instantly
 * through the same VM extensions the classic screens use.
 */
@Composable
internal fun CliSettingsScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    var subScreen by rememberSaveable { mutableStateOf<String?>(null) }
    var expandedSections by rememberSaveable(stateSaver = CliStringSetSaver) {
        mutableStateOf(emptySet())
    }
    val onToggleSection: (String) -> Unit = { key ->
        expandedSections =
            if (key in expandedSections) expandedSections - key else expandedSections + key
    }

    BackHandler(enabled = subScreen != null) { subScreen = null }
    // Sub-screens push in from the right and pop back out, following the shared cliSlide law.
    AnimatedContent(
        targetState = subScreen,
        transitionSpec = { cliSlide(forward = targetState != null) },
        modifier = modifier,
        label = "cfgSub",
    ) { sub ->
        if (sub != null) {
            CliCfgSubScreen(
                key = sub,
                viewModel = viewModel,
                onBack = { subScreen = null },
                modifier = Modifier,
            )
        } else {
            CliSettingsRootColumn(
                viewModel = viewModel,
                state = state,
                expandedSections = expandedSections,
                onToggleSection = onToggleSection,
                onOpenSub = { subScreen = it },
            )
        }
    }
}

@Composable
private fun CliSettingsRootColumn(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    expandedSections: Set<String>,
    onToggleSection: (String) -> Unit,
    onOpenSub: (String) -> Unit,
) {
    // No hydration gate, same as the classic settings screens: the VM warms settings up in
    // init, and every write goes through repository update lambdas over the CURRENT value,
    // so a pre-hydration frame can only look default, not overwrite anything.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = CliSpacing.md),
    ) {
        // Root list only - the sub-screens returned above carry their own back rows.
        // Section order is a product decision: network, rules, dns, security, tor/i2p,
        // application, extras.
        CliScreenHeader(label = stringResource(R.string.cli_dock_settings), icon = R.drawable.pix_settings)
        CliNetworkSection(
            viewModel = viewModel,
            state = state,
            expanded = SECTION_NETWORK in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_NETWORK) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliRulesSection(
            viewModel = viewModel,
            state = state,
            expanded = SECTION_RULES in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_RULES) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliDnsSection(
            viewModel = viewModel,
            dns = state.settings.dns,
            domainStrategy = state.settings.traffic.domainStrategy,
            sniff = state.settings.expert.sniff,
            refreshInProgress = state.dnsFilterRefreshInProgress,
            expanded = SECTION_DNS in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_DNS) },
            onOpenAppBypass = { onOpenSub(SUB_DNS_BYPASS) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliSecuritySection(
            viewModel = viewModel,
            settings = state.settings,
            expanded = SECTION_SECURITY in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_SECURITY) },
            onOpenAnomaly = { onOpenSub(SUB_ANOMALY) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPrivacySection(
            viewModel = viewModel,
            settings = state.settings,
            expanded = SECTION_PRIVACY in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_PRIVACY) },
            onOpenTor = { onOpenSub(SUB_TOR) },
            onOpenI2p = { onOpenSub(SUB_I2P) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliApplicationSection(
            viewModel = viewModel,
            settings = state.settings,
            expanded = SECTION_APP in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_APP) },
            onOpenSub = onOpenSub,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliExtrasSection(
            settings = state.settings,
            expanded = SECTION_EXTRAS in expandedSections,
            onToggleExpanded = { onToggleSection(SECTION_EXTRAS) },
            onOpenFileShare = { onOpenSub(SUB_FILE_SHARE) },
            onWebAppsEnabledChanged = viewModel::onWebAppsEnabledChanged,
            onOpenWebApps = { onOpenSub(SUB_WEBAPPS) },
            onOpenProxyServer = { onOpenSub(SUB_LAN_PROXY) },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliMoreSection(onOpenSub = onOpenSub)
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

@Composable
private fun CliCfgSubScreen(
    key: String,
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    // There are no top back rows: system back closes a sub-screen via the host BackHandler and
    // screens open straight into content. The I2P screen is frozen and keeps its row.
    when (key) {
        SUB_DNS_BYPASS -> CliDnsBypassAppsScreen(viewModel, modifier)
        SUB_TOR -> CliTorSubScreen(viewModel, modifier)
        SUB_I2P -> CliI2pSubScreen(viewModel, modifier)
        SUB_LAN_PROXY -> CliLanProxySubScreen(viewModel, modifier)
        SUB_FILE_SHARE -> CliFileShareSubScreen(viewModel, modifier)
        SUB_WEBAPPS -> CliWebAppsSubScreen(viewModel, modifier)
        SUB_ANOMALY -> CliAnomalySubScreen(viewModel, modifier)
        SUB_UPDATES -> CliUpdatesSubScreen(viewModel, modifier)
        SUB_DATA -> CliDataSubScreen(viewModel, modifier)
        SUB_LOGS -> CliLogsScreen(
            viewModel = viewModel,
            modifier = modifier.fillMaxSize(),
        )
        SUB_ABOUT -> CliAboutSubScreen(viewModel, modifier)
        SUB_HELP -> CliHelpSubScreen(modifier)
        // A key saved by an older build (e.g. "routing", now the apps tab) restores into
        // nothing renderable - pop back instead of leaving the user on a blank screen.
        else -> LaunchedEffect(key) { onBack() }
    }
}

private const val SUB_DNS_BYPASS = "dns_bypass"
private const val SUB_TOR = "tor"
private const val SUB_I2P = "i2p"
private const val SUB_LAN_PROXY = "lan_proxy"
private const val SUB_FILE_SHARE = "file_share"
private const val SUB_WEBAPPS = "webapps"
private const val SUB_ANOMALY = "anomaly"
private const val SUB_UPDATES = "updates"
private const val SUB_DATA = "data"
private const val SUB_LOGS = "journals"
private const val SUB_ABOUT = "about"
private const val SUB_HELP = "help"

private const val SECTION_NETWORK = "network"
private const val SECTION_RULES = "rules"
private const val SECTION_DNS = "dns"
private const val SECTION_SECURITY = "security"
private const val SECTION_PRIVACY = "privacy"
private const val SECTION_APP = "application"
private const val SECTION_EXTRAS = "extras"

/** Shared option id for the free-input entry of preset dropdowns («custom…»). */
internal const val CLI_OPT_CUSTOM = "custom"

/** network: connection behaviors, tunnel shape and the per-transport network rules. */
@Composable
private fun CliNetworkSection(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val settings = state.settings
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_network),
        icon = R.drawable.pix_globe,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_reconnect),
            checked = settings.connection.autoReconnect,
            onToggle = viewModel::onAutoReconnectChanged,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_start),
            checked = settings.connection.autoStartOnBoot,
            onToggle = viewModel::onAutoStartChanged,
        )
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_latency_method),
            value = settings.connection.latencyProbeMethod.name.lowercase(),
            options = LatencyProbeMethod.entries.map { method ->
                CliDropdownOption(id = method.name, label = method.name.lowercase())
            },
            selectedId = settings.connection.latencyProbeMethod.name,
            onSelect = { id -> viewModel.onLatencyProbeMethodSelected(LatencyProbeMethod.valueOf(id)) },
        )
        if (settings.traffic.mode == TrafficMode.TUNNEL) {
            CliDropdownRow(
                label = stringResource(R.string.cli_cfg_tun_stack),
                value = settings.traffic.tunStack.name.lowercase(),
                options = TunStack.entries.map { stack ->
                    CliDropdownOption(id = stack.name, label = stack.name.lowercase())
                },
                selectedId = settings.traffic.tunStack.name,
                onSelect = { id -> viewModel.onTunStackSelected(TunStack.valueOf(id)) },
            )
            CliMtuRows(viewModel = viewModel, currentMtu = settings.traffic.mtu)
        }
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_prefer_ipv6),
            checked = settings.traffic.preferIpv6,
            onToggle = viewModel::onPreferIpv6Changed,
        )
    }
}

/** Network rules: its own collapsible root section for wifi/cellular profile bindings. */
@Composable
private fun CliRulesSection(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_rules),
        icon = R.drawable.pix_journal,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliNetworkRulesRows(viewModel = viewModel, state = state)
    }
}

/** MTU: standard frame sizes as presets, the custom option opens a digits input below. */
@Composable
private fun CliMtuRows(
    viewModel: HomeViewModel,
    currentMtu: Int,
) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var mtuText by rememberSaveable { mutableStateOf("") }
    CliDropdownRow(
        label = "mtu",
        value = currentMtu.toString(),
        options = MTU_PRESETS.map { mtu ->
            CliDropdownOption(id = mtu.toString(), label = mtu.toString())
        } + CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        selectedId = if (currentMtu in MTU_PRESETS) currentMtu.toString() else CLI_OPT_CUSTOM,
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM) {
                customOpen = true
            } else {
                customOpen = false
                id.toIntOrNull()?.let(viewModel::onMtuChanged)
            }
        },
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            prompt = "mtu",
            value = mtuText,
            onValueChange = { raw -> mtuText = raw.filter(Char::isDigit).take(4) },
            onSubmit = {
                mtuText.toIntOrNull()?.let { mtu ->
                    viewModel.onMtuChanged(mtu.coerceIn(MIN_MTU, MAX_MTU))
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
        )
    }
}

// ipv6 minimum, typical vpn overhead, ethernet, jumbo.
private val MTU_PRESETS = listOf(1280, 1400, 1500, 9000)
private const val MIN_MTU = 576
private const val MAX_MTU = 9000

/** tor / i2p: the core permissions plus the entry into the tor route sub-screen. */
@Composable
private fun CliPrivacySection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenTor: () -> Unit,
    onOpenI2p: () -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_privacy),
        icon = R.drawable.pix_incognito,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_tor_core),
            checked = settings.privacyRoute.permitted,
            onToggle = viewModel::onTorRoutePermittedChanged,
        )
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_i2p_core),
            checked = settings.i2p.enabled,
            onToggle = viewModel::onI2pEnabledChanged,
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_tor),
            onTap = onOpenTor,
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_i2p),
            onTap = onOpenI2p,
        )
    }
}

/** application: language and terminal upkeep. Appearance is fixed by the brand canon. */
@Composable
private fun CliApplicationSection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSub: (String) -> Unit,
) {
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_app),
        icon = R.drawable.pix_settings,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_language),
            value = localeLabel(settings.ui.locale),
            options = AppLocale.entries.map { locale ->
                CliDropdownOption(id = locale.name, label = localeLabel(locale))
            },
            selectedId = settings.ui.locale.name,
            onSelect = { id -> viewModel.onLocaleSelected(AppLocale.valueOf(id)) },
        )
        // Home widget defaults; an individual widget can override them in its configure form.
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_widget_bg),
            value = stringResource(
                if (settings.widgets.blackBackground) R.string.cli_widget_bg_black else R.string.cli_widget_bg_white,
            ),
            options = listOf(
                CliDropdownOption(id = WIDGET_BG_OPT_BLACK, label = stringResource(R.string.cli_widget_bg_black)),
                CliDropdownOption(id = WIDGET_BG_OPT_WHITE, label = stringResource(R.string.cli_widget_bg_white)),
            ),
            selectedId = if (settings.widgets.blackBackground) WIDGET_BG_OPT_BLACK else WIDGET_BG_OPT_WHITE,
            onSelect = { id -> viewModel.onWidgetBlackBackgroundChanged(id == WIDGET_BG_OPT_BLACK) },
        )
        CliWidgetAlphaRow(viewModel = viewModel, alphaPercent = settings.widgets.alphaPercent)
        CliTerminalClearRows()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_data),
            onTap = { onOpenSub(SUB_DATA) },
        )
    }
}

@Composable
private fun CliWidgetAlphaRow(
    viewModel: HomeViewModel,
    alphaPercent: Int,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CliSpacing.sm),
    ) {
        Text(
            text = stringResource(R.string.cli_cfg_widget_alpha),
            style = CliType.body,
            color = colors.fg,
            modifier = Modifier.weight(1f),
        )
        CliChip(
            label = "-",
            onClick = { viewModel.onWidgetAlphaPercentChanged((alphaPercent - 10).coerceAtLeast(0)) },
        )
        Text(text = "$alphaPercent%", style = CliType.body, color = colors.accent)
        CliChip(
            label = "+",
            onClick = { viewModel.onWidgetAlphaPercentChanged((alphaPercent + 10).coerceAtMost(100)) },
        )
    }
}

private const val WIDGET_BG_OPT_BLACK = "black"
private const val WIDGET_BG_OPT_WHITE = "white"

/**
 * Updates, journals, help and about live at the settings root as a flat panel of action rows:
 * this is a primary entry point and must not be hidden inside the application expander.
 */
@Composable
private fun CliMoreSection(onOpenSub: (String) -> Unit) {
    CliPanel(modifier = Modifier.fillMaxWidth()) {
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_updates),
            icon = R.drawable.pix_update,
            onTap = { onOpenSub(SUB_UPDATES) },
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_journals),
            icon = R.drawable.pix_journal,
            onTap = { onOpenSub(SUB_LOGS) },
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_help),
            icon = R.drawable.pix_info,
            onTap = { onOpenSub(SUB_HELP) },
        )
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_about),
            icon = R.drawable.pix_star,
            onTap = { onOpenSub(SUB_ABOUT) },
        )
    }
}

/** Terminal line lifetime: presets plus a free hour entry, persisted device-locally. */
@Composable
private fun CliTerminalClearRows() {
    val context = LocalContext.current
    var retentionHours by remember { mutableIntStateOf(CliTerminalPrefs.readRetentionHours(context)) }
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var customHours by rememberSaveable { mutableStateOf("") }
    CliDropdownRow(
        label = stringResource(R.string.cli_cfg_terminal_clear),
        value = "${retentionHours}h",
        options = TERMINAL_CLEAR_PRESETS.map { hours ->
            CliDropdownOption(id = hours.toString(), label = "${hours}h")
        } + CliDropdownOption(id = CLI_OPT_CUSTOM, label = stringResource(R.string.cli_common_custom)),
        selectedId = if (retentionHours in TERMINAL_CLEAR_PRESETS) {
            retentionHours.toString()
        } else {
            CLI_OPT_CUSTOM
        },
        onSelect = { id ->
            if (id == CLI_OPT_CUSTOM) {
                customOpen = true
            } else {
                customOpen = false
                id.toIntOrNull()?.let { hours ->
                    CliTerminalPrefs.writeRetentionHours(context, hours)
                    retentionHours = hours
                }
            }
        },
    )
    if (customOpen) {
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            prompt = "h",
            value = customHours,
            onValueChange = { raw -> customHours = raw.filter(Char::isDigit).take(3) },
            onSubmit = {
                customHours.toIntOrNull()?.let { hours ->
                    val bounded = hours.coerceIn(CliTerminalPrefs.MIN_HOURS, CliTerminalPrefs.MAX_HOURS)
                    CliTerminalPrefs.writeRetentionHours(context, bounded)
                    retentionHours = bounded
                    customOpen = false
                }
            },
            onDismiss = { customOpen = false },
            numeric = true,
        )
    }
}

private val TERMINAL_CLEAR_PRESETS = listOf(6, 12, 24, 48)

// Locale names are self-describing words, not translations - same in every UI language.
private fun localeLabel(locale: AppLocale): String = when (locale) {
    AppLocale.SYSTEM -> "system"
    AppLocale.RU -> "русский"
    AppLocale.EN -> "english"
}
