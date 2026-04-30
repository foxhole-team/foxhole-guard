package com.foxhole.beta.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
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
    const val ROUTING = "settings/routing"
    const val ROUTING_APPS = "settings/routing/apps"
    const val ROUTING_APPS_PICKER = "settings/routing/apps/picker"
    const val ROUTING_SITES = "settings/routing/sites"
    const val SMART_START = "settings/smart-start"
    const val APPLICATION = "settings/application"
    const val HELP = "settings/help"
    const val ABOUT = "settings/about"
    const val EXPERT = "settings/expert"
    const val DIAGNOSTICS = "settings/diagnostics"

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
    val currentSection = navBackStackEntry?.destination?.appSection()
    val rootSwipeSection = navBackStackEntry?.destination?.rootSwipeSection()
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
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        bottomBar = {
            FoxholeBottomBar(
                currentSection = currentSection,
                onSectionSelected = { section -> navController.navigateToSection(section) },
            )
        },
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .consumeWindowInsets(innerPadding)
                    .then(
                        if (rootSwipeSection != null) {
                            Modifier.sectionSwipeNavigation(
                                currentSection = rootSwipeSection,
                                onSectionSelected = { section -> navController.navigateToSection(section) },
                            )
                        } else {
                            Modifier
                        },
                    ).testTag("app_section_swipe_surface"),
        ) {
            NavHost(
                navController = navController,
                startDestination = AppRoute.HOME,
                modifier = Modifier.fillMaxSize(),
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
                        onLocalProxyAuthChanged = viewModel::onLocalProxyAuthChanged,
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
                        onOpenRouting = { navController.navigate(AppRoute.ROUTING) },
                        onOpenRoutingApps = { navController.navigate(AppRoute.ROUTING_APPS) },
                        onOpenRoutingSites = { navController.navigate(AppRoute.ROUTING_SITES) },
                        onOpenSmartStart = { navController.navigate(AppRoute.SMART_START) },
                        onOpenApplication = { navController.navigate(AppRoute.APPLICATION) },
                        onOpenHelp = { navController.navigate(AppRoute.HELP) },
                        onOpenAbout = { navController.navigate(AppRoute.ABOUT) },
                        onOpenExpert = { navController.navigate(AppRoute.EXPERT) },
                        onOpenDiagnostics = { navController.navigate(AppRoute.DIAGNOSTICS) },
                        onShowExpertSettingsChanged = viewModel::onShowExpertSettingsChanged,
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
                        onTrafficModeSelected = viewModel::onTrafficModeSelected,
                        onLatencyProbeMethodSelected = viewModel::onLatencyProbeMethodSelected,
                        onTunStackSelected = viewModel::onTunStackSelected,
                        onLocalProxyAuthEnabledChanged = viewModel::onLocalProxyAuthEnabledChanged,
                        onLocalProxyAuthChanged = viewModel::onLocalProxyAuthChanged,
                        onLocalProxyLanAccessChanged = viewModel::onLocalProxyLanAccessChanged,
                        onSocksSurfaceChanged = viewModel::onSocksSurfaceChanged,
                        onHttpSurfaceChanged = viewModel::onHttpSurfaceChanged,
                        onMixedSurfaceChanged = viewModel::onMixedSurfaceChanged,
                        onMtuChanged = viewModel::onMtuChanged,
                        onPreferIpv6Changed = viewModel::onPreferIpv6Changed,
                        onDomainStrategySelected = viewModel::onDomainStrategySelected,
                        onAutoRefreshSubscriptionsChanged = viewModel::onAutoRefreshSubscriptionsChanged,
                        onSubscriptionRefreshIntervalSelected = viewModel::onSubscriptionRefreshIntervalSelected,
                    )
                }
                composable(AppRoute.ROUTING) {
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    RoutingSettingsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onCreatePreset = viewModel::createPreset,
                        onUpdatePreset = viewModel::updatePreset,
                        onSetActivePreset = viewModel::setActivePreset,
                        onDeletePreset = viewModel::deletePreset,
                        onSaveRule = viewModel::saveRule,
                        onDeleteRule = viewModel::deleteRule,
                        onImportPresetText = viewModel::importPresetText,
                        onExportPresetDocument = viewModel::exportPresetDocument,
                        onAddCatalog = viewModel::addCatalog,
                        onRefreshCatalog = viewModel::refreshCatalog,
                        onDeleteCatalog = viewModel::deleteCatalog,
                        onLoadCatalogPreview = viewModel::loadCatalogPreview,
                        onImportPresetFromCatalog = viewModel::importPresetFromCatalog,
                    )
                }
                composable(AppRoute.ROUTING_APPS) {
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    RoutingAppsScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onPerAppRoutingModeSelected = viewModel::onPerAppRoutingModeSelected,
                        onOpenPicker = { navController.navigate(AppRoute.ROUTING_APPS_PICKER) },
                        onSelectedPackagesChanged = viewModel::onSelectedPackagesChanged,
                    )
                }
                composable(AppRoute.ROUTING_APPS_PICKER) {
                    LaunchedEffect(Unit) {
                        viewModel.ensureInstalledAppsLoaded()
                    }
                    val state by viewModel.routingRouteState.collectAsStateWithLifecycle()
                    AppPickerScreen(
                        state = state,
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                        onSaveSelection = {
                            viewModel.onSelectedPackagesChanged(it)
                            navController.navigateUp()
                        },
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
                        onIpInfoEndpointChanged = viewModel::onIpInfoEndpointChanged,
                    )
                }
                composable(AppRoute.HELP) {
                    HelpScreen(
                        snackbarHostState = snackbarHostState,
                        onNavigateUp = navController::navigateUp,
                    )
                }
                composable(AppRoute.ABOUT) {
                    val state by viewModel.aboutRouteState.collectAsStateWithLifecycle()
                    AboutScreen(
                        state = state,
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
                        onShowExpertSettingsChanged = viewModel::onShowExpertSettingsChanged,
                        onAcknowledgeUnsafeWarning = viewModel::acknowledgeUnsafeWarning,
                        onSniffChanged = viewModel::onSniffChanged,
                        onRouteOnlyChanged = viewModel::onRouteOnlyChanged,
                        onStrictRouteChanged = viewModel::onStrictRouteChanged,
                        onBypassLanChanged = viewModel::onBypassLanChanged,
                        onAllowPrivateOutboundHostsChanged = viewModel::onAllowPrivateOutboundHostsChanged,
                        onBlockScreenshotsChanged = viewModel::onBlockScreenshotsChanged,
                        onNetworkActivityLoggingChanged = viewModel::onNetworkActivityLoggingChanged,
                        onSmartStartReplayLoggingChanged = viewModel::onSmartStartReplayLoggingChanged,
                        onDiagnosticsRetentionSelected = viewModel::onDiagnosticsRetentionSelected,
                        onAllowHttpConfigImportsChanged = viewModel::onAllowHttpConfigImportsChanged,
                        onAllowInsecureTlsChanged = viewModel::onAllowInsecureTlsChanged,
                        onLocalProxyAuthEnabledChanged = viewModel::onLocalProxyAuthEnabledChanged,
                        onLocalProxyAuthChanged = viewModel::onLocalProxyAuthChanged,
                        onLocalProxyLanAccessChanged = viewModel::onLocalProxyLanAccessChanged,
                        onSocksSurfaceChanged = viewModel::onSocksSurfaceChanged,
                        onHttpSurfaceChanged = viewModel::onHttpSurfaceChanged,
                        onMixedSurfaceChanged = viewModel::onMixedSurfaceChanged,
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
                        onCreateDiagnosticsArchive = viewModel::createDiagnosticsArchive,
                        onShareDiagnosticsArchive = viewModel::exportDiagnostics,
                        onSupportBotHandleChanged = viewModel::onSupportBotHandleChanged,
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
                backgroundAlpha = 0.78f,
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

private fun NavDestination.appSection(): AppSection =
    when {
        route?.startsWith(AppRoute.SETTINGS) == true -> AppSection.SETTINGS
        else -> AppSection.DASHBOARD
    }

private fun NavDestination.rootSwipeSection(): AppSection? =
    when (route) {
        AppRoute.HOME -> AppSection.DASHBOARD
        AppRoute.SETTINGS -> AppSection.SETTINGS
        else -> null
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
        restoreState = false
        popUpTo(graph.findStartDestination().id) {
            saveState = false
        }
    }
}

private const val SECTION_SWIPE_THRESHOLD_FRACTION = 0.22f
