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
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.PendingRoutingScenarioChange
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.components.CliSectionDivider
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.isPrimaryConnectionRuntime
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.refreshIpInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Home: top ~half is the terminal panel (brand logo + scrolling status log), the bottom half
 * is the touch control block - profile row, connection facts and the CONNECT/TOR buttons.
 */
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
    var pendingModeCycle by rememberSaveable { mutableStateOf<CliConnectMode?>(null) }
    var clearTerminalOpen by rememberSaveable { mutableStateOf(false) }
    val profileGeoRefresh = rememberProfileGeoRefresh(viewModel, terminal, home)

    // The consent sheet that used to live here is gone with the button that raised it: the mode
    // cycler is absent while the Tor module is off, so nothing on this screen can ask for the
    // permission any more. It is granted where the module is — settings → TOR / I2P — and the
    // screen no longer carries a sheet for a path that cannot be reached.

    CliHomeNarrationEffects(
        viewModel = viewModel,
        terminal = terminal,
        home = home,
        torIdentityProbe = torIdentityProbe,
    )

    val connection = home.connection
    // The firewall is a background filter, not a route: it takes no part in connected/disconnected,
    // colours no status and drives no buttons (see [isRouteConnection]).
    val connected = connection.isRouteConnection()
    val busy = connection.isRouteTransition()

    BackHandler(enabled = selectorOpen) { selectorOpen = false }

    val statusPrinters = rememberCliStatusPrinters(viewModel = viewModel, terminal = terminal, home = home)

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(selectorOpen) {
                if (selectorOpen) detectTapGestures { selectorOpen = false }
            }
            .padding(horizontal = CliSpacing.md),
    ) {
        CliTerminalPanel(
            terminal = terminal,
            home = home,
            listState = terminalListState,
            followsOutput = terminalFollowsOutput,
            onFollowsOutputChanged = onTerminalFollowsOutputChanged,
            onInteraction = { selectorOpen = false },
            onClearRequested = { clearTerminalOpen = true },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        CliClearTerminalSheet(
            visible = clearTerminalOpen,
            terminal = terminal,
            onDismiss = { clearTerminalOpen = false },
        )
        CliSectionDivider()

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
            onSelectorOpenChanged = { selectorOpen = it },
            onProfileHold = profileGeoRefresh.onHold,
        )

        CliSectionDivider()
        if (cliHomeButtonsReady(home.profilesLoaded, home.settingsHydrated)) {
            CliSecondaryButtonsRow(
                viewModel = viewModel,
                terminal = terminal,
                home = home,
                connected = connected,
                // Tap prints the current state; hold adds identity, encryption and the journal tails.
                onStatus = {
                    selectorOpen = false
                    statusPrinters.tap()
                },
                onStatusHold = {
                    selectorOpen = false
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
                atomicModePromptPending = pendingRoutingChange is PendingRoutingScenarioChange.OperatingMode,
                pendingModeCycle = pendingModeCycle,
                onPendingModeCycleChanged = { pendingModeCycle = it },
                onInteraction = { selectorOpen = false },
            )
        } else {
            CliHomeButtonsLoadingState()
        }
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
}

/** Profile facts and selector share one measured slot, so paging never jerks the button rows. */
@Composable
private fun CliHomeProfileArea(
    viewModel: HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: com.foxhole.guard.ui.TorIdentityProbeState,
    connected: Boolean,
    selectorOpen: Boolean,
    onSelectorOpenChanged: (Boolean) -> Unit,
    onProfileHold: () -> Unit,
) {
    var profileAreaMinHeightPx by rememberSaveable { mutableIntStateOf(0) }
    val density = LocalDensity.current
    AnimatedContent(
        targetState = selectorOpen,
        transitionSpec = { cliSlide(forward = targetState) },
        label = "profileArea",
    ) { selecting ->
        when {
            selecting ->
                CliProfileQuickSelector(
                    viewModel = viewModel,
                    onDone = { onSelectorOpenChanged(false) },
                    modifier = Modifier.heightIn(
                        min = with(density) { profileAreaMinHeightPx.toDp() },
                    ),
                )
            !home.profilesLoaded -> CliHomeBootLoadingPanel(viewModel = viewModel, home = home)
            else ->
                CliConnectionFactsPanel(
                    viewModel = viewModel,
                    home = home,
                    torIdentityProbe = torIdentityProbe,
                    connected = connected,
                    onProfileTap = { onSelectorOpenChanged(true) },
                    onProfileHold = {
                        onSelectorOpenChanged(false)
                        onProfileHold()
                    },
                    modifier = Modifier.onSizeChanged { profileAreaMinHeightPx = it.height },
                )
        }
    }
}

/** One modal slot: an atomic-off change takes precedence over the older Tor safety prompt. */
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
    val onHold: () -> Unit,
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
        onHold = {
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

/** Latest identity generation among physical, profile and Tor routes for manual refresh proof. */
internal fun com.foxhole.guard.ui.HomeRouteUiState.latestNetworkGeoFetchedAt(): Long =
    maxOf(
        deviceIpInfo?.fetchedAt ?: 0L,
        ipInfo?.fetchedAt ?: 0L,
        torIpInfo?.fetchedAt ?: 0L,
    )

internal fun cliHomeButtonsReady(profilesLoaded: Boolean, settingsHydrated: Boolean): Boolean =
    profilesLoaded && settingsHydrated

internal const val CLI_HOME_BUTTONS_LOADING_TAG = "cli_home_buttons_loading"

/** Keeps both 48dp button rows reserved while cold-start data is still being decrypted. */
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

/**
 * Everything the terminal narrates by itself: the boot stages and one effect per snapshot stream.
 * Extracted whole so the screen body stays a layout, not a mixture of layout and wiring.
 */
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
    // The status block resolves per-lane app icons from the loaded inventory.
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    LaunchedEffect(Unit) { terminal.welcome(com.foxhole.guard.BuildConfig.VERSION_NAME) }
    // Cold-boot narration: "loading environment…" until the profile store decrypts, then the
    // one-time ready line.
    LaunchedEffect(home.profilesLoaded) { terminal.onBootStage(home.profilesLoaded) }
    LaunchedEffect(home.settingsHydrated, pendingFirewallPackages) {
        if (home.settingsHydrated) {
            terminal.onPendingFirewallActions(pendingFirewallPackages, pendingFirewallMessage)
        }
    }
    // One ordered transaction for VPN → Tor → I2P → route identities. Separate effects race on a warm
    // connected composition: an IP could commit before CONNECTED opened its live row, then that
    // late row would stay spinning forever. Replaying deduped snapshots here is cheap and makes the
    // terminal order deterministic: VPN final identity first, followed by Tor and I2P.
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

/**
 * Only a result fetched for this connected generation may become the terminal's VPN identity.
 * The dashboard intentionally retains the previous/device address while a post-connect lookup is
 * running; the generation boundary, rather than the loading flag (which is cleared after publish),
 * prevents that retained value from producing a contradictory `VPN IP` row.
 */
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

/** What a tap and a hold on STATUS do. There is no status modal — both answer into the log. */
@Immutable
private data class CliStatusPrinters(
    val tap: () -> Unit,
    val hold: () -> Unit,
)

/**
 * The two `status` answers.
 *
 * The short one is built in composition and printed straight away. The long one also quotes the
 * journals, which are read asynchronously: the command echoes at once, the read parks its result in
 * [pendingExtras], the next composition builds the rows from it — string resources and all — and the
 * effect prints them and clears the holder.
 */
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
    return CliStatusPrinters(
        tap = {
            terminal.command(CliCommands.STATUS)
            terminal.emitBlock(statusRows)
        },
        hold = {
            terminal.command(CliCommands.STATUS_ALL)
            // The extended block is the one that prints the address, so it is also the one with a
            // reason to re-query it.
            viewModel.refreshIpInfo()
            scope.launch { pendingExtras = viewModel.cliStatusExtras() }
        },
    )
}

/**
 * The connect MODE axis of the primary row. VPN <-> VPN+TOR is live (the existing tor
 * toggle semantics); TOR (tor-only) is selectable only while idle and is UI-state only.
 */
internal enum class CliConnectMode(
    val label: String,
    val preset: RoutingModePreset,
) {
    VPN("VPN", RoutingModePreset.VPN),
    TOR("TOR", RoutingModePreset.TOR),
    VPN_TOR("VPN+TOR", RoutingModePreset.VPN_TOR),
}
