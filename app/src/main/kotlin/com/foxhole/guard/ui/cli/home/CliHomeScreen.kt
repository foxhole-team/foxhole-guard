
package com.foxhole.guard.ui.cli.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.onRoutingModePresetSelected
import com.foxhole.guard.ui.onTorRoutePermittedChanged
import com.foxhole.guard.ui.refreshIpInfo

/**
 * Home: top ~half is the terminal panel (brand logo + scrolling status log), the bottom half
 * is the touch control block - profile row, connection facts and the CONNECT/TOR buttons.
 */
@Composable
internal fun CliHomeScreen(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    modifier: Modifier = Modifier,
) {
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    var selectorOpen by rememberSaveable { mutableStateOf(false) }
    var torConsentOpen by rememberSaveable { mutableStateOf(false) }
    var pendingModeAfterConsent by rememberSaveable { mutableStateOf<CliConnectMode?>(null) }
    var pendingModeCycle by rememberSaveable { mutableStateOf<CliConnectMode?>(null) }

    // The Tor core is permission-gated (privacyRoute.permitted, default OFF). Granting is
    // async - the engage call fires only once the flag lands back in the route state.
    LaunchedEffect(home.settings.privacyRoute.permitted) {
        val pendingMode = pendingModeAfterConsent
        if (pendingMode != null && home.settings.privacyRoute.permitted) {
            pendingModeAfterConsent = null
            val accepted = viewModel.onRoutingModePresetSelected(
                preset = pendingMode.preset,
                scope = home.settings.privacyRoute.scope,
            )
            if (!accepted) pendingModeCycle = null
        }
    }

    // The status block resolves per-lane app names from the loaded inventory.
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    LaunchedEffect(Unit) { terminal.welcome(com.foxhole.guard.BuildConfig.VERSION_NAME) }
    LaunchedEffect(home.connection) { terminal.onConnection(home.connection) }
    LaunchedEffect(home.torPhase) { terminal.onTorPhase(home.torPhase) }
    LaunchedEffect(home.i2pPhase) { terminal.onI2pPhase(home.i2pPhase) }
    LaunchedEffect(home.ipInfo, home.torIpInfo) {
        terminal.onIpInfo(home.torIpInfo ?: home.ipInfo)
    }

    val connection = home.connection
    // The firewall is a background filter, not a route: it takes no part in connected/disconnected,
    // colours no status and drives no buttons (see [isRouteConnection]).
    val connected = connection.isRouteConnection()
    val busy = connection.isRouteTransition()

    // System back from an open selector pages the area back rather than dropping the tab.
    BackHandler(enabled = selectorOpen) { selectorOpen = false }

    // There is no status modal: `status` is an ordinary terminal command whose answer is printed
    // into the log. Both entry points lead here.
    val statusRows = cliStatusRows(home = home, torOnlyLive = isTorOnlyLive(home))
    val printStatus: () -> Unit = {
        terminal.command(CliCommands.STATUS)
        terminal.emitBlock(statusRows)
        viewModel.refreshIpInfo()
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = CliSpacing.md)) {
        CliTerminalPanel(
            terminal = terminal,
            home = home,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))

        // Both yes/no modals are bottom sheets and do not move the home layout.
        home.torTransitionPrompt?.let { prompt ->
            CliTorPromptPanel(viewModel = viewModel, prompt = prompt)
        }
        if (torConsentOpen) {
            CliTorConsentPanel(
                onConfirm = {
                    torConsentOpen = false
                    viewModel.onTorRoutePermittedChanged(true)
                },
                onCancel = {
                    // A dismissed consent is a complete cancellation, not merely a hidden sheet.
                    // Clear both owners so a later permission grant cannot apply the stale route
                    // and the MODE button cannot keep advertising a mode that was never selected.
                    torConsentOpen = false
                    pendingModeAfterConsent = null
                    pendingModeCycle = null
                },
            )
        }

        // Profile selection lives in this same area: tapping the facts panel pages it into the
        // selector and back. Nothing slides in from above or below — buttons and terminal stay put.
        // The area holds at least the facts panel's height for both pages: with 0-1 profiles the
        // selector is shorter, and without this the swap shrank the area and jerked the buttons.
        var profileAreaMinHeightPx by rememberSaveable { mutableIntStateOf(0) }
        val density = LocalDensity.current
        AnimatedContent(
            targetState = selectorOpen,
            transitionSpec = { cliSlide(forward = targetState) },
            label = "profileArea",
        ) { selecting ->
            if (selecting) {
                CliProfileQuickSelector(
                    viewModel = viewModel,
                    onDone = { selectorOpen = false },
                    modifier = Modifier.heightIn(
                        min = with(density) { profileAreaMinHeightPx.toDp() },
                    ),
                )
            } else {
                CliConnectionFactsPanel(
                    viewModel = viewModel,
                    home = home,
                    connected = connected,
                    onProfileTap = { selectorOpen = true },
                    modifier = Modifier.onSizeChanged { profileAreaMinHeightPx = it.height },
                )
            }
        }

        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliSecondaryButtonsRow(
            viewModel = viewModel,
            terminal = terminal,
            home = home,
            connected = connected,
            // STATUS prints the block and re-queries ip and geo; both halves live in printStatus.
            onStatus = printStatus,
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
        CliPrimaryButtonsRow(
            viewModel = viewModel,
            terminal = terminal,
            home = home,
            connected = connected,
            busy = busy,
            pendingModeCycle = pendingModeCycle,
            onPendingModeCycleChanged = { pendingModeCycle = it },
            onTorConsentNeeded = { mode ->
                pendingModeAfterConsent = mode
                torConsentOpen = true
            },
        )
        Spacer(modifier = Modifier.height(CliSpacing.sm))
    }
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
