package com.foxhole.guard.ui.cli.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.VisualStyle
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.PendingRoutingScenarioChange
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliVisualStyle
import com.foxhole.guard.ui.cli.cliPanelSwap
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliHomeSectionGap
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartSheetContent
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.isPrimaryConnectionRuntime
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.refreshIpInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CliHomeScreen(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    terminalListState: LazyListState,
    terminalFollowsOutput: Boolean,
    onTerminalFollowsOutputChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val subscriptionRefreshInProgress by
        viewModel.manualSubscriptionRefreshInProgress.collectAsStateWithLifecycle()
    val torIdentityProbe by viewModel.torIdentityProbe.collectAsStateWithLifecycle()
    val pendingRoutingChange by
        viewModel.pendingRoutingScenarioConfirmation.collectAsStateWithLifecycle()
    var selectorOpen by rememberSaveable { mutableStateOf(false) }
    var expandedSmartId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pendingModeCycle by rememberSaveable { mutableStateOf<CliConnectMode?>(null) }
    var clearTerminalOpen by rememberSaveable { mutableStateOf(false) }
    var quickStartOpen by rememberSaveable { mutableStateOf(false) }
    val profileGeoRefresh = rememberProfileGeoRefresh(viewModel, terminal, home)
    val selectorScope = rememberCoroutineScope()
    val closeSelector: () -> Unit = {
        if (expandedSmartId == null) {
            selectorOpen = false
        } else {
            expandedSmartId = null
            selectorScope.launch {
                delay(SELECTOR_COLLAPSE_MS.toLong())
                selectorOpen = false
            }
        }
    }

    CliHomeNarrationEffects(
        viewModel = viewModel,
        terminal = terminal,
        home = home,
        torIdentityProbe = torIdentityProbe,
    )

    val connection = home.connection
    val connected = connection.isRouteConnection()
    val busy = connection.isRouteTransition()

    BackHandler(enabled = selectorOpen) { closeSelector() }

    val statusPrinters = rememberCliStatusPrinters(viewModel = viewModel, terminal = terminal, home = home)

    if (quickStartOpen) {
        CliQuickStartSheetContent(onDismiss = { quickStartOpen = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(selectorOpen) {
                if (selectorOpen) detectTapGestures { closeSelector() }
            }
            .padding(horizontal = CliSpacing.md),
    ) {
        CliTerminalPanel(
            terminal = terminal,
            home = home,
            listState = terminalListState,
            followsOutput = terminalFollowsOutput,
            onFollowsOutputChanged = onTerminalFollowsOutputChanged,
            onInteraction = closeSelector,
            onClearRequested = { clearTerminalOpen = true },
            onHelpRequested = { quickStartOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        CliClearTerminalSheet(
            visible = clearTerminalOpen,
            terminal = terminal,
            onDismiss = { clearTerminalOpen = false },
        )
        CliHomeSectionGap()

        CliHomeConfirmationSlot(
            viewModel = viewModel,
            terminal = terminal,
            routingChange = pendingRoutingChange,
            torPrompt = home.torTransitionPrompt,
        )

        CliHomeProfileArea(
            viewModel = viewModel,
            home = home,
            torIdentityProbe = torIdentityProbe,
            connected = connected,
            selectorOpen = selectorOpen,
            expandedSmartId = expandedSmartId,
            onExpandedSmartChange = { expandedSmartId = it },
            onSelectorOpen = { selectorOpen = true },
            onSelectorClose = closeSelector,
            onProfileHold = profileGeoRefresh.onRefresh,
        )

        CliHomeSectionGap()
        CliHomeButtonsArea(
            viewModel = viewModel,
            terminal = terminal,
            home = home,
            connected = connected,
            busy = busy,
            subscriptionRefreshInProgress = subscriptionRefreshInProgress,
            atomicModePromptPending =
            pendingRoutingChange is PendingRoutingScenarioChange.OperatingMode,
            pendingModeCycle = pendingModeCycle,
            onPendingModeCycleChanged = { pendingModeCycle = it },
            statusPrinters = statusPrinters,
            onCloseSelector = closeSelector,
        )
        CliChromeTailSpacer()
    }
}

@Composable
@Suppress("LongParameterList")
private fun CliHomeButtonsArea(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    busy: Boolean,
    subscriptionRefreshInProgress: Boolean,
    atomicModePromptPending: Boolean,
    pendingModeCycle: CliConnectMode?,
    onPendingModeCycleChanged: (CliConnectMode?) -> Unit,
    statusPrinters: CliStatusPrinters,
    onCloseSelector: () -> Unit,
) {
    val plainStyle = LocalCliVisualStyle.current == VisualStyle.PLAIN
    AnimatedContent(
        targetState = cliHomeButtonsReady(home.profilesLoaded, home.settingsHydrated),
        transitionSpec = { cliPanelSwap(forward = true, plain = plainStyle) },
        label = "homeButtons",
    ) { buttonsReady ->
        if (buttonsReady) {
            Column {
                CliSecondaryButtonsRow(
                    viewModel = viewModel,
                    terminal = terminal,
                    home = home,
                    connected = connected,
                    onStatus = {
                        onCloseSelector()
                        statusPrinters.tap()
                    },
                    onStatusHold = {
                        onCloseSelector()
                        statusPrinters.hold()
                    },
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliPrimaryButtonsRow(
                    viewModel = viewModel,
                    terminal = terminal,
                    home = home,
                    connected = connected,
                    busy = busy,
                    subscriptionRefreshInProgress = subscriptionRefreshInProgress,
                    atomicModePromptPending = atomicModePromptPending,
                    pendingModeCycle = pendingModeCycle,
                    onPendingModeCycleChanged = onPendingModeCycleChanged,
                    onInteraction = onCloseSelector,
                )
            }
        } else {
            CliHomeButtonsLoadingState()
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun CliHomeProfileArea(
    viewModel: HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: com.foxhole.guard.ui.TorIdentityProbeState,
    connected: Boolean,
    selectorOpen: Boolean,
    expandedSmartId: Long?,
    onExpandedSmartChange: (Long?) -> Unit,
    onSelectorOpen: () -> Unit,
    onSelectorClose: () -> Unit,
    onProfileHold: () -> Unit,
) {
    var profileAreaMinHeightPx by rememberSaveable { mutableIntStateOf(0) }
    var smartSelectorHeightActive by remember { mutableStateOf(expandedSmartId != null) }
    LaunchedEffect(selectorOpen, expandedSmartId) {
        when {
            !selectorOpen -> smartSelectorHeightActive = false
            expandedSmartId != null -> smartSelectorHeightActive = true
            smartSelectorHeightActive -> {
                delay(SELECTOR_COLLAPSE_MS.toLong())
                smartSelectorHeightActive = false
            }
        }
    }
    val density = LocalDensity.current
    val profileAreaMinHeight = with(density) { profileAreaMinHeightPx.toDp() }
    val selectorHeightModifier = if (
        cliProfileSelectorUsesFixedHeight(
            measuredHeightPx = profileAreaMinHeightPx,
            smartHeightActive = smartSelectorHeightActive,
        )
    ) {
        Modifier.height(profileAreaMinHeight)
    } else {
        Modifier.heightIn(min = profileAreaMinHeight)
    }
    val surface = when {
        selectorOpen -> CliProfileAreaSurface.SELECTOR
        !home.profilesLoaded -> CliProfileAreaSurface.LOADING
        else -> CliProfileAreaSurface.FACTS
    }
    AnimatedContent(
        targetState = surface,
        transitionSpec = {
            cliSlide(
                forward = targetState == CliProfileAreaSurface.SELECTOR ||
                    initialState == CliProfileAreaSurface.LOADING,
            )
        },
        label = "profileArea",
    ) { shown ->
        when (shown) {
            CliProfileAreaSurface.SELECTOR ->
                CliProfileQuickSelector(
                    viewModel = viewModel,
                    expandedSmartId = expandedSmartId,
                    onExpandedSmartChange = onExpandedSmartChange,
                    onDone = onSelectorClose,
                    modifier = selectorHeightModifier,
                )
            CliProfileAreaSurface.LOADING ->
                CliHomeBootLoadingPanel(viewModel = viewModel, home = home)
            CliProfileAreaSurface.FACTS ->
                CliConnectionFactsPanel(
                    viewModel = viewModel,
                    home = home,
                    torIdentityProbe = torIdentityProbe,
                    connected = connected,
                    onProfileTap = onSelectorOpen,
                    onProfileHold = {
                        onSelectorClose()
                        onProfileHold()
                    },
                    modifier = Modifier
                        .heightIn(min = profileAreaMinHeight)
                        .onSizeChanged { size ->
                            profileAreaMinHeightPx = maxOf(profileAreaMinHeightPx, size.height)
                        },
                )
        }
    }
}

internal fun cliProfileSelectorUsesFixedHeight(
    measuredHeightPx: Int,
    smartHeightActive: Boolean,
): Boolean = measuredHeightPx > 0 && !smartHeightActive

private enum class CliProfileAreaSurface { SELECTOR, LOADING, FACTS }

@Composable
private fun CliHomeConfirmationSlot(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    routingChange: PendingRoutingScenarioChange?,
    torPrompt: TorTransitionPrompt?,
) {
    routingChange?.let { change ->
        CliRoutingChangeConfirmSheet(
            change = change,
            onConfirm = {
                when (change) {
                    is PendingRoutingScenarioChange.OperatingMode ->
                        terminal.command(modeCommandFor(change.target))
                    is PendingRoutingScenarioChange.I2pRelay ->
                        terminal.command(CliCommands.i2p(change.targetEnabled))
                    is PendingRoutingScenarioChange.Tor,
                    is PendingRoutingScenarioChange.Vpn,
                    -> Unit
                }
                viewModel.confirmPendingRoutingScenario()
            },
            onDismiss = viewModel::dismissPendingRoutingScenario,
        )
    } ?: torPrompt?.let { prompt ->
        CliTorPromptPanel(
            viewModel = viewModel,
            prompt = prompt,
            onLiveModeSwitchConfirmed = { target ->
                terminal.command(modeCommandFor(target))
            },
        )
    }
}

@Immutable
private data class CliProfileGeoRefreshActions(
    val onRefresh: () -> Unit,
)

@Composable
private fun rememberProfileGeoRefresh(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
): CliProfileGeoRefreshActions {
    var pending by remember { mutableStateOf(false) }
    var observedLoading by remember { mutableStateOf(false) }
    var baselineAt by remember { mutableLongStateOf(0L) }
    val latestFetchedAt = home.latestNetworkGeoFetchedAt()
    val updatedMessage = stringResource(R.string.cli_home_network_geo_updated)
    LaunchedEffect(home.ipInfoLoading, latestFetchedAt, pending) {
        if (!pending) return@LaunchedEffect
        if (home.ipInfoLoading) {
            observedLoading = true
        } else if (observedLoading) {
            if (latestFetchedAt > baselineAt) terminal.note(updatedMessage, CliLineTone.INFO)
            pending = false
            observedLoading = false
        }
    }
    return CliProfileGeoRefreshActions(
        onRefresh = {
            baselineAt = latestFetchedAt
            observedLoading = home.ipInfoLoading
            pending = true
            viewModel.refreshIpInfo()
        },
    )
}

@Composable
private fun CliClearTerminalSheet(
    visible: Boolean,
    terminal: CliTerminalState,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    CliConfirmSheet(
        title = stringResource(R.string.cli_terminal_clear_title),
        question = stringResource(R.string.cli_terminal_clear_question),
        icon = R.drawable.pix_trash,
        onConfirm = {
            terminal.clearHistory()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

internal fun com.foxhole.guard.ui.HomeRouteUiState.latestNetworkGeoFetchedAt(): Long =
    maxOf(
        deviceIpInfo?.fetchedAt ?: 0L,
        ipInfo?.fetchedAt ?: 0L,
        torIpInfo?.fetchedAt ?: 0L,
    )

internal fun cliHomeButtonsReady(profilesLoaded: Boolean, settingsHydrated: Boolean): Boolean =
    profilesLoaded && settingsHydrated

internal const val CLI_HOME_BUTTONS_LOADING_TAG = "cli_home_buttons_loading"

@Composable
private fun CliHomeButtonsLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CLI_HOME_BUTTONS_BLOCK_HEIGHT)
            .testTag(CLI_HOME_BUTTONS_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CliLoadingRow(text = stringResource(R.string.cli_common_loading_interface))
    }
}

private val CLI_HOME_BUTTONS_BLOCK_HEIGHT = 48.dp * 2 + CliSpacing.sm

@Composable
private fun CliHomeNarrationEffects(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: com.foxhole.guard.ui.TorIdentityProbeState,
) {
    val pendingFirewallPackages = home.settings.expert.pendingQuarantinePackages
    val pendingFirewallMessage = androidx.compose.ui.res.pluralStringResource(
        R.plurals.cli_firewall_action_required_status,
        pendingFirewallPackages.size,
        pendingFirewallPackages.size,
    )
    val torOnlyLive = isTorOnlyLive(home)
    val activeRuntimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val currentStatusWord = cliStatusWordFor(
        state = home.connection.routeState(),
        runtimes = activeRuntimes,
        i2pConnected = activeRuntimes.i2p && home.i2pPhase.phase.networkUp,
        firewallLive = cliFirewallLive(
            settings = home.settings,
            connection = home.connection,
            runtimes = activeRuntimes,
        ),
    )
    val currentStatusText = cliStatusWordText(currentStatusWord)
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    LaunchedEffect(Unit) { terminal.welcome(com.foxhole.guard.BuildConfig.VERSION_NAME) }
    LaunchedEffect(home.profilesLoaded, currentStatusText, currentStatusWord) {
        terminal.updateCurrentStatus(currentStatusText, cliStatusWordTone(currentStatusWord))
        terminal.onBootStage(home.profilesLoaded)
    }
    LaunchedEffect(home.settingsHydrated, pendingFirewallPackages) {
        if (home.settingsHydrated) {
            terminal.onPendingFirewallActions(pendingFirewallPackages, pendingFirewallMessage)
        }
    }
    LaunchedEffect(
        home.connection,
        home.torPhase,
        torIdentityProbe,
        home.i2pPhase,
        home.ipInfo,
        home.torIpInfo,
        home.ipInfoLoading,
    ) {
        terminal.onConnection(home.connection)
        terminal.onTorPhase(home.torPhase)
        terminal.onTorIdentityProbe(torIdentityProbe)
        terminal.onI2pPhase(home.i2pPhase)
        terminal.onRouteIpInfo(
            vpnInfo = terminalVpnIdentity(home, terminal.vpnIdentityNotBeforeMs),
            torInfo = home.torIpInfo.takeIf { home.torPhase.phase == TorNetworkPhase.CONNECTED },
        )
    }
    val torNarrationRevision = terminal.torNarrationRevision
    LaunchedEffect(torNarrationRevision) {
        if (terminal.torNarrationPending) {
            delay(TOR_TERMINAL_STAGE_VISIBLE_MS)
            terminal.advanceTorNarration()
        }
    }
}

private const val TOR_TERMINAL_STAGE_VISIBLE_MS = 650L

internal fun terminalVpnIdentity(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    notBeforeMs: Long? = home.connection.lastChangeAt,
): IpInfo? =
    home.ipInfo?.takeIf { info ->
        home.connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED) &&
            home.connection.isPrimaryConnectionRuntime() &&
            notBeforeMs != null &&
            info.fetchedAt >= notBeforeMs
    }

@Immutable
private data class CliStatusPrinters(
    val tap: () -> Unit,
    val hold: () -> Unit,
)

@Composable
private fun rememberCliStatusPrinters(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
): CliStatusPrinters {
    val torOnlyLive = isTorOnlyLive(home)
    val statusRows = cliStatusRows(home = home, torOnlyLive = torOnlyLive)
    var pendingExtras by remember { mutableStateOf<CliStatusExtras?>(null) }
    val extendedRows = pendingExtras?.let { extras ->
        cliExtendedStatusRows(home = home, torOnlyLive = torOnlyLive, extras = extras)
    }
    LaunchedEffect(pendingExtras) {
        if (extendedRows != null) {
            terminal.emitBlock(extendedRows)
            pendingExtras = null
        }
    }
    val scope = rememberCoroutineScope()
    val statusCommand = stringResource(R.string.cli_cmd_status)
    val statusNote = stringResource(R.string.cli_home_status_note)
    val statusFullCommand = stringResource(R.string.cli_cmd_status_full)
    val statusFullNote = stringResource(R.string.cli_home_status_full_note)
    return CliStatusPrinters(
        tap = {
            terminal.command(statusCommand)
            terminal.footnote(statusNote)
            terminal.emitBlock(statusRows)
        },
        hold = {
            terminal.command(statusFullCommand)
            terminal.footnote(statusFullNote)
            viewModel.refreshIpInfo()
            scope.launch { pendingExtras = viewModel.cliStatusExtras() }
        },
    )
}

internal enum class CliConnectMode(
    val label: String,
    val preset: RoutingModePreset,
) {
    VPN("VPN", RoutingModePreset.VPN),
    TOR("TOR", RoutingModePreset.TOR),
    VPN_TOR("VPN+TOR", RoutingModePreset.VPN_TOR),
}
