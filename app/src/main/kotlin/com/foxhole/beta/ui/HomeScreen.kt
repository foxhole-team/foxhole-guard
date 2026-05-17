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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.foxhole.beta.ui.theme.LocalFoxholeUiPalette
import com.foxhole.beta.ui.BottomDockOverlayPadding
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.ScreenHorizontalPadding
import com.foxhole.beta.ui.ScreenSectionSpacing
import com.foxhole.beta.ui.ScreenVerticalPadding
import kotlinx.coroutines.delay

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod", "LongParameterList")
fun HomeScreen(
    state: HomeRouteUiState,
    trafficMapState: TrafficMapUiState,
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
    onSelectActiveProtocolOption: (String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefreshIpInfo: () -> Unit,
    onResetUsageTracking: () -> Unit,
    onTrafficUiVisibilityChanged: (Boolean) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onRenewTorIp: () -> Unit,
    onDashboardCardOrderChanged: (List<DashboardCard>) -> Unit,
) {
    val context = LocalContext.current
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRefreshProfileDialog by rememberSaveable { mutableStateOf(false) }
    var smartRefreshConfirmationProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var smartStartFirstAnalysisProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var acceptedSmartStartFirstAnalysisProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstAnalysisProtocolMenuProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstAnalysisProtocolMenuStarted by rememberSaveable { mutableStateOf(false) }
    var selectedConnectionFeature by rememberSaveable { mutableStateOf<HomeConnectionFeature?>(null) }
    val wifiLanAddress by rememberWifiLanAddress()
    val proxyModel =
        remember(state, wifiLanAddress) {
            resolveHomeDashboardProxyModel(
                state = state,
                wifiLanAddress = wifiLanAddress,
            )
        }
    val modeOption = proxyModel.modeOption
    val connectionFeatureIndicators = remember(state) { homeConnectionFeatureIndicators(state) }
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
    val uiPalette = LocalFoxholeUiPalette.current
    val autoTone = foxholeSystemAwareAccentColor(fallback = MaterialTheme.colorScheme.primary)
    val torOperationTone = Color(0xFFE89B3C)
    val topStatusState = homeTopStatusState(state)
    val statusTone =
        if (state.torOperation.active) {
            torOperationTone
        } else if (state.autoConnect.running || state.reconnectInProgress) {
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
    val connectionDurationText = rememberConnectionDurationText(state.connection)
    val dashboardProtocolModel = remember(state) { resolveHomeDashboardProtocolModel(state) }
    val dashboardProtocolLatencies = dashboardProtocolModel.latenciesByOptionId
    val dashboardDownProtocolIds = dashboardProtocolModel.downOptionIds
    val dashboardUnavailableProtocolIds = dashboardProtocolModel.latencyUnavailableOptionIds
    val dashboardShowSmartStartLatency = dashboardProtocolModel.showSmartStartLatency
    val dashboardLatencyPresentation = dashboardProtocolModel.latencyPresentation
    val dashboardSelectedLatencyMs = dashboardLatencyPresentation.latencyMs
    val dashboardSelectedLatencyDown = dashboardLatencyPresentation.isDown
    val dashboardSelectedLatencyUnavailable = dashboardLatencyPresentation.isUnavailable
    val dashboardProtocolPresentation = dashboardProtocolModel.presentation
    val dashboardSelectedServerPingMs = dashboardProtocolModel.selectedServerPingMs
    val dashboardSelectedServerPingUnavailable = dashboardProtocolModel.selectedServerPingUnavailable
    val dashboardConnectionDetailsReady = dashboardProtocolModel.connectionDetailsReady
    val dashboardConnectionMetricsLoading = dashboardProtocolModel.connectionMetricsLoading
    val smartStartDashboardControlsEnabled = state.settings.ui.smartStartDashboardControlsEnabled
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
        val nextOrder = reorderedDashboardCards(
            order = dashboardCardOrder,
            card = card,
            steps = steps,
        )
        if (nextOrder != null) {
            dashboardCardOrder = nextOrder
            onDashboardCardOrderChanged(nextOrder)
        }
        return nextOrder != null
    }
    val deviceInternetAvailable by rememberDefaultInternetAvailability()
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
    val networkModel =
        remember(state, selectedVisibleNetworkIpInfo, deviceInternetAvailable) {
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
    val profileModel = remember(state) { resolveHomeDashboardProfileModel(state = state) }
    val isSmartDashboardProfile = profileModel.isSmartDashboardProfile
    val activeProfileId = profileModel.activeProfileId
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

    DisposableEffect(onTrafficUiVisibilityChanged) {
        onTrafficUiVisibilityChanged(true)
        onDispose { onTrafficUiVisibilityChanged(false) }
    }

    FoxholeScaffold(
        title = stringResource(R.string.app_name),
        snackbarHostState = snackbarHostState,
        bannerTopPadding = HomeDashboardBannerTopPadding,
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = uiPalette.cardContainerColor,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
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
                                        if (state.autoConnect.running) {
                                            HomeAutoConnectStatusLine(
                                                state = state.autoConnect,
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
                                            onSelect = { selectedMode ->
                                                applyHomeModeSelection(
                                                    mode = selectedMode,
                                                    onTrafficModeSelected = onTrafficModeSelected,
                                                    onPerAppRoutingModeSelected = onPerAppRoutingModeSelected,
                                                    selectedPackages = state.settings.expert.selectedPackages,
                                                    currentPerAppRoutingMode = state.settings.expert.perAppRoutingMode,
                                                )
                                            },
	                                    )
	                                }
		                            }
	                        }
                        }
                        HomeConnectionFeatureIndicators(
                            indicators = connectionFeatureIndicators,
                            onIndicatorClick = { feature -> selectedConnectionFeature = feature },
                            modifier =
                                Modifier
                                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                        )
                    }
                }
            }
            dashboardCardOrder.forEach { card ->
                when (card) {
                    DashboardCard.TRAFFIC_MAP -> {
                        if (state.settings.ui.trafficMapEnabled) {
                            item(key = DashboardCard.TRAFFIC_MAP) {
                                DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (activeReorderCard == DashboardCard.TRAFFIC_MAP) {
                                                    Modifier
                                                } else {
                                                    Modifier.animateItem()
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
                                        legendLoading =
                                            shouldShowTrafficMapLegendLoading(
                                                connectionState = state.connection.state,
                                                appLoaded = state.profilesLoaded,
                                                explicitLoading = state.ipInfoLoading,
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
                                                if (activeReorderCard == DashboardCard.PROFILES) {
                                                    Modifier
                                                } else {
                                                    Modifier.animateItem()
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
                            title = stringResource(R.string.vpn_profile),
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
                                            dashboardConnectionMetricsLoading ->
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
                                    onProtocolOptionSelected = onSelectActiveProtocolOption,
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
                                                    metricsRefreshing = state.protocolMetricsRefreshing,
                                                    refreshingOptionId =
                                                        state.protocolMetricsRefreshingOptionId
                                                            ?: state.autoConnect.currentOptionId
                                                                .takeIf { state.protocolMetricsRefreshing },
                                                    recommendedOptionId = state.recommendedProtocolOptionId,
                                                    recommendedOptionIds = state.recommendedProtocolOptionIds,
                                                    favoriteOptionId = state.favoriteProtocolOptionId,
                                                    activeOptionId = dashboardProtocolPresentation.selectedProtocolOptionId,
                                                    onSelectOption = onSelectActiveProtocolOption,
                                                    onRefreshMetrics = { requestSmartProfileMetricsRefresh(state.activeProfile.id) },
                                                    onCancelRefreshMetrics = onCancelSmartProfileMetricsRefresh,
                                                    showLatency = dashboardShowSmartStartLatency,
                                                    compact = true,
                                                    actionIconSize = 18.dp,
                                                    showMetricsTable = false,
                                                    showStatusHeader = true,
                                                    latencyProbeMethod = state.settings.connection.latencyProbeMethod,
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
                            DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (activeReorderCard == DashboardCard.ACTIONS) {
                                                    Modifier
                                                } else {
                                                    Modifier.animateItem()
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
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.import_label))
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
                                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
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
                                        Icon(Icons.Outlined.FileUpload, contentDescription = null)
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
                                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
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
                                DashboardCardDragContainer(
                                    modifier =
                                        Modifier
                                            .then(
                                                if (activeReorderCard == DashboardCard.NETWORK) {
                                                    Modifier
                                                } else {
                                                    Modifier.animateItem()
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
                                if (showNetworkIpInfoLoading) {
                                    HomeNetworkLoadingBlock(
                                        title = stringResource(networkInfoTitleRes),
                                        labels =
                                            listOf(
                                                stringResource(R.string.home_network_country_label),
                                                stringResource(R.string.home_network_city_label),
                                                stringResource(R.string.home_network_ip_label),
                                                stringResource(R.string.home_network_provider_label),
                                            ),
                                        modifier =
                                            Modifier
                                                .weight(if (showNetworkRouteDetails) 1f else 2f)
                                                .testTag("home_network_loading"),
                                    )
                                } else {
                                    val countryText =
                                        if (networkIpInfo != null) {
                                            buildCountryLine(networkIpInfo)
                                        } else {
                                            "-"
                                        }
                                    val cityText = networkIpInfo?.let(::buildCityLine) ?: "-"
                                    val ipText = if (networkIpInfo != null) primaryVisibleIp(networkIpInfo) else "-"
                                    val providerText = networkIpInfo?.isp?.takeIf { it.isNotBlank() } ?: "-"
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        HomeNetworkColumnTitle(stringResource(networkInfoTitleRes))
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Language,
                                            label = stringResource(R.string.home_network_country_label),
                                            value = countryText,
                                            modifier = Modifier.testTag("home_network_country"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.LocationCity,
                                            label = stringResource(R.string.home_network_city_label),
                                            value = cityText,
                                            modifier = Modifier.testTag("home_network_city"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Public,
                                            label = stringResource(R.string.home_network_ip_label),
                                            value = ipText,
                                            modifier = Modifier.testTag("home_network_primary_ip"),
                                            valueMonospace = networkIpInfo != null,
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            icon = Icons.Outlined.Business,
                                            label = stringResource(R.string.home_network_provider_label),
                                            value = providerText,
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
                                                    dashboardSelectedServerPingMs != null ->
                                                        stringResource(
                                                            R.string.latency_pill_value,
                                                            boundedDisplayLatencyMs(dashboardSelectedServerPingMs),
                                                        )
                                                    dashboardSelectedServerPingUnavailable -> stringResource(R.string.latency_pill_unavailable)
                                                    else -> stringResource(R.string.smart_profile_metric_unavailable)
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
                                                valueMonospace = connectionMetricsAvailable && dashboardSelectedServerPingMs != null,
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
                                                if (activeReorderCard == DashboardCard.TRAFFIC) {
                                                    Modifier
                                                } else {
                                                    Modifier.animateItem()
                                                },
                                            )
                                            .dashboardCardZIndex(activeReorderCard, DashboardCard.TRAFFIC),
                                    card = DashboardCard.TRAFFIC,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
	                val trafficModel = resolveHomeDashboardTrafficModel(state, System.currentTimeMillis())
	                val trafficLoading = false
                val totalTrafficText =
                    buildAnnotatedString {
                        val periodBytes = trafficModel.selectedProtocolTotalBytes ?: trafficModel.totalBytes
                        append(stringResource(R.string.home_total_traffic_title))
                        append(" ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(stringResource(R.string.home_total_traffic_days, trafficModel.totalDays))
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
                                    contentDescription = stringResource(R.string.reset_usage_tracking),
                                    onClick = onResetUsageTracking,
                                    modifier = Modifier.testTag("home_reset_usage_button"),
                                    tint = autoTone,
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
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
                                        value = formatBytes(context, state.traffic.rxTotalBytes),
                                        secondary = formatRate(context, state.traffic.rxBytesPerSec),
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
                                        value = formatBytes(context, state.traffic.txTotalBytes),
                                        secondary = formatRate(context, state.traffic.txBytesPerSec),
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
                                        secondary = formatRate(context, state.traffic.rxBytesPerSec + state.traffic.txBytesPerSec),
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
            onLocalProxyLanAccessChanged = onLocalProxyLanAccessChanged,
            onRenewTorIp = onRenewTorIp,
            onRestart = onToggleConnection,
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
    val fallbackMoveDistancePx = with(density) { DashboardCardReorderFallbackMoveDistance.toPx() }
    var dragOffset by remember(card) { mutableStateOf(0f) }
    var cardHeightPx by remember(card) { mutableStateOf(0f) }
    val active = activeCard == card
    val dragShape = MaterialTheme.shapes.large
    val moveDistancePx = cardHeightPx.takeIf { it > 0f } ?: fallbackMoveDistancePx
    val moveThresholdPx =
        (moveDistancePx * DASHBOARD_CARD_REORDER_THRESHOLD_FRACTION)
            .coerceAtLeast(fallbackMoveDistancePx)

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .onGloballyPositioned { coordinates ->
                    cardHeightPx = coordinates.size.height.toFloat()
                }
                .graphicsLayer {
                    translationY = if (active) dragOffset else 0f
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
                            onActiveCardChange(card)
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDragCancel = {
                            dragOffset = 0f
                            onActiveCardChange(null)
                        },
                        onDragEnd = {
                            dragOffset = 0f
                            onActiveCardChange(null)
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount.y
                            while (dragOffset > moveThresholdPx) {
                                if (!onMove(card, 1)) {
                                    dragOffset = moveThresholdPx
                                    break
                                }
                                dragOffset -= moveDistancePx
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            while (dragOffset < -moveThresholdPx) {
                                if (!onMove(card, -1)) {
                                    dragOffset = -moveThresholdPx
                                    break
                                }
                                dragOffset += moveDistancePx
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                    )
                },
    ) {
        content()
    }
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
    card: DashboardCard,
    steps: Int,
): List<DashboardCard>? {
    val from = order.indexOf(card)
    if (steps == 0 || from < 0) {
        return null
    }

    val to = (from + steps).coerceIn(0, order.lastIndex)
    return if (from == to) {
        null
    } else {
        order.toMutableList().apply {
            removeAt(from)
            add(to, card)
        }
    }
}

private fun Modifier.dashboardCardZIndex(
    activeCard: DashboardCard?,
    card: DashboardCard,
): Modifier =
    zIndex(
        if (activeCard == card) DASHBOARD_CARD_ACTIVE_Z_INDEX else 0f,
    )

private const val DASHBOARD_CARD_ACTIVE_Z_INDEX = 100f
private const val DASHBOARD_CARD_REORDER_THRESHOLD_FRACTION = 0.5f
private val DashboardCardReorderFallbackMoveDistance = 96.dp
private val ImportMenuWidthChrome = 62.dp
private val ImportMenuMinWidth = 188.dp
private val ImportMenuMaxWidth = 392.dp
