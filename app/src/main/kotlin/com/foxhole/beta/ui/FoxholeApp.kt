package com.foxhole.beta.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import kotlinx.coroutines.launch
import kotlin.math.abs

private object AppRoute {
    const val HOME = "home"
    const val PROFILES = "profiles"
    const val PROFILE_ID = "profileId"
    const val PROFILE_DETAIL = "profiles/{$PROFILE_ID}"
    const val PROFILE_VIEW_CONFIG = "profiles/{$PROFILE_ID}/view-config"
    const val PROFILE_EDIT_CONFIG = "profiles/{$PROFILE_ID}/edit-config"
    const val SETTINGS = "settings"
    const val TRAFFIC = "settings/traffic"
    const val PRIVACY_ROUTE = "settings/privacy-route"
    const val ROUTING_APPS = "settings/routing/apps"
    const val ROUTING_APPS_PICKER = "settings/routing/apps/picker"
    const val ROUTING_BLOCKED_APPS_PICKER = "settings/routing/apps/blocked-picker"
    const val PRIVACY_ROUTE_APPS_PICKER = "settings/privacy-route/apps-picker"
    const val ROUTING_SITES = "settings/routing/sites"
    const val SMART_START = "settings/smart-start"
    const val APPLICATION = "settings/application"
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
        icon = Icons.Outlined.Dashboard,
        titleRes = R.string.dashboard,
        testTag = "bottom_nav_dashboard",
    ),
    SETTINGS(
        rootRoute = AppRoute.SETTINGS,
        icon = Icons.Outlined.Settings,
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val currentSection = navBackStackEntry?.destination?.rootAppSection()
    val showBottomBar = currentRoute.isRootRoute()
    val rootSwipeSection = navBackStackEntry?.destination?.rootSwipeSection()
    val settingsBackSwipeEnabled = navBackStackEntry?.destination?.settingsBackSwipeEnabled() == true
    var qrScannerVisible by rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val insecureTlsImportWarning by viewModel.insecureTlsImportWarning.collectAsStateWithLifecycle()
    val importProfileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                val raw =
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }.orEmpty()
                viewModel.importProfileRaw(raw)
            }
        }

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showBottomBar) {
                FoxholeBottomBar(
                    currentSection = currentSection,
                    onSectionSelected = { section -> navController.navigateToSection(section) },
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .consumeWindowInsets(innerPadding)
                    .then(
                        when {
                            rootSwipeSection != null ->
                                Modifier.sectionSwipeNavigation(
                                    currentSection = rootSwipeSection,
                                    onSectionSelected = { section -> navController.navigateToSection(section) },
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
                    val state by viewModel.homeRouteState.collectAsStateWithLifecycle()
                    HomeScreen(
                        state = state,
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
                        onToggleConnection = viewModel::onToggleConnection,
                        onAutoConnect = viewModel::onAutoConnectActiveProfile,
                        onTrafficModeSelected = viewModel::onTrafficModeSelected,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onKillSwitchChanged = viewModel::onKillSwitchChanged,
                        onBlockedPackagesEnabledChanged = viewModel::onBlockedPackagesEnabledChanged,
                        onPrivacyRouteModeSelected = viewModel::onPrivacyRouteModeSelected,
                        onSelectActiveProtocolOption = { optionId ->
                            state.activeProfile?.id?.let { profileId ->
                                viewModel.onSelectProfileProtocolOption(profileId, optionId)
                            }
                        },
                        onUpdateAutoConnectExcludedOptions = { excludedIds ->
                            state.activeProfile?.id?.let { profileId ->
                                viewModel.onSmartProfileAutoConnectExcludedOptionsChanged(profileId, excludedIds)
                            }
                        },
                        onRefreshSmartProfileMetrics = viewModel::refreshSmartProfileMetrics,
                        onCancelSmartProfileMetricsRefresh = viewModel::cancelSmartProfileMetricsRefresh,
                        onOpenProfiles = { navController.navigateToProfilesRoot() },
                        onRefreshIpInfo = viewModel::refreshIpInfo,
                        onResetUsageTracking = viewModel::resetUsageTracking,
                        onTrafficUiVisibilityChanged = viewModel::onTrafficUiVisibilityChanged,
                        onLocalProxyLanAccessChanged = viewModel::onLocalProxyLanAccessChanged,
                    )
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
                    val profileId = backStackEntry.arguments?.getLong(AppRoute.PROFILE_ID) ?: return@composable
                    ProfileConfigViewScreen(
                        profile = state.profile(profileId),
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
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    SettingsHomeScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = null,
                        onOpenTraffic = { navController.navigate(AppRoute.TRAFFIC) },
                        onOpenPrivacyRoute = { navController.navigate(AppRoute.PRIVACY_ROUTE) },
                        onOpenRoutingApps = { navController.navigate(AppRoute.ROUTING_APPS) },
                        onOpenRoutingSites = { navController.navigate(AppRoute.ROUTING_SITES) },
                        onOpenSmartStart = { navController.navigate(AppRoute.SMART_START) },
                        onOpenApplication = { navController.navigate(AppRoute.APPLICATION) },
                        onOpenExpert = { navController.navigate(AppRoute.EXPERT) },
                        onOpenDiagnostics = { navController.navigate(AppRoute.DIAGNOSTICS) },
                        onOpenStatistics = { navController.navigate(AppRoute.STATISTICS) },
                        onUnlockExpertSettings = viewModel::unlockExpertSettings,
                    )
                }
                composable(AppRoute.SMART_START) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    SmartStartSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSmartStartProtocolSelectionTimeoutChanged = viewModel::onSmartStartProtocolSelectionTimeoutChanged,
                        onSmartStartRefreshSelectionTimeoutChanged = viewModel::onSmartStartRefreshSelectionTimeoutChanged,
                        onSmartStartTransportPrioritySelected = viewModel::onSmartStartTransportPrioritySelected,
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
                        onKillSwitchChanged = viewModel::onKillSwitchChanged,
                        onTrafficModeSelected = viewModel::onTrafficModeSelected,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onOpenRoutingApps = { navController.navigate(AppRoute.ROUTING_APPS) },
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
                        onDomainStrategySelected = viewModel::onDomainStrategySelected,
                        onBypassLanChanged = viewModel::onBypassLanChanged,
                        onAutoRefreshSubscriptionsChanged = viewModel::onAutoRefreshSubscriptionsChanged,
                        onSubscriptionRefreshIntervalSelected = viewModel::onSubscriptionRefreshIntervalSelected,
                        onIpInfoEndpointChanged = viewModel::onIpInfoEndpointChanged,
                    )
                }
                composable(AppRoute.PRIVACY_ROUTE) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    PrivacyRouteSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPrivacyRouteModeSelected = viewModel::onPrivacyRouteModeSelected,
                        onPrivacyRouteScopeSelected = viewModel::onPrivacyRouteScopeSelected,
                        onOpenPrivacyRouteApps = { navController.navigate(AppRoute.PRIVACY_ROUTE_APPS_PICKER) },
                        onPrivacyRouteSelectedPackagesChanged = viewModel::onPrivacyRouteSelectedPackagesChanged,
                    )
                }
                composable(AppRoute.ROUTING_APPS) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    RoutingAppsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onOpenPicker = { navController.navigate(AppRoute.ROUTING_APPS_PICKER) },
                        onOpenBlockedPicker = { navController.navigate(AppRoute.ROUTING_BLOCKED_APPS_PICKER) },
                        onSelectedPackagesChanged = viewModel::onSelectedPackagesChanged,
                        onBlockedPackagesChanged = viewModel::onBlockedPackagesChanged,
                        onBlockAppsAlwaysChanged = viewModel::onBlockAppsAlwaysChanged,
                    )
                }
                composable(AppRoute.ROUTING_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
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
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
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
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
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
                        onAutoReconnectChanged = viewModel::onAutoReconnectChanged,
                        onAutoStartChanged = viewModel::onAutoStartChanged,
                        onBlockScreenshotsChanged = viewModel::onBlockScreenshotsChanged,
                    )
                }
                composable(AppRoute.EXPERT) {
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    ExpertSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onShowExpertSettingsChanged = viewModel::onShowExpertSettingsChanged,
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
                        onDiagnosticsRetentionSelected = viewModel::onDiagnosticsRetentionSelected,
                        onClearUsage = viewModel::resetUsageTracking,
                    )
                }
                composable(AppRoute.STATISTICS) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.settingsRouteState.collectAsStateWithLifecycle()
                    StatisticsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onAppTrafficStatsEnabledChanged = viewModel::onAppTrafficStatsEnabledChanged,
                        onClearUsage = viewModel::resetUsageTracking,
                    )
                }
            }
        }
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
) {
    val selectedSection = currentSection ?: AppSection.DASHBOARD
    val uiPalette = LocalFoxholeUiPalette.current
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier =
                Modifier
                    .fillMaxWidth(0.62f)
                    .widthIn(min = 180.dp, max = 248.dp),
            shape = MaterialTheme.shapes.large,
            color = Color.Transparent,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(0.dp, Color.Transparent),
        ) {
            FoxholeGlassPanel(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(60.dp),
                shape = MaterialTheme.shapes.large,
                containerColor = uiPalette.bottomBarContainerColor,
                borderColor = uiPalette.bottomBarBorderColor,
                blurRadius = 18.dp,
                backgroundAlpha = 1f,
            ) {
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
                    val indicatorOffset by animateDpAsState(
                        targetValue = tabWidth * selectedIndex,
                        animationSpec =
                            tween(
                                durationMillis = FoxholeMotionTokens.NavigationIndicatorDurationMs,
                                easing = FoxholeMotionTokens.NavigationIndicatorEasing,
                            ),
                        label = "bottom_bar_indicator",
                    )

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
    }
}

@Composable
private fun RowScope.FoxholeBottomBarItem(
    section: AppSection,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember(section) { MutableInteractionSource() }
    val contentColor by animateColorAsState(
        targetValue =
            if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.NavigationIndicatorDurationMs,
                easing = FoxholeMotionTokens.NavigationIndicatorEasing,
            ),
        label = "bottom_bar_color_${section.name.lowercase()}",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.84f,
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.NavigationIndicatorDurationMs,
                easing = FoxholeMotionTokens.NavigationIndicatorEasing,
        ),
        label = "bottom_bar_alpha_${section.name.lowercase()}",
    )
    val iconBaseColor = foxholeSystemAwareAccentColor(fallback = contentColor, darkFallback = FoxholeInfoAccent)
    val iconColor by animateColorAsState(
        targetValue = if (selected) iconBaseColor else iconBaseColor.copy(alpha = 0.72f),
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.NavigationIndicatorDurationMs,
                easing = FoxholeMotionTokens.NavigationIndicatorEasing,
            ),
        label = "bottom_bar_icon_color_${section.name.lowercase()}",
    )
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.92f,
        animationSpec =
            tween(
                durationMillis = FoxholeMotionTokens.NavigationIndicatorDurationMs,
                easing = FoxholeMotionTokens.NavigationIndicatorEasing,
            ),
        label = "bottom_bar_scale_${section.name.lowercase()}",
    )

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
                    ).size(18.dp),
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

private fun Modifier.settingsBackSwipeNavigation(
    onNavigateBack: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        var dragDistance = 0f
        var consumed = false
        val switchThreshold = size.width * DETAIL_BACK_SWIPE_THRESHOLD_FRACTION
        detectHorizontalDragGestures(
            onDragStart = {
                dragDistance = 0f
                consumed = false
            },
            onHorizontalDrag = { change, dragAmount ->
                if (consumed) {
                    return@detectHorizontalDragGestures
                }
                dragDistance += dragAmount
                if (abs(dragDistance) < switchThreshold) {
                    return@detectHorizontalDragGestures
                }
                change.consume()
                consumed = true
                onNavigateBack()
            },
            onDragEnd = {
                dragDistance = 0f
                consumed = false
            },
            onDragCancel = {
                dragDistance = 0f
                consumed = false
            },
        )
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

private fun rootEnter(): EnterTransition =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = ROOT_FADE_IN_MS,
                easing = LinearOutSlowInEasing,
            ),
    )

private fun rootExit(): ExitTransition =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = ROOT_FADE_OUT_MS,
                easing = FastOutLinearInEasing,
            ),
    )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailForwardEnter(): EnterTransition =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_IN_MS,
                easing = LinearOutSlowInEasing,
            ),
    ) +
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailForwardExit(): ExitTransition =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_OUT_MS,
                easing = FastOutLinearInEasing,
            ),
    ) +
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Start,
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailBackEnter(): EnterTransition =
    fadeIn(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_IN_MS,
                easing = LinearOutSlowInEasing,
            ),
    ) +
        slideIntoContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

private fun AnimatedContentTransitionScope<NavBackStackEntry>.detailBackExit(): ExitTransition =
    fadeOut(
        animationSpec =
            tween(
                durationMillis = DETAIL_FADE_OUT_MS,
                easing = FastOutLinearInEasing,
            ),
    ) +
        slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.End,
            animationSpec =
                tween(
                    durationMillis = DETAIL_TRANSITION_MS,
                    easing = FastOutSlowInEasing,
                ),
        )

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

private fun NavHostController.navigateToSection(section: AppSection) {
    val currentRoute = currentDestination?.route
    if (currentRoute == section.rootRoute) {
        return
    }
    if (currentRoute?.startsWith(section.rootRoute) == true) {
        popBackStack(section.rootRoute, inclusive = false)
        return
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
private const val ROOT_FADE_OUT_MS = 90
private const val ROOT_FADE_IN_MS = 150
private const val DETAIL_FADE_IN_MS = 120
private const val DETAIL_FADE_OUT_MS = 90
private const val DETAIL_TRANSITION_MS = 280
