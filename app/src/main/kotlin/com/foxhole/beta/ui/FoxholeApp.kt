package com.foxhole.beta.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foxhole.beta.BuildConfig
import com.foxhole.beta.R
import com.foxhole.beta.core.data.ProfileImportPayloadTooLargeException
import com.foxhole.beta.core.data.readLocalProfileImportUtf8Capped
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ThemeMode
import com.foxhole.beta.ui.theme.FoxholeTheme
import com.foxhole.beta.ui.theme.LocalFoxholeDarkTheme
import com.foxhole.beta.ui.theme.LocalFoxholeThemeMode
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private object AppRoute {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val PROFILE_ID = "profileId"
    const val PROFILE_DETAIL = "profiles/{$PROFILE_ID}"
    const val PROFILE_VIEW_CONFIG = "profiles/{$PROFILE_ID}/view-config"
    const val PROFILE_EDIT_CONFIG = "profiles/{$PROFILE_ID}/edit-config"
    const val SETTINGS = "settings"
    const val TRAFFIC = "settings/traffic"
    const val DNS = "settings/dns"
    const val DNS_APPS_PICKER = "settings/dns/apps-picker"
    const val NETWORK_RULES = "settings/network-rules"
    const val SECURITY = "settings/security"
    const val SECURITY_APP_MONITOR = "settings/security/app-monitor"
    const val PRIVACY_ROUTE = "settings/privacy-route"
    const val ROUTING_APPS = "settings/routing/apps"
    const val ROUTING_APPS_PICKER = "settings/routing/apps/picker"
    const val ROUTING_BLOCKED_APPS_PICKER = "settings/routing/apps/blocked-picker"
    const val PRIVACY_ROUTE_APPS_PICKER = "settings/privacy-route/apps-picker"
    const val ROUTING_SITES = "settings/routing/sites"
    const val SMART_START = "settings/smart-start"
    const val APPLICATION = "settings/application"
    const val ABOUT = "settings/about"
    const val EXPERT = "settings/expert"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val STATISTICS = "settings/statistics"

    fun profileDetail(profileId: Long): String = "profiles/$profileId"

    fun profileViewConfig(profileId: Long): String = "profiles/$profileId/view-config"

    fun profileEditConfig(profileId: Long): String = "profiles/$profileId/edit-config"
}

private enum class AppSection(
    val rootRoute: String,
    val icon: ImageVector,
    val titleRes: Int,
    val testTag: String,
) {
    DASHBOARD(
        rootRoute = AppRoute.HOME,
        icon = FoxholeIcons.Dashboard,
        titleRes = R.string.dashboard,
        testTag = "bottom_nav_dashboard",
    ),
    SETTINGS(
        rootRoute = AppRoute.SETTINGS,
        icon = FoxholeIcons.Settings,
        titleRes = R.string.settings,
        testTag = "bottom_nav_settings",
    ),
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun FoxholeApp(
    viewModel: HomeViewModel,
    snackbarHostState: SnackbarHostState,
    navController: NavHostController = rememberNavController(),
    bottomDockOverlayHost: ViewGroup? = null,
    bottomDockBlurTarget: BlurTarget? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var rootSection by rememberSaveable { mutableStateOf(AppSection.DASHBOARD) }
    val currentSection =
        when (currentRoute) {
            AppRoute.HOME -> rootSection
            AppRoute.SETTINGS -> AppSection.SETTINGS
            else -> navBackStackEntry?.destination?.rootAppSection()
        }
    val showBottomBar = currentRoute.isRootRoute()
    val rootSwipeSection =
        when (currentRoute) {
            AppRoute.HOME -> rootSection
            AppRoute.SETTINGS -> AppSection.SETTINGS
            else -> navBackStackEntry?.destination?.rootSwipeSection()
        }
    val settingsBackSwipeEnabled = navBackStackEntry?.destination?.settingsBackSwipeEnabled() == true
    val settingsDetailNavigationGate = remember { SettingsDetailNavigationGate() }
    val navigationTransitionTelemetry = remember { NavigationTransitionTelemetry() }
    NavigationTransitionTelemetryEffect(currentRoute, navigationTransitionTelemetry)
    val selectRootSection: (AppSection) -> Unit = { section ->
        if (currentRoute == AppRoute.HOME) {
            if (rootSection != section) {
                navigationTransitionTelemetry.recordTap(
                    routeFrom = rootSection.rootRoute,
                    routeTo = section.rootRoute,
                )
                navigationTransitionTelemetry.recordNavigateCall(section.rootRoute)
                rootSection = section
            }
        } else if (currentRoute == AppRoute.SETTINGS) {
            if (section != AppSection.SETTINGS) {
                navigationTransitionTelemetry.recordTap(
                    routeFrom = AppRoute.SETTINGS,
                    routeTo = section.rootRoute,
                )
                navigationTransitionTelemetry.recordNavigateCall(section.rootRoute)
                rootSection = section
                navController.navigate(AppRoute.HOME) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                }
            }
        } else {
            navController.navigateToSection(
                section = section,
                telemetry = navigationTransitionTelemetry,
            )
        }
    }
    val navigateToSettingsDetail: (String) -> Unit = { route ->
        navController.navigateToSettingsDetail(
            route = route,
            gate = settingsDetailNavigationGate,
            telemetry = navigationTransitionTelemetry,
        )
    }
    val backdropBlurHost =
        remember(bottomDockOverlayHost, bottomDockBlurTarget) {
            if (bottomDockOverlayHost != null && bottomDockBlurTarget != null) {
                FoxholeBackdropBlurHost(
                    overlayHost = bottomDockOverlayHost,
                    blurTarget = bottomDockBlurTarget,
                )
            } else {
                null
            }
        }
    val topChromeController = remember { FoxholeTopChromeController() }
    var qrScannerVisible by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val insecureTlsImportWarning by viewModel.insecureTlsImportWarning.collectAsStateWithLifecycle()
    val profileImportTooLargeMessage = stringResource(R.string.profile_import_too_large)
    val profileImportFailedMessage = stringResource(R.string.profile_import_failed)
    val importProfileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            stream.readLocalProfileImportUtf8Capped()
                        }.orEmpty()
                    }
                }.onSuccess { raw ->
                    viewModel.importProfileRaw(raw)
                }.onFailure { error ->
                    if (error is ProfileImportPayloadTooLargeException) {
                        snackbarHostState.showBanner(profileImportTooLargeMessage, FoxholeBannerTone.ERROR)
                    } else {
                        snackbarHostState.showBanner(profileImportFailedMessage, FoxholeBannerTone.ERROR)
                    }
                }
            }
        }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .then(
                        if (BuildConfig.DEBUG) {
                            Modifier.semantics { testTagsAsResourceId = true }
                        } else {
                            Modifier
                        },
                    )
                    .padding(top = innerPadding.calculateTopPadding())
                    .consumeWindowInsets(innerPadding)
                    .then(
                        when {
                            rootSwipeSection != null ->
                                Modifier.sectionSwipeNavigation(
                                    currentSection = rootSwipeSection,
                                    onSectionSelected = selectRootSection,
                                )
                            settingsBackSwipeEnabled ->
                                Modifier.settingsBackSwipeNavigation(
                                    onNavigateBack = navController::navigateUp,
                                )
                            else -> Modifier
                        },
                    )
                    .testTag("app_section_swipe_surface"),
        ) {
            CompositionLocalProvider(
                LocalFoxholeBackdropBlurHost provides backdropBlurHost,
                LocalFoxholeTopChromeController provides topChromeController,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = AppRoute.HOME,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (targetState.destination.route.isSettingsDetailRoute()) {
                            detailForwardEnter()
                        } else {
                            rootEnter()
                        }
                    },
                    exitTransition = {
                        if (targetState.destination.route.isSettingsDetailRoute()) {
                            detailForwardExit()
                        } else {
                            rootExit()
                        }
                    },
                    popEnterTransition = {
                        if (initialState.destination.route.isSettingsDetailRoute()) {
                            detailBackEnter()
                        } else {
                            rootEnter()
                        }
                    },
                    popExitTransition = {
                        if (initialState.destination.route.isSettingsDetailRoute()) {
                            detailBackExit()
                        } else {
                            rootExit()
                        }
                    },
                ) {
                composable(AppRoute.HOME) {
                    AnimatedContent(
                        targetState = rootSection,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            if (targetState.ordinal > initialState.ordinal) {
                                detailForwardEnter() togetherWith detailForwardExit()
                            } else {
                                detailBackEnter() togetherWith detailBackExit()
                            }
                        },
                        label = "root-section-transition",
                    ) { section ->
                        when (section) {
                            AppSection.DASHBOARD -> {
                            val state by viewModel.homeRouteState.collectAsStateWithLifecycle()
                            HomeScreen(
                                state = state,
                                trafficStateFlow = viewModel.dashboardTraffic,
                                trafficMapStateFlow = viewModel.trafficMapUiState,
                                snackbarHostState = snackbarHostState,
                                onImportFromClipboard = viewModel::onPasteFromClipboard,
                                onImportFromFile = {
                                    importProfileLauncher.launch(
                                        arrayOf(
                                            "application/json",
                                            "text/plain",
                                            "text/*",
                                            "application/octet-stream",
                                        ),
                                    )
                                },
                                onImportFromQr = { qrScannerVisible = true },
                                onRefreshProfile = viewModel::onRefreshProfile,
                                onRestartProfile = viewModel::onRestartActiveProfile,
                                onRefreshAndRestartProfile = viewModel::onRefreshAndRestartActiveProfile,
                                onToggleConnection = viewModel::onToggleConnection,
                                onAutoConnect = viewModel::onAutoConnectActiveProfile,
                                onTrafficModeSelected = viewModel::onTrafficModeSelected,
                                onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                                onKillSwitchChanged = viewModel::onKillSwitchChanged,
                                onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                                onPrivacyRouteModeSelected = viewModel::onPrivacyRouteModeSelected,
                                onOpenPrivacyRoute = { navigateToSettingsDetail(AppRoute.PRIVACY_ROUTE) },
                                onEnableDirectTorQuickStart = viewModel::onEnableDirectTorQuickStart,
                                onSelectActiveProtocolOptionRequested = viewModel::onSelectActiveProtocolOptionRequested,
                                onUpdateAutoConnectExcludedOptions = { excludedIds ->
                                    state.activeProfile?.id?.let { profileId ->
                                        viewModel.onSmartProfileAutoConnectExcludedOptionsChanged(
                                            profileId = profileId,
                                            excludedOptionIds = excludedIds,
                                        )
                                    }
                                },
                                onRefreshSmartProfileMetrics = viewModel::refreshSmartProfileMetrics,
                                onCancelSmartProfileMetricsRefresh = viewModel::cancelSmartProfileMetricsRefresh,
                                onConfirmDisableTorForUdpProtocol = viewModel::confirmDisableTorForUdpProtocol,
                                onConfirmMoveTorIntoVpn = viewModel::confirmMoveTorIntoVpn,
                                onConfirmKeepTorOnDeviceAndStartVpn = viewModel::confirmKeepTorOnDeviceAndStartVpn,
                                onDismissTorTransitionPrompt = viewModel::dismissTorTransitionPrompt,
                                onOpenProfiles = { navController.navigateToProfilesRoot() },
                                onRefreshIpInfo = viewModel::refreshIpInfo,
                                onResetUsageTracking = viewModel::resetUsageTracking,
                                onTrafficUiVisibilityChanged = viewModel::onTrafficUiVisibilityChanged,
                                onLocalProxyLanAccessChanged = viewModel::onLocalProxyLanAccessChanged,
                                onRenewTorIp = viewModel::onRenewTorIp,
                                onDashboardCardOrderChanged = viewModel::onDashboardCardOrderChanged,
                            )
                        }
                            AppSection.SETTINGS -> {
                            val expertVisible by viewModel.settingsHomeExpertVisible.collectAsStateWithLifecycle()
                            SettingsHomeScreen(
                                expertVisible = expertVisible,
                                snackbarHostState = snackbarHostState,
                                onNavigateUp = null,
                                onOpenTraffic = { navigateToSettingsDetail(AppRoute.TRAFFIC) },
                                onOpenDns = { navigateToSettingsDetail(AppRoute.DNS) },
                                onOpenNetworkRules = { navigateToSettingsDetail(AppRoute.NETWORK_RULES) },
                                onOpenSecurity = { navigateToSettingsDetail(AppRoute.SECURITY) },
                                onOpenPrivacyRoute = { navigateToSettingsDetail(AppRoute.PRIVACY_ROUTE) },
                                onOpenRoutingApps = { navigateToSettingsDetail(AppRoute.ROUTING_APPS) },
                                onOpenRoutingSites = { navigateToSettingsDetail(AppRoute.ROUTING_SITES) },
                                onOpenSmartStart = { navigateToSettingsDetail(AppRoute.SMART_START) },
                                onOpenApplication = { navigateToSettingsDetail(AppRoute.APPLICATION) },
                                onOpenExpert = { navigateToSettingsDetail(AppRoute.EXPERT) },
                                onOpenDiagnostics = { navigateToSettingsDetail(AppRoute.DIAGNOSTICS) },
                                onOpenStatistics = { navigateToSettingsDetail(AppRoute.STATISTICS) },
                                onOpenAbout = { navigateToSettingsDetail(AppRoute.ABOUT) },
                            )
                        }
                        }
                    }
                }
                composable(AppRoute.PROFILES) {
                    val state by viewModel.profilesRouteState.collectAsStateWithLifecycle()
                    ProfilesScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSetActiveProfile = viewModel::onSelectProfile,
                        onEditProfile = { profileId -> navController.navigate(AppRoute.profileEditConfig(profileId)) },
                        onSelectProtocolOption = viewModel::onSelectProfileProtocolOption,
                        onUpdateAutoConnectExcludedOptions = viewModel::onSmartProfileAutoConnectExcludedOptionsChanged,
                        onRefreshSmartProfileMetrics = viewModel::refreshSmartProfileMetrics,
                        onCancelSmartProfileMetricsRefresh = viewModel::cancelSmartProfileMetricsRefresh,
                        onRefreshProfile = viewModel::refreshProfile,
                        onDeleteProfile = viewModel::deleteProfile,
                        onLoadProfileConfig = { id, optionId -> viewModel.getResolvedConfig(id, optionId) },
                        onCreateProfileExport = { requests ->
                            viewModel.createProfileExport(
                                requests.map { request ->
                                    com.foxhole.beta.core.profile.ProfileExportRequest(
                                        profileId = request.profileId,
                                        selectionKeys = request.selectionKeys,
                                    )
                                },
                            )
                        },
                        onCreateProfileExportShareIntent = viewModel::exportProfileShareIntent,
                    )
                }
                composable(
                    route = AppRoute.PROFILE_DETAIL,
                    arguments = listOf(navArgument(AppRoute.PROFILE_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val state by viewModel.profilesRouteState.collectAsStateWithLifecycle()
                    val profileId = backStackEntry.arguments?.getLong(AppRoute.PROFILE_ID) ?: return@composable
                    ProfileDetailScreen(
                        profile = state.profile(profileId),
                        activeProfileId = state.activeProfileId,
                        excludedAutoConnectOptionIds = state.smartProfileExcludedOptionIdsByProfileId[profileId].orEmpty(),
                        rememberedSmartStartLatenciesByOptionId = state.smartStartRememberedLatency(profileId),
                        downOptionIds = state.smartProfileDownOptionIds(profileId),
                        latencyUnavailableOptionIds = state.smartProfileLatencyUnavailable(profileId),
                        serverPingByOptionId = state.smartProfileServerPings(profileId),
                        serverPingUnavailableOptionIds = state.smartProfileServerPingUnavailable(profileId),
                        metricsUpdatedAtByOptionId = state.smartProfileMetricsUpdatedAt(profileId),
                        metricsRefreshing = profileId in state.smartProfileMetricsRefreshingProfileIds,
                        refreshingOptionId = state.smartProfileMetricsRefreshingOptionIdByProfileId[profileId],
                        recommendedProtocolOptionId = state.recommendedProtocolOptionByProfileId[profileId],
                        recommendedProtocolOptionIds = state.recommendedProtocolOptionsByProfileId[profileId].orEmpty(),
                        favoriteProtocolOptionId = state.favoriteProtocolOptionByProfileId[profileId],
                        latencyProbeMethod = state.settings.connection.latencyProbeMethod,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = { navController.navigateToProfilesRoot() },
                        onSetActiveProfile = viewModel::onSelectProfile,
                        onSelectProtocolOption = viewModel::onSelectProfileProtocolOption,
                        onUpdateAutoConnectExcludedOptions = viewModel::onSmartProfileAutoConnectExcludedOptionsChanged,
                        onRefreshSmartProfileMetrics = viewModel::refreshSmartProfileMetrics,
                        onCancelSmartProfileMetricsRefresh = viewModel::cancelSmartProfileMetricsRefresh,
                        onRefreshProfile = viewModel::refreshProfile,
                        onDeleteProfile = {
                            viewModel.deleteProfile(profileId)
                            navController.navigateToProfilesRoot()
                        },
                        onViewConfig = { navController.navigate(AppRoute.profileViewConfig(profileId)) },
                        onEditConfig = { navController.navigate(AppRoute.profileEditConfig(profileId)) },
                    )
                }
                composable(
                    route = AppRoute.PROFILE_VIEW_CONFIG,
                    arguments = listOf(navArgument(AppRoute.PROFILE_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val state by viewModel.profilesRouteState.collectAsStateWithLifecycle()
                    val homeState by viewModel.homeRouteState.collectAsStateWithLifecycle()
                    val profileId = backStackEntry.arguments?.getLong(AppRoute.PROFILE_ID) ?: return@composable
                    ProfileConfigViewScreen(
                        profile = state.profile(profileId),
                        showExpertSettings = homeState.settings.ui.showExpertSettings,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onEditConfig = { navController.navigate(AppRoute.profileEditConfig(profileId)) },
                        onLoadConfig = { id -> viewModel.getResolvedConfig(id) },
                    )
                }
                composable(
                    route = AppRoute.PROFILE_EDIT_CONFIG,
                    arguments = listOf(navArgument(AppRoute.PROFILE_ID) { type = NavType.LongType }),
                ) { backStackEntry ->
                    val state by viewModel.profilesRouteState.collectAsStateWithLifecycle()
                    val homeState by viewModel.homeRouteState.collectAsStateWithLifecycle()
                    val profileId = backStackEntry.arguments?.getLong(AppRoute.PROFILE_ID) ?: return@composable
                    ProfileConfigEditScreen(
                        profile = state.profile(profileId),
                        canReconnectNow =
                            homeState.activeProfile?.id == profileId &&
                                homeState.connection.state in
                                setOf(
                                    ConnectionState.CONNECTING,
                                    ConnectionState.CONNECTED,
                                    ConnectionState.RECONNECTING,
                        ),
                        showExpertSettings = homeState.settings.ui.showExpertSettings,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onLoadConfig = { id, optionId -> viewModel.getResolvedConfig(id, optionId) },
                        onSaveConfig = { id, optionId, config, reconnectAfterSave ->
                            if (viewModel.updateResolvedConfig(id, config, reconnectAfterSave, optionId)) {
                                navController.navigateUp()
                            }
                        },
                    )
                }
                composable(AppRoute.SETTINGS) {
                    val expertVisible by viewModel.settingsHomeExpertVisible.collectAsStateWithLifecycle()
                    SettingsHomeScreen(
                        expertVisible = expertVisible,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = null,
                        onOpenTraffic = { navigateToSettingsDetail(AppRoute.TRAFFIC) },
                        onOpenDns = { navigateToSettingsDetail(AppRoute.DNS) },
                        onOpenNetworkRules = { navigateToSettingsDetail(AppRoute.NETWORK_RULES) },
                        onOpenSecurity = { navigateToSettingsDetail(AppRoute.SECURITY) },
                        onOpenPrivacyRoute = { navigateToSettingsDetail(AppRoute.PRIVACY_ROUTE) },
                        onOpenRoutingApps = { navigateToSettingsDetail(AppRoute.ROUTING_APPS) },
                        onOpenRoutingSites = { navigateToSettingsDetail(AppRoute.ROUTING_SITES) },
                        onOpenSmartStart = { navigateToSettingsDetail(AppRoute.SMART_START) },
                        onOpenApplication = { navigateToSettingsDetail(AppRoute.APPLICATION) },
                        onOpenExpert = { navigateToSettingsDetail(AppRoute.EXPERT) },
                        onOpenDiagnostics = { navigateToSettingsDetail(AppRoute.DIAGNOSTICS) },
                        onOpenStatistics = { navigateToSettingsDetail(AppRoute.STATISTICS) },
                        onOpenAbout = { navigateToSettingsDetail(AppRoute.ABOUT) },
                    )
                }
                composable(AppRoute.SMART_START) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    SmartStartSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSmartStartEnabledChanged = viewModel::onSmartStartEnabledChanged,
                        onSmartStartProtocolSelectionTimeoutChanged = viewModel::onSmartStartProtocolSelectionTimeoutChanged,
                        onSmartStartRefreshSelectionTimeoutChanged = viewModel::onSmartStartRefreshSelectionTimeoutChanged,
                        onSmartStartTransportPrioritySelected = viewModel::onSmartStartTransportPrioritySelected,
                        onAutoReconnectChanged = viewModel::onAutoReconnectChanged,
                        onSmartStartV2RayTunSubscriptionsEnabledChanged = viewModel::onSmartStartV2RayTunSubscriptionsEnabledChanged,
                        onSmartStartFailoverEnabledChanged = viewModel::onSmartStartFailoverEnabledChanged,
                        onSmartStartSubscriptionRetryAttemptsChanged = viewModel::onSmartStartSubscriptionRetryAttemptsChanged,
                        onSmartStartSubscriptionRetryDelaySecondsChanged = viewModel::onSmartStartSubscriptionRetryDelaySecondsChanged,
                        onClearSmartStartData = viewModel::clearSmartStartData,
                    )
                }
                composable(AppRoute.TRAFFIC) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    TrafficSettingsScreen(
                        title = stringResource(R.string.traffic_settings),
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onAcknowledgeUnsafeWarning = viewModel::acknowledgeUnsafeWarning,
                        onOpenSystemVpnSettings = viewModel::openSystemVpnSettings,
                        onTrafficModeSelected = viewModel::onTrafficModeSelected,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onOpenRoutingApps = { navigateToSettingsDetail(AppRoute.ROUTING_APPS) },
                        onLatencyProbeMethodSelected = viewModel::onLatencyProbeMethodSelected,
                        onTunStackSelected = viewModel::onTunStackSelected,
                        onLocalProxyAuthEnabledChanged = viewModel::onLocalProxyAuthEnabledChanged,
                        onLocalProxyAuthChanged = viewModel::onLocalProxyAuthChanged,
                        onLanProxyAuthEnabledChanged = viewModel::onLanProxyAuthEnabledChanged,
                        onLanProxyAuthChanged = viewModel::onLanProxyAuthChanged,
                        onLocalProxyLanAccessChanged = viewModel::onLocalProxyLanAccessChanged,
                        onProxySurfaceModeSelected = viewModel::onProxySurfaceModeSelected,
                        onLanProxySurfaceModeSelected = viewModel::onLanProxySurfaceModeSelected,
                        onSocksSurfaceChanged = viewModel::onSocksSurfaceChanged,
                        onHttpSurfaceChanged = viewModel::onHttpSurfaceChanged,
                        onMixedSurfaceChanged = viewModel::onMixedSurfaceChanged,
                        onMtuChanged = viewModel::onMtuChanged,
                        onPreferIpv6Changed = viewModel::onPreferIpv6Changed,
                        onBypassLanChanged = viewModel::onBypassLanChanged,
                        onAutoRefreshSubscriptionsChanged = viewModel::onAutoRefreshSubscriptionsChanged,
                        onSubscriptionRefreshIntervalSelected = viewModel::onSubscriptionRefreshIntervalSelected,
                        onIpInfoEndpointChanged = viewModel::onIpInfoEndpointChanged,
                    )
                }
                composable(AppRoute.DNS) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    DnsSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onDnsSettingsChanged = viewModel::onDnsSettingsChanged,
                        onDomainStrategySelected = viewModel::onDomainStrategySelected,
                        onOpenDnsBypassApps = { navigateToSettingsDetail(AppRoute.DNS_APPS_PICKER) },
                        onDnsDomainBypassRulesChanged = viewModel::onDnsDomainBypassRulesChanged,
                        onDnsFilterManualRefresh = viewModel::onDnsFilterManualRefresh,
                    )
                }
                composable(AppRoute.SECURITY) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    SecuritySettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                        onNewAppQuarantineChanged = viewModel::onNewAppQuarantineChanged,
                        onInstalledAppMonitoringChanged = viewModel::onInstalledAppMonitoringChanged,
                        onSystemDnsProtectionChanged = viewModel::onSystemDnsProtectionChanged,
                        onAnomalyEnabledChanged = viewModel::onAnomalyEnabledChanged,
                        onNotifyUnusualTrafficChanged = viewModel::onNotifyUnusualTrafficChanged,
                        onAnomalySensitivitySelected = viewModel::onAnomalySensitivitySelected,
                        onAnalyzeBackgroundTrafficChanged = viewModel::onAnalyzeBackgroundTrafficChanged,
                        onAnalyzeDestinationCountriesChanged = viewModel::onAnalyzeDestinationCountriesChanged,
                        onAnomalyHistoryRetentionSelected = viewModel::onAnomalyHistoryRetentionSelected,
                    )
                }
                composable(AppRoute.SECURITY_APP_MONITOR) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    SecuritySettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                        onNewAppQuarantineChanged = viewModel::onNewAppQuarantineChanged,
                        onInstalledAppMonitoringChanged = viewModel::onInstalledAppMonitoringChanged,
                        onSystemDnsProtectionChanged = viewModel::onSystemDnsProtectionChanged,
                        onAnomalyEnabledChanged = viewModel::onAnomalyEnabledChanged,
                        onNotifyUnusualTrafficChanged = viewModel::onNotifyUnusualTrafficChanged,
                        onAnomalySensitivitySelected = viewModel::onAnomalySensitivitySelected,
                        onAnalyzeBackgroundTrafficChanged = viewModel::onAnalyzeBackgroundTrafficChanged,
                        onAnalyzeDestinationCountriesChanged = viewModel::onAnalyzeDestinationCountriesChanged,
                        onAnomalyHistoryRetentionSelected = viewModel::onAnomalyHistoryRetentionSelected,
                    )
                }
                composable(AppRoute.NETWORK_RULES) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    NetworkRulesSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onNetworkRulesChanged = viewModel::onNetworkRulesChanged,
                    )
                }
                composable(AppRoute.PRIVACY_ROUTE) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    PrivacyRouteSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPrivacyRouteModeSelected = viewModel::onPrivacyRouteModeSelected,
                        onPrivacyRouteScopeSelected = viewModel::onPrivacyRouteScopeSelected,
                        onPrivacyRouteBypassVpnTunnelChanged = viewModel::onPrivacyRouteBypassVpnTunnelChanged,
                        onOpenPrivacyRouteApps = { navigateToSettingsDetail(AppRoute.PRIVACY_ROUTE_APPS_PICKER) },
                        onPrivacyRouteSelectedPackagesChanged = viewModel::onPrivacyRouteSelectedPackagesChanged,
                    )
                }
                composable(AppRoute.ROUTING_APPS) {
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    RoutingAppsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onOpenPicker = { navigateToSettingsDetail(AppRoute.ROUTING_APPS_PICKER) },
                        onOpenBlockedPicker = { navigateToSettingsDetail(AppRoute.ROUTING_BLOCKED_APPS_PICKER) },
                        onSelectedPackagesChanged = viewModel::onSelectedPackagesChanged,
                        onBlockedPackagesChanged = viewModel::onBlockedPackagesChanged,
                        onBlockAppsAlwaysChanged = viewModel::onBlockAppsAlwaysChanged,
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                    )
                }
                composable(AppRoute.ROUTING_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
                    AppPickerScreen(
                        title = stringResource(R.string.app_picker_title),
                        selectionTitle = stringResource(R.string.selected_app_exceptions),
                        selectedPackages = state.settings.expert.selectedPackages,
                        lockedPackages = state.settings.expert.blockedPackages.toSet(),
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSelectionChanged = viewModel::onSelectedPackagesChanged,
                    )
                }
                composable(AppRoute.ROUTING_BLOCKED_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
                    AppPickerScreen(
                        title = stringResource(R.string.blocked_app_exceptions),
                        selectionTitle = stringResource(R.string.blocked_app_exceptions),
                        selectedPackages = state.settings.expert.blockedPackages,
                        lockedPackages = state.settings.expert.selectedPackages.toSet(),
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSelectionChanged = viewModel::onBlockedPackagesChanged,
                    )
                }
                composable(AppRoute.PRIVACY_ROUTE_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
                    AppPickerScreen(
                        title = stringResource(R.string.privacy_route_selected_apps_title),
                        selectionTitle = stringResource(R.string.privacy_route_selected_apps_title),
                        selectedPackages = state.settings.privacyRoute.selectedPackages,
                        lockedPackages = emptySet(),
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSelectionChanged = viewModel::onPrivacyRouteSelectedPackagesChanged,
                    )
                }
                composable(AppRoute.DNS_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.appPickerRouteState.collectAsStateWithLifecycle()
                    AppPickerScreen(
                        title = stringResource(R.string.dns_per_app_bypass_title),
                        selectionTitle = stringResource(R.string.dns_per_app_bypass_title),
                        selectedPackages = state.settings.dns.appBypassPackages,
                        lockedPackages = emptySet(),
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSelectionChanged = viewModel::onDnsBypassPackagesChanged,
                    )
                }
                composable(AppRoute.ROUTING_SITES) {
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    RoutingSitesScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSaveSiteRule = viewModel::saveSiteRule,
                        onDeleteRule = viewModel::deleteRule,
                        onSniffChanged = viewModel::onSniffChanged,
                    )
                }
                composable(AppRoute.APPLICATION) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    ApplicationSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onThemeSelected = viewModel::onThemeSelected,
                        onLocaleSelected = viewModel::onLocaleSelected,
                        onAutoStartChanged = viewModel::onAutoStartChanged,
                        onBlockScreenshotsChanged = viewModel::onBlockScreenshotsChanged,
                        onNetworkCardEnabledChanged = viewModel::onNetworkCardEnabledChanged,
                        onTrafficCardEnabledChanged = viewModel::onTrafficCardEnabledChanged,
                        onTrafficMapEnabledChanged = viewModel::onTrafficMapEnabledChanged,
                        onEnableTrafficMapSupportSettings = viewModel::enableTrafficMapSupportSettings,
                        onShowExpertSettingsChanged = viewModel::onShowExpertSettingsChanged,
                        onShowFirewallStatusChanged = viewModel::onShowFirewallStatusChanged,
                        onShowTorQuickLaunchChanged = viewModel::onShowTorQuickLaunchChanged,
                        onSmartStartDashboardControlsEnabledChanged = viewModel::onSmartStartDashboardControlsEnabledChanged,
                    )
                }
                composable(AppRoute.ABOUT) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    AboutSettingsScreen(
                        appVersion = state.appVersion,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                    )
                }
                composable(AppRoute.EXPERT) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    ExpertSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onAcknowledgeUnsafeWarning = viewModel::acknowledgeUnsafeWarning,
                        onRouteOnlyChanged = viewModel::onRouteOnlyChanged,
                        onStrictRouteChanged = viewModel::onStrictRouteChanged,
                        onAllowPrivateOutboundHostsChanged = viewModel::onAllowPrivateOutboundHostsChanged,
                        onSmartStartReplayLoggingChanged = viewModel::onSmartStartReplayLoggingChanged,
                        onAllowInsecureTlsChanged = viewModel::onAllowInsecureTlsChanged,
                        onClashApiChanged = viewModel::onClashApiChanged,
                        onResetToSafeDefaults = viewModel::resetExpertToSafeDefaults,
                    )
                }
                composable(AppRoute.DIAGNOSTICS) {
                    val state by viewModel.diagnosticsRouteState.collectAsStateWithLifecycle()
                    DiagnosticsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onNetworkActivityLoggingChanged = viewModel::onNetworkActivityLoggingChanged,
                        onNetworkActivityPersistentLoggingChanged = viewModel::onNetworkActivityPersistentLoggingChanged,
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                        onDiagnosticsRetentionSelected = viewModel::onDiagnosticsRetentionSelected,
                        onSanitizeNetworkActivityPrivateDataChanged = viewModel::onSanitizeNetworkActivityPrivateDataChanged,
                        onRawLiveDiagnosticsChanged = viewModel::onRawLiveDiagnosticsChanged,
                        onStatisticsMetricEnabledChanged = viewModel::onStatisticsMetricEnabledChanged,
                        onOpenSecurityAppMonitorSettings = { navigateToSettingsDetail(AppRoute.SECURITY_APP_MONITOR) },
                    )
                }
                composable(AppRoute.STATISTICS) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    DisposableEffect(Unit) {
                        viewModel.onStatisticsUiVisibilityChanged(true)
                        onDispose { viewModel.onStatisticsUiVisibilityChanged(false) }
                    }
                    val state by viewModel.statisticsRouteState.collectAsStateWithLifecycle()
                    StatisticsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onStatisticsEnabledChanged = viewModel::onStatisticsEnabledChanged,
                        onStatisticsRetentionSelected = viewModel::onStatisticsRetentionSelected,
                        onStatisticsMetricEnabledChanged = viewModel::onStatisticsMetricEnabledChanged,
                        onAppTrafficStatsEnabledChanged = viewModel::onAppTrafficStatsEnabledChanged,
                        onNetworkActivityLoggingChanged = viewModel::onNetworkActivityLoggingChanged,
                        onOpenNetworkActivityLogSettings = { navigateToSettingsDetail(AppRoute.DIAGNOSTICS) },
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                        onClearUsage = viewModel::resetUsageTracking,
                    )
                }
                }
            }
        }
    }

    if (showBottomBar) {
        FoxholeBottomBar(
            currentSection = currentSection,
            onSectionSelected = selectRootSection,
            overlayHost = bottomDockOverlayHost,
            blurTarget = bottomDockBlurTarget,
        )
    }
    backdropBlurHost?.let { host ->
        FoxholeRootTopChromeOverlay(
            host = host,
            controller = topChromeController,
        )
    }

    if (qrScannerVisible) {
        QrScannerOverlay(
            title = stringResource(R.string.scan_qr_code),
            onDismiss = { qrScannerVisible = false },
            onQrDetected = { value ->
                qrScannerVisible = false
                viewModel.importProfileRaw(value)
            },
        )
    }

    insecureTlsImportWarning?.let { warning ->
        val protocolLabels = warning.protocolLabels.ifEmpty { listOf("VPN") }
        val issueLineValues = mutableListOf<String>()
        for (protocolLabel in protocolLabels) {
            issueLineValues += stringResource(R.string.insecure_tls_import_issue, protocolLabel)
        }
        val issueLines = issueLineValues.joinToString(separator = "\n")
        ConfirmDialog(
            title = stringResource(R.string.insecure_tls_import_warning_title),
            body = stringResource(R.string.insecure_tls_import_warning_body) + "\n\n" + issueLines,
            confirmLabel = stringResource(R.string.insecure_tls_import_apply),
            icon = Icons.Outlined.WarningAmber,
            iconTint = FoxholeWarningAccent,
            iconContainerColor = FoxholeWarningAccent.copy(alpha = 0.14f),
            dismissLabel = stringResource(R.string.insecure_tls_import_cancel),
            secondaryLabel =
                if (warning.canExcludeAndApply) {
                    stringResource(R.string.insecure_tls_import_exclude_and_apply)
                } else {
                    null
                },
            onSecondary =
                if (warning.canExcludeAndApply) {
                    viewModel::excludeInsecureTlsAndImport
                } else {
                    null
                },
            prominentActions = true,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            onDismiss = viewModel::dismissInsecureTlsImportWarning,
            onConfirm = viewModel::confirmInsecureTlsImport,
        )
    }

}

@Composable
private fun FoxholeBottomBar(
    currentSection: AppSection?,
    onSectionSelected: (AppSection) -> Unit,
    overlayHost: ViewGroup?,
    blurTarget: BlurTarget?,
) {
    val selectedSection = currentSection ?: AppSection.DASHBOARD
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { 28.dp.toPx() }
    val themeMode = LocalFoxholeThemeMode.current
    val backgroundColor = foxholeBottomDockBackgroundColor()
    val borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f).toArgb()
    val borderWidthPx = with(density) { 1.dp.roundToPx() }
    val navigationBottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val windowWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val dockWidth = (windowWidth * 0.62f).coerceIn(180.dp, 248.dp)
    val dockWidthPx = with(density) { dockWidth.roundToPx() }
    val dockHeightPx = with(density) { 60.dp.roundToPx() }
    val dockBottomMarginPx = with(density) { (navigationBottomPadding + 10.dp).roundToPx() }
    val externalDockView = rememberExternalBottomDockView(overlayHost, blurTarget, selectedSection)
    SideEffect {
        externalDockView?.apply {
            configureBackground(
                backgroundColor = backgroundColor,
                borderColor = borderColor,
                borderWidthPx = borderWidthPx,
                cornerRadiusPx = cornerRadiusPx,
            )
            selectedSectionState.value = selectedSection
            themeModeState.value = themeMode
            onSectionSelectedState.value = onSectionSelected
            layoutParams =
                FrameLayout.LayoutParams(dockWidthPx, dockHeightPx).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = dockBottomMarginPx
                }
            elevation = 0f
        }
    }
    if (externalDockView != null) {
        return
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        FoxholeBottomDockGlassLayer(
            modifier =
                Modifier
                    .width(dockWidth)
                    .height(60.dp),
            shape = MaterialTheme.shapes.extraLarge,
            borderColor = MaterialTheme.colorScheme.outlineVariant,
        ) {
            FoxholeBottomBarDockContent(
                selectedSection = selectedSection,
                onSectionSelected = onSectionSelected,
                drawBorder = false,
            )
        }
    }
}

@Composable
private fun rememberExternalBottomDockView(
    overlayHost: ViewGroup?,
    blurTarget: BlurTarget?,
    selectedSection: AppSection,
): FoxholeBottomDockBlurView? {
    val externalDockView =
        remember(overlayHost, blurTarget) {
            if (overlayHost != null && blurTarget != null) {
                FoxholeBottomDockBlurView(overlayHost.context, selectedSection)
            } else {
                null
            }
        }
    DisposableEffect(externalDockView, overlayHost) {
        if (externalDockView != null && overlayHost != null) {
            overlayHost.addView(externalDockView)
            onDispose { overlayHost.removeView(externalDockView) }
        } else {
            onDispose {}
        }
    }
    return externalDockView
}

private class FoxholeBottomDockBlurView(
    context: Context,
    initialSelectedSection: AppSection,
) : FrameLayout(context) {
    val selectedSectionState = mutableStateOf(initialSelectedSection)
    val themeModeState = mutableStateOf(ThemeMode.SYSTEM)
    val onSectionSelectedState = mutableStateOf<(AppSection) -> Unit>({})
    private val outlineBackground = GradientDrawable()

    init {
        clipChildren = true
        clipToPadding = true
        addView(
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setContent {
                    FoxholeTheme(themeMode = themeModeState.value) {
                        FoxholeBottomBarDockContent(
                            selectedSection = selectedSectionState.value,
                            onSectionSelected = onSectionSelectedState.value,
                            drawBorder = false,
                        )
                    }
                }
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    fun configureBackground(
        backgroundColor: Int,
        borderColor: Int,
        borderWidthPx: Int,
        cornerRadiusPx: Float,
    ) {
        outlineBackground.shape = GradientDrawable.RECTANGLE
        outlineBackground.cornerRadius = cornerRadiusPx
        outlineBackground.setColor(backgroundColor)
        outlineBackground.setStroke(borderWidthPx, borderColor)
        background = outlineBackground
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = true
        foreground = null
    }
}

@Composable
private fun FoxholeBottomBarDockContent(
    selectedSection: AppSection,
    onSectionSelected: (AppSection) -> Unit,
    drawBorder: Boolean = true,
) {
    val uiPalette = LocalFoxholeUiPalette.current
    val dark = LocalFoxholeDarkTheme.current
    val borderAlpha = if (dark) 0.40f else 0.56f
    BoxWithConstraints(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(MaterialTheme.shapes.large),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            border =
                if (drawBorder) {
                    androidx.compose.foundation.BorderStroke(
                        1.dp,
                        uiPalette.bottomBarBorderColor.copy(alpha = borderAlpha),
                    )
                } else {
                    null
                },
            shadowElevation = 0.dp,
        ) {}
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp)
                    .height(52.dp),
        ) {
            val sections = AppSection.entries
            val tabWidth = maxWidth / sections.size
            val selectedIndex = sections.indexOf(selectedSection).coerceAtLeast(0)
            val indicatorOffset = tabWidth * selectedIndex

            Box(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier =
                        Modifier
                            .offset { IntOffset(x = indicatorOffset.roundToPx(), y = 0) }
                            .width(tabWidth)
                            .fillMaxHeight(),
                    shape = MaterialTheme.shapes.medium,
                    color = uiPalette.bottomBarIndicatorColor,
                    shadowElevation = 0.dp,
                ) {}
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    sections.forEach { section ->
                        FoxholeBottomBarItem(
                            section = section,
                            selected = section == selectedSection,
                            onClick = { onSectionSelected(section) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.FoxholeBottomBarItem(
    section: AppSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember(section) { MutableInteractionSource() }
    val contentColor =
        if (selected) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    val contentAlpha = if (selected) 1f else 0.84f
    val iconBaseColor = foxholeSystemAwareAccentColor(fallback = contentColor, darkFallback = FoxholeInfoAccent)
    val iconColor = if (selected) iconBaseColor else iconBaseColor.copy(alpha = 0.72f)
    val iconScale = if (selected) 1f else 0.92f

    Box(
        modifier =
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(MaterialTheme.shapes.medium)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .testTag(section.testTag),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.graphicsLayer(alpha = contentAlpha),
        ) {
            Icon(
                imageVector = section.icon,
                contentDescription = null,
                tint = iconColor,
                modifier =
                    Modifier.graphicsLayer(
                        scaleX = iconScale,
                        scaleY = iconScale,
                    ).size(FoxholeIconSizes.Navigation),
            )
            Text(
                text = stringResource(section.titleRes),
                color = contentColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

private fun Modifier.sectionSwipeNavigation(
    currentSection: AppSection?,
    onSectionSelected: (AppSection) -> Unit,
): Modifier =
    pointerInput(currentSection) {
        var dragDistance = 0f
        val switchThreshold = size.width * SECTION_SWIPE_THRESHOLD_FRACTION
        detectHorizontalDragGestures(
            onDragStart = {
                dragDistance = 0f
            },
            onHorizontalDrag = { change, dragAmount ->
                dragDistance += dragAmount
                if (abs(dragDistance) < switchThreshold) {
                    return@detectHorizontalDragGestures
                }
                val targetSection =
                    if (dragDistance < 0f) {
                        AppSection.SETTINGS
                    } else {
                        AppSection.DASHBOARD
                    }
                if (targetSection != currentSection) {
                    change.consume()
                    onSectionSelected(targetSection)
                }
                dragDistance = 0f
            },
            onDragEnd = {
                dragDistance = 0f
            },
            onDragCancel = {
                dragDistance = 0f
            },
        )
    }

@Composable
private fun Modifier.settingsBackSwipeNavigation(
    onNavigateBack: () -> Unit,
): Modifier {
    val layoutDirection = LocalLayoutDirection.current
    return pointerInput(layoutDirection) {
        var dragDistance = 0f
        var edgeEligible = false
        var consumed = false
        val switchThreshold = min(size.width * DETAIL_BACK_SWIPE_THRESHOLD_FRACTION, 96.dp.toPx())
        val edgeWidth = DETAIL_BACK_SWIPE_EDGE_WIDTH.toPx()
        detectHorizontalDragGestures(
            onDragStart = { start ->
                dragDistance = 0f
                edgeEligible =
                    when (layoutDirection) {
                        LayoutDirection.Ltr -> start.x <= edgeWidth
                        LayoutDirection.Rtl -> start.x >= size.width - edgeWidth
                    }
                consumed = false
            },
            onHorizontalDrag = { change, dragAmount ->
                if (consumed || !edgeEligible) {
                    return@detectHorizontalDragGestures
                }
                val directedDrag =
                    when (layoutDirection) {
                        LayoutDirection.Ltr -> dragAmount
                        LayoutDirection.Rtl -> -dragAmount
                    }
                if (directedDrag <= 0f) {
                    dragDistance = 0f
                    return@detectHorizontalDragGestures
                }
                dragDistance += directedDrag
                if (dragDistance < switchThreshold) {
                    return@detectHorizontalDragGestures
                }
                change.consume()
                consumed = true
                onNavigateBack()
            },
            onDragEnd = {
                dragDistance = 0f
                edgeEligible = false
                consumed = false
            },
            onDragCancel = {
                dragDistance = 0f
                edgeEligible = false
                consumed = false
            },
        )
    }
}

private fun NavDestination.rootAppSection(): AppSection? =
    when {
        route == AppRoute.HOME -> AppSection.DASHBOARD
        route == AppRoute.SETTINGS -> AppSection.SETTINGS
        else -> null
    }

private fun NavDestination.rootSwipeSection(): AppSection? =
    when (route) {
        AppRoute.HOME -> AppSection.DASHBOARD
        AppRoute.SETTINGS -> AppSection.SETTINGS
        else -> null
    }

private fun NavDestination.settingsBackSwipeEnabled(): Boolean {
    val currentRoute = route ?: return false
    return currentRoute.startsWith("${AppRoute.SETTINGS}/")
}

private fun String?.isRootRoute(): Boolean =
    this == AppRoute.HOME || this == AppRoute.SETTINGS

private fun String?.isSettingsDetailRoute(): Boolean =
    this?.startsWith("${AppRoute.SETTINGS}/") == true

private fun rootEnter(): EnterTransition = EnterTransition.None

private fun rootExit(): ExitTransition = ExitTransition.None

private fun detailForwardEnter(): EnterTransition =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_IN_MS,
                easing = LinearOutSlowInEasing,
            ),
    ) +
        slideInHorizontally(
            initialOffsetX = { fullWidth -> detailTransitionOffsetPx(fullWidth) },
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun detailForwardExit(): ExitTransition =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_OUT_MS,
                easing = FastOutLinearInEasing,
            ),
    ) +
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> -detailTransitionOffsetPx(fullWidth) },
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun detailBackEnter(): EnterTransition =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_IN_MS,
                easing = LinearOutSlowInEasing,
            ),
    ) +
        slideInHorizontally(
            initialOffsetX = { fullWidth -> -detailTransitionOffsetPx(fullWidth) },
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun detailBackExit(): ExitTransition =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_OUT_MS,
                easing = FastOutLinearInEasing,
            ),
    ) +
        slideOutHorizontally(
            targetOffsetX = { fullWidth -> detailTransitionOffsetPx(fullWidth) },
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

internal fun detailTransitionOffsetPx(fullWidthPx: Int): Int =
    (fullWidthPx * DETAIL_TRANSITION_OFFSET_FRACTION)
        .roundToInt()
        .coerceAtLeast(1)

private fun NavHostController.navigateToProfilesRoot() {
    val currentRoute = currentDestination?.route
    if (currentRoute == AppRoute.PROFILES) {
        return
    }
    if (currentRoute?.startsWith(AppRoute.PROFILES) == true) {
        if (popBackStack(AppRoute.PROFILES, inclusive = false)) {
            return
        }
    }
    navigate(AppRoute.PROFILES) {
        launchSingleTop = true
        restoreState = false
        popUpTo(AppRoute.HOME) {
            inclusive = false
            saveState = false
        }
    }
}

private fun NavHostController.navigateToSettingsDetail(
    route: String,
    gate: SettingsDetailNavigationGate,
    telemetry: NavigationTransitionTelemetry,
) {
    val currentRoute = currentDestination?.route
    when (val decision = gate.tryAccept(currentRoute, route)) {
        is SettingsDetailNavigationDecision.Accepted -> {
            telemetry.recordTap(
                routeFrom = currentRoute,
                routeTo = route,
                tapTimeMs = decision.atMs,
            )
            telemetry.recordNavigateCall(route)
            navigate(route) {
                launchSingleTop = true
            }
        }
        is SettingsDetailNavigationDecision.Rejected -> {
            telemetry.recordRejected(
                routeFrom = currentRoute,
                routeTo = route,
                reason = decision.reason,
            )
        }
    }
}

private fun NavHostController.navigateToSection(
    section: AppSection,
    telemetry: NavigationTransitionTelemetry? = null,
) {
    val currentRoute = currentDestination?.route
    if (currentRoute == section.rootRoute) {
        return
    }
    telemetry?.recordTap(
        routeFrom = currentRoute,
        routeTo = section.rootRoute,
    )
    if (currentRoute?.startsWith(section.rootRoute) == true) {
        telemetry?.recordNavigateCall(section.rootRoute)
        if (!popBackStack(section.rootRoute, inclusive = false)) {
            telemetry?.recordCancelled(section.rootRoute)
        }
        return
    }
    if (section.rootRoute == AppRoute.HOME) {
        telemetry?.recordNavigateCall(section.rootRoute)
        if (popBackStack(AppRoute.HOME, inclusive = false)) {
            return
        }
        telemetry?.recordCancelled(section.rootRoute)
    } else {
        telemetry?.recordNavigateCall(section.rootRoute)
    }
    navigate(section.rootRoute) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

private const val SECTION_SWIPE_THRESHOLD_FRACTION = 0.22f
private const val DETAIL_BACK_SWIPE_THRESHOLD_FRACTION = 0.18f
private val DETAIL_BACK_SWIPE_EDGE_WIDTH = 32.dp
private const val DETAIL_FADE_IN_MS = 80
private const val DETAIL_FADE_OUT_MS = 60
private const val DETAIL_TRANSITION_MS = 150
private const val DETAIL_TRANSITION_OFFSET_FRACTION = 0.14f
