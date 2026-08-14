package com.foxhole.guard.ui.cli

import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.guard.R
import com.foxhole.guard.core.security.LockState
import com.foxhole.guard.ui.FoxholeBannerAction
import com.foxhole.guard.ui.FoxholeBannerEvent
import com.foxhole.guard.ui.FoxholeBannerTone
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.biometricDecryptCipher
import com.foxhole.guard.ui.biometricUnlockAvailable
import com.foxhole.guard.ui.builtInPinPadEnabled
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliChip
import com.foxhole.guard.ui.cli.components.CliDivider
import com.foxhole.guard.ui.cli.components.CliHintBar
import com.foxhole.guard.ui.cli.home.CliHomeScreen
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalState
import com.foxhole.guard.ui.cli.home.CliTerminalStrings
import com.foxhole.guard.ui.cli.map.CliMapScreen
import com.foxhole.guard.ui.cli.onboarding.CliBetaNoticeSheet
import com.foxhole.guard.ui.cli.onboarding.CliOnboardingWizard
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartSheet
import com.foxhole.guard.ui.cli.profiles.CliProfilesScreen
import com.foxhole.guard.ui.cli.security.CliCredentialActions
import com.foxhole.guard.ui.cli.security.CliSystemUnlockActions
import com.foxhole.guard.ui.cli.security.CliUnlockOptions
import com.foxhole.guard.ui.cli.security.CliUnlockScreen
import com.foxhole.guard.ui.cli.settings.CliRoutingScreen
import com.foxhole.guard.ui.cli.settings.CliSettingsScreen
import com.foxhole.guard.ui.cli.stats.CliStatsScreen
import com.foxhole.guard.ui.cli.webapps.CliWebAppFrame
import com.foxhole.guard.ui.cli.webapps.CliWebAppsScreen
import com.foxhole.guard.ui.closeWebApp
import com.foxhole.guard.ui.factoryResetLocalData
import com.foxhole.guard.ui.initialUnlockBackoffSeconds
import com.foxhole.guard.ui.legacyPasswordCredential
import com.foxhole.guard.ui.notifyWebAppExternalBlocked
import com.foxhole.guard.ui.onDashboardUiVisibilityChanged
import com.foxhole.guard.ui.onNetworkRuleProfileSwitchAccepted
import com.foxhole.guard.ui.onNetworkRuleProfileSwitchDismissed
import com.foxhole.guard.ui.onProfilesUiVisibilityChanged
import com.foxhole.guard.ui.onProtocolRecommendationAccepted
import com.foxhole.guard.ui.onProtocolRecommendationDismissed
import com.foxhole.guard.ui.onStatisticsUiVisibilityChanged
import com.foxhole.guard.ui.onTrafficUiVisibilityChanged
import com.foxhole.guard.ui.onWebAppFrameReleased
import com.foxhole.guard.ui.recordPromptAuthFailure
import com.foxhole.guard.ui.scrambleKeypadDigits
import com.foxhole.guard.ui.systemBiometricAllowed
import com.foxhole.guard.ui.unlockWithBiometricCipher
import com.foxhole.guard.ui.unlockWithPassword
import kotlinx.coroutines.delay

/**
 * Root of the CLI experiment. Same contract as FoxholeApp: while the lock state is not
 * UNLOCKED only the unlock gate is composed. Navigation is a plain state switch over the
 * six flat screens; the bottom icon dock is the only chrome.
 *
 * Screen selection, terminal history and the banner bridge live ABOVE the lock gate so a
 * lock/unlock cycle neither wipes them nor drops events emitted while locked.
 */
@Composable
fun CliApp(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()

    // LOGS left the enum: restore by name with a HOME fallback, so state saved by an older
    // build (e.g. "LOGS" surviving process death) degrades gracefully instead of crashing.
    var screen by rememberSaveable(
        stateSaver = Saver(
            save = { value -> value.name },
            restore = { name -> CliScreen.entries.firstOrNull { e -> e.name == name } ?: CliScreen.HOME },
        ),
    ) { mutableStateOf(CliScreen.HOME) }
    val appContext = LocalContext.current.applicationContext
    // The history is journalled to disk, so it outlives this composition: see
    // [rememberCliTerminalState]. The activity is portrait-locked as well, but that alone only
    // closed rotation — the journal closes every other re-creation path too.
    val terminalStrings = remember(appContext) { cliTerminalStrings(appContext.resources) }
    val terminal = rememberCliTerminalState(strings = terminalStrings)
    // The Home page leaves the composition when another dock screen is open. Keep the terminal's
    // viewport above that page switch so returning never recreates it at row zero. `followOutput`
    // is separate from the LazyListState: new lines may arrive while Home is not composed, and a
    // viewport that was at the bottom must catch up on return while a user reading history stays
    // exactly where they left it.
    val terminalListState = rememberLazyListState()
    var terminalFollowsOutput by rememberSaveable { mutableStateOf(true) }
    // Saveable: a rotation used to drop the accept row while the ViewModel kept the offer armed,
    // leaving no way to accept or clear it for the rest of the session.
    var pendingActionBanner by rememberSaveable(stateSaver = actionBannerSaver) {
        mutableStateOf<FoxholeBannerEvent?>(null)
    }

    CliBannerBridge(
        viewModel = viewModel,
        terminal = terminal,
        pendingActionBanner = pendingActionBanner,
        onPendingActionBannerChange = { pendingActionBanner = it },
    )

    if (lockState != LockState.UNLOCKED) {
        CliUnlockGate(viewModel = viewModel, lockState = lockState)
        return
    }

    // First run, after the lock so a wizard can never be used to reach a locked app's state.
    val onboardingRequired by viewModel.onboardingRequired.collectAsStateWithLifecycle()
    if (onboardingRequired) {
        CliOnboardingWizard(viewModel = viewModel)
        return
    }

    // Quick start is the first overlay after the wizard. Its own stored gate makes it one-shot.
    CliQuickStartSheet(viewModel = viewModel)

    // The beta notice becomes eligible only after quick start has recorded its dismissal.
    val betaNoticeRequired by viewModel.betaNoticeRequired.collectAsStateWithLifecycle()
    if (betaNoticeRequired) {
        CliBetaNoticeSheet(onAcknowledge = viewModel::onBetaNoticeAcknowledged)
    }
    CliConnectionHapticEffect(viewModel = viewModel)

    CliRouteVisibilityEffect(viewModel = viewModel, screen = screen)

    // The webapps tab is conditional. If it disappears underfoot, or is restored from saved
    // state already disabled, fall back to HOME rather than an empty screen.
    val settingsRouteState by viewModel.settingsRouteState.collectAsStateWithLifecycle()
    val webAppsVisible = settingsRouteState.settings.webApps.enabled &&
        settingsRouteState.settings.webApps.dockScreenEnabled
    val dockScreens = remember(webAppsVisible) {
        if (webAppsVisible) {
            CliScreen.entries.toList()
        } else {
            CliScreen.entries.filter { it != CliScreen.WEBAPPS }
        }
    }
    LaunchedEffect(webAppsVisible) {
        if (!webAppsVisible && screen == CliScreen.WEBAPPS) {
            screen = CliScreen.HOME
        }
    }

    // Back gesture: sub-screens win with their own BackHandlers (they sit deeper in the
    // composition), any tab returns to HOME, and on HOME the handler is off so the system
    // predictive-back shows leaving the app.
    var backPeek by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = screen != CliScreen.HOME) { events ->
        try {
            events.collect { event -> backPeek = event.progress }
            screen = CliScreen.HOME
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
            // Gesture cancelled: the content returns.
        } finally {
            backPeek = 0f
        }
    }

    // An open web app is the top Box layer, full-screen over the dock. Its state lives in the
    // view model, so the frame survives rotation and can be opened by intent.
    val openWebApp by viewModel.openWebAppState.collectAsStateWithLifecycle()
    val webAppProxyCredentials by viewModel.webAppProxyCredentials.collectAsStateWithLifecycle()
    Box(modifier = Modifier.fillMaxSize().testTag(CLI_APP_ROOT_TAG)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.bg)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            AnimatedContent(
                targetState = screen,
                transitionSpec = { cliSlide(forward = targetState.ordinal > initialState.ordinal) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 6.dp)
                    .weight(1f)
                    .graphicsLayer {
                        translationX = cliSnapFraction(backPeek) * size.width * BACK_PEEK_SHIFT
                    },
                label = "cliTab",
            ) { target ->
                CliTabContent(
                    target = target,
                    viewModel = viewModel,
                    terminal = terminal,
                    terminalListState = terminalListState,
                    terminalFollowsOutput = terminalFollowsOutput,
                    onTerminalFollowsOutputChanged = { terminalFollowsOutput = it },
                )
            }
            pendingActionBanner?.let { banner ->
                CliActionBannerRow(
                    viewModel = viewModel,
                    banner = banner,
                    onClear = { pendingActionBanner = null },
                )
            }
            CliDivider()
            CliHintBar(current = screen, onSelect = { screen = it }, screens = dockScreens)
        }
        openWebApp?.let { app ->
            CliWebAppFrame(
                app = app,
                proxyCredentials = webAppProxyCredentials,
                onClose = viewModel::closeWebApp,
                onReleased = viewModel::onWebAppFrameReleased,
                onExternalBlocked = viewModel::notifyWebAppExternalBlocked,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * The heavy route states are visibility-gated; on disposal (lock, teardown) every hook
 * must flip false or high-frequency traffic updates keep running behind the lock screen.
 * APPS has no hook on purpose: the routing screen only reads always-warm settings state
 * and writes through plain VM callbacks - nothing high-frequency to gate.
 *
 * Extracted from [CliApp] only to keep that function within its size budget; it is composed at
 * exactly the same point, so the hooks still flip with the same screen state.
 */
@Composable
private fun CliRouteVisibilityEffect(
    viewModel: HomeViewModel,
    screen: CliScreen,
) {
    DisposableEffect(screen) {
        viewModel.onDashboardUiVisibilityChanged(screen == CliScreen.HOME)
        viewModel.onProfilesUiVisibilityChanged(screen == CliScreen.PROFILES)
        viewModel.onTrafficUiVisibilityChanged(screen == CliScreen.HOME || screen == CliScreen.MAP)
        viewModel.onStatisticsUiVisibilityChanged(screen == CliScreen.STATS)
        onDispose {
            viewModel.onDashboardUiVisibilityChanged(false)
            viewModel.onProfilesUiVisibilityChanged(false)
            viewModel.onTrafficUiVisibilityChanged(false)
            viewModel.onStatisticsUiVisibilityChanged(false)
        }
    }
}

/**
 * Bridges ViewModel banner events into the terminal log and the accept row.
 *
 * Extracted from [CliApp] only to keep that function within its size budget; it holds no state of its
 * own and must stay above the lock gate so events emitted while locked still land in the log.
 */
@Composable
private fun CliBannerBridge(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    pendingActionBanner: FoxholeBannerEvent?,
    onPendingActionBannerChange: (FoxholeBannerEvent?) -> Unit,
) {
    // Producers stamp an elapsed-realtime deadline on the offer; honour it so a row tapped an hour
    // later can't apply a stale recommendation. Clearing the row also disarms the ViewModel state.
    LaunchedEffect(pendingActionBanner) {
        val banner = pendingActionBanner ?: return@LaunchedEffect
        val expiresAt = banner.expiresAtElapsedMs ?: return@LaunchedEffect
        delay(expiresAt - SystemClock.elapsedRealtime())
        viewModel.dismissBannerAction(banner.action)
        onPendingActionBannerChange(null)
    }

    // User feedback events land in the terminal log instead of a Material snackbar; actionable
    // banners additionally arm the accept row below the content. Deliberately without
    // repeatOnLifecycle: the terminal log is persistent and events that arrive in the background
    // must reach it — a lifecycle gate would silently drop them.
    //
    // This is the ONLY collector of the buffered event stream, and it has to stay the only one:
    // the bus is single-consumer, so a second collector would split the journal (FoxholeBannerEvents).
    LaunchedEffect(Unit) {
        viewModel.snackbars.stream.collect { banner ->
            terminal.note(text = banner.message, tone = bannerTone(banner.tone))
            if (banner.action != null) {
                onPendingActionBannerChange(banner)
            }
        }
    }
}

// Lock gate: composed instead of the main content until the vault is unlocked.
@Composable
private fun CliUnlockGate(
    viewModel: HomeViewModel,
    lockState: LockState,
) {
    CliUnlockScreen(
        options = CliUnlockOptions(
            lockState = lockState,
            legacyPassword = viewModel.legacyPasswordCredential(),
            biometricAvailable = viewModel.biometricUnlockAvailable(),
            builtInPinPad = viewModel.builtInPinPadEnabled(),
            scrambleDigits = viewModel.scrambleKeypadDigits(),
            initialBackoffSeconds = viewModel.initialUnlockBackoffSeconds(),
        ),
        credentials = CliCredentialActions(
            unlock = viewModel::unlockWithPassword,
            biometricCipher = viewModel::biometricDecryptCipher,
            unlockBiometric = viewModel::unlockWithBiometricCipher,
            resetLocalData = viewModel::factoryResetLocalData,
        ),
        system = CliSystemUnlockActions(
            biometricAllowed = viewModel::systemBiometricAllowed,
            unlock = viewModel::unlockWithSystemAuth,
            recordFailure = viewModel::recordPromptAuthFailure,
        ),
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun CliTabContent(
    target: CliScreen,
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    terminalListState: LazyListState,
    terminalFollowsOutput: Boolean,
    onTerminalFollowsOutputChanged: (Boolean) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().testTag(cliScreenTag(target))) {
        when (target) {
            CliScreen.HOME -> CliHomeScreen(
                viewModel = viewModel,
                terminal = terminal,
                terminalListState = terminalListState,
                terminalFollowsOutput = terminalFollowsOutput,
                onTerminalFollowsOutputChanged = onTerminalFollowsOutputChanged,
            )
            CliScreen.PROFILES -> CliProfilesScreen(viewModel = viewModel, terminal = terminal)
            CliScreen.APPS -> CliRoutingScreen(viewModel = viewModel, onBack = null)
            CliScreen.MAP -> CliMapScreen(viewModel = viewModel)
            CliScreen.WEBAPPS -> CliWebAppsScreen(viewModel = viewModel)
            CliScreen.STATS -> CliStatsScreen(viewModel = viewModel)
            CliScreen.SETTINGS -> CliSettingsScreen(viewModel = viewModel)
        }
    }
}

internal const val CLI_APP_ROOT_TAG = "cli_app_root"

internal fun cliScreenTag(screen: CliScreen): String = "cli_screen_${screen.name.lowercase()}"

@Composable
private fun CliActionBannerRow(
    viewModel: HomeViewModel,
    banner: FoxholeBannerEvent,
    onClear: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier.padding(horizontal = CliSpacing.md, vertical = CliSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = banner.message,
            style = CliType.small,
            color = colors.warn,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        CliButton(
            // Producers usually stamp their own label; the fallback is for an offer that carried
            // none, and it is a translated resource like every other label on screen.
            label = banner.actionLabel ?: stringResource(R.string.cli_common_btn_ok),
            color = colors.warn,
            onClick = {
                when (banner.action) {
                    FoxholeBannerAction.ACCEPT_PROTOCOL_RECOMMENDATION ->
                        viewModel.onProtocolRecommendationAccepted()
                    FoxholeBannerAction.ACCEPT_NETWORK_RULE_PROFILE ->
                        viewModel.onNetworkRuleProfileSwitchAccepted()
                    null -> Unit
                }
                // Accept already disarms the ViewModel offer; only clear the row here.
                onClear()
            },
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        CliChip(
            label = stringResource(R.string.cli_banner_dismiss),
            // The label is a glyph; without this TalkBack announces the letter "x".
            contentDescription = stringResource(R.string.cli_banner_dismiss_action),
            onClick = {
                viewModel.dismissBannerAction(banner.action)
                onClear()
            },
        )
    }
}

/** Disarms the ViewModel offer behind a banner the user dismissed or that timed out. */
private fun HomeViewModel.dismissBannerAction(action: FoxholeBannerAction?) {
    when (action) {
        FoxholeBannerAction.ACCEPT_PROTOCOL_RECOMMENDATION -> onProtocolRecommendationDismissed()
        FoxholeBannerAction.ACCEPT_NETWORK_RULE_PROFILE -> onNetworkRuleProfileSwitchDismissed()
        null -> Unit
    }
}

// rememberSaveable cannot bundle a data class with enum members on its own. Only the fields the
// row and its expiry read are restored; the offer itself lives in the ViewModel.
private val actionBannerSaver: Saver<FoxholeBannerEvent?, Any> = Saver(
    save = { banner ->
        banner?.let {
            listOf(
                it.message,
                it.tone.name,
                it.actionLabel,
                it.action?.name,
                it.durationMillis,
                it.expiresAtElapsedMs,
            )
        }
    },
    restore = { saved ->
        val fields = saved as? List<*> ?: return@Saver null
        val message = fields.getOrNull(0) as? String ?: return@Saver null
        val tone = FoxholeBannerTone.entries.firstOrNull { it.name == fields.getOrNull(1) }
        FoxholeBannerEvent(
            message = message,
            tone = tone ?: FoxholeBannerTone.INFO,
            actionLabel = fields.getOrNull(2) as? String,
            action = FoxholeBannerAction.entries.firstOrNull { it.name == fields.getOrNull(3) },
            durationMillis = fields.getOrNull(4) as? Long,
            expiresAtElapsedMs = fields.getOrNull(5) as? Long,
        )
    },
)

// Not composable: this would rebuild on every CliApp recomposition for a single consumer, the
// terminal's initial remember. Resources suffice — a locale change recreates the activity, so
// everything printed from here on speaks the new language.
//
// Lines already in the journal keep the language they were printed in: the file stores rendered
// text, not templates, and re-rendering yesterday's events in a language the user has only just
// chosen would rewrite history rather than translate it. A log is a record of what was said.
private fun cliTerminalStrings(resources: Resources) = CliTerminalStrings(
    bootLoading = resources.getString(R.string.cli_home_term_boot_loading),
    bootReady = resources.getString(R.string.cli_home_term_boot_ready),
    connecting = resources.getString(R.string.cli_home_term_connecting),
    connected = resources.getString(R.string.cli_home_term_connected),
    tunnelUp = resources.getString(R.string.cli_home_term_tunnel_up),
    tunnelUpNamed = resources.getString(R.string.cli_home_term_tunnel_up_named),
    vpnEstablished = resources.getString(R.string.cli_home_term_vpn_established),
    vpnExitFailed = resources.getString(R.string.cli_home_term_vpn_ip_failed),
    i2pEstablished = resources.getString(R.string.cli_home_term_i2p_established),
    reconnecting = resources.getString(R.string.cli_home_term_reconnecting),
    error = resources.getString(R.string.cli_home_term_error),
    unknown = resources.getString(R.string.cli_home_term_unknown_error),
    closed = resources.getString(R.string.cli_home_term_closed),
    torBesideVpn = resources.getString(R.string.cli_home_term_tor_beside_vpn),
    torConnecting = resources.getString(R.string.cli_home_term_tor_connecting),
    torCircuits = resources.getString(R.string.cli_home_term_tor_circuits),
    torConnected = resources.getString(R.string.cli_home_term_tor_connected),
    torExitLookup = resources.getString(R.string.cli_home_term_tor_ip_lookup),
    torExitFailed = resources.getString(R.string.cli_home_term_tor_ip_failed),
    torStopped = resources.getString(R.string.cli_home_term_tor_stopped),
    i2pStarting = resources.getString(R.string.cli_home_term_i2p_starting),
    i2pDiscovering = resources.getString(R.string.cli_home_term_i2p_discovering),
    i2pTunnels = resources.getString(R.string.cli_home_term_i2p_tunnels),
    i2pTunnelsCount = resources.getString(R.string.cli_home_term_i2p_tunnels_count),
    i2pConnected = resources.getString(R.string.cli_home_term_i2p_connected),
    i2pStopped = resources.getString(R.string.cli_home_term_i2p_stopped),
    disconnectingVpn = resources.getString(R.string.cli_home_term_disconnecting_vpn),
    disconnectingTor = resources.getString(R.string.cli_home_term_disconnecting_tor),
    disconnectingI2p = resources.getString(R.string.cli_home_term_disconnecting_i2p),
    disconnectingAndroidTunnel = resources.getString(R.string.cli_home_term_disconnecting_android_tunnel),
    exitKeyIp = resources.getString(R.string.cli_home_term_key_ip),
    exitKeyGeo = resources.getString(R.string.cli_home_term_key_geo),
    exitKeyIsp = resources.getString(R.string.cli_home_term_key_isp),
    vpnExitKeyIp = resources.getString(R.string.cli_home_status_vpn_identity),
    torExitKeyIp = resources.getString(R.string.cli_home_status_tor_identity),
    reasonLabels = mapOf(
        AutoConnectReasonCode.HANDSHAKE_TIMEOUT to
            resources.getString(R.string.cli_home_term_reason_handshake_timeout),
        AutoConnectReasonCode.VALIDATION_TIMEOUT to
            resources.getString(R.string.cli_home_term_reason_validation_timeout),
        AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED to
            resources.getString(R.string.cli_home_term_reason_latency_blocked),
        AutoConnectReasonCode.DNS_FAILURE to
            resources.getString(R.string.cli_home_term_reason_dns_failure),
        AutoConnectReasonCode.CONNECT_ERROR to
            resources.getString(R.string.cli_home_term_reason_connect_error),
        AutoConnectReasonCode.RESTORED_LAST_GOOD to
            resources.getString(R.string.cli_home_term_reason_restored_last_good),
    ),
)

// Fraction of screen width the predictive-back gesture shifts content before committing.
private const val BACK_PEEK_SHIFT = 0.15f

private fun bannerTone(tone: FoxholeBannerTone): CliLineTone = when (tone) {
    FoxholeBannerTone.ERROR -> CliLineTone.ERR
    FoxholeBannerTone.WARNING -> CliLineTone.WARN
    // Successful settings/mode/scenario notices are terminal service messages, not a VPN lane.
    // Keep them in the canonical blue neon; semantic VPN/Tor/I2P results retain their own tones.
    FoxholeBannerTone.SUCCESS -> CliLineTone.INFO
    FoxholeBannerTone.INFO -> CliLineTone.INFO
}
