package com.foxhole.guard.ui.cli.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AccentColor
import com.foxhole.core.model.AppLocale
import com.foxhole.core.model.LatencyProbeMethod
import com.foxhole.core.model.PanelAppearance
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TunStack
import com.foxhole.core.model.VisualStyle
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.SettingsRouteUiState
import com.foxhole.guard.ui.cli.CliRetentionUnit
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.CliTerminalPrefs
import com.foxhole.guard.ui.cli.CliTopContentGap
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.LocalCliPanelAppearance
import com.foxhole.guard.ui.cli.cliAccentSwatch
import com.foxhole.guard.ui.cli.cliDynamicAccentOrNull
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliActionRow
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliDropdownOption
import com.foxhole.guard.ui.cli.components.CliDropdownRow
import com.foxhole.guard.ui.cli.components.CliGlassHeaderScreen
import com.foxhole.guard.ui.cli.components.CliInputModal
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRowDivider
import com.foxhole.guard.ui.cli.components.CliScreenHeader
import com.foxhole.guard.ui.cli.components.CliStringSetSaver
import com.foxhole.guard.ui.cli.components.CliToggleRow
import com.foxhole.guard.ui.cli.logs.CliLogsScreen
import com.foxhole.guard.ui.onAccentColorSelected
import com.foxhole.guard.ui.onAtomicConnectionChanged
import com.foxhole.guard.ui.onAutoReconnectChanged
import com.foxhole.guard.ui.onAutoStartChanged
import com.foxhole.guard.ui.onLatencyProbeMethodSelected
import com.foxhole.guard.ui.onLocalProxyLanAccessChanged
import com.foxhole.guard.ui.onLocaleSelected
import com.foxhole.guard.ui.onMtuChanged
import com.foxhole.guard.ui.onPanelAppearanceSelected
import com.foxhole.guard.ui.onPreferIpv6Changed
import com.foxhole.guard.ui.onTunStackSelected
import com.foxhole.guard.ui.onVisualStyleSelected
import com.foxhole.guard.ui.onWebAppsEnabledChanged

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
                modifier = Modifier.statusBarsPadding().padding(top = CliTopContentGap),
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
    val dnsUpdatePhase by viewModel.dnsFilterUpdatePhase.collectAsStateWithLifecycle()
    CliGlassHeaderScreen(
        header = {
            CliScreenHeader(label = stringResource(R.string.cli_dock_settings), icon = R.drawable.pix_settings)
        },
    ) { topInset ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CliSpacing.md),
        ) {
            Spacer(modifier = Modifier.height(topInset))
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
                refreshPhase = dnsUpdatePhase,
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
            CliModulesSection(
                viewModel = viewModel,
                settings = state.settings,
                expanded = SECTION_MODULES in expandedSections,
                onToggleExpanded = { onToggleSection(SECTION_MODULES) },
                onOpenTor = { onOpenSub(SUB_TOR) },
                onOpenI2p = { onOpenSub(SUB_I2P) },
                onOpenFirewall = { onOpenSub(SUB_FIREWALL) },
                onOpenAnomaly = { onOpenSub(SUB_ANOMALY) },
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliExtrasSection(
                settings = state.settings,
                expanded = SECTION_EXTRAS in expandedSections,
                onToggleExpanded = { onToggleSection(SECTION_EXTRAS) },
                onWebAppsEnabledChanged = viewModel::onWebAppsEnabledChanged,
                onProxyServerEnabledChanged = viewModel::onLocalProxyLanAccessChanged,
                onOpenWebApps = { onOpenSub(SUB_WEBAPPS) },
                onOpenProxyServer = { onOpenSub(SUB_LAN_PROXY) },
            )
            Spacer(modifier = Modifier.height(CliSpacing.sm))
            CliMoreSection(viewModel = viewModel, onOpenSub = onOpenSub)
            CliChromeTailSpacer()
        }
    }
}

@Composable
private fun CliCfgSubScreen(
    key: String,
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    when (key) {
        SUB_DNS_BYPASS -> CliDnsBypassAppsScreen(viewModel, modifier)
        SUB_TOR -> CliTorSubScreen(viewModel, modifier)
        SUB_I2P -> CliI2pSubScreen(viewModel, modifier)
        SUB_LAN_PROXY -> CliLanProxySubScreen(viewModel, modifier)
        SUB_WEBAPPS -> CliWebAppsSubScreen(viewModel, modifier)
        SUB_ANOMALY -> CliAnomalySubScreen(viewModel, modifier)
        SUB_FIREWALL -> CliFirewallSubScreen(viewModel, modifier)
        SUB_UPDATES -> CliUpdatesSubScreen(viewModel, modifier)
        SUB_WIDGETS -> CliWidgetsSubScreen(viewModel, modifier)
        SUB_DATA -> CliDataSubScreen(viewModel, modifier)
        SUB_LOGS -> CliLogsScreen(
            viewModel = viewModel,
            modifier = modifier.fillMaxSize(),
        )
        SUB_ABOUT -> CliAboutSubScreen(viewModel, modifier)
        SUB_HELP -> CliHelpSubScreen(modifier)
        else -> LaunchedEffect(key) { onBack() }
    }
}

private const val SUB_DNS_BYPASS = "dns_bypass"
private const val SUB_TOR = "tor"
private const val SUB_I2P = "i2p"
private const val SUB_LAN_PROXY = "lan_proxy"
private const val SUB_WEBAPPS = "webapps"
private const val SUB_ANOMALY = "anomaly"
private const val SUB_FIREWALL = "firewall"
private const val SUB_UPDATES = "updates"
private const val SUB_WIDGETS = "widgets"
private const val SUB_DATA = "data"
private const val SUB_LOGS = "journals"
private const val SUB_ABOUT = "about"
private const val SUB_HELP = "help"

private const val SECTION_NETWORK = "network"
private const val SECTION_RULES = "rules"
private const val SECTION_DNS = "dns"
private const val SECTION_SECURITY = "security"

private const val SECTION_MODULES = "modules"
private const val SECTION_APP = "application"
private const val SECTION_EXTRAS = "extras"

internal const val CLI_OPT_CUSTOM = "custom"

@Composable
private fun CliNetworkSection(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val settings = state.settings
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_network),
        icon = R.drawable.pix_globe,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_atomic_connection),
            icon = R.drawable.pix_shield,
            checked = settings.connection.atomicConnection,
            onToggle = viewModel::onAtomicConnectionChanged,
            infoText = stringResource(R.string.cli_cfg_atomic_connection_note),
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_reconnect),
            icon = R.drawable.pix_restart,
            checked = settings.connection.autoReconnect,
            onToggle = viewModel::onAutoReconnectChanged,
            infoText = stringResource(R.string.cli_cfg_auto_reconnect_note),
        )
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_auto_start),
            icon = R.drawable.pix_power,
            checked = settings.connection.autoStartOnBoot,
            onToggle = viewModel::onAutoStartChanged,
            infoText = stringResource(R.string.cli_cfg_auto_start_note),
        )
        CliRowDivider()
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_latency_method),
            icon = R.drawable.pix_clock,
            value = settings.connection.latencyProbeMethod.name.lowercase(),
            options = LatencyProbeMethod.entries.map { method ->
                CliDropdownOption(id = method.name, label = method.name.lowercase())
            },
            selectedId = settings.connection.latencyProbeMethod.name,
            onSelect = { id -> viewModel.onLatencyProbeMethodSelected(LatencyProbeMethod.valueOf(id)) },
        )
        if (settings.traffic.mode == TrafficMode.TUNNEL) {
            CliRowDivider()
            CliDropdownRow(
                label = stringResource(R.string.cli_cfg_tun_stack),
                icon = R.drawable.pix_device,
                value = settings.traffic.tunStack.name.lowercase(),
                options = TunStack.entries.map { stack ->
                    CliDropdownOption(id = stack.name, label = stack.name.lowercase())
                },
                selectedId = settings.traffic.tunStack.name,
                onSelect = { id -> viewModel.onTunStackSelected(TunStack.valueOf(id)) },
            )
            CliRowDivider()
            CliMtuRows(viewModel = viewModel, currentMtu = settings.traffic.mtu)
        }
        CliRowDivider()
        CliToggleRow(
            label = stringResource(R.string.cli_cfg_prefer_ipv6),
            icon = R.drawable.pix_globe,
            checked = settings.traffic.preferIpv6,
            onToggle = viewModel::onPreferIpv6Changed,
        )
    }
}

@Composable
private fun CliRulesSection(
    viewModel: HomeViewModel,
    state: SettingsRouteUiState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_rules),
        icon = R.drawable.pix_settings,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        CliNetworkRulesRows(viewModel = viewModel, state = state)
    }
}

@Composable
private fun CliMtuRows(
    viewModel: HomeViewModel,
    currentMtu: Int,
) {
    var customOpen by rememberSaveable { mutableStateOf(false) }
    var mtuText by rememberSaveable { mutableStateOf("") }
    CliDropdownRow(
        label = "mtu",
        icon = R.drawable.pix_up,
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
            icon = R.drawable.pix_up,
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

private val MTU_PRESETS = listOf(1280, 1400, 1500, 9000)
private const val MIN_MTU = 576
private const val MAX_MTU = 9000

@Composable
private fun CliApplicationSection(
    viewModel: HomeViewModel,
    settings: Settings,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenSub: (String) -> Unit,
) {
    val colors = LocalCliColors.current
    CliPanel(
        title = stringResource(R.string.cli_cfg_group_app),
        icon = R.drawable.pix_settings,
        iconColor = colors.accent,
        modifier = Modifier.fillMaxWidth(),
        collapsible = true,
        expanded = expanded,
        onToggleExpanded = onToggleExpanded,
    ) {
        val autoAppearanceLabel = stringResource(R.string.cli_cfg_appearance_auto)
        val standardAppearanceLabel = stringResource(R.string.cli_cfg_appearance_standard)
        val darkAppearanceLabel = stringResource(R.string.cli_cfg_appearance_dark)
        val lightAppearanceLabel = stringResource(R.string.cli_cfg_appearance_light)
        val appearanceLabel = { appearance: PanelAppearance ->
            panelAppearanceLabel(
                appearance,
                autoAppearanceLabel,
                standardAppearanceLabel,
                darkAppearanceLabel,
                lightAppearanceLabel,
            )
        }
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_appearance),
            icon = R.drawable.pix_star,
            value = appearanceLabel(settings.ui.panelAppearance),
            options = PanelAppearance.entries.map { appearance ->
                CliDropdownOption(
                    id = appearance.name,
                    label = appearanceLabel(appearance),
                    icon = panelAppearanceIcon(appearance),
                )
            },
            selectedId = settings.ui.panelAppearance.name,
            onSelect = { id ->
                viewModel.onPanelAppearanceSelected(PanelAppearance.valueOf(id))
            },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        val pixelVisualLabel = stringResource(R.string.cli_cfg_visual_style_pixel)
        val plainVisualLabel = stringResource(R.string.cli_cfg_visual_style_plain)
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_visual_style),
            icon = R.drawable.pix_edit,
            value = visualStyleLabel(settings.ui.visualStyle, pixelVisualLabel, plainVisualLabel),
            options = VisualStyle.entries.map { style ->
                CliDropdownOption(
                    id = style.name,
                    label = visualStyleLabel(style, pixelVisualLabel, plainVisualLabel),
                    icon = visualStyleIcon(style),
                )
            },
            selectedId = settings.ui.visualStyle.name,
            onSelect = { id ->
                viewModel.onVisualStyleSelected(VisualStyle.valueOf(id))
            },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        CliAccentColorRow(viewModel = viewModel, settings = settings)
        CliRowDivider()
        val systemLocaleLabel = stringResource(R.string.cli_cfg_locale_system)
        val russianLocaleLabel = stringResource(R.string.cli_cfg_locale_ru)
        val englishLocaleLabel = stringResource(R.string.cli_cfg_locale_en)
        CliDropdownRow(
            label = stringResource(R.string.cli_cfg_language),
            icon = R.drawable.pix_globe,
            value = localeLabel(settings.ui.locale, systemLocaleLabel, russianLocaleLabel, englishLocaleLabel),
            options = AppLocale.entries.map { locale ->
                CliDropdownOption(
                    id = locale.name,
                    label = localeLabel(locale, systemLocaleLabel, russianLocaleLabel, englishLocaleLabel),
                    flagCountry = localeFlagCountry(locale),
                )
            },
            selectedId = settings.ui.locale.name,
            onSelect = { id -> viewModel.onLocaleSelected(AppLocale.valueOf(id)) },
            showSelectedOptionIcon = true,
        )
        CliRowDivider()
        CliTerminalClearRows()
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_widgets),
            icon = R.drawable.pix_home,
            onTap = { onOpenSub(SUB_WIDGETS) },
        )
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_data),
            icon = R.drawable.pix_export,
            onTap = { onOpenSub(SUB_DATA) },
        )
    }
}

@Composable
private fun CliMoreSection(
    viewModel: HomeViewModel,
    onOpenSub: (String) -> Unit,
) {
    CliPanel(modifier = Modifier.fillMaxWidth()) {
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_updates),
            icon = R.drawable.pix_settings,
            attention = rememberCliUpdatesAttention(viewModel),
            onTap = { onOpenSub(SUB_UPDATES) },
        )
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_journals),
            icon = R.drawable.pix_journal,
            onTap = { onOpenSub(SUB_LOGS) },
        )
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_help),
            icon = R.drawable.pix_info,
            onTap = { onOpenSub(SUB_HELP) },
        )
        CliRowDivider()
        CliActionRow(
            label = stringResource(R.string.cli_cfg_more_about),
            icon = R.drawable.pix_star,
            onTap = { onOpenSub(SUB_ABOUT) },
        )
    }
}

@Composable
private fun CliTerminalClearRows() {
    val context = LocalContext.current
    var retentionHours by remember { mutableIntStateOf(CliTerminalPrefs.readRetentionHours(context)) }
    var customUnit by rememberSaveable { mutableStateOf<CliRetentionUnit?>(null) }
    var customValue by rememberSaveable { mutableStateOf("") }
    val applyHours: (Int) -> Unit = { hours ->
        CliTerminalPrefs.writeRetentionHours(context, hours)
        retentionHours = CliTerminalPrefs.readRetentionHours(context)
    }
    val customLabel = stringResource(R.string.cli_common_custom)
    CliDropdownRow(
        label = stringResource(R.string.cli_cfg_terminal_clear),
        icon = R.drawable.pix_clock,
        value = CliTerminalPrefs.retentionLabel(retentionHours),
        options = TERMINAL_CLEAR_PRESETS.map { hours ->
            CliDropdownOption(id = hours.toString(), label = CliTerminalPrefs.retentionLabel(hours))
        } + listOf(
            CliDropdownOption(id = CLI_OPT_CUSTOM_HOURS, label = "$customLabel · h"),
            CliDropdownOption(id = CLI_OPT_CUSTOM_DAYS, label = "$customLabel · d"),
        ),
        selectedId = terminalRetentionOptionId(retentionHours),
        onSelect = { id ->
            when (id) {
                CLI_OPT_CUSTOM_HOURS -> customUnit = CliRetentionUnit.HOURS
                CLI_OPT_CUSTOM_DAYS -> customUnit = CliRetentionUnit.DAYS
                else -> {
                    customUnit = null
                    id.toIntOrNull()?.let(applyHours)
                }
            }
        },
    )
    customUnit?.let { unit ->
        CliInputModal(
            title = stringResource(R.string.cli_input_value_title),
            icon = R.drawable.pix_clock,
            prompt = if (unit == CliRetentionUnit.DAYS) "d" else "h",
            value = customValue,
            onValueChange = { raw -> customValue = raw.filter(Char::isDigit).take(CUSTOM_ENTRY_MAX_DIGITS) },
            onSubmit = {
                CliTerminalPrefs.hoursFromInput(customValue, unit)?.let { hours ->
                    applyHours(hours)
                    customUnit = null
                }
            },
            onDismiss = { customUnit = null },
            numeric = true,
        )
    }
}

private fun terminalRetentionOptionId(retentionHours: Int): String =
    when {
        retentionHours in TERMINAL_CLEAR_PRESETS -> retentionHours.toString()
        retentionHours % CliTerminalPrefs.HOURS_PER_DAY == 0 -> CLI_OPT_CUSTOM_DAYS
        else -> CLI_OPT_CUSTOM_HOURS
    }

private val TERMINAL_CLEAR_PRESETS = listOf(6, 12, 24, 48)

private const val CLI_OPT_CUSTOM_HOURS = "custom_hours"
private const val CLI_OPT_CUSTOM_DAYS = "custom_days"
private const val CUSTOM_ENTRY_MAX_DIGITS = 3

private fun localeLabel(
    locale: AppLocale,
    systemLabel: String,
    russianLabel: String,
    englishLabel: String,
): String = when (locale) {
    AppLocale.SYSTEM -> systemLabel
    AppLocale.RU -> russianLabel
    AppLocale.EN -> englishLabel
}

private fun panelAppearanceLabel(
    appearance: PanelAppearance,
    autoLabel: String,
    standardLabel: String,
    darkLabel: String,
    lightLabel: String,
): String = when (appearance) {
    PanelAppearance.AUTO -> autoLabel
    PanelAppearance.STANDARD -> standardLabel
    PanelAppearance.DARK -> darkLabel
    PanelAppearance.LIGHT -> lightLabel
}

private fun panelAppearanceIcon(appearance: PanelAppearance): Int = when (appearance) {
    PanelAppearance.AUTO -> R.drawable.pix_star
    PanelAppearance.STANDARD -> R.drawable.pix_device
    PanelAppearance.DARK -> R.drawable.pix_incognito
    PanelAppearance.LIGHT -> R.drawable.pix_globe
}

private fun visualStyleIcon(style: VisualStyle): Int = when (style) {
    VisualStyle.PIXEL -> R.drawable.pix_qr
    VisualStyle.PLAIN -> R.drawable.pix_edit
}

@Composable
private fun CliAccentColorRow(viewModel: HomeViewModel, settings: Settings) {
    val appearance = LocalCliPanelAppearance.current
    val colors = LocalCliColors.current
    val autoLabel = stringResource(R.string.cli_cfg_accent_auto)
    val orangeLabel = stringResource(R.string.cli_cfg_accent_orange)
    val greenLabel = stringResource(R.string.cli_cfg_accent_green)
    val limeLabel = stringResource(R.string.cli_cfg_accent_lime)
    val blueLabel = stringResource(R.string.cli_cfg_accent_blue)
    val pinkLabel = stringResource(R.string.cli_cfg_accent_pink)
    val cyanLabel = stringResource(R.string.cli_cfg_accent_cyan)
    val accentLabel = { accent: AccentColor ->
        when (accent) {
            AccentColor.AUTO -> autoLabel
            AccentColor.ORANGE -> orangeLabel
            AccentColor.GREEN -> greenLabel
            AccentColor.LIME -> limeLabel
            AccentColor.BLUE -> blueLabel
            AccentColor.PINK -> pinkLabel
            AccentColor.CYAN -> cyanLabel
        }
    }
    CliDropdownRow(
        label = stringResource(R.string.cli_cfg_accent),
        icon = R.drawable.pix_star,
        value = accentLabel(settings.ui.accentColor),
        options = AccentColor.entries.map { accent ->
            CliDropdownOption(
                id = accent.name,
                label = accentLabel(accent),
                swatch = if (accent == AccentColor.AUTO) {
                    cliDynamicAccentOrNull() ?: colors.accent
                } else {
                    cliAccentSwatch(appearance, accent)
                },
            )
        },
        selectedId = settings.ui.accentColor.name,
        onSelect = { id -> viewModel.onAccentColorSelected(AccentColor.valueOf(id)) },
        showSelectedOptionIcon = true,
    )
}

private fun visualStyleLabel(
    style: VisualStyle,
    pixelLabel: String,
    plainLabel: String,
): String = when (style) {
    VisualStyle.PIXEL -> pixelLabel
    VisualStyle.PLAIN -> plainLabel
}

private fun localeFlagCountry(locale: AppLocale): String? = when (locale) {
    AppLocale.SYSTEM -> null
    AppLocale.RU -> "ru"
    AppLocale.EN -> "gb"
}
