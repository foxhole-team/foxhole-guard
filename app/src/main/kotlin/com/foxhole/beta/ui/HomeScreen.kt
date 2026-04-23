package com.foxhole.beta.ui

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CheckCircle
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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.profile.MultiProtocolProfileSupport
import com.foxhole.beta.ui.FoxholeCard
import com.foxhole.beta.ui.BottomDockOverlayPadding
import com.foxhole.beta.ui.FoxholeScaffold
import com.foxhole.beta.ui.ScreenHorizontalPadding
import com.foxhole.beta.ui.ScreenSectionSpacing
import com.foxhole.beta.ui.ScreenVerticalPadding
import com.foxhole.beta.ui.visibleProfileTrafficTotals
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
    onOpenProfiles: () -> Unit,
    onRefreshIpInfo: () -> Unit,
    onResetUsageTracking: () -> Unit,
    onTrafficUiVisibilityChanged: (Boolean) -> Unit,
    onLocalProxyAuthChanged: (LocalAuthSettings) -> Unit,
) {
    val context = LocalContext.current
    var importMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var showRefreshProfileDialog by rememberSaveable { mutableStateOf(false) }
    var editProxyUsernameVisible by rememberSaveable { mutableStateOf(false) }
    var editProxyPasswordVisible by rememberSaveable { mutableStateOf(false) }
    val modeOption = currentHomeModeOption(state)
    val proxySurface = activeProxySurface(state)
    val lanProxySurface = activeLanProxySurface(state)
    val dashboardProxySurface = proxySurface ?: lanProxySurface
    val wifiLanAddress by rememberWifiLanAddress()
    val lanProxyActive = wifiLanAddress != null && lanProxySurface != null
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.3f
    val proxyAuth = state.settings.expert.localSurfaces.auth
    val statusTone =
        if (state.autoConnect.running) {
            FoxholeInfoAccent
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
    val dashboardProtocolLatencies =
        remember(
            state.smartStartRememberedLatenciesByOptionId,
            state.protocolLatenciesByOptionId,
            state.autoConnect.options,
        ) {
            state.smartStartRememberedLatenciesByOptionId +
                state.protocolLatenciesByOptionId +
                state.autoConnect.options
                    .mapNotNull { option ->
                        option.latencyMs?.takeIf {
                            option.status == AutoConnectProbeStatus.SUCCESS || option.status == AutoConnectProbeStatus.WINNER
                        }?.let { latencyMs ->
                            option.optionId to latencyMs
                        }
                    }
                    .toMap()
        }
    val dashboardDownProtocolIds =
        remember(state.autoConnect.options) {
            state.autoConnect.options
                .filter { option -> option.status == AutoConnectProbeStatus.FAILED }
                .map { option -> option.optionId }
                .toSet()
        }
    val connectionDurationText = rememberConnectionDurationText(state.connection)
    val dashboardUnavailableProtocolIds =
        remember(state.protocolLatencyUnavailableOptionIds, state.autoConnect.options) {
            state.protocolLatencyUnavailableOptionIds +
                state.autoConnect.options
                    .filter { option ->
                        option.status in setOf(AutoConnectProbeStatus.SUCCESS, AutoConnectProbeStatus.WINNER) &&
                            option.latencyUnavailable
                    }.map(AutoConnectProbeOptionUiState::optionId)
                    .toSet()
        }
    val dashboardShowSmartStartLatency =
        remember(
            dashboardProtocolLatencies,
            dashboardDownProtocolIds,
            dashboardUnavailableProtocolIds,
        ) {
            dashboardProtocolLatencies.isNotEmpty() ||
                dashboardDownProtocolIds.isNotEmpty() ||
                dashboardUnavailableProtocolIds.isNotEmpty()
        }
    val dashboardLatencyPresentation =
        remember(
            state.autoConnect.running,
            state.autoConnect.currentOptionId,
            state.autoConnect.options,
            state.selectedProtocolLatencyMs,
            state.selectedProtocolLatencyUnavailable,
            state.connection.state,
        ) {
            resolveDashboardLatencyPresentation(state)
        }
    val dashboardSelectedLatencyMs = dashboardLatencyPresentation.latencyMs
    val dashboardSelectedLatencyDown = dashboardLatencyPresentation.isDown
    val dashboardSelectedLatencyUnavailable = dashboardLatencyPresentation.isUnavailable
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
    val visibleNetworkIpInfo = if (keepPinnedNetworkInfo) pinnedIpInfo else state.ipInfo
    val showNetworkLoading =
        shouldShowDashboardNetworkLoading(
            visibleIpInfo = visibleNetworkIpInfo,
            explicitLoading = state.ipInfoLoading,
            connectionState = state.connection.state,
            autoConnectRunning = state.autoConnect.running,
            deviceInternetAvailable = deviceInternetAvailable,
        )
    val isSmartDashboardProfile =
        state.activeProfile?.let(MultiProtocolProfileSupport::hasMultipleSupportedOptions) == true

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
                    .padding(padding),
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
                                IconButton(
                                    onClick = onOpenProfiles,
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(17.dp),
                                    )
                                }
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
                                    isSmartProfile = MultiProtocolProfileSupport.hasMultipleSupportedOptions(state.activeProfile),
                                    showSmartBadge = false,
                                    trailing = {
                                        when {
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
                                val dashboardProtocolPresentation =
                                    remember(state.activeProfile, state.autoConnect) {
                                        resolveHomeDashboardProtocolPresentation(
                                            activeProfile = state.activeProfile,
                                            autoConnect = state.autoConnect,
                                        )
                                    }
                                ProtocolMetadataRow(
                                    protocol = dashboardProtocolPresentation.protocolHint,
                                    subscriptionExpiresAt = state.activeProfile.subscriptionExpiresAt,
                                    protocolOptions = dashboardProtocolPresentation.protocolOptions,
                                    selectedProtocolOptionId = dashboardProtocolPresentation.selectedProtocolOptionId,
                                    onProtocolOptionSelected = onSelectActiveProtocolOption,
                                    compact = true,
                                    animateSelection = true,
                                    latencyByOptionId = dashboardProtocolLatencies,
                                    selectorBorderColor = dashboardSelectorBorderColor,
                                    leadingContent =
                                        if (MultiProtocolProfileSupport.hasMultipleSupportedOptions(state.activeProfile)) {
                                            {
                                                SmartProfileAutoConnectMenu(
                                                    profile = state.activeProfile,
                                                    excludedOptionIds = state.activeProfileExcludedOptionIds,
                                                    onUpdateExcludedOptionIds = onUpdateAutoConnectExcludedOptions,
                                                    latencyByOptionId = dashboardProtocolLatencies,
                                                    unavailableOptionIds = dashboardDownProtocolIds,
                                                    latencyUnavailableOptionIds = dashboardUnavailableProtocolIds,
                                                    showLatency = dashboardShowSmartStartLatency,
                                                    compact = true,
                                                    actionIconSize = 18.dp,
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
                                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
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
                                    Text(stringResource(R.string.import_from_clipboard))
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
                                    Text(stringResource(R.string.import_from_file))
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
                                    Text(stringResource(R.string.scan_qr_code))
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
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.refresh))
                        }
                    }
                }
            }
            item {
                FoxholeCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HomeCardHeader(
                            icon = Icons.Outlined.Public,
                            title = stringResource(R.string.home_network_title),
                            trailing = {
                                IconButton(
                                    onClick = onRefreshIpInfo,
                                    enabled = !state.autoConnect.running,
                                    modifier = Modifier.size(32.dp).testTag("home_refresh_ip_icon"),
                                ) {
                                    Icon(
                                        Icons.Outlined.Refresh,
                                        contentDescription = stringResource(R.string.refresh_ip_info),
                                    )
                                }
                            },
                        )
                        Box(
                            modifier = Modifier.fillMaxWidth().height(HomeNetworkContentHeight),
                            contentAlignment = Alignment.TopStart,
                        ) {
                            if (showNetworkLoading) {
                                HomeNetworkLoadingBlock(modifier = Modifier.testTag("home_network_loading"))
                            } else if (visibleNetworkIpInfo == null) {
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        text = stringResource(R.string.home_network_unavailable),
                                        modifier = Modifier.testTag("home_network_country"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "-",
                                        modifier = Modifier.testTag("home_network_primary_ip"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(R.string.home_network_retry_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                val networkIpInfo = visibleNetworkIpInfo
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text(
                                        text = buildCountryLine(networkIpInfo),
                                        modifier = Modifier.testTag("home_network_country"),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = primaryVisibleIp(networkIpInfo),
                                        modifier = Modifier.testTag("home_network_primary_ip"),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    secondaryVisibleIp(networkIpInfo)?.let { secondary ->
                                        Text(
                                            text = "IPv6 $secondary",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    remoteVisibleDnsServers(networkIpInfo).takeIf { it.isNotEmpty() }?.let { dnsServers ->
                                        Text(
                                            text = "${stringResource(R.string.network_dns_remote)}: ${dnsServers.joinToString(separator = " • ")}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    networkIpInfo.isp?.takeIf { it.isNotBlank() }?.let { provider ->
                                        Text(
                                            text = provider,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                val totals = visibleProfileTrafficTotals(state)
                val totalBytes = totals.sumOf { it.rxTotalBytes + it.txTotalBytes }
                val totalDays =
                    ((System.currentTimeMillis() - state.settings.usageTrackingStartedAt).coerceAtLeast(0L) / 86_400_000L) + 1L
                FoxholeCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HomeCardHeader(
                            icon = Icons.Outlined.SwapVert,
                            title = stringResource(R.string.home_traffic_title),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text =
                                        buildAnnotatedString {
                                            append(stringResource(R.string.home_total_traffic_title))
                                            append(" ")
                                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                                append(stringResource(R.string.home_total_traffic_days, totalDays))
                                            }
                                        },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = formatBytes(context, totalBytes),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                                IconButton(
                                    onClick = onResetUsageTracking,
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DeleteSweep,
                                        contentDescription = stringResource(R.string.reset_usage_tracking),
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                val hasIncomingTraffic = state.traffic.rxBytesPerSec > 0L
                                val hasOutgoingTraffic = state.traffic.txBytesPerSec > 0L
                                val incomingTrafficTint =
                                    if (hasIncomingTraffic) {
                                        FoxholePositiveAccent
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                val outgoingTrafficTint =
                                    if (hasOutgoingTraffic) {
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
                                    connectionDurationText?.let { duration ->
                                        Text(
                                            text =
                                                buildAnnotatedString {
                                                    withStyle(
                                                        SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                                    ) {
                                                        append(stringResource(R.string.home_connection_time_label))
                                                        append(" ")
                                                    }
                                                    append(duration)
                                                },
                                            modifier = Modifier.testTag("home_connection_duration"),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.SemiBold,
                                            textAlign = TextAlign.End,
                                            maxLines = 1,
                                        )
                                    }
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
                                            label = context.getString(R.string.username),
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
                                            label = context.getString(R.string.password),
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
}
