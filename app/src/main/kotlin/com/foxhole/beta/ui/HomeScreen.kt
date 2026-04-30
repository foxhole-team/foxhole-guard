package com.foxhole.beta.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Tune
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.ui.BottomDockOverlayPadding
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.ScreenHorizontalPadding
import com.foxhole.beta.ui.ScreenSectionSpacing
import com.foxhole.beta.ui.ScreenVerticalPadding
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    state: HomeRouteUiState,
    snackbarHostState: SnackbarHostState,
    onImportFromClipboard: () -> Unit,
    onImportFromFile: () -> Unit,
    onImportFromQr: () -> Unit,
    onRefreshProfile: () -> Unit,
    onToggleConnection: () -> Unit,
    onAutoConnect: () -> Unit,
    onTrafficModeSelected: (TrafficMode) -> Unit,
    onPerAppRoutingModeSelected: (PerAppRoutingMode) -> Unit,
    onSelectActiveProtocolOption: (String) -> Unit,
    onUpdateAutoConnectExcludedOptions: (Set<String>) -> Unit,
    onRefreshSmartProfileMetrics: (Long) -> Unit,
    onCancelSmartProfileMetricsRefresh: () -> Unit,
    onOpenProfiles: () -> Unit,
    onRefreshIpInfo: () -> Unit,
    onResetUsageTracking: () -> Unit,
    onTrafficUiVisibilityChanged: (Boolean) -> Unit,
    onLocalProxyAuthChanged: (LocalAuthSettings) -> Unit,
) {
    val context = LocalContext.current
    val usernameLabel = stringResource(R.string.username)
    val passwordLabel = stringResource(R.string.password)
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRefreshProfileDialog by rememberSaveable { mutableStateOf(false) }
    var editProxyUsernameVisible by rememberSaveable { mutableStateOf(false) }
    var editProxyPasswordVisible by rememberSaveable { mutableStateOf(false) }
    var dismissedSmartStartReminderProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    var smartRefreshConfirmationProfileId by rememberSaveable { mutableStateOf<Long?>(null) }
    val wifiLanAddress by rememberWifiLanAddress()
    val proxyModel =
        remember(state, wifiLanAddress) {
            resolveHomeDashboardProxyModel(
                state = state,
                wifiLanAddress = wifiLanAddress,
            )
        }
    val modeOption = proxyModel.modeOption
    val lanProxySurface = proxyModel.lanProxySurface
    val dashboardProxySurface = proxyModel.dashboardProxySurface
    val lanProxyActive = proxyModel.lanProxyActive
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val proxyAuth = state.settings.expert.localSurfaces.auth
    val autoTone = MaterialTheme.colorScheme.primary
    val statusTone =
        if (state.autoConnect.running) {
            autoTone
        } else {
            homeStatusTone(state.connection.state)
        }
    val dashboardSelectorBorderColor =
        if (darkTheme) {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.78f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.58f)
        }
    val dashboardSecondaryActionBorderColor =
        if (darkTheme) {
            MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
        } else {
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f)
        }
    val dashboardSecondaryActionIconSize = 21.dp
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
    LaunchedEffect(state.autoConnect.running, state.ipInfo, state.connection.state) {
        when {
            state.autoConnect.running -> {
                if (!keepPinnedNetworkInfo) {
                    pinnedIpInfo = state.ipInfo ?: pinnedIpInfo
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
        remember(state, selectedVisibleNetworkIpInfo, deviceInternetAvailable, dashboardConnectionDetailsReady) {
            resolveHomeDashboardNetworkModel(
                state = state,
                visibleIpInfo = selectedVisibleNetworkIpInfo,
                deviceInternetAvailable = deviceInternetAvailable,
                connectionDetailsReady = dashboardConnectionDetailsReady,
            )
        }
    val visibleNetworkIpInfo = networkModel.visibleIpInfo
    val showNetworkLoading = networkModel.showLoading
    val showNetworkConnectionStatus = networkModel.showConnectionStatus
    val networkInfoTitleRes = networkModel.titleRes
    val profileModel =
        remember(state, dismissedSmartStartReminderProfileId) {
            resolveHomeDashboardProfileModel(
                state = state,
                dismissedSmartStartReminderProfileId = dismissedSmartStartReminderProfileId,
            )
        }
    val isSmartDashboardProfile = profileModel.isSmartDashboardProfile
    val activeProfileId = profileModel.activeProfileId
    val showSmartStartRefreshReminder = profileModel.showSmartStartRefreshReminder

    fun requestSmartProfileMetricsRefresh(profileId: Long) {
        smartRefreshConfirmationProfileId = profileId
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
    ) { padding ->
        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag("home_dashboard_list"),
            contentPadding =
                PaddingValues(
                    start = ScreenHorizontalPadding,
                    top = ScreenVerticalPadding,
                    end = ScreenHorizontalPadding,
                    bottom = BottomDockOverlayPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(ScreenSectionSpacing),
        ) {
            item {
                FoxholeCard {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = statusTone.copy(alpha = 0.10f),
                    ) {
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
                                modifier = Modifier.size(54.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Surface(
                                    modifier = Modifier.size(36.dp),
                                    shape = MaterialTheme.shapes.large,
                                    color = statusTone.copy(alpha = 0.14f),
                                ) {
                                    Spacer(modifier = Modifier.fillMaxSize())
                                }
                                Image(
                                    painter = painterResource(R.drawable.foxhole_logo),
                                    contentDescription = stringResource(R.string.app_name),
                                    modifier = Modifier.size(42.dp),
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
                                    val statusVisualState =
                                        if (state.autoConnect.running) {
                                            ConnectionState.CONNECTING
                                        } else {
                                            state.connection.state
                                        }
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
                                                state = statusVisualState,
                                                label = homeStatusLabel(state.connection.state),
                                                textStyle = MaterialTheme.typography.titleMedium,
                                                accentColor = statusTone,
                                            )
                                        }
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        HomeModeDropdown(
                                            selected = modeOption,
                                            onSelect = { selectedMode ->
                                                applyHomeModeSelection(
                                                    mode = selectedMode,
                                                    onTrafficModeSelected = onTrafficModeSelected,
                                                    onPerAppRoutingModeSelected = onPerAppRoutingModeSelected,
                                                )
                                            },
                                        )
                                        if (lanProxyActive) {
                                            HomeLanProxyChip()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (showSmartStartRefreshReminder) {
                val reminderProfileId = checkNotNull(activeProfileId)
                item {
                    SmartStartRefreshReminderCard(
                        onRefresh = { requestSmartProfileMetricsRefresh(reminderProfileId) },
                        onLater = { dismissedSmartStartReminderProfileId = reminderProfileId },
                    )
                }
            }
            item {
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
                            title = stringResource(R.string.profile),
                            titleContent =
                                if (isSmartDashboardProfile) {
                                    {
                                        Text(
                                            text = stringResource(R.string.profile),
                                            modifier = Modifier.weight(1f, fill = false),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        SmartProfileBadge(
                                            compact = true,
                                        )
                                    }
                                } else {
                                    null
                                },
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
                                Modifier.fillMaxWidth(),
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
                                    text = stringResource(R.string.manage_profiles_summary),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                InlineSmartProfileTitle(
                                    title = dashboardProfileTitle(state.activeProfile.name),
                                    isSmartProfile = isSmartDashboardProfile,
                                    showSmartBadge = false,
                                    trailing = {
                                        when {
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
                                    selectorBorderColor = dashboardSelectorBorderColor,
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
            item {
                FoxholeCard {
                    HomeConnectionActions(
                        state = state,
                        onToggleConnection = onToggleConnection,
                        onAutoConnect = onAutoConnect,
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
                            ) {
                                Icon(
                                    Icons.Outlined.ContentPaste,
                                    contentDescription = null,
                                    modifier = Modifier.size(dashboardSecondaryActionIconSize),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.import_label))
                            }
                            FoxholeDropdownMenu(
                                expanded = importMenuExpanded,
                                onDismissRequest = { importMenuExpanded = false },
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
                                        title = stringResource(R.string.import_from_clipboard),
                                        summary = stringResource(R.string.import_from_clipboard_summary),
                                    )
                                }
                                FoxholeDropdownItem(
                                    modifier = Modifier.testTag("home_import_from_file_action"),
                                    leadingContent = {
                                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
                                    },
                                    onClick = {
                                        importMenuExpanded = false
                                        onImportFromFile()
                                    },
                                ) {
                                    ImportDropdownItemText(
                                        title = stringResource(R.string.import_from_file),
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
                                        title = stringResource(R.string.scan_qr_code),
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
            item {
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
                                                .weight(if (showNetworkConnectionStatus) 1f else 2f)
                                                .testTag("home_network_loading"),
                                    )
                                    if (showNetworkConnectionStatus) {
                                        HomeNetworkVerticalDivider()
                                        HomeConnectionStatusLoadingBlock(
                                            modifier =
                                                Modifier
                                                    .weight(1f)
                                                    .testTag("home_connection_status_loading"),
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
                                    if (showNetworkConnectionStatus) {
                                        HomeNetworkVerticalDivider()
                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                        ) {
                                            val connectionMetricsAvailable = state.connection.state == ConnectionState.CONNECTED
                                            val vpnLatencyText =
                                                when {
                                                    !connectionMetricsAvailable -> stringResource(R.string.smart_start_protocol_status_no_data)
                                                    dashboardSelectedLatencyDown -> stringResource(R.string.latency_pill_down)
                                                    dashboardSelectedLatencyMs != null ->
                                                        stringResource(
                                                            R.string.latency_pill_value,
                                                            boundedDisplayLatencyMs(dashboardSelectedLatencyMs),
                                                        )
                                                    dashboardSelectedLatencyUnavailable -> stringResource(R.string.latency_pill_unavailable)
                                                    else -> stringResource(R.string.smart_profile_metric_unavailable)
                                                }
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
                                            val profileStatusText =
                                                when {
                                                    !connectionMetricsAvailable -> stringResource(R.string.smart_start_protocol_status_no_data)
                                                    dashboardSelectedLatencyDown -> stringResource(R.string.latency_quality_failed)
                                                    dashboardSelectedLatencyMs != null ->
                                                        latencyQualityLabel(
                                                            classifyVpnLatency(
                                                                latencyMs = dashboardSelectedLatencyMs,
                                                                failed = false,
                                                                unavailable = false,
                                                            ),
                                                        )
                                                    dashboardSelectedLatencyUnavailable -> stringResource(R.string.latency_quality_unavailable)
                                                    else -> stringResource(R.string.smart_start_protocol_status_no_data)
                                                }
                                            HomeNetworkColumnTitle(stringResource(R.string.home_network_profile_info_title))
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_vpn_latency_label),
                                                value = vpnLatencyText,
                                                valueMonospace = connectionMetricsAvailable && dashboardSelectedLatencyMs != null,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_server_ping_label),
                                                value = serverPingText,
                                                valueMonospace = connectionMetricsAvailable && dashboardSelectedServerPingMs != null,
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_connect_time_label),
                                                value = connectionDurationText ?: "-",
                                                valueMonospace = connectionDurationText != null,
                                                modifier = Modifier.testTag("home_connection_duration"),
                                            )
                                            HomeNetworkSubtleDivider()
                                            HomeNetworkDetailLine(
                                                label = stringResource(R.string.home_network_status_label),
                                                value = profileStatusText,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
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
                                val incomingTrafficTint =
                                    if (trafficModel.hasIncomingTraffic) {
                                        FoxholePositiveAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                val outgoingTrafficTint =
                                    if (trafficModel.hasOutgoingTraffic) {
                                        FoxholeInfoAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
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
                                        label = stringResource(R.string.home_received_label),
                                        value = formatBytes(context, state.traffic.rxTotalBytes),
                                        secondary = formatRate(context, state.traffic.rxBytesPerSec),
                                        valueTag = "home_traffic_rx_value",
                                        secondaryTag = "home_traffic_rx_rate",
                                    )
                                    TrafficStatBlock(
                                        modifier = Modifier.weight(1f),
                                        icon = Icons.Outlined.ArrowUpward,
                                        iconTint = outgoingTrafficTint,
                                        label = stringResource(R.string.home_sent_label),
                                        value = formatBytes(context, state.traffic.txTotalBytes),
                                        secondary = formatRate(context, state.traffic.txBytesPerSec),
                                        valueTag = "home_traffic_tx_value",
                                        secondaryTag = "home_traffic_tx_rate",
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
                                        label = stringResource(R.string.home_total_label),
                                        value = formatBytes(context, state.traffic.rxTotalBytes + state.traffic.txTotalBytes),
                                        secondary = formatRate(context, state.traffic.rxBytesPerSec + state.traffic.txBytesPerSec),
                                        valueTag = "home_traffic_total_value",
                                        secondaryTag = "home_traffic_total_rate",
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (dashboardProxySurface != null) {
                item {
                    FoxholeCard {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            HomeCardHeader(
                                icon = Icons.Outlined.Tune,
                                title =
                                    if (lanProxySurface != null) {
                                        stringResource(R.string.home_lan_proxy_title)
                                    } else {
                                        stringResource(R.string.home_proxy_title)
                                    },
                            )
                            Text(
                                text = dashboardProxySurface.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (lanProxySurface != null) {
                                Text(
                                    text =
                                        if (wifiLanAddress != null) {
                                            "${wifiLanAddress}:${dashboardProxySurface.settings.port}"
                                        } else {
                                            stringResource(R.string.proxy_surface_lan_waiting_for_wifi)
                                        },
                                    style =
                                        if (wifiLanAddress != null) {
                                            MaterialTheme.typography.titleSmall
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                    fontFamily = if (wifiLanAddress != null) FontFamily.Monospace else null,
                                    fontWeight = if (wifiLanAddress != null) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (wifiLanAddress != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "${dashboardProxySurface.settings.host}:${dashboardProxySurface.settings.port}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (proxyAuth.enabled) {
                                ProxyCredentialRow(
                                    label = stringResource(R.string.username),
                                    value = proxyAuth.username,
                                    onEdit = { editProxyUsernameVisible = true },
                                    editContentDescription = stringResource(R.string.edit_proxy_username_title),
                                    onCopy = {
                                        copyTextToClipboard(
                                            context = context,
                                            label = usernameLabel,
                                            value = proxyAuth.username,
                                        )
                                    },
                                )
                                ProxyCredentialRow(
                                    label = stringResource(R.string.password),
                                    value = proxyAuth.password,
                                    onEdit = { editProxyPasswordVisible = true },
                                    editContentDescription = stringResource(R.string.edit_proxy_password_title),
                                    onCopy = {
                                        copyTextToClipboard(
                                            context = context,
                                            label = passwordLabel,
                                            value = proxyAuth.password,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (editProxyUsernameVisible) {
        TextValueDialog(
            title = stringResource(R.string.edit_proxy_username_title),
            icon = Icons.Outlined.Edit,
            initialValue = proxyAuth.username,
            singleLine = true,
            onDismiss = { editProxyUsernameVisible = false },
            onConfirm = { updated ->
                onLocalProxyAuthChanged(proxyAuth.copy(username = updated.trim().ifBlank { proxyAuth.username }))
            },
        )
    }

    if (editProxyPasswordVisible) {
        TextValueDialog(
            title = stringResource(R.string.edit_proxy_password_title),
            icon = Icons.Outlined.Edit,
            initialValue = proxyAuth.password,
            singleLine = true,
            onDismiss = { editProxyPasswordVisible = false },
            onConfirm = { updated ->
                onLocalProxyAuthChanged(proxyAuth.copy(password = updated.trim().ifBlank { proxyAuth.password }))
            },
        )
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
