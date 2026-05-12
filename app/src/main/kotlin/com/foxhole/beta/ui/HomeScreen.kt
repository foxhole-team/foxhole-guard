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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileUpload
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
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.TrafficMapUiState
import com.foxhole.beta.core.model.TrafficMapStyle
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.ui.BottomDockOverlayPadding
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.ScreenHorizontalPadding
import com.foxhole.beta.ui.ScreenSectionSpacing
import com.foxhole.beta.ui.ScreenVerticalPadding
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
@Suppress("LongParameterList")
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
    onSelectActiveProtocolOption: (String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefreshIpInfo: () -> Unit,
    onResetUsageTracking: () -> Unit,
    onTrafficUiVisibilityChanged: (Boolean) -> Unit,
    onLocalProxyLanAccessChanged: (Boolean) -> Unit,
    onTrafficMapStyleSelected: (TrafficMapStyle) -> Unit,
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
                if (
                    state.settings.expert.perAppRoutingMode != PerAppRoutingMode.FULL_TUNNEL &&
                    state.settings.expert.selectedPackages.isNotEmpty()
                ) {
                    add(HomeModeOption.SPLIT)
                }
                add(HomeModeOption.PROXY)
            }
        }
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val autoTone = foxholeSystemAwareAccentColor(fallback = MaterialTheme.colorScheme.primary)
    val topStatusState = homeTopStatusState(state)
    val statusTone =
        if (state.autoConnect.running || state.reconnectInProgress) {
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
    ) {
        if (steps == 0) {
            return
        }
        val current = dashboardCardOrder.toMutableList()
        val from = current.indexOf(card)
        val to = (from + steps).coerceIn(0, current.lastIndex)
        if (from < 0 || from == to) {
            return
        }
        current.removeAt(from)
        current.add(to, card)
        dashboardCardOrder = current
        onDashboardCardOrderChanged(current)
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
                    pinnedIpInfo = pinnedIpInfo ?: state.ipInfo
                }
                keepPinnedNetworkInfo = pinnedIpInfo != null
            }
            state.connection.state in pinnedConnectionStates && state.ipInfo == null && pinnedIpInfo != null -> {
                keepPinnedNetworkInfo = true
            }
            state.ipInfo != null -> {
                pinnedIpInfo = state.ipInfo
                keepPinnedNetworkInfo = false
            }
            keepPinnedNetworkInfo && state.connection.state !in pinnedConnectionStates -> {
                keepPinnedNetworkInfo = false
                pinnedIpInfo = null
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
    val showNetworkLoading = networkModel.showLoading
    val showNetworkConnectionStatus = networkModel.showConnectionStatus
    val showNetworkRouteDetails = showNetworkConnectionStatus
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

    LaunchedEffect(state.connection.state, state.activeProfile?.id) {
        onTrafficUiVisibilityChanged(true)
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
                    .padding(padding)
                    .testTag("home_dashboard_list"),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalPadding + safeStartPadding,
                    top = ScreenVerticalPadding,
                    end = ScreenHorizontalPadding + safeEndPadding,
                    bottom = BottomDockOverlayPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(ScreenSectionSpacing),
        ) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                                    modifier = Modifier.animateItem(),
                                    card = DashboardCard.TRAFFIC_MAP,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
                                    TrafficMapDashboardCard(
                                        state = trafficMapState,
                                        style = state.settings.ui.trafficMapStyle,
                                        onStyleSelected = onTrafficMapStyleSelected,
                                    )
                                }
                            }
                        }
                    }

                    DashboardCard.PROFILES -> {
                        item(key = DashboardCard.PROFILES) {
                            DashboardCardDragContainer(
                                modifier = Modifier.animateItem(),
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
                                    modifier = Modifier.size(30.dp),
                                    tint = autoTone,
                                )
                            },
                        )
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = HomeDashboardProfileContentHeight),
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
                                                    color = autoTone,
                                                )
                                            !dashboardConnectionDetailsReady -> Unit
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
                                        if (isSmartDashboardProfile) {
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
                                modifier = Modifier.animateItem(),
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
                                    modifier = Modifier.animateItem(),
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
                            modifier = Modifier.fillMaxWidth().heightIn(min = HomeNetworkContentHeight),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (showNetworkLoading) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top,
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
                                        modifier =
                                            Modifier
                                                .weight(if (showNetworkRouteDetails) 1f else 2f)
                                                .testTag("home_network_loading"),
                                        loadingColor = autoTone,
                                    )
                                    if (showNetworkRouteDetails) {
                                        HomeNetworkVerticalDivider()
                                        HomeConnectionStatusLoadingBlock(
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .testTag("home_connection_status_loading"),
                                            loadingColor = autoTone,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    val networkIpInfo = visibleNetworkIpInfo
                                    val countryText =
                                        if (networkIpInfo != null) {
                                            buildCountryLine(networkIpInfo)
                                        } else {
                                            stringResource(R.string.home_network_unavailable)
                                        }
                                    val ipText = if (networkIpInfo != null) primaryVisibleIp(networkIpInfo) else "-"
                                    val cityText = networkIpInfo?.let(::buildCityLine) ?: "-"
                                    val providerText = networkIpInfo?.isp?.takeIf { it.isNotBlank() } ?: "-"
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        HomeNetworkColumnTitle(stringResource(networkInfoTitleRes))
                                        HomeNetworkDetailLine(
                                            label = stringResource(R.string.home_network_country_label),
                                            value = countryText,
                                            modifier = Modifier.testTag("home_network_country"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            label = stringResource(R.string.home_network_city_label),
                                            value = cityText,
                                            modifier = Modifier.testTag("home_network_city"),
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            label = stringResource(R.string.home_network_ip_label),
                                            value = ipText,
                                            modifier = Modifier.testTag("home_network_primary_ip"),
                                            valueMonospace = networkIpInfo != null,
                                        )
                                        HomeNetworkSubtleDivider()
                                        HomeNetworkDetailLine(
                                            label = stringResource(R.string.home_network_provider_label),
                                            value = providerText,
                                        )
                                    }
                                    if (showNetworkRouteDetails) {
                                        HomeNetworkVerticalDivider()
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
                                                label = stringResource(R.string.home_network_server_ping_label),
                                                value = serverPingText,
                                                valueMonospace = connectionMetricsAvailable && dashboardSelectedServerPingMs != null,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_dns_label),
                                                value = dnsStatusText,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_transport_type_label),
                                                value = transportTypeText,
                                                valueMonospace = transportTypeText != "-",
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
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
                                    modifier = Modifier.animateItem(),
                                    card = DashboardCard.TRAFFIC,
                                    activeCard = activeReorderCard,
                                    onActiveCardChange = { activeReorderCard = it },
                                    onMove = ::moveDashboardCard,
                                ) {
                val trafficModel = resolveHomeDashboardTrafficModel(state, System.currentTimeMillis())
                val totalTrafficText =
                    buildAnnotatedString {
                        append(stringResource(R.string.home_total_traffic_title))
                        append(" ")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append(stringResource(R.string.home_total_traffic_days, trafficModel.totalDays))
                        }
                        append(" ")
                        append(formatBytes(context, trafficModel.totalBytes))
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
                                    modifier = Modifier.size(32.dp).testTag("home_reset_usage_button"),
                                    tint = autoTone,
                                )
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val trafficLabelTint = MaterialTheme.colorScheme.primary
                                val incomingTrafficTint =
                                    if (trafficModel.hasIncomingTraffic) {
                                        FoxholePositiveAccent
                                    } else {
                                        trafficLabelTint
                                    }
                                val outgoingTrafficTint =
                                    if (trafficModel.hasOutgoingTraffic) {
                                        FoxholeInfoAccent
                                    } else {
                                        trafficLabelTint
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
            dismissLabel = stringResource(R.string.cancel),
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
            dismissLabel = stringResource(R.string.cancel),
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
            onDismiss = { selectedConnectionFeature = null },
            onKillSwitchChanged = onKillSwitchChanged,
            onFirewallEnabledChanged = onFirewallEnabledChanged,
            onPrivacyRouteModeSelected = onPrivacyRouteModeSelected,
            onLocalProxyLanAccessChanged = onLocalProxyLanAccessChanged,
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
    onMove: (DashboardCard, Int) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dragThresholdPx = with(density) { DashboardCardReorderDragThreshold.toPx() }
    var dragOffset by remember(card) { mutableStateOf(0f) }
    val active = activeCard == card

    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .zIndex(if (active) 1f else 0f)
                .graphicsLayer {
                    val scale = if (active) 1.018f else 1f
                    scaleX = scale
                    scaleY = scale
                    translationY = if (active) dragOffset else 0f
                    shadowElevation = if (active) 8f else 0f
                }
                .pointerInput(card, dragThresholdPx) {
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
                            while (abs(dragOffset) >= dragThresholdPx) {
                                val steps = (dragOffset / dragThresholdPx).toInt()
                                onMove(card, steps)
                                dragOffset -= dragThresholdPx * steps
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

private val DashboardCardReorderDragThreshold = 48.dp
private val ImportMenuWidthChrome = 62.dp
private val ImportMenuMinWidth = 188.dp
private val ImportMenuMaxWidth = 392.dp
