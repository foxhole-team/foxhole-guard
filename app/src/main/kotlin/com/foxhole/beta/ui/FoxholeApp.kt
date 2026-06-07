package com.foxhole.beta.ui

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Trace
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navigation
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
import com.foxhole.beta.vpn.FoxholeTileService
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.drawable.Icon as AndroidIcon
import android.provider.Settings as AndroidSettings

private object AppRoute {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val PROFILE_ID = "profileId"
    const val PROFILE_DETAIL = "profiles/{$PROFILE_ID}"
    const val PROFILE_VIEW_CONFIG = "profiles/{$PROFILE_ID}/view-config"
    const val PROFILE_EDIT_CONFIG = "profiles/{$PROFILE_ID}/edit-config"
    const val SETTINGS = "settings"
    const val TRAFFIC_MAP_DETAIL = "traffic-map"
    const val TRAFFIC = "settings/traffic"
    const val DNS = "settings/dns"
    const val DNS_APPS_PICKER = "settings/dns/apps-picker"
    const val NETWORK_RULES = "settings/network-rules"
    const val SECURITY = "settings/security"
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

private sealed interface RootGraph {
    val route: String

    data object Dashboard : RootGraph {
        override val route: String = "root/dashboard"
    }

    data object Settings : RootGraph {
        override val route: String = "root/settings"
    }
}

private enum class AppSection(
    val graphRoute: String,
    val rootRoute: String,
    val icon: ImageVector,
    val titleRes: Int,
    val testTag: String,
) {
    DASHBOARD(
        graphRoute = RootGraph.Dashboard.route,
        rootRoute = AppRoute.HOME,
        icon = FoxholeIcons.Dashboard,
        titleRes = R.string.dashboard,
        testTag = "bottom_nav_dashboard",
    ),
    SETTINGS(
        graphRoute = RootGraph.Settings.route,
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
    chromeMode: ChromeMode = FoxholeDefaultChromeMode,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentSection = navBackStackEntry?.destination?.rootAppSection()
    val showBottomBar = currentRoute.isRootRoute()
    val settingsDetailNavigationGate = remember { SettingsDetailNavigationGate() }
    val navigationTransitionTelemetry = remember { NavigationTransitionTelemetry() }
    NavigationTransitionTelemetryEffect(currentRoute, navigationTransitionTelemetry)
    val selectRootSection: (AppSection) -> Unit = { section ->
        navController.navigateToSection(
            section = section,
            telemetry = navigationTransitionTelemetry,
        )
    }
    val navigateToSettingsDetail: (String) -> Unit = { route ->
        navController.navigateToSettingsDetail(
            route = route,
            gate = settingsDetailNavigationGate,
            telemetry = navigationTransitionTelemetry,
        )
    }
    val backdropBlurHost =
        remember(bottomDockOverlayHost, bottomDockBlurTarget, chromeMode) {
            if (
                chromeMode == ChromeMode.GlassBlur &&
                bottomDockOverlayHost != null &&
                bottomDockBlurTarget != null
            ) {
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
                    .testTag("app_section_swipe_surface"),
        ) {
            CompositionLocalProvider(
                LocalFoxholeBackdropBlurHost provides backdropBlurHost,
                LocalFoxholeTopChromeController provides topChromeController,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = RootGraph.Dashboard.route,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        if (targetState.destination.route.isSettingsDetailRoute()) {
                            detailForwardEnter()
                        } else {
                            rootEnter()
                        }
                    },
                    exitTransition = {
                        if (
                            initialState.destination.route.isSettingsDetailRoute() &&
                            targetState.destination.route.isSettingsDetailRoute()
                        ) {
                            detailForwardExit()
                        } else {
                            rootExit()
                        }
                    },
                    popEnterTransition = {
                        if (
                            initialState.destination.route.isSettingsDetailRoute() &&
                            targetState.destination.route.isSettingsDetailRoute()
                        ) {
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
                    navigation(
                        route = RootGraph.Dashboard.route,
                        startDestination = AppRoute.HOME,
                    ) {
                        composable(AppRoute.HOME) {
                    NavigationTransitionTelemetryEffect(AppRoute.HOME, navigationTransitionTelemetry)
                    HomeScreen(
                        layoutStateFlow = viewModel.dashboardLayoutState,
                        headerStateFlow = viewModel.dashboardHeaderState,
                        profileCardStateFlow = viewModel.dashboardProfileCardState,
                        actionsCardStateFlow = viewModel.dashboardActionsCardState,
                        networkCardStateFlow = viewModel.dashboardNetworkCardState,
                        trafficCardStateFlow = viewModel.dashboardTrafficCardState,
                        mapCardStateFlow = viewModel.dashboardMapCardState,
                        dialogStateFlow = viewModel.dashboardDialogState,
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
                        onUpdateAutoConnectExcludedOptions = viewModel::onActiveProfileAutoConnectExcludedOptionsChanged,
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
                        onOpenTrafficMapDetails = { navController.navigate(AppRoute.TRAFFIC_MAP_DETAIL) },
                    )
                }
                        composable(AppRoute.TRAFFIC_MAP_DETAIL) {
                    NavigationTransitionTelemetryEffect(AppRoute.TRAFFIC_MAP_DETAIL, navigationTransitionTelemetry)
                    TrafficMapDetailScreen(
                        stateFlow = viewModel.trafficMapUiState,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                    )
                }
                        composable(AppRoute.PROFILES) {
                    NavigationTransitionTelemetryEffect(AppRoute.PROFILES, navigationTransitionTelemetry)
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
                    }
                    navigation(
                        route = RootGraph.Settings.route,
                        startDestination = AppRoute.SETTINGS,
                    ) {
                        composable(AppRoute.SETTINGS) {
                    NavigationTransitionTelemetryEffect(AppRoute.SETTINGS, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.SMART_START, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.TRAFFIC, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.DNS, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.SECURITY, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.NETWORK_RULES, navigationTransitionTelemetry)
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    NetworkRulesSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onNetworkRulesChanged = viewModel::onNetworkRulesChanged,
                    )
                }
                        composable(AppRoute.PRIVACY_ROUTE) {
                    NavigationTransitionTelemetryEffect(AppRoute.PRIVACY_ROUTE, navigationTransitionTelemetry)
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    PrivacyRouteSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPrivacyRouteModeSelected = viewModel::onPrivacyRouteModeConfigured,
                        onPrivacyRouteScopeSelected = viewModel::onPrivacyRouteScopeConfigured,
                        onPrivacyRouteBypassVpnTunnelChanged = viewModel::onPrivacyRouteBypassVpnTunnelConfigured,
                        onOpenPrivacyRouteApps = { navigateToSettingsDetail(AppRoute.PRIVACY_ROUTE_APPS_PICKER) },
                        onPrivacyRouteSelectedPackagesChanged = viewModel::onPrivacyRouteSelectedPackagesConfigured,
                    )
                }
                        composable(AppRoute.ROUTING_APPS) {
                    NavigationTransitionTelemetryEffect(AppRoute.ROUTING_APPS, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.ROUTING_APPS_PICKER, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(
                        AppRoute.ROUTING_BLOCKED_APPS_PICKER,
                        navigationTransitionTelemetry,
                    )
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
                    NavigationTransitionTelemetryEffect(
                        AppRoute.PRIVACY_ROUTE_APPS_PICKER,
                        navigationTransitionTelemetry,
                    )
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
                        onSelectionChanged = viewModel::onPrivacyRouteSelectedPackagesConfigured,
                    )
                }
                        composable(AppRoute.DNS_APPS_PICKER) {
                    NavigationTransitionTelemetryEffect(AppRoute.DNS_APPS_PICKER, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.ROUTING_SITES, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.APPLICATION, navigationTransitionTelemetry)
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
                        onOpenQuickSettingsTile = {
                            requestQuickSettingsTile(
                                context = context,
                                snackbarHostState = snackbarHostState,
                                scope = scope,
                            )
                        },
                    )
                }
                        composable(AppRoute.ABOUT) {
                    NavigationTransitionTelemetryEffect(AppRoute.ABOUT, navigationTransitionTelemetry)
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    AboutSettingsScreen(
                        appVersion = state.appVersion,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                    )
                }
                        composable(AppRoute.EXPERT) {
                    NavigationTransitionTelemetryEffect(AppRoute.EXPERT, navigationTransitionTelemetry)
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
                    NavigationTransitionTelemetryEffect(AppRoute.DIAGNOSTICS, navigationTransitionTelemetry)
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
                        onOpenSecurityAppMonitorSettings = { navigateToSettingsDetail(AppRoute.SECURITY) },
                    )
                }
                        composable(AppRoute.STATISTICS) {
                    NavigationTransitionTelemetryEffect(AppRoute.STATISTICS, navigationTransitionTelemetry)
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
                        onAppTrafficUsageAccessConsentChanged = viewModel::onAppTrafficUsageAccessConsentChanged,
                        onNetworkActivityLoggingChanged = viewModel::onNetworkActivityLoggingChanged,
                        onOpenNetworkActivityLogSettings = { navigateToSettingsDetail(AppRoute.DIAGNOSTICS) },
                        onFirewallEnabledChanged = viewModel::onFirewallEnabledChanged,
                        onClearUsage = viewModel::resetUsageTracking,
                        onClearDiagnostics = viewModel::clearDiagnosticsLocalData,
                        onClearNetworkActivity = viewModel::clearNetworkActivityLocalData,
                        onClearAppTrafficStats = viewModel::clearAppTrafficLocalData,
                        onClearProfilesAndSecrets = viewModel::clearProfilesAndSecretsLocalData,
                        onFactoryReset = viewModel::factoryResetLocalData,
                    )
                }
                }
            }
        }
    }
    }

    if (showBottomBar) {
        when (chromeMode) {
            ChromeMode.Material ->
                FoxholeMaterialBottomBar(
                    currentSection = currentSection,
                    onSectionSelected = selectRootSection,
                )
            ChromeMode.GlassStatic,
            ChromeMode.GlassBlur,
            ->
                FoxholeBottomBar(
                    currentSection = currentSection,
                    onSectionSelected = selectRootSection,
                    overlayHost = bottomDockOverlayHost.takeIf { chromeMode == ChromeMode.GlassBlur },
                    blurTarget = bottomDockBlurTarget.takeIf { chromeMode == ChromeMode.GlassBlur },
                )
        }
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
private fun FoxholeMaterialBottomBar(
    currentSection: AppSection?,
    onSectionSelected: (AppSection) -> Unit,
) {
    val selectedSection = currentSection ?: AppSection.DASHBOARD
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = foxholeBottomDockElevation(),
        ) {
            AppSection.entries.forEach { section ->
                NavigationBarItem(
                    selected = section == selectedSection,
                    onClick = { onSectionSelected(section) },
                    icon = {
                        Icon(
                            imageVector = section.icon,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(section.titleRes),
                            maxLines = 1,
                        )
                    },
                    modifier = Modifier.testTag(section.testTag),
                )
            }
        }
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
    val borderColor = foxholeBottomDockBorderColor().toArgb()
    val borderWidthPx = with(density) { 1.dp.roundToPx() }
    val dockElevationPx = with(density) { foxholeBottomDockElevation().toPx() }
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
            elevation = dockElevationPx
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
            borderColor = foxholeBottomDockBorderColor(),
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
                .selectable(
                    selected = selected,
                    role = Role.Tab,
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

private fun NavDestination.rootAppSection(): AppSection? =
    AppSection.entries.firstOrNull { section -> belongsToRootSection(section) }

private fun NavDestination.belongsToRootSection(section: AppSection): Boolean =
    hierarchy.any { destination ->
        destination.route == section.graphRoute || destination.route == section.rootRoute
    }

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
            traceSettingsNavigationSection("Settings/navigation") {
                telemetry.recordNavigateCall(route)
                navigate(route) {
                    launchSingleTop = true
                }
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

private inline fun <T> traceSettingsNavigationSection(name: String, block: () -> T): T {
    Trace.beginSection(name)
    return try {
        block()
    } finally {
        Trace.endSection()
    }
}

private fun NavHostController.navigateToSection(
    section: AppSection,
    telemetry: NavigationTransitionTelemetry? = null,
) {
    val currentRoute = currentDestination?.route
    val alreadyInSection = currentDestination?.belongsToRootSection(section) == true
    if (currentRoute == section.rootRoute) {
        return
    }
    telemetry?.recordTap(
        routeFrom = currentRoute,
        routeTo = section.graphRoute,
    )
    if (alreadyInSection) {
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
    navigate(section.graphRoute) {
        launchSingleTop = true
        restoreState = true
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
    }
}

private fun requestQuickSettingsTile(
    context: Context,
    snackbarHostState: SnackbarHostState,
    scope: CoroutineScope,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(StatusBarManager::class.java)?.let { statusBarManager ->
            statusBarManager.requestAddTileService(
                ComponentName(context, FoxholeTileService::class.java),
                context.getString(R.string.app_name),
                AndroidIcon.createWithResource(context, R.drawable.ic_tile_vpn),
                context.mainExecutor,
            ) { result: Int ->
                val messageRes =
                    when (result) {
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> R.string.quick_settings_tile_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> R.string.quick_settings_tile_already_added
                        StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> R.string.quick_settings_tile_not_added
                        else -> R.string.quick_settings_tile_not_added
                    }
                scope.launch {
                    snackbarHostState.showSnackbar(context.getString(messageRes))
                }
            }
            return
        }
    }

    val opened =
        runCatching {
            context.startActivity(
                Intent(AndroidSettings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
    val messageRes =
        if (opened) {
            R.string.quick_settings_tile_open_settings_hint
        } else {
            R.string.quick_settings_tile_not_added
        }
    scope.launch {
        snackbarHostState.showSnackbar(context.getString(messageRes))
    }
}

