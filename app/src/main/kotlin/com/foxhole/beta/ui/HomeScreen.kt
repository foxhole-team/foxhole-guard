@file:Suppress("ImportOrdering")

package com.foxhole.beta.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationCity
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.DashboardCard
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.UiSettings
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import com.foxhole.beta.ui.BottomDockOverlayPadding
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.ScreenHorizontalPadding
import com.foxhole.beta.ui.ScreenSectionSpacing
import com.foxhole.beta.ui.ScreenVerticalPadding
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
fun HomeScreen(
    state: HomeRouteUiState,
    trafficMapStateFlow: StateFlow<TrafficMapUiState>,
    snackbarHostState: SnackbarHostState,
    onImportFromClipboard: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromQr: () -> Unit,
    onRefreshProfile: () -> Unit,
    onToggleConnection: () -> Unit,
    onAutoConnect: () -> Unit,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onKillSwitchChanged: (Boolean) -> Unit,
    onFirewallEnabledChanged: (Boolean) -> Unit,
    onPrivacyRouteModeSelected: (PrivacyRouteMode) -> Unit,
    onOpenPrivacyRoute: () -> Unit,
    onEnableDirectTorQuickStart: () -> Unit,
    onSelectActiveProtocolOptionRequested: (String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onConfirmDisableTorForUdpProtocol: (TorTransitionPrompt.DisableTorForUdpProtocol) -> Unit,
    onConfirmMoveTorIntoVpn: (TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive) -> Unit,
    onConfirmKeepTorOnDeviceAndStartVpn: (TorTransitionPrompt.StartTcpVpnWhileTorOnlyActive) -> Unit,
    onDismissTorTransitionPrompt: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefreshIpInfo: () -> Unit,
    onResetUsageTracking: () -> Unit,
    onTrafficUiVisibilityChanged: (Boolean) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onRenewTorIp: () -> Unit,
    onDashboardCardOrderChanged: (List<DashboardCard>) -> Unit,
) {
    DebugRecompositionCounter("HomeScreen")
    val context = LocalContext.current
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRefreshProfileDialog by rememberSaveable { mutableStateOf(false) }
    var smartRefreshConfirmationProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var smartStartFirstAnalysisProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var acceptedSmartStartFirstAnalysisProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstAnalysisProtocolMenuProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstAnalysisProtocolMenuStarted by rememberSaveable { mutableStateOf(false) }
    var selectedConnectionFeature by rememberSaveable { mutableStateOf<HomeConnectionFeature?>(null) }
    val shouldObserveWifiLanAddress =
        state.settings.expert.localSurfaces.allowLanAccess &&
            (
                selectedConnectionFeature == HomeConnectionFeature.LAN_PROXY ||
                    state.settings.traffic.mode == TrafficMode.PROXY
                )
    val wifiLanAddress by rememberWifiLanAddress(enabled = shouldObserveWifiLanAddress)
    val proxyModel =
        remember(
            state.settings.traffic.mode,
            state.settings.expert.localSurfaces,
            state.settings.expert.perAppRoutingMode,
            state.settings.expert.selectedPackages,
            wifiLanAddress,
        ) {
            resolveHomeDashboardProxyModel(
                state = state,
                wifiLanAddress = wifiLanAddress,
            )
        }
    val modeOption = proxyModel.modeOption
    val connectionFeatureIndicators =
        remember(
            state.settings.ui.showFirewallStatus,
            state.settings.expert.firewallEnabled,
            state.settings.expert.localSurfaces.allowLanAccess,
            state.settings.privacyRoute,
            state.settings.traffic.mode,
            state.activeProfile,
            state.connection.state,
            state.connection.profileId,
            state.connection.protocolHint,
        ) {
            homeConnectionFeatureIndicators(state)
        }
    val homeModeOptions =
        remember(
            state.settings.traffic.mode,
            state.settings.expert.perAppRoutingMode,
            state.settings.expert.selectedPackages,
        ) {
            buildList {
                add(HomeModeOption.TUNNEL)
                if (state.settings.homeSplitTunnelConfigured()) {
                    add(HomeModeOption.SPLIT)
                }
                add(HomeModeOption.PROXY)
            }
        }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val autoTone = foxholeSystemAwareAccentColor(fallback = MaterialTheme.colorScheme.primary)
    val torOperationTone = Color(0xFFE89B3C)
    val topStatusState = homeTopStatusState(state)
    val topStatusLoading = shouldShowHomeTopStatusLoading(state)
    val statusTone =
        if (state.torOperation.active) {
            torOperationTone
        } else if (state.autoConnect.running || state.protocolMetricsRefreshing || state.reconnectInProgress) {
            autoTone
        } else {
            homeStatusTone(state.connection.state)
        }
    val dashboardSecondaryActionBorderColor =
        autoTone.copy(alpha = if (darkTheme) 0.30f else 0.24f)
    val dashboardSecondaryActionColors =
        ButtonDefaults.outlinedButtonColors(
            contentColor = autoTone,
            containerColor = autoTone.copy(alpha = if (darkTheme) 0.07f else 0.05f),
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.48f),
            disabledContainerColor = Color.Transparent,
        )
    val dashboardSecondaryActionIconSize = 21.dp
    val connectionDurationText = rememberConnectionDurationText(state.connection)
    val dashboardProtocolModel =
        remember(
            state.activeProfile,
            state.connection,
            state.autoConnect,
            state.smartStartRememberedLatenciesByOptionId,
            state.protocolLatenciesByOptionId,
            state.protocolDownOptionIds,
            state.protocolLatencyUnavailableOptionIds,
            state.protocolServerPingsByOptionId,
            state.protocolServerPingUnavailableOptionIds,
            state.protocolTunnelPingsByOptionId,
            state.protocolTunnelPingUnavailableOptionIds,
            state.protocolMetricsRefreshing,
            state.protocolMetricsRefreshingOptionId,
            state.dashboardConnectionMetricsLoading,
            state.reconnectInProgress,
            state.reconnectRequired,
        ) {
            resolveHomeDashboardProtocolModel(state)
        }
    val dashboardProtocolLatencies = dashboardProtocolModel.latenciesByOptionId
    val dashboardDownProtocolIds = dashboardProtocolModel.downOptionIds
    val dashboardUnavailableProtocolIds = dashboardProtocolModel.latencyUnavailableOptionIds
    val dashboardShowSmartStartLatency = dashboardProtocolModel.showSmartStartLatency
    val dashboardLatencyPresentation = dashboardProtocolModel.latencyPresentation
    val dashboardSelectedLatencyMs = dashboardLatencyPresentation.latencyMs
    val dashboardSelectedLatencyDown = dashboardLatencyPresentation.isDown
    val dashboardSelectedLatencyUnavailable = dashboardLatencyPresentation.isUnavailable
    val dashboardLatencyPillVisible =
        dashboardSelectedLatencyMs != null ||
            dashboardSelectedLatencyDown ||
            dashboardSelectedLatencyUnavailable
    val dashboardProtocolPresentation = dashboardProtocolModel.presentation
    val dashboardConnectionDetailsReady = dashboardProtocolModel.connectionDetailsReady
    val dashboardConnectionMetricsLoading = dashboardProtocolModel.connectionMetricsLoading
    val dashboardLatencySkeletonVisible = dashboardConnectionMetricsLoading && dashboardLatencyPillVisible
    val protocolMetricsAnalysisState =
        remember(
            state.protocolMetricsRefreshingOptionId,
            dashboardProtocolPresentation,
        ) {
            homeProtocolMetricsAnalysisState(state, dashboardProtocolPresentation)
        }
    val smartStartDashboardControlsEnabled = isDashboardSmartStartControlsEnabled(state.settings)
    var activeReorderCard by rememberSaveable { mutableStateOf<DashboardCard?>(null) }
    var dashboardCardOrder by remember {
        mutableStateOf(normalizedDashboardCardOrder(state.settings.ui.dashboardCardOrder))
    }

    LaunchedEffect(state.settings.ui.dashboardCardOrder, activeReorderCard) {
        if (activeReorderCard == null) {
            dashboardCardOrder = normalizedDashboardCardOrder(state.settings.ui.dashboardCardOrder)
        }
    }

    fun moveDashboardCard(
        card: DashboardCard,
        steps: Int,
    ): Boolean {
        val nextOrder =
            reorderedDashboardCards(
                order = dashboardCardOrder,
                visibleOrder = visibleDashboardCardOrder(dashboardCardOrder, state.settings.ui),
                card = card,
                steps = steps,
            )
        if (nextOrder != null) {
            dashboardCardOrder = nextOrder
            onDashboardCardOrderChanged(nextOrder)
        }
        return nextOrder != null
    }
    var pinnedIpInfo by remember { mutableStateOf(state.ipInfo) }
    var keepPinnedNetworkInfo by remember { mutableStateOf(false) }
    val pinnedConnectionStates =
        remember {
            setOf(
                ConnectionState.CONNECTING,
                ConnectionState.RECONNECTING,
            )
        }
    val networkInfoPinnedForProtocolSearch = state.autoConnect.running || state.protocolMetricsRefreshing
    LaunchedEffect(networkInfoPinnedForProtocolSearch, state.ipInfo, state.connection.state) {
        when {
            networkInfoPinnedForProtocolSearch -> {
                if (!keepPinnedNetworkInfo) {
                    keepPinnedNetworkInfo = true
                }
            }
            state.connection.state in pinnedConnectionStates && state.ipInfo == null && pinnedIpInfo != null -> {
                keepPinnedNetworkInfo = true
            }
            state.ipInfo != null -> {
                pinnedIpInfo = state.ipInfo
                keepPinnedNetworkInfo = false
            }
            keepPinnedNetworkInfo && pinnedIpInfo == null -> {
                keepPinnedNetworkInfo = false
            }
        }
    }
    val selectedVisibleNetworkIpInfo = if (keepPinnedNetworkInfo) pinnedIpInfo else state.ipInfo
    val routeTransitionMayNeedInternetProbe =
        state.reconnectInProgress ||
            state.connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.RECONNECTING) ||
            state.autoConnect.running ||
            state.protocolMetricsRefreshing
    val shouldObserveDeviceInternet =
        selectedVisibleNetworkIpInfo == null ||
            state.ipInfoLoading ||
            routeTransitionMayNeedInternetProbe
    val deviceInternetAvailable by rememberDefaultInternetAvailability(enabled = shouldObserveDeviceInternet)
    val networkModel =
        remember(
            selectedVisibleNetworkIpInfo,
            deviceInternetAvailable,
            state.connection,
            state.torIpInfo,
            state.ipInfoLoading,
            state.ipInfoRefreshReason,
            state.dashboardConnectionMetricsLoading,
            state.profilesLoaded,
            state.reconnectInProgress,
            state.autoConnect.running,
            state.protocolMetricsRefreshing,
            state.activeProfile,
            state.settings.expert.firewallEnabled,
            state.settings.privacyRoute.enabled,
        ) {
            resolveHomeDashboardNetworkModel(
                state = state,
                visibleIpInfo = selectedVisibleNetworkIpInfo,
                deviceInternetAvailable = deviceInternetAvailable,
            )
        }
    val visibleNetworkIpInfo = networkModel.visibleIpInfo
    val showNetworkIpInfoLoading = networkModel.showIpInfoLoading
    val showNetworkConnectionDetailsLoading = networkModel.showConnectionDetailsLoading
    val showNetworkConnectionStatus = networkModel.showConnectionStatus
    val showNetworkRouteDetails =
        showNetworkConnectionStatus &&
            (
                showNetworkConnectionDetailsLoading ||
                    dashboardConnectionDetailsReady ||
                    state.connection.state == ConnectionState.CONNECTED
                )
    val networkInfoTitleRes = networkModel.titleRes
    val profileModel =
        remember(
            state.activeProfile,
            state.connection.state,
            state.connection.profileId,
        ) {
            resolveHomeDashboardProfileModel(state = state)
        }
    val isSmartDashboardProfile = profileModel.isSmartDashboardProfile
    val selectedProfileId = profileModel.selectedProfileId
    val localGuardProfileRuntimeActive = profileModel.localGuardActive
    val activeProfileId = selectedProfileId
    val firstAnalysisProtocolMenuActive = firstAnalysisProtocolMenuProfileId == activeProfileId
    val firstAnalysisProtocolMenuBusy = state.autoConnect.running || state.protocolMetricsRefreshing
    val firstAnalysisProtocolMenuForceExpanded =
        firstAnalysisProtocolMenuActive &&
            (!firstAnalysisProtocolMenuStarted || firstAnalysisProtocolMenuBusy)

    LaunchedEffect(
        activeProfileId,
        state.autoConnect.running,
        state.protocolMetricsRefreshing,
        firstAnalysisProtocolMenuProfileId,
        firstAnalysisProtocolMenuStarted,
    ) {
        if (firstAnalysisProtocolMenuProfileId != null && activeProfileId != firstAnalysisProtocolMenuProfileId) {
            firstAnalysisProtocolMenuProfileId = null
            firstAnalysisProtocolMenuStarted = false
            return@LaunchedEffect
        }
        if (firstAnalysisProtocolMenuActive && firstAnalysisProtocolMenuBusy) {
            firstAnalysisProtocolMenuStarted = true
        }
        val firstAnalysisProtocolMenuFinished =
            firstAnalysisProtocolMenuActive &&
                firstAnalysisProtocolMenuStarted &&
                !firstAnalysisProtocolMenuBusy
        if (firstAnalysisProtocolMenuFinished) {
            firstAnalysisProtocolMenuProfileId = null
            firstAnalysisProtocolMenuStarted = false
        }
    }
    fun requestSmartProfileMetricsRefresh(profileId: Long) {
        smartRefreshConfirmationProfileId = profileId
    }

    fun startAutoConnectAfterLocalDialogs() {
        onAutoConnect()
    }

    fun requestAutoConnect() {
        val profileId = activeProfileId
        if (
            profileId != null &&
            acceptedSmartStartFirstAnalysisProfileId != profileId &&
            shouldShowSmartStartFirstAnalysisInfo(state)
        ) {
            smartStartFirstAnalysisProfileId = profileId
            return
        }
        startAutoConnectAfterLocalDialogs()
    }

    val dashboardListState = rememberLazyListState()
    val topChromeScrimProgress = rememberFoxholeTopChromeScrimProgress(dashboardListState)
    val connectionHeaderScrolled by remember { derivedStateOf { topChromeScrimProgress() > 0.01f } }
    val initialDashboardStartupStage =
        remember {
            initialDashboardStartupStage(DashboardStartupCompositionWarmState.markEntered())
        }
    var dashboardStartupStage by rememberSaveable { mutableStateOf(initialDashboardStartupStage) }
    val trafficCardRuntimeVisible =
        state.settings.ui.trafficCardEnabled &&
            shouldComposeDashboardCardNow(
                card = DashboardCard.TRAFFIC,
                startupStage = dashboardStartupStage,
                activeReorderCard = activeReorderCard,
            )
    val trafficMapHeavyContentReady =
        state.settings.ui.trafficMapEnabled &&
            shouldComposeTrafficMapHeavyContent(
                startupStage = dashboardStartupStage,
                activeReorderCard = activeReorderCard,
            )
    val trafficMapState =
        if (trafficMapHeavyContentReady) {
            val collectedTrafficMapState by trafficMapStateFlow.collectAsStateWithLifecycle()
            collectedTrafficMapState
        } else {
            remember { TrafficMapUiState() }
        }
    val latestTrafficUiVisibilityChanged by rememberUpdatedState(onTrafficUiVisibilityChanged)

    LaunchedEffect(Unit) {
        while (dashboardStartupStage < DASHBOARD_STARTUP_STAGE_ALL) {
            delay(DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS)
            dashboardStartupStage += 1
        }
    }
    DisposableEffect(trafficCardRuntimeVisible) {
        if (trafficCardRuntimeVisible) {
            latestTrafficUiVisibilityChanged(true)
        }
        onDispose {
            if (trafficCardRuntimeVisible) {
                latestTrafficUiVisibilityChanged(false)
            }
        }
    }

    FoxholeScaffold(
        title = stringResource(R.string.app_name),
        snackbarHostState = snackbarHostState,
        bannerTopPadding = HomeDashboardBannerTopPadding,
        topChromeScrimProgress = topChromeScrimProgress,
        actions = {
            SettingsHelpAction(
                title = stringResource(R.string.help_quick_start_title),
                body = stringResource(R.string.help_quick_start_body),
                icon = Icons.Outlined.RocketLaunch,
            )
        },
    ) { padding ->
        val (safeStartPadding, safeEndPadding) = foxholeHorizontalSafePadding()
        LazyColumn(
            state = dashboardListState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .testTag("home_dashboard_list"),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalPadding + safeStartPadding,
                    top = padding.calculateTopPadding() + ScreenVerticalPadding,
                    end = ScreenHorizontalPadding + safeEndPadding,
                    bottom = BottomDockOverlayPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(ScreenSectionSpacing),
        ) {
            item {
                HomeConnectionHeader(
                    state = state,
                    topStatusState = topStatusState,
                    topStatusLoading = topStatusLoading,
                    statusTone = statusTone,
                    protocolMetricsAnalysisState = protocolMetricsAnalysisState,
                    modeOption = modeOption,
                    homeModeOptions = homeModeOptions,
                    connectionFeatureIndicators = connectionFeatureIndicators,
                    scrolled = connectionHeaderScrolled,
                    darkTheme = darkTheme,
                    onModeSelected = { selectedMode ->
                        applyHomeModeSelection(
                            mode = selectedMode,
                            onTrafficModeSelected = onTrafficModeSelected,
                            onPerAppRoutingModeSelected = onPerAppRoutingModeSelected,
                            selectedPackages = state.settings.expert.selectedPackages,
                            currentPerAppRoutingMode = state.settings.expert.perAppRoutingMode,
                        )
                    },
                    onConnectionFeatureClick = { feature -> selectedConnectionFeature = feature },
                )
            }
            dashboardCardOrder.forEach { card ->
                if (!shouldComposeDashboardCardNow(card, dashboardStartupStage, activeReorderCard)) {
                    return@forEach
                }
                when (card) {
                    DashboardCard.TRAFFIC_MAP -> {
                        if (state.settings.ui.trafficMapEnabled) {
                            item(key = DashboardCard.TRAFFIC_MAP) {
                                DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (
                                                    shouldAnimateDashboardCardPlacement(
                                                        activeReorderCard,
                                                        DashboardCard.TRAFFIC_MAP,
                                                    )
                                                ) {
                                                    Modifier.animateItem()
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.TRAFFIC_MAP),
                                    card = DashboardCard.TRAFFIC_MAP,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
                                    TrafficMapDashboardCard(
                                        state = trafficMapState,
                                        contentReady = trafficMapHeavyContentReady,
                                        legendLoading =
                                            shouldShowTrafficMapLegendLoading(
                                                connectionState = state.connection.state,
                                                appLoaded = state.profilesLoaded,
                                            ),
                                    )
                                }
                            }
                        }
                    }

                    DashboardCard.PROFILES -> {
                        item(key = DashboardCard.PROFILES) {
                            DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (
                                                    shouldAnimateDashboardCardPlacement(
                                                        activeReorderCard,
                                                        DashboardCard.PROFILES,
                                                    )
                                                ) {
                                                    Modifier.animateItem()
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.PROFILES),
                                card = DashboardCard.PROFILES,
                                activeCard = activeReorderCard,
                                onActiveCardChange = { activeReorderCard = it },
                                onMove = ::moveDashboardCard,
                            ) {
                                FoxholeCard(
                    onClick = onOpenProfiles,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("home_profiles_action"),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HomeCardHeader(
                            icon = Icons.Outlined.AccountTree,
                            title =
                                stringResource(
                                    if (localGuardProfileRuntimeActive) {
                                        R.string.selected_profile
                                    } else {
                                        R.string.vpn_profile
                                    },
                                ),
                            trailing = {
                                HomeHeaderActionButton(
                                    icon = Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    onClick = onOpenProfiles,
                                    tint = autoTone,
                                )
                            },
                        )
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(HomeDashboardProfileContentHeight),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (state.activeProfile == null && !state.profilesLoaded) {
                                HomeProfileLoadingBlock()
                            } else if (state.activeProfile == null) {
                                Text(
                                    text = stringResource(R.string.no_profiles),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    text = stringResource(R.string.no_profiles_import_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                InlineSmartProfileTitle(
                                    title = dashboardProfileTitle(state.activeProfile.name),
                                    isSmartProfile = isSmartDashboardProfile,
                                    showSmartBadge = false,
                                    showV2RayTunBadge =
                                        state.activeProfile.sourceType == ProfileSourceType.SUBSCRIPTION_URL &&
                                            !isSmartDashboardProfile,
                                    trailing = {
                                        when {
                                            dashboardLatencySkeletonVisible ->
                                                ProtocolLatencyLoadingPill(
                                                    compact = true,
                                                    showLabel = true,
                                                )
                                            dashboardSelectedLatencyMs != null ->
                                                ProtocolLatencyPill(
                                                    latencyMs = dashboardSelectedLatencyMs,
                                                    compact = true,
                                                    showLabel = true,
                                                )
                                            dashboardSelectedLatencyDown ->
                                                ProtocolLatencyPill(
                                                    compact = true,
                                                    isDown = true,
                                                    showLabel = true,
                                                )
                                            dashboardSelectedLatencyUnavailable ->
                                                ProtocolLatencyPill(
                                                    compact = true,
                                                    isUnavailable = true,
                                                    showLabel = true,
                                                )
                                            else -> Unit
                                        }
                                    },
                                )
                                ProtocolMetadataRow(
                                    protocol = dashboardProtocolPresentation.protocolHint,
                                    subscriptionExpiresAt = state.activeProfile.subscriptionExpiresAt,
                                    protocolOptions = dashboardProtocolPresentation.protocolOptions,
                                    selectedProtocolOptionId = dashboardProtocolPresentation.selectedProtocolOptionId,
                                    onProtocolOptionSelected = onSelectActiveProtocolOptionRequested,
                                    compact = true,
                                    animateSelection = true,
                                    latencyByOptionId = dashboardProtocolLatencies,
                                    downProtocolOptionIds = dashboardDownProtocolIds,
                                    latencyUnavailableOptionIds = dashboardUnavailableProtocolIds,
                                    recommendedProtocolOptionId = state.recommendedProtocolOptionId,
                                    recommendedProtocolOptionIds = state.recommendedProtocolOptionIds,
                                    favoriteProtocolOptionId = state.favoriteProtocolOptionId,
                                    selectorBorderColor = autoTone.copy(alpha = if (darkTheme) 0.30f else 0.24f),
                                    highlightSelectedOption = false,
                                    showInsecureTlsBadge = false,
                                    leadingContent =
                                        if (isSmartDashboardProfile && smartStartDashboardControlsEnabled) {
                                            {
                                                SmartProfileAutoConnectMenu(
                                                    profile = state.activeProfile,
                                                    excludedOptionIds = state.activeProfileExcludedOptionIds,
                                                    onUpdateExcludedOptionIds = onUpdateAutoConnectExcludedOptions,
                                                    latencyByOptionId = dashboardProtocolLatencies,
                                                    unavailableOptionIds = dashboardDownProtocolIds,
                                                    latencyUnavailableOptionIds = dashboardUnavailableProtocolIds,
                                                    serverPingByOptionId = state.protocolServerPingsByOptionId,
                                                    serverPingUnavailableOptionIds = state.protocolServerPingUnavailableOptionIds,
                                                    metricsUpdatedAtByOptionId = state.protocolMetricsUpdatedAtByOptionId,
                                                    metricsRefreshing = state.protocolMetricsRefreshing || state.autoConnect.running,
                                                    refreshingOptionId =
                                                        state.protocolMetricsRefreshingOptionId
                                                            ?: state.autoConnect.currentOptionId
                                                                .takeIf { state.protocolMetricsRefreshing || state.autoConnect.running },
                                                    recommendedOptionId = state.recommendedProtocolOptionId,
                                                    recommendedOptionIds = state.recommendedProtocolOptionIds,
                                                    favoriteOptionId = state.favoriteProtocolOptionId,
                                                    activeOptionId = dashboardProtocolPresentation.selectedProtocolOptionId,
                                                    onSelectOption = onSelectActiveProtocolOptionRequested,
                                                    onRefreshMetrics = { requestSmartProfileMetricsRefresh(state.activeProfile.id) },
                                                    onCancelRefreshMetrics = onCancelSmartProfileMetricsRefresh,
                                                    showLatency = dashboardShowSmartStartLatency,
                                                    compact = true,
                                                    actionIconSize = 18.dp,
                                                    showMetricsTable = false,
                                                    showStatusHeader = true,
                                                    latencyProbeMethod = state.settings.connection.latencyProbeMethod,
                                                    serverPingLabelRes = R.string.smart_profile_menu_server_ping_column,
                                                    forceExpanded = firstAnalysisProtocolMenuForceExpanded,
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                )
                            }
                        }
                    }
                                }
                            }
                        }
                    }

                    DashboardCard.ACTIONS -> {
                        item(key = DashboardCard.ACTIONS) {
                            val importFromClipboardTitle = stringResource(R.string.import_from_clipboard)
                            val importFromFileTitle = stringResource(R.string.import_from_file)
                            val importFromQrTitle = stringResource(R.string.scan_qr_code)
                            val importDropdownMenuWidth =
                                rememberImportDropdownMenuWidth(
                                    titles =
                                        listOf(
                                            importFromClipboardTitle,
                                            importFromFileTitle,
                                            importFromQrTitle,
                                        ),
                                )
                            DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (
                                                    shouldAnimateDashboardCardPlacement(
                                                        activeReorderCard,
                                                        DashboardCard.ACTIONS,
                                                    )
                                                ) {
                                                    Modifier.animateItem()
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.ACTIONS),
                                card = DashboardCard.ACTIONS,
                                activeCard = activeReorderCard,
                                onActiveCardChange = { activeReorderCard = it },
                                onMove = ::moveDashboardCard,
                            ) {
                                FoxholeCard {
                    HomeConnectionActions(
                        state = state,
                        onToggleConnection = onToggleConnection,
                        onAutoConnect = ::requestAutoConnect,
                        smartStartControlsEnabled = smartStartDashboardControlsEnabled,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            OutlinedButton(
                                onClick = { importMenuExpanded = true },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(HomePrimaryActionHeight)
                                        .testTag("home_import_action"),
                                border = BorderStroke(1.dp, dashboardSecondaryActionBorderColor),
                                colors = dashboardSecondaryActionColors,
                            ) {
                                Icon(
                                    Icons.Outlined.FileUpload,
                                    contentDescription = null,
                                    modifier = Modifier.size(dashboardSecondaryActionIconSize),
                                    tint = autoTone,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_label),
                                    color = autoTone,
                                )
                            }
                            FoxholeDropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
                                popupGap = 0.dp,
                                modifier = Modifier.width(importDropdownMenuWidth),
                            ) {
                                FoxholeDropdownItem(
                                    modifier = Modifier.testTag("home_import_from_clipboard_action"),
                                    leadingContent = {
                                        Icon(
                                            Icons.Outlined.ContentPaste,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        importMenuExpanded = false
                                        onImportFromClipboard()
                                    },
                                ) {
                                    ImportDropdownItemText(
                                        title = importFromClipboardTitle,
                                        summary = stringResource(R.string.import_from_clipboard_summary),
                                    )
                                }
                                FoxholeDropdownItem(
                                    modifier = Modifier.testTag("home_import_from_file_action"),
                                    leadingContent = {
                                        Icon(
                                            Icons.Outlined.FileUpload,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        importMenuExpanded = false
                                        onImportFromFile()
                                    },
                                ) {
                                    ImportDropdownItemText(
                                        title = importFromFileTitle,
                                        summary = stringResource(R.string.import_from_file_summary),
                                    )
                                }
                                FoxholeDropdownItem(
                                    modifier = Modifier.testTag("home_import_from_qr_action"),
                                    leadingContent = {
                                        Icon(
                                            Icons.Outlined.QrCodeScanner,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    onClick = {
                                        importMenuExpanded = false
                                        onImportFromQr()
                                    },
                                ) {
                                    ImportDropdownItemText(
                                        title = importFromQrTitle,
                                        summary = stringResource(R.string.scan_qr_code_summary),
                                    )
                                }
                            }
                        }
                        OutlinedButton(
                            onClick = { showRefreshProfileDialog = true },
                            enabled = state.activeProfile?.sourceType == ProfileSourceType.SUBSCRIPTION_URL,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .height(HomePrimaryActionHeight)
                                    .testTag("home_refresh_action"),
                            border = BorderStroke(1.dp, dashboardSecondaryActionBorderColor),
                            colors = dashboardSecondaryActionColors,
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(dashboardSecondaryActionIconSize),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                    }
                                }
                            }
                        }
                    }

                    DashboardCard.NETWORK -> {
                        if (state.settings.ui.networkCardEnabled) {
                            item(key = DashboardCard.NETWORK) {
                                DebugRecompositionCounter("HomeNetworkCard")
                                DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (
                                                    shouldAnimateDashboardCardPlacement(
                                                        activeReorderCard,
                                                        DashboardCard.NETWORK,
                                                    )
                                                ) {
                                                    Modifier.animateItem()
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.NETWORK),
                                    card = DashboardCard.NETWORK,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
                                    FoxholeCard(modifier = Modifier.testTag("home_network_card")) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HomeCardHeader(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.home_network_title),
                            trailing = {
                                HomeHeaderActionButton(
                                    icon = Icons.Outlined.Refresh,
                                    contentDescription = stringResource(R.string.refresh_ip_info),
                                    onClick = onRefreshIpInfo,
                                    enabled = !state.autoConnect.running,
                                    modifier = Modifier.size(32.dp).testTag("home_refresh_ip_icon"),
                                    tint = autoTone,
                                )
                            },
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().height(HomeNetworkContentHeight),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                val networkIpInfo = visibleNetworkIpInfo
                                if (
                                    shouldShowHomeNetworkFullLoading(
                                        visibleIpInfo = networkIpInfo,
                                        showIpInfoLoading = showNetworkIpInfoLoading,
                                    )
                                ) {
                                    HomeNetworkLoadingBlock(
                                        title = stringResource(networkInfoTitleRes),
                                        labels =
                                            listOf(
                                                stringResource(R.string.home_network_country_label),
                                                stringResource(R.string.home_network_city_label),
                                                stringResource(R.string.home_network_ip_label),
                                                stringResource(R.string.home_network_provider_label),
                                            ),
                                        icons =
                                            listOf(
                                                Icons.Outlined.Language,
                                                Icons.Outlined.LocationCity,
                                                Icons.Outlined.Public,
                                                Icons.Outlined.Business,
                                            ),
                                        modifier =
                                            Modifier
                                                .weight(if (showNetworkRouteDetails) 1f else 2f)
                                                .testTag("home_network_loading"),
                                    )
                                } else {
                                    val rowValueLoading =
                                        networkModel.showRefreshProgress ||
                                            showNetworkIpInfoLoading
                                    val geoRowsLoading =
                                        rememberHomeNetworkGeoRowsLoading(
                                            networkIpInfo = networkIpInfo,
                                            refreshLoading = rowValueLoading,
                                        ) || networkModel.showGeoRowsLoading
                                    val detailLoadingPolicy =
                                        homeNetworkDetailLoadingPolicy(
                                            refreshLoading = rowValueLoading,
                                            geoRowsLoading = geoRowsLoading,
                                        )
                                    val countryLine =
                                        networkIpInfo?.let { info ->
                                            dashboardCountryLineForGeoState(
                                                ipInfo = info,
                                                geoRowsLoading = geoRowsLoading,
                                            )
                                        }
                                    val cityLine = networkIpInfo?.let(::buildCityLineOrNull)
                                    val countryValue =
                                        homeNetworkGeoRowDetailValue(
                                            value = countryLine,
                                            refreshLoading = rowValueLoading,
                                            geoRowsLoading = geoRowsLoading,
                                        )
                                    val cityValue =
                                        homeNetworkGeoRowDetailValue(
                                            value = cityLine,
                                            refreshLoading = rowValueLoading,
                                            geoRowsLoading = geoRowsLoading,
                                        )
                                    val ipValue =
                                        homeNetworkDetailValue(
                                            value = networkIpInfo?.let(::primaryVisibleIpOrNull),
                                            loading = detailLoadingPolicy.ip,
                                        )
                                    val providerValue =
                                        homeNetworkDetailValue(
                                            value = networkIpInfo?.let(::providerLineOrNull),
                                            loading = detailLoadingPolicy.provider,
                                        )
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        HomeNetworkColumnTitle(stringResource(networkInfoTitleRes))
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Language,
                                            label = stringResource(R.string.home_network_country_label),
                                            value = countryValue.text,
                                            valueLoading = countryValue.loading,
                                            modifier = Modifier.testTag("home_network_country"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.LocationCity,
                                            label = stringResource(R.string.home_network_city_label),
                                            value = cityValue.text,
                                            valueLoading = cityValue.loading,
                                            modifier = Modifier.testTag("home_network_city"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Public,
                                            label = stringResource(R.string.home_network_ip_label),
                                            value = ipValue.text,
                                            valueLoading = ipValue.loading,
                                            modifier = Modifier.testTag("home_network_primary_ip"),
                                            valueMonospace = ipValue.text != "-",
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Business,
                                            label = stringResource(R.string.home_network_provider_label),
                                            value = providerValue.text,
                                            valueLoading = providerValue.loading,
                                            modifier = Modifier.testTag("home_network_provider"),
                                        )
                                    }
                                }
                                if (showNetworkRouteDetails) {
                                    HomeNetworkVerticalDivider()
                                    if (showNetworkConnectionDetailsLoading) {
                                        HomeConnectionStatusLoadingBlock(
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .testTag("home_connection_status_loading"),
                                        )
                                    } else {
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            val connectionMetricsAvailable = state.connection.state == ConnectionState.CONNECTED
                                            val serverPingText =
                                                when {
                                                    !connectionMetricsAvailable -> stringResource(R.string.smart_start_protocol_status_no_data)
                                                    dashboardProtocolModel.selectedServerPingMs != null ->
                                                        latencyPillValueText(
                                                            dashboardProtocolModel.selectedServerPingMs,
                                                        )
                                                    dashboardProtocolModel.selectedServerPingUnavailable ->
                                                        stringResource(R.string.latency_pill_unavailable)
                                                    else ->
                                                        stringResource(R.string.smart_profile_metric_unavailable)
                                                }
                                            val remoteDnsServer =
                                                networkIpInfo?.remoteDnsServers?.firstOrNull { server -> server.isNotBlank() }
                                            val localDnsServer =
                                                networkIpInfo?.localDnsServers?.firstOrNull { server -> server.isNotBlank() }
                                            val dnsStatusText =
                                                when {
                                                    !connectionMetricsAvailable ->
                                                        stringResource(R.string.smart_start_protocol_status_no_data)
                                                    remoteDnsServer != null || localDnsServer != null || networkIpInfo != null ->
                                                        dashboardDnsModeLine(
                                                            ipInfo = networkIpInfo,
                                                            secureMode = state.settings.dns.secureMode,
                                                        )
                                                    else ->
                                                        dashboardDnsModeLine(
                                                            ipInfo = null,
                                                            secureMode = state.settings.dns.secureMode,
                                                        )
                                                }
                                            val transportTypeText = dashboardTransportTypeLabel(dashboardProtocolPresentation.protocolHint)
                                            HomeNetworkColumnTitle(stringResource(R.string.home_network_profile_info_title))
                                            HomeNetworkDetailLine(
                                                icon = Icons.Outlined.Speed,
                                                label = stringResource(R.string.home_network_server_ping_label),
                                                value = serverPingText,
                                                valueMonospace = connectionMetricsAvailable && dashboardProtocolModel.selectedServerPingMs != null,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                icon = Icons.Outlined.Dns,
                                                label = stringResource(R.string.home_network_dns_label),
                                                value = dnsStatusText,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                icon = Icons.Outlined.SwapVert,
                                                label = stringResource(R.string.home_network_transport_type_label),
                                                value = transportTypeText,
                                                valueMonospace = transportTypeText != "-",
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                icon = Icons.Outlined.AccessTime,
                                                label = stringResource(R.string.home_network_connect_time_label),
                                                value = connectionDurationText ?: "-",
                                                valueMonospace = connectionDurationText != null,
                                                modifier = Modifier.testTag("home_connection_duration"),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                                    }
                                }
                            }
                        }
                    }

                    DashboardCard.TRAFFIC -> {
                        if (state.settings.ui.trafficCardEnabled) {
                            item(key = DashboardCard.TRAFFIC) {
                                DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (
                                                    shouldAnimateDashboardCardPlacement(
                                                        activeReorderCard,
                                                        DashboardCard.TRAFFIC,
                                                    )
                                                ) {
                                                    Modifier.animateItem()
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.TRAFFIC),
                                    card = DashboardCard.TRAFFIC,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
                                    val trafficModel =
                                        resolveHomeDashboardTrafficModel(
                                            state,
                                            System.currentTimeMillis(),
                                        )
                                    val trafficLoading = false
                                    val totalTrafficText =
                                        buildAnnotatedString {
                                            val periodBytes = trafficModel.selectedProtocolTotalBytes ?: trafficModel.totalBytes
                                            append(stringResource(R.string.home_total_traffic_title))
                                            append(" ")
                                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                                append(
                                                    stringResource(
                                                        R.string.home_total_traffic_days,
                                                        trafficModel.totalDays,
                                                    ),
                                                )
                                            }
                                            append(" ")
                                            append(formatBytes(context, periodBytes))
                                        }
                                    FoxholeCard {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            HomeCardHeader(
                                                icon = Icons.Outlined.SwapVert,
                                                title = stringResource(R.string.home_traffic_title),
                                                titleContent = {
                                                    Text(
                                                        text = stringResource(R.string.home_traffic_title),
                                                        style = MaterialTheme.typography.titleMedium,
                                                        fontWeight = FontWeight.SemiBold,
                                                        maxLines = 1,
                                                    )
                                                    Text(
                                                        text = totalTrafficText,
                                                        modifier = Modifier.weight(1f),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontWeight = FontWeight.SemiBold,
                                                        textAlign = TextAlign.End,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                trailing = {
                                                    HomeHeaderActionButton(
                                                        icon = Icons.Outlined.DeleteSweep,
                                                        contentDescription =
                                                            stringResource(R.string.reset_usage_tracking),
                                                        onClick = onResetUsageTracking,
                                                        modifier = Modifier.testTag("home_reset_usage_button"),
                                                        tint = autoTone,
                                                    )
                                                },
                                            )
                                            HorizontalDivider(
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                    val trafficLabelTint = MaterialTheme.colorScheme.primary
                                                    val inactiveTrafficIconTint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    val incomingTrafficTint =
                                                        if (trafficModel.hasIncomingTraffic) {
                                                            FoxholePositiveAccent
                                                        } else {
                                                            inactiveTrafficIconTint
                                                        }
                                                    val outgoingTrafficTint =
                                                        if (trafficModel.hasOutgoingTraffic) {
                                                            Color(0xFF2F80ED)
                                                        } else {
                                                            inactiveTrafficIconTint
                                                        }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(
                                                            text = stringResource(R.string.home_session_traffic_title),
                                                            modifier = Modifier.weight(1f),
                                                            style = MaterialTheme.typography.labelMedium,
                                                            fontWeight = FontWeight.SemiBold,
                                                        )
                                                    }
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    ) {
                                                        TrafficStatBlock(
                                                            modifier = Modifier.weight(1f),
                                                            icon = Icons.Outlined.ArrowDownward,
                                                            iconTint = incomingTrafficTint,
                                                            labelColor = trafficLabelTint,
                                                            label = stringResource(R.string.home_received_label),
                                                            value =
                                                                formatBytes(
                                                                    context,
                                                                    state.traffic.rxTotalBytes,
                                                                ),
                                                            secondary =
                                                                formatRate(
                                                                    context,
                                                                    state.traffic.rxBytesPerSec,
                                                                ),
                                                            valueTag = "home_traffic_rx_value",
                                                            secondaryTag = "home_traffic_rx_rate",
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            loading = trafficLoading,
                                                        )
                                                        TrafficStatBlock(
                                                            modifier = Modifier.weight(1f),
                                                            icon = Icons.Outlined.ArrowUpward,
                                                            iconTint = outgoingTrafficTint,
                                                            labelColor = trafficLabelTint,
                                                            label = stringResource(R.string.home_sent_label),
                                                            value =
                                                                formatBytes(
                                                                    context,
                                                                    state.traffic.txTotalBytes,
                                                                ),
                                                            secondary =
                                                                formatRate(
                                                                    context,
                                                                    state.traffic.txBytesPerSec,
                                                                ),
                                                            valueTag = "home_traffic_tx_value",
                                                            secondaryTag = "home_traffic_tx_rate",
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            loading = trafficLoading,
                                                        )
                                                        TrafficStatBlock(
                                                            modifier = Modifier.weight(1f),
                                                            headerSpacing = 1.dp,
                                                            leadingContent = {
                                                                Text(
                                                                    text =
                                                                        buildAnnotatedString {
                                                                            withStyle(SpanStyle(color = incomingTrafficTint)) { append("↓") }
                                                                            withStyle(SpanStyle(color = outgoingTrafficTint)) { append("↑") }
                                                                        },
                                                                    style = MaterialTheme.typography.labelSmall,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    maxLines = 1,
                                                                )
                                                            },
                                                            leadingContentSpacing = 0.dp,
                                                            labelColor = trafficLabelTint,
                                                            label = stringResource(R.string.home_total_label),
                                                            value = formatBytes(context, state.traffic.rxTotalBytes + state.traffic.txTotalBytes),
                                                            secondary =
                                                                formatRate(
                                                                    context,
                                                                    state.traffic.rxBytesPerSec + state.traffic.txBytesPerSec,
                                                                ),
                                                            valueTag = "home_traffic_total_value",
                                                            secondaryTag = "home_traffic_total_rate",
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            loading = trafficLoading,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showRefreshProfileDialog) {
        ProfileRefreshConfirmDialog(
            onDismiss = { showRefreshProfileDialog = false },
            onConfirm = {
                showRefreshProfileDialog = false
                onRefreshProfile()
            },
        )
    }

    smartRefreshConfirmationProfileId?.let { profileId ->
        ConfirmDialog(
            title = stringResource(R.string.smart_profile_metrics_refresh_confirm_title),
            body = stringResource(R.string.smart_profile_metrics_refresh_confirm_body),
            confirmLabel = stringResource(R.string.refresh),
            icon = Icons.Outlined.Refresh,
            dismissLabel = stringResource(R.string.close),
            onDismiss = { smartRefreshConfirmationProfileId = null },
            onConfirm = {
                smartRefreshConfirmationProfileId = null
                onRefreshSmartProfileMetrics(profileId)
            },
        )
    }

    smartStartFirstAnalysisProfileId?.let { profileId ->
        ConfirmDialog(
            title = stringResource(R.string.smart_start_first_analysis_title),
            body = stringResource(R.string.smart_start_first_analysis_body),
            confirmLabel = stringResource(R.string.smart_start_first_analysis_continue),
            icon = Icons.Outlined.Speed,
            dismissLabel = stringResource(R.string.close),
            onDismiss = { smartStartFirstAnalysisProfileId = null },
            onConfirm = {
                smartStartFirstAnalysisProfileId = null
                acceptedSmartStartFirstAnalysisProfileId = profileId
                firstAnalysisProtocolMenuProfileId = profileId
                firstAnalysisProtocolMenuStarted = false
                startAutoConnectAfterLocalDialogs()
            },
        )
    }

    selectedConnectionFeature?.let { feature ->
        HomeConnectionFeatureDialog(
            feature = feature,
            state = state,
            wifiLanAddress = wifiLanAddress,
            onDismiss = { selectedConnectionFeature = null },
            onKillSwitchChanged = onKillSwitchChanged,
            onFirewallEnabledChanged = onFirewallEnabledChanged,
            onPrivacyRouteModeSelected = onPrivacyRouteModeSelected,
            onOpenPrivacyRoute = onOpenPrivacyRoute,
            onEnableDirectTorQuickStart = onEnableDirectTorQuickStart,
            onLocalProxyLanAccessChanged = onLocalProxyLanAccessChanged,
            onRenewTorIp = onRenewTorIp,
            onRestart = onToggleConnection,
        )
    }

    state.torTransitionPrompt?.let { prompt ->
        TorTransitionPromptDialog(
            prompt = prompt,
            onDismiss = onDismissTorTransitionPrompt,
            onConfirmDisableTorForUdpProtocol = onConfirmDisableTorForUdpProtocol,
            onConfirmMoveTorIntoVpn = onConfirmMoveTorIntoVpn,
            onConfirmKeepTorOnDeviceAndStartVpn = onConfirmKeepTorOnDeviceAndStartVpn,
        )
    }
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun HomeConnectionHeader(
    state: HomeRouteUiState,
    topStatusState: ConnectionState,
    topStatusLoading: Boolean,
    statusTone: Color,
    protocolMetricsAnalysisState: AutoConnectUiState,
    modeOption: HomeModeOption,
    homeModeOptions: List<HomeModeOption>,
    connectionFeatureIndicators: List<HomeConnectionFeatureIndicator>,
    scrolled: Boolean,
    darkTheme: Boolean,
    onModeSelected: (HomeModeOption) -> Unit,
    onConnectionFeatureClick: (HomeConnectionFeature) -> Unit,
) {
    val headerContainer =
        MaterialTheme.colorScheme.surface.copy(
            alpha =
                when {
                    scrolled -> 0.92f
                    darkTheme -> 0.54f
                    else -> 0.68f
                },
        )
    val headerBorder =
        BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (scrolled) 0.34f else 0.24f),
        )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = headerContainer,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = headerBorder,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HomeTopStatusInnerSurfaceMinHeight)
                        .padding(
                            horizontal = HomeTopStatusInnerHorizontalPadding,
                            vertical = HomeTopStatusInnerVerticalPadding,
                        ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier.size(30.dp),
                        shape = MaterialTheme.shapes.large,
                        color = statusTone.copy(alpha = 0.13f),
                    ) {
                        Spacer(modifier = Modifier.fillMaxSize())
                    }
                    Image(
                        painter = painterResource(R.drawable.foxhole_logo),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(34.dp),
                    )
                }
                Column(
                    modifier =
                        Modifier
                            .padding(start = 2.dp)
                            .weight(1f)
                            .foxholeAnimateContentSize(),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (topStatusLoading) {
                                HomeTopStatusLoadingBlock(
                                    accentColor = statusTone,
                                    modifier = Modifier.weight(1f),
                                )
                            } else if (state.autoConnect.running || state.protocolMetricsRefreshing) {
                                HomeAutoConnectStatusLine(
                                    state =
                                        if (state.autoConnect.running) {
                                            state.autoConnect
                                        } else {
                                            protocolMetricsAnalysisState
                                        },
                                    modifier = Modifier.weight(1f),
                                    textStyle = MaterialTheme.typography.titleMedium,
                                )
                            } else {
                                HomeStatusBadge(
                                    state = topStatusState,
                                    label = homeStatusLabel(state, topStatusState),
                                    textStyle = MaterialTheme.typography.titleMedium,
                                    accentColor = statusTone,
                                    loading =
                                        state.torOperation.active ||
                                            topStatusState in setOf(
                                                ConnectionState.CONNECTING,
                                                ConnectionState.RECONNECTING,
                                            ),
                                    smartMarker =
                                        state.connection.isSmartStartConnection &&
                                            topStatusState in setOf(
                                                ConnectionState.CONNECTED,
                                                ConnectionState.CONNECTING,
                                                ConnectionState.RECONNECTING,
                                            ),
                                )
                            }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            HomeModeDropdown(
                                selected = modeOption,
                                values = homeModeOptions,
                                onSelect = onModeSelected,
                            )
                        }
                    }
                }
            }
            HomeConnectionFeatureIndicators(
                indicators = connectionFeatureIndicators,
                onIndicatorClick = onConnectionFeatureClick,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun DashboardCardDragContainer(
    modifier: Modifier = Modifier,
    card: DashboardCard,
    activeCard: DashboardCard?,
    onActiveCardChange: (DashboardCard?) -> Unit,
    onMove: (DashboardCard, Int) -> Boolean,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    DebugRecompositionCounter("DashboardCard:${card.name}")
    val fallbackMoveDistancePx = with(density) { DashboardCardReorderFallbackMoveDistance.toPx() }
    var dragOffset by remember(card) { mutableFloatStateOf(0f) }
    var blockedReorderDirection by remember(card) { mutableStateOf(0) }
    var cardHeightPx by remember(card) { mutableFloatStateOf(0f) }
    val active = activeCard == card
    val dragShape = MaterialTheme.shapes.large
    val moveDistancePx = cardHeightPx.takeIf { it > 0f } ?: fallbackMoveDistancePx
    val moveThresholdPx =
        (moveDistancePx * DASHBOARD_CARD_REORDER_THRESHOLD_FRACTION)
            .coerceAtLeast(fallbackMoveDistancePx)
    val displayDragOffset =
        dashboardCardDisplayDragOffset(
            offset = dragOffset,
            blockedDirection = blockedReorderDirection,
            thresholdPx = moveThresholdPx,
        )

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardHeightPx = coordinates.size.height.toFloat()
                }
                .graphicsLayer {
                    translationY = if (active) displayDragOffset else 0f
                    val scale = if (active) 1.018f else 1f
                    scaleX = scale
                    scaleY = scale
                    shape = dragShape
                    clip = active
                    shadowElevation = if (active) 8f else 0f
                }
                .zIndex(if (active) DASHBOARD_CARD_ACTIVE_Z_INDEX else 0f)
                .pointerInput(card, moveDistancePx, moveThresholdPx) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = {
                            dragOffset = 0f
                            blockedReorderDirection = 0
                            onActiveCardChange(card)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragCancel = {
                            dragOffset = 0f
                            blockedReorderDirection = 0
                            onActiveCardChange(null)
                        },
                        onDragEnd = {
                            dragOffset = 0f
                            blockedReorderDirection = 0
                            onActiveCardChange(null)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val nextState =
                                updateDashboardCardDragState(
                                    offset = dragOffset,
                                    blockedDirection = blockedReorderDirection,
                                    dragDelta = dragAmount.y,
                                    moveThresholdPx = moveThresholdPx,
                                    moveDistancePx = moveDistancePx,
                                    onMove = { direction -> onMove(card, direction) },
                                    onMoved = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                )
                            dragOffset = nextState.offset
                            blockedReorderDirection = nextState.blockedDirection
                        },
                    )
                },
    ) {
        content()
    }
}

private data class DashboardCardDragState(
    val offset: Float,
    val blockedDirection: Int,
)

private fun updateDashboardCardDragState(
    offset: Float,
    blockedDirection: Int,
    dragDelta: Float,
    moveThresholdPx: Float,
    moveDistancePx: Float,
    onMove: (Int) -> Boolean,
    onMoved: () -> Unit,
): DashboardCardDragState {
    var nextOffset = offset + dragDelta
    var nextBlockedDirection =
        when {
            blockedDirection > 0 && nextOffset < moveThresholdPx -> 0
            blockedDirection < 0 && nextOffset > -moveThresholdPx -> 0
            else -> blockedDirection
        }
    while (nextBlockedDirection != 1 && nextOffset > moveThresholdPx) {
        if (!onMove(1)) {
            nextBlockedDirection = 1
            break
        }
        nextOffset -= moveDistancePx
        onMoved()
    }
    while (nextBlockedDirection != -1 && nextOffset < -moveThresholdPx) {
        if (!onMove(-1)) {
            nextBlockedDirection = -1
            break
        }
        nextOffset += moveDistancePx
        onMoved()
    }
    return DashboardCardDragState(offset = nextOffset, blockedDirection = nextBlockedDirection)
}

private fun dashboardCardDisplayDragOffset(
    offset: Float,
    blockedDirection: Int,
    thresholdPx: Float,
): Float {
    val displayOffset =
        if (blockedDirection == 0 || thresholdPx <= 0f) {
            offset
        } else {
            val direction = blockedDirection.coerceIn(-1, 1).toFloat()
            val directedOffset = offset * direction
            if (directedOffset <= thresholdPx) {
                offset
            } else {
                val overflow = directedOffset - thresholdPx
                direction * (thresholdPx + overflow * DASHBOARD_CARD_EDGE_RESISTANCE_FRACTION)
            }
        }
    return displayOffset
}

@Composable
private fun rememberImportDropdownMenuWidth(titles: List<String>): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val titleWidth =
        remember(titles, titleStyle, density, textMeasurer) {
            val maxTitleWidthPx =
                titles.maxOf { title ->
                    textMeasurer.measure(
                        text = AnnotatedString(title),
                        style = titleStyle,
                        maxLines = 1,
                    ).size.width
                }
            with(density) { maxTitleWidthPx.toDp() }
        }
    return (titleWidth + ImportMenuWidthChrome).coerceIn(ImportMenuMinWidth, ImportMenuMaxWidth)
}

@Composable
private fun ImportDropdownItemText(
    title: String,
    summary: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun normalizedDashboardCardOrder(order: List<DashboardCard>): List<DashboardCard> =
    (order + DashboardCard.entries)
        .distinct()
        .filter { card -> card in DashboardCard.entries }

private fun reorderedDashboardCards(
    order: List<DashboardCard>,
    visibleOrder: List<DashboardCard>,
    card: DashboardCard,
    steps: Int,
): List<DashboardCard>? {
    val fromVisible = visibleOrder.indexOf(card)
    val toVisible = fromVisible + steps
    val target = visibleOrder.getOrNull(toVisible)
    return when {
        steps == 0 || fromVisible < 0 || target == null || target == card -> null
        else ->
            order.toMutableList().apply {
                remove(card)
                val targetIndex = indexOf(target)
                val insertionIndex = if (steps > 0) targetIndex + 1 else targetIndex
                add(insertionIndex.coerceIn(0, size), card)
            }
    }
}

private fun visibleDashboardCardOrder(
    order: List<DashboardCard>,
    uiSettings: UiSettings,
): List<DashboardCard> =
    order.filter { card ->
        when (card) {
            DashboardCard.TRAFFIC_MAP -> uiSettings.trafficMapEnabled
            DashboardCard.PROFILES -> true
            DashboardCard.ACTIONS -> true
            DashboardCard.NETWORK -> uiSettings.networkCardEnabled
            DashboardCard.TRAFFIC -> uiSettings.trafficCardEnabled
        }
    }

private fun Modifier.dashboardCardZIndex(
    activeCard: DashboardCard?,
    card: DashboardCard,
): Modifier =
    zIndex(
        if (activeCard == card) DASHBOARD_CARD_ACTIVE_Z_INDEX else 0f,
    )

internal fun shouldAnimateDashboardCardPlacement(
    activeCard: DashboardCard?,
    card: DashboardCard,
): Boolean = activeCard != null && activeCard != card

internal fun shouldComposeDashboardCardNow(
    card: DashboardCard,
    startupStage: Int,
    activeReorderCard: DashboardCard?,
): Boolean =
    startupStage >= dashboardCardStartupStage(card) ||
        activeReorderCard != null ||
        startupStage >= DASHBOARD_STARTUP_STAGE_ALL

internal fun shouldComposeTrafficMapHeavyContent(
    startupStage: Int,
    activeReorderCard: DashboardCard?,
): Boolean =
    activeReorderCard != null ||
        startupStage >= DASHBOARD_STARTUP_STAGE_WARM_RETURN ||
        startupStage >= DASHBOARD_STARTUP_STAGE_ALL

internal fun initialDashboardStartupStage(dashboardAlreadyWarm: Boolean): Int =
    if (dashboardAlreadyWarm) DASHBOARD_STARTUP_STAGE_WARM_RETURN else DASHBOARD_STARTUP_STAGE_INITIAL

private object DashboardStartupCompositionWarmState {
    private var entered = false

    fun markEntered(): Boolean {
        val alreadyWarm = entered
        entered = true
        return alreadyWarm
    }
}

private fun dashboardCardStartupStage(card: DashboardCard): Int =
    when (card) {
        DashboardCard.TRAFFIC_MAP -> 2
        DashboardCard.NETWORK -> 1
        DashboardCard.PROFILES -> 2
        DashboardCard.ACTIONS -> 3
        DashboardCard.TRAFFIC -> DASHBOARD_STARTUP_STAGE_ALL
    }

@Composable
private fun rememberHomeNetworkGeoRowsLoading(
    networkIpInfo: IpInfo?,
    refreshLoading: Boolean,
): Boolean {
    val fetchedAt = networkIpInfo?.fetchedAt
    val countryCode = networkIpInfo?.countryCode
    val countryName = networkIpInfo?.countryName
    val city = networkIpInfo?.city
    val isp = networkIpInfo?.isp
    var nowMs by remember(fetchedAt, countryCode, countryName, city, isp, refreshLoading) {
        mutableStateOf(System.currentTimeMillis())
    }
    val loading =
        shouldShowHomeNetworkGeoRowsLoading(
            ipInfo = networkIpInfo,
            nowMs = nowMs,
            refreshLoading = refreshLoading,
        )
    LaunchedEffect(fetchedAt, countryCode, countryName, city, isp, refreshLoading, loading) {
        val info = networkIpInfo ?: return@LaunchedEffect
        if (!loading || refreshLoading) {
            return@LaunchedEffect
        }
        val remainingMs = homeNetworkGeoRowsLoadingRemainingMs(info, System.currentTimeMillis())
        if (remainingMs > 0L) {
            delay(remainingMs)
        }
        nowMs = System.currentTimeMillis()
    }
    return loading
}

private const val DASHBOARD_CARD_ACTIVE_Z_INDEX = 100f
private const val DASHBOARD_CARD_REORDER_THRESHOLD_FRACTION = 0.5f
private const val DASHBOARD_CARD_EDGE_RESISTANCE_FRACTION = 0.18f
private const val DASHBOARD_STARTUP_STAGE_INITIAL = 0
private const val DASHBOARD_STARTUP_STAGE_WARM_RETURN = 2
private const val DASHBOARD_STARTUP_STAGE_ALL = 4
private const val DASHBOARD_CARD_STARTUP_STAGE_DELAY_MS = 32L
private val DashboardCardReorderFallbackMoveDistance = 96.dp
private val ImportMenuWidthChrome = 62.dp
private val ImportMenuMinWidth = 188.dp
private val ImportMenuMaxWidth = 392.dp
