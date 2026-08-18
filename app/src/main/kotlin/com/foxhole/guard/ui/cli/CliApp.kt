package com.foxhole.guard.ui.cli

import android.content.res.Resources
import android.os.SystemClock
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import com.foxhole.guard.ui.cli.components.CliHintBar
import com.foxhole.guard.ui.cli.components.LocalCliGlassBlurEnabled
import com.foxhole.guard.ui.cli.home.CliHomeScreen
import com.foxhole.guard.ui.cli.home.CliLineTone
import com.foxhole.guard.ui.cli.home.CliTerminalState
import com.foxhole.guard.ui.cli.home.CliTerminalStrings
import com.foxhole.guard.ui.cli.map.CliMapScreen
import com.foxhole.guard.ui.cli.onboarding.CliBetaNoticeSheet
import com.foxhole.guard.ui.cli.onboarding.CliOnboardingWizard
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartSheet
import com.foxhole.guard.ui.cli.onboarding.CliUpdateNoticeSheet
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

@Composable
fun CliApp(viewModel: HomeViewModel) {
    val colors = LocalCliColors.current
    val lockState by viewModel.lockState.collectAsStateWithLifecycle()

    var screen by rememberSaveable(
        stateSaver = Saver(
            save = { value -> value.name },
            restore = { name -> CliScreen.entries.firstOrNull { e -> e.name == name } ?: CliScreen.HOME },
        ),
    ) { mutableStateOf(CliScreen.HOME) }
    val appContext = LocalContext.current.applicationContext
    val terminalStrings = remember(appContext) { cliTerminalStrings(appContext.resources) }
    val terminal = rememberCliTerminalState(strings = terminalStrings)
    val terminalListState = rememberLazyListState()
    var terminalFollowsOutput by rememberSaveable { mutableStateOf(true) }
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

    val onboardingRequired by viewModel.onboardingRequired.collectAsStateWithLifecycle()
    if (onboardingRequired) {
        CliOnboardingWizard(viewModel = viewModel)
        return
    }

    CliQuickStartSheet(viewModel = viewModel)

    val betaNoticeRequired by viewModel.betaNoticeRequired.collectAsStateWithLifecycle()
    if (betaNoticeRequired) {
        CliBetaNoticeSheet(onAcknowledge = viewModel::onBetaNoticeAcknowledged)
    } else {
        CliUpdateNoticeSheet(viewModel = viewModel)
    }
    CliConnectionHapticEffect(viewModel = viewModel)

    CliRouteVisibilityEffect(viewModel = viewModel, screen = screen)

    val dockScreens = rememberDockScreens(
        viewModel = viewModel,
        screen = screen,
        onResetToHome = { screen = CliScreen.HOME },
    )

    var backPeek by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = screen != CliScreen.HOME) { events ->
        try {
            events.collect { event -> backPeek = event.progress }
            screen = CliScreen.HOME
        } catch (_: kotlin.coroutines.cancellation.CancellationException) {
        } finally {
            backPeek = 0f
        }
    }

    var bottomChromeHeightPx by remember { mutableIntStateOf(0) }
    val bottomChromeClearance = with(LocalDensity.current) { bottomChromeHeightPx.toDp() }
    CompositionLocalProvider(
        // Blur stays dormant until the backdrop renderer is stable across supported devices.
        LocalCliGlassBlurEnabled provides false,
        LocalCliBottomChromeClearance provides bottomChromeClearance,
    ) {
        Box(modifier = Modifier.fillMaxSize().testTag(CLI_APP_ROOT_TAG)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.bg)
                    .clipToBounds(),
            ) {
                CliTabPager(
                    screen = screen,
                    backPeek = backPeek,
                    viewModel = viewModel,
                    terminal = terminal,
                    terminalListState = terminalListState,
                    terminalFollowsOutput = terminalFollowsOutput,
                    onTerminalFollowsOutputChanged = { terminalFollowsOutput = it },
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { size -> bottomChromeHeightPx = size.height }
                    .navigationBarsPadding(),
            ) {
                pendingActionBanner?.let { banner ->
                    CliActionBannerRow(
                        viewModel = viewModel,
                        banner = banner,
                        onClear = { pendingActionBanner = null },
                    )
                }
                CliHintBar(current = screen, onSelect = { screen = it }, screens = dockScreens)
            }
            CliWebAppOverlay(viewModel = viewModel)
        }
    }
}

val LocalCliBottomChromeClearance = staticCompositionLocalOf { 0.dp }

@Composable
private fun rememberDockScreens(
    viewModel: HomeViewModel,
    screen: CliScreen,
    onResetToHome: () -> Unit,
): List<CliScreen> {
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
    LaunchedEffect(webAppsVisible, screen) {
        if (!webAppsVisible && screen == CliScreen.WEBAPPS) {
            onResetToHome()
        }
    }
    return dockScreens
}

@Composable
private fun CliWebAppOverlay(viewModel: HomeViewModel) {
    val openWebApp by viewModel.openWebAppState.collectAsStateWithLifecycle()
    val webAppProxyCredentials by viewModel.webAppProxyCredentials.collectAsStateWithLifecycle()
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

@Composable
private fun CliBannerBridge(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    pendingActionBanner: FoxholeBannerEvent?,
    onPendingActionBannerChange: (FoxholeBannerEvent?) -> Unit,
) {
    LaunchedEffect(pendingActionBanner) {
        val banner = pendingActionBanner ?: return@LaunchedEffect
        val expiresAt = banner.expiresAtElapsedMs ?: return@LaunchedEffect
        delay(expiresAt - SystemClock.elapsedRealtime())
        viewModel.dismissBannerAction(banner.action)
        onPendingActionBannerChange(null)
    }

    LaunchedEffect(Unit) {
        viewModel.snackbars.stream.collect { banner ->
            terminal.note(text = banner.message, tone = bannerTone(banner.tone))
            if (banner.action != null) {
                onPendingActionBannerChange(banner)
            }
        }
    }
}

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
private fun CliTabPager(
    screen: CliScreen,
    backPeek: Float,
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    terminalListState: LazyListState,
    terminalFollowsOutput: Boolean,
    onTerminalFollowsOutputChanged: (Boolean) -> Unit,
) {
    AnimatedContent(
        targetState = screen,
        transitionSpec = { cliSlide(forward = targetState.ordinal > initialState.ordinal) },
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationX = backPeek * size.width * BACK_PEEK_SHIFT
            },
        label = "cliTab",
    ) { target ->
        CliTabContent(
            target = target,
            viewModel = viewModel,
            terminal = terminal,
            terminalListState = terminalListState,
            terminalFollowsOutput = terminalFollowsOutput,
            onTerminalFollowsOutputChanged = onTerminalFollowsOutputChanged,
        )
    }
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
                modifier = Modifier.statusBarsPadding().padding(top = CliTopContentGap),
            )
            CliScreen.PROFILES -> CliProfilesScreen(
                viewModel = viewModel,
                terminal = terminal,
                modifier = Modifier.statusBarsPadding().padding(top = CliTopContentGap),
            )
            CliScreen.APPS -> CliRoutingScreen(viewModel = viewModel, onBack = null)
            CliScreen.MAP -> CliMapScreen(viewModel = viewModel)
            CliScreen.WEBAPPS -> CliWebAppsScreen(viewModel = viewModel)
            CliScreen.STATS -> CliStatsScreen(viewModel = viewModel)
            CliScreen.SETTINGS -> CliSettingsScreen(viewModel = viewModel)
        }
    }
}

internal const val CLI_APP_ROOT_TAG = "cli_app_root"

internal val CliTopContentGap = 6.dp

internal fun cliScreenTag(screen: CliScreen): String = "cli_screen_${screen.name.lowercase()}"

@Composable
private fun CliActionBannerRow(
    viewModel: HomeViewModel,
    banner: FoxholeBannerEvent,
    onClear: () -> Unit,
) {
    val colors = LocalCliColors.current
    Row(
        modifier = Modifier
            .padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs)
            .background(colors.panel, RoundedCornerShape(CliSpacing.md))
            .border(1.dp, colors.border, RoundedCornerShape(CliSpacing.md))
            .padding(horizontal = CliSpacing.sm, vertical = CliSpacing.xs),
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
                onClear()
            },
        )
        Spacer(modifier = Modifier.width(CliSpacing.sm))
        CliChip(
            label = stringResource(R.string.cli_banner_dismiss),
            contentDescription = stringResource(R.string.cli_banner_dismiss_action),
            onClick = {
                viewModel.dismissBannerAction(banner.action)
                onClear()
            },
        )
    }
}

private fun HomeViewModel.dismissBannerAction(action: FoxholeBannerAction?) {
    when (action) {
        FoxholeBannerAction.ACCEPT_PROTOCOL_RECOMMENDATION -> onProtocolRecommendationDismissed()
        FoxholeBannerAction.ACCEPT_NETWORK_RULE_PROFILE -> onNetworkRuleProfileSwitchDismissed()
        null -> Unit
    }
}

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
    torStarting = resources.getString(R.string.cli_home_term_tor_starting),
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
    stepTunnel = resources.getString(R.string.cli_home_term_step_tunnel),
    torConnectingTitle = resources.getString(R.string.cli_home_term_tor_connecting_title),
    torEstablished = resources.getString(R.string.cli_home_term_tor_established),
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

private const val BACK_PEEK_SHIFT = 0.15f

private fun bannerTone(tone: FoxholeBannerTone): CliLineTone = when (tone) {
    FoxholeBannerTone.ERROR -> CliLineTone.ERR
    FoxholeBannerTone.WARNING -> CliLineTone.WARN
    FoxholeBannerTone.SUCCESS -> CliLineTone.INFO
    FoxholeBannerTone.INFO -> CliLineTone.INFO
}
