package com.foxhole.guard.ui.cli.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.HomeAdditionalInfoCategory
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.RoutingModePreset
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMapUiState
import com.foxhole.core.model.networkUp
import com.foxhole.guard.R
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.PendingRoutingScenarioChange
import com.foxhole.guard.ui.TorTransitionPrompt
import com.foxhole.guard.ui.cli.CliCommands
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.ui.cli.LocalCliColors
import com.foxhole.guard.ui.cli.cliBootstrapFade
import com.foxhole.guard.ui.cli.cliBootstrapSwap
import com.foxhole.guard.ui.cli.cliProfileSelectorSizeSpec
import com.foxhole.guard.ui.cli.cliSlide
import com.foxhole.guard.ui.cli.cliVerticalEnter
import com.foxhole.guard.ui.cli.cliVerticalExit
import com.foxhole.guard.ui.cli.cliVerticalSizeSpec
import com.foxhole.guard.ui.cli.components.CliChromeTailSpacer
import com.foxhole.guard.ui.cli.components.CliConfirmSheet
import com.foxhole.guard.ui.cli.components.CliHomeSectionGap
import com.foxhole.guard.ui.cli.components.CliLoadingRow
import com.foxhole.guard.ui.cli.components.CliPanel
import com.foxhole.guard.ui.cli.components.CliRoutingChangeConfirmSheet
import com.foxhole.guard.ui.cli.map.CliMapOverviewContent
import com.foxhole.guard.ui.cli.map.CliMapOverviewPanel
import com.foxhole.guard.ui.cli.map.rememberCliMapOverviewState
import com.foxhole.guard.ui.cli.onboarding.CliQuickStartSheetContent
import com.foxhole.guard.ui.confirmPendingRoutingScenario
import com.foxhole.guard.ui.dismissPendingRoutingScenario
import com.foxhole.guard.ui.isPrimaryConnectionRuntime
import com.foxhole.guard.ui.loadInstalledApps
import com.foxhole.guard.ui.onQuickSelectorSingleProfileSelected
import com.foxhole.guard.ui.refreshIpInfo
import com.foxhole.guard.ui.trafficmap.TrafficMapAssetState
import com.foxhole.guard.ui.trafficmap.TrafficMapAssets
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun CliHomeScreen(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    terminalListState: LazyListState,
    terminalFollowsOutput: Boolean,
    onTerminalFollowsOutputChanged: (Boolean) -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val home by viewModel.homeRouteState.collectAsStateWithLifecycle()
    val subscriptionRefreshInProgress by
        viewModel.manualSubscriptionRefreshInProgress.collectAsStateWithLifecycle()
    val torIdentityProbe by viewModel.torIdentityProbe.collectAsStateWithLifecycle()
    val pendingRoutingChange by
        viewModel.pendingRoutingScenarioConfirmation.collectAsStateWithLifecycle()
    val profileSelector = rememberCliProfileSelectorCoordinator(viewModel)
    var pendingModeCycle by rememberSaveable { mutableStateOf<CliConnectMode?>(null) }
    var clearTerminalOpen by rememberSaveable { mutableStateOf(false) }
    var quickStartOpen by rememberSaveable { mutableStateOf(false) }
    val profileGeoRefresh = rememberProfileGeoRefresh(viewModel, terminal, home)
    LaunchedEffect(Unit) {
        onTerminalFollowsOutputChanged(true)
    }

    CliHomeNarrationEffects(
        viewModel = viewModel,
        terminal = terminal,
        home = home,
        torIdentityProbe = torIdentityProbe,
    )

    val connection = home.connection
    val connected = connection.isRouteConnection()
    val busy = connection.isRouteTransition()

    val statusPrinters = rememberCliStatusPrinters(viewModel = viewModel, terminal = terminal, home = home)

    if (quickStartOpen) {
        CliQuickStartSheetContent(onDismiss = { quickStartOpen = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(profileSelector.open) {
                if (profileSelector.open) detectTapGestures { profileSelector.close() }
            }
            .padding(horizontal = CliSpacing.md),
    ) {
        CliTerminalPanel(
            terminal = terminal,
            home = home,
            listState = terminalListState,
            followsOutput = terminalFollowsOutput,
            onFollowsOutputChanged = onTerminalFollowsOutputChanged,
            onInteraction = profileSelector.close,
            onClearRequested = { clearTerminalOpen = true },
            onHelpRequested = { quickStartOpen = true },
            contentAfterHeader = {
                CliHomeAdditionalInfoSlot(
                    viewModel = viewModel,
                    home = home,
                    onOpenMap = onOpenMap,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        CliClearTerminalSheet(
            visible = clearTerminalOpen,
            terminal = terminal,
            onDismiss = { clearTerminalOpen = false },
        )
        CliHomeSectionGap()

        CliHomeConfirmationSlot(
            viewModel = viewModel,
            terminal = terminal,
            routingChange = pendingRoutingChange,
            torPrompt = home.torTransitionPrompt,
        )

        CliHomeProfileArea(
            viewModel = viewModel,
            home = home,
            torIdentityProbe = torIdentityProbe,
            connected = connected,
            selectorOpen = profileSelector.open,
            expandedSmartId = profileSelector.expandedSmartId,
            selectorCollapsePending = profileSelector.collapsePending,
            onSelectorCollapseFinished = profileSelector.finishCollapse,
            onExpandedSmartChange = profileSelector.changeExpanded,
            selectionCommitPending = profileSelector.selectionCommitPending,
            onProfileSelected = profileSelector.selectProfile,
            onProtocolSelected = profileSelector.selectProtocol,
            onSelectionSlideFinished = profileSelector.finishSelection,
            onSelectorOpen = profileSelector.openSelector,
            onSelectorClose = profileSelector.close,
            onProfileHold = profileGeoRefresh.onRefresh,
        )

        CliHomeSectionGap()
        CliHomeButtonsArea(
            viewModel = viewModel,
            terminal = terminal,
            home = home,
            connected = connected,
            busy = busy,
            subscriptionRefreshInProgress = subscriptionRefreshInProgress,
            atomicModePromptPending =
            pendingRoutingChange is PendingRoutingScenarioChange.OperatingMode,
            pendingModeCycle = pendingModeCycle,
            onPendingModeCycleChanged = { pendingModeCycle = it },
            statusPrinters = statusPrinters,
            onCloseSelector = profileSelector.close,
        )
        CliChromeTailSpacer(extraGap = 0.dp)
    }
}

@Composable
private fun CliHomeAdditionalInfoSlot(
    viewModel: HomeViewModel,
    home: HomeRouteUiState,
    onOpenMap: () -> Unit,
) {
    AnimatedVisibility(
        visible = home.settings.ui.showHomeAdditionalInfo,
        enter = cliVerticalEnter(),
        exit = cliVerticalExit(),
    ) {
        Column {
            CliHomeAdditionalInfoPanel(
                viewModel = viewModel,
                home = home,
                onOpenMap = onOpenMap,
            )
            CliHomeSectionGap()
        }
    }
}

@Composable
private fun CliHomeAdditionalInfoPanel(
    viewModel: HomeViewModel,
    home: HomeRouteUiState,
    onOpenMap: () -> Unit,
) {
    val category = home.settings.ui.homeAdditionalInfoCategory
    val colors = LocalCliColors.current
    val map by viewModel.trafficMapUiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val assetState by TrafficMapAssets.state.collectAsStateWithLifecycle()
    val mapAssetsReady = rememberCliHomeMapAssetsReady(
        category = category,
        assetState = assetState,
        prewarm = { TrafficMapAssets.prewarm(context) },
    )
    val previewReady = cliHomeAdditionalInfoReady(
        profilesLoaded = home.profilesLoaded,
        settingsHydrated = home.settingsHydrated,
        mapAssetsReady = mapAssetsReady,
    )
    val sizeAnimation = if (category == HomeAdditionalInfoCategory.ROUTE) {
        Modifier.animateContentSize(animationSpec = cliVerticalSizeSpec())
    } else {
        Modifier
    }
    CliHomeSectionTypography {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(CLI_HOME_ADDITIONAL_INFO_TAG)
                .then(sizeAnimation),
        ) {
            AnimatedContent(
                targetState = previewReady,
                transitionSpec = { cliBootstrapFade() },
                label = "homeAdditionalInfoReady",
            ) { ready ->
                if (!ready) {
                    CliHomeAdditionalInfoPreloader(
                        category = category,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    CliMapOverviewPanel(
                        map = map,
                        home = home,
                        overview = rememberCliMapOverviewState(map = map, home = home),
                        content = category.cliMapOverviewContent(),
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenMap.takeIf { category == HomeAdditionalInfoCategory.MAP },
                        title = category.cliHomeAdditionalInfoTitle(),
                        icon = category.cliHomeAdditionalInfoIcon(),
                        titleModifier = Modifier.cliHomeSectionHeaderPlacement(),
                        titleColor = colors.accent,
                        mapHorizontalInset = HOME_MAP_HORIZONTAL_INSET,
                        mapLegendTopSpacing = HOME_MAP_LEGEND_TOP_SPACING,
                        mapLegendCentered = true,
                        mapLegendOffsetY = HOME_MAP_LEGEND_OFFSET_Y,
                        showRouteHeader = category != HomeAdditionalInfoCategory.ROUTE,
                    )
                }
            }
        }
    }
}

@Composable
private fun CliHomeAdditionalInfoPreloader(
    category: HomeAdditionalInfoCategory,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCliColors.current
    val placeholderMap = remember { TrafficMapUiState() }
    val placeholderHome = remember { HomeRouteUiState() }
    Box(modifier = modifier) {
        CliMapOverviewPanel(
            map = placeholderMap,
            home = placeholderHome,
            overview = rememberCliMapOverviewState(map = placeholderMap, home = placeholderHome),
            content = category.cliMapOverviewContent(),
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0f)
                .clearAndSetSemantics {},
            title = category.cliHomeAdditionalInfoTitle(),
            icon = category.cliHomeAdditionalInfoIcon(),
            titleModifier = Modifier.cliHomeSectionHeaderPlacement(),
            titleColor = colors.accent,
            mapHorizontalInset = HOME_MAP_HORIZONTAL_INSET,
            mapLegendTopSpacing = HOME_MAP_LEGEND_TOP_SPACING,
            mapLegendCentered = true,
            mapLegendOffsetY = HOME_MAP_LEGEND_OFFSET_Y,
            showRouteHeader = category != HomeAdditionalInfoCategory.ROUTE,
            prewarmMapAssets = false,
        )
        CliPanel(
            modifier = Modifier.matchParentSize(),
            title = category.cliHomeAdditionalInfoTitle(),
            titleModifier = Modifier.cliHomeSectionHeaderPlacement(),
            titleColor = colors.accent,
            icon = category.cliHomeAdditionalInfoIcon(),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CliLoadingRow(text = stringResource(R.string.cli_common_loading_data))
            }
        }
    }
}

internal const val CLI_HOME_ADDITIONAL_INFO_TAG = "cli_home_additional_info"

internal fun cliHomeAdditionalInfoReady(
    profilesLoaded: Boolean,
    settingsHydrated: Boolean,
    mapAssetsReady: Boolean,
): Boolean = profilesLoaded && settingsHydrated && mapAssetsReady

@Composable
internal fun rememberCliHomeMapAssetsReady(
    category: HomeAdditionalInfoCategory,
    assetState: TrafficMapAssetState,
    prewarm: () -> Unit,
): Boolean {
    val currentPrewarm by rememberUpdatedState(prewarm)
    LaunchedEffect(category, assetState) {
        if (
            category == HomeAdditionalInfoCategory.MAP &&
            assetState is TrafficMapAssetState.Idle
        ) {
            currentPrewarm()
        }
    }
    return category != HomeAdditionalInfoCategory.MAP ||
        assetState is TrafficMapAssetState.Ready ||
        assetState is TrafficMapAssetState.Error
}

private fun HomeAdditionalInfoCategory.cliMapOverviewContent(): CliMapOverviewContent =
    when (this) {
        HomeAdditionalInfoCategory.MAP -> CliMapOverviewContent.MAP
        HomeAdditionalInfoCategory.ROUTE -> CliMapOverviewContent.ROUTE
    }

@Composable
private fun HomeAdditionalInfoCategory.cliHomeAdditionalInfoTitle(): String =
    stringResource(
        when (this) {
            HomeAdditionalInfoCategory.MAP -> R.string.cli_cfg_home_additional_info_map
            HomeAdditionalInfoCategory.ROUTE -> R.string.cli_cfg_home_additional_info_route
        },
    )

private fun HomeAdditionalInfoCategory.cliHomeAdditionalInfoIcon(): Int =
    when (this) {
        HomeAdditionalInfoCategory.MAP -> R.drawable.lin_map
        HomeAdditionalInfoCategory.ROUTE -> R.drawable.lin_link
    }

private val HOME_MAP_HORIZONTAL_INSET = 16.dp
private val HOME_MAP_LEGEND_TOP_SPACING = 2.dp
private val HOME_MAP_LEGEND_OFFSET_Y = 3.dp

@Composable
@Suppress("LongParameterList")
private fun CliHomeButtonsArea(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    connected: Boolean,
    busy: Boolean,
    subscriptionRefreshInProgress: Boolean,
    atomicModePromptPending: Boolean,
    pendingModeCycle: CliConnectMode?,
    onPendingModeCycleChanged: (CliConnectMode?) -> Unit,
    statusPrinters: CliStatusPrinters,
    onCloseSelector: () -> Unit,
) {
    AnimatedContent(
        targetState = cliHomeButtonsReady(home.profilesLoaded, home.settingsHydrated),
        transitionSpec = { cliBootstrapSwap() },
        label = "homeButtons",
    ) { buttonsReady ->
        if (buttonsReady) {
            Column {
                CliSecondaryButtonsRow(
                    viewModel = viewModel,
                    terminal = terminal,
                    home = home,
                    connected = connected,
                    onStatus = {
                        onCloseSelector()
                        statusPrinters.tap()
                    },
                    onStatusHold = {
                        onCloseSelector()
                        statusPrinters.hold()
                    },
                )
                Spacer(modifier = Modifier.height(CliSpacing.sm))
                CliPrimaryButtonsRow(
                    viewModel = viewModel,
                    terminal = terminal,
                    home = home,
                    connected = connected,
                    busy = busy,
                    subscriptionRefreshInProgress = subscriptionRefreshInProgress,
                    atomicModePromptPending = atomicModePromptPending,
                    pendingModeCycle = pendingModeCycle,
                    onPendingModeCycleChanged = onPendingModeCycleChanged,
                    onInteraction = onCloseSelector,
                )
            }
        } else {
            CliHomeButtonsLoadingState()
        }
    }
}

private data class CliProfileSelectorCoordinator(
    val open: Boolean,
    val expandedSmartId: Long?,
    val collapsePending: Boolean,
    val finishCollapse: () -> Unit,
    val selectionCommitPending: Boolean,
    val close: () -> Unit,
    val openSelector: () -> Unit,
    val changeExpanded: (Long?) -> Unit,
    val selectProfile: (Long) -> Unit,
    val selectProtocol: (Long, String) -> Unit,
    val finishSelection: () -> Unit,
)

@Composable
private fun rememberCliProfileSelectorCoordinator(
    viewModel: HomeViewModel,
): CliProfileSelectorCoordinator {
    var selectorOpen by rememberSaveable { mutableStateOf(false) }
    var expandedSmartId by rememberSaveable { mutableStateOf<Long?>(null) }
    var backCollapsePending by remember { mutableStateOf(false) }
    var pendingSelection by remember { mutableStateOf<CliPendingSelectorSelection?>(null) }
    val closeSelector: () -> Unit = {
        backCollapsePending = false
        selectorOpen = false
    }
    val finishCollapse: () -> Unit = {
        if (backCollapsePending) {
            selectorOpen = false
            backCollapsePending = false
        }
    }
    LaunchedEffect(pendingSelection) {
        if (pendingSelection == null) return@LaunchedEffect
        if (expandedSmartId != null) {
            backCollapsePending = true
            expandedSmartId = null
        } else if (!backCollapsePending) {
            selectorOpen = false
        }
    }
    CliSelectorBackHandler(
        selectorOpen = selectorOpen,
        expandedSmartId = expandedSmartId,
        collapsePending = backCollapsePending,
        onCollapse = {
            backCollapsePending = true
            expandedSmartId = null
        },
        onClose = closeSelector,
    )
    return CliProfileSelectorCoordinator(
        open = selectorOpen,
        expandedSmartId = expandedSmartId,
        collapsePending = backCollapsePending,
        finishCollapse = finishCollapse,
        selectionCommitPending = pendingSelection != null,
        close = closeSelector,
        openSelector = { selectorOpen = true },
        changeExpanded = { expandedSmartId = it },
        selectProfile = { profileId ->
            if (pendingSelection == null) {
                pendingSelection = CliPendingSelectorSelection.Profile(profileId)
            }
        },
        selectProtocol = { profileId, optionId ->
            if (pendingSelection == null) {
                pendingSelection = CliPendingSelectorSelection.Protocol(profileId, optionId)
            }
        },
        finishSelection = {
            when (val selection = pendingSelection) {
                is CliPendingSelectorSelection.Profile ->
                    viewModel.onQuickSelectorSingleProfileSelected(selection.profileId)
                is CliPendingSelectorSelection.Protocol ->
                    viewModel.onSelectProfileProtocolOption(selection.profileId, selection.optionId)
                null -> Unit
            }
            pendingSelection = null
        },
    )
}

@Composable
@Suppress("LongParameterList")
private fun CliHomeProfileArea(
    viewModel: HomeViewModel,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: com.foxhole.guard.ui.TorIdentityProbeState,
    connected: Boolean,
    selectorOpen: Boolean,
    expandedSmartId: Long?,
    selectorCollapsePending: Boolean,
    onSelectorCollapseFinished: () -> Unit,
    onExpandedSmartChange: (Long?) -> Unit,
    selectionCommitPending: Boolean,
    onProfileSelected: (Long) -> Unit,
    onProtocolSelected: (Long, String) -> Unit,
    onSelectionSlideFinished: () -> Unit,
    onSelectorOpen: () -> Unit,
    onSelectorClose: () -> Unit,
    onProfileHold: () -> Unit,
) {
    var profileAreaMeasuredHeightPx by rememberSaveable { mutableIntStateOf(0) }
    val smartSelectorHeightActive = expandedSmartId != null
    val density = LocalDensity.current
    val profileAreaMeasuredHeight = with(density) { profileAreaMeasuredHeightPx.toDp() }
    val selectorHeightConstraint = if (
        cliProfileSelectorUsesFixedHeight(
            measuredHeightPx = profileAreaMeasuredHeightPx,
            smartHeightActive = smartSelectorHeightActive,
        )
    ) {
        Modifier.height(profileAreaMeasuredHeight)
    } else {
        Modifier.heightIn(min = profileAreaMeasuredHeight)
    }
    val selectorHeightModifier = Modifier
        .animateContentSize(
            animationSpec = cliProfileSelectorSizeSpec(),
            alignment = Alignment.BottomStart,
            finishedListener = { _, _ ->
                if (selectorCollapsePending) onSelectorCollapseFinished()
            },
        )
        .then(selectorHeightConstraint)
    val surface = when {
        selectorOpen -> CliProfileAreaSurface.SELECTOR
        !cliHomeButtonsReady(home.profilesLoaded, home.settingsHydrated) ->
            CliProfileAreaSurface.LOADING
        else -> CliProfileAreaSurface.FACTS
    }
    val surfaceTransition = updateTransition(targetState = surface, label = "profileArea")
    CliProfileAreaTransitionEffects(
        transition = surfaceTransition,
        expandedSmartId = expandedSmartId,
        selectionCommitPending = selectionCommitPending,
        onExpandedSmartChange = onExpandedSmartChange,
        onSelectionSlideFinished = onSelectionSlideFinished,
    )
    surfaceTransition.AnimatedContent(
        transitionSpec = {
            if (
                initialState == CliProfileAreaSurface.LOADING ||
                targetState == CliProfileAreaSurface.LOADING
            ) {
                cliBootstrapSwap()
            } else {
                cliSlide(
                    forward = targetState == CliProfileAreaSurface.SELECTOR ||
                        selectionCommitPending,
                )
            }
        },
    ) { shown ->
        when (shown) {
            CliProfileAreaSurface.SELECTOR ->
                CliProfileQuickSelector(
                    viewModel = viewModel,
                    expandedSmartId = expandedSmartId,
                    onExpandedSmartChange = onExpandedSmartChange,
                    onProfileSelected = onProfileSelected,
                    onProtocolSelected = onProtocolSelected,
                    onDone = onSelectorClose,
                    modifier = selectorHeightModifier,
                )
            CliProfileAreaSurface.LOADING ->
                CliHomeBootLoadingPanel(viewModel = viewModel, home = home)
            CliProfileAreaSurface.FACTS ->
                CliConnectionFactsPanel(
                    viewModel = viewModel,
                    home = home,
                    torIdentityProbe = torIdentityProbe,
                    connected = connected,
                    onProfileTap = onSelectorOpen,
                    onProfileHold = {
                        onSelectorClose()
                        onProfileHold()
                    },
                    modifier = Modifier
                        .onSizeChanged { size ->
                            profileAreaMeasuredHeightPx = size.height
                        },
                )
        }
    }
}

@Composable
private fun CliProfileAreaTransitionEffects(
    transition: androidx.compose.animation.core.Transition<CliProfileAreaSurface>,
    expandedSmartId: Long?,
    selectionCommitPending: Boolean,
    onExpandedSmartChange: (Long?) -> Unit,
    onSelectionSlideFinished: () -> Unit,
) {
    LaunchedEffect(
        transition.currentState,
        transition.targetState,
        expandedSmartId,
        selectionCommitPending,
    ) {
        if (
            transition.currentState == CliProfileAreaSurface.FACTS &&
            transition.targetState == CliProfileAreaSurface.FACTS &&
            expandedSmartId != null
        ) {
            onExpandedSmartChange(null)
        }
        if (
            selectionCommitPending &&
            transition.currentState == CliProfileAreaSurface.FACTS &&
            transition.targetState == CliProfileAreaSurface.FACTS
        ) {
            onSelectionSlideFinished()
        }
    }
}

internal fun cliProfileSelectorUsesFixedHeight(
    measuredHeightPx: Int,
    smartHeightActive: Boolean,
): Boolean = measuredHeightPx > 0 && !smartHeightActive

private enum class CliProfileAreaSurface { SELECTOR, LOADING, FACTS }

private sealed interface CliPendingSelectorSelection {
    data class Profile(val profileId: Long) : CliPendingSelectorSelection

    data class Protocol(val profileId: Long, val optionId: String) : CliPendingSelectorSelection
}

@Composable
private fun CliSelectorBackHandler(
    selectorOpen: Boolean,
    expandedSmartId: Long?,
    collapsePending: Boolean,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(enabled = selectorOpen) {
        when {
            collapsePending -> Unit
            expandedSmartId != null -> onCollapse()
            else -> onClose()
        }
    }
}

@Composable
private fun CliHomeConfirmationSlot(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    routingChange: PendingRoutingScenarioChange?,
    torPrompt: TorTransitionPrompt?,
) {
    routingChange?.let { change ->
        CliRoutingChangeConfirmSheet(
            change = change,
            onConfirm = {
                when (change) {
                    is PendingRoutingScenarioChange.OperatingMode ->
                        terminal.command(modeCommandFor(change.target))
                    is PendingRoutingScenarioChange.I2pRelay ->
                        terminal.command(CliCommands.i2p(change.targetEnabled))
                    is PendingRoutingScenarioChange.Tor,
                    is PendingRoutingScenarioChange.Vpn,
                    -> Unit
                }
                viewModel.confirmPendingRoutingScenario()
            },
            onDismiss = viewModel::dismissPendingRoutingScenario,
        )
    } ?: torPrompt?.let { prompt ->
        CliTorPromptPanel(
            viewModel = viewModel,
            prompt = prompt,
            onLiveModeSwitchConfirmed = { target ->
                terminal.command(modeCommandFor(target))
            },
        )
    }
}

@Immutable
private data class CliProfileGeoRefreshActions(
    val onRefresh: () -> Unit,
)

@Composable
private fun rememberProfileGeoRefresh(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
): CliProfileGeoRefreshActions {
    var pending by remember { mutableStateOf(false) }
    var observedLoading by remember { mutableStateOf(false) }
    var baselineAt by remember { mutableLongStateOf(0L) }
    val latestFetchedAt = home.latestNetworkGeoFetchedAt()
    val refreshCommand = stringResource(R.string.cli_cmd_update_geodata)
    val refreshingMessage = stringResource(R.string.cli_home_network_geo_refreshing)
    val updatedMessage = stringResource(R.string.cli_home_network_geo_updated)
    LaunchedEffect(home.ipInfoLoading, latestFetchedAt, pending, terminal.promptText) {
        if (!pending) return@LaunchedEffect
        if (home.ipInfoLoading) {
            observedLoading = true
        } else if (observedLoading) {
            if (terminal.promptText != null) return@LaunchedEffect
            delay(GEO_TERMINAL_PROGRESS_VISIBLE_MS)
            terminal.finishGeoRefresh()
            if (latestFetchedAt > baselineAt) terminal.note(updatedMessage, CliLineTone.INFO)
            pending = false
            observedLoading = false
        }
    }
    return CliProfileGeoRefreshActions(
        onRefresh = {
            baselineAt = latestFetchedAt
            observedLoading = home.ipInfoLoading
            pending = true
            terminal.command(refreshCommand)
            terminal.beginGeoRefresh(refreshingMessage)
            viewModel.refreshIpInfo()
        },
    )
}

@Composable
private fun CliClearTerminalSheet(
    visible: Boolean,
    terminal: CliTerminalState,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    CliConfirmSheet(
        title = stringResource(R.string.cli_terminal_clear_title),
        question = stringResource(R.string.cli_terminal_clear_question),
        icon = R.drawable.lin_trash,
        onConfirm = {
            terminal.clearHistory()
            onDismiss()
        },
        onDismiss = onDismiss,
    )
}

internal fun com.foxhole.guard.ui.HomeRouteUiState.latestNetworkGeoFetchedAt(): Long =
    maxOf(
        deviceIpInfo?.fetchedAt ?: 0L,
        ipInfo?.fetchedAt ?: 0L,
        torIpInfo?.fetchedAt ?: 0L,
    )

internal fun cliHomeButtonsReady(profilesLoaded: Boolean, settingsHydrated: Boolean): Boolean =
    profilesLoaded && settingsHydrated

internal const val CLI_HOME_BUTTONS_LOADING_TAG = "cli_home_buttons_loading"

@Composable
private fun CliHomeButtonsLoadingState() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(CLI_HOME_BUTTONS_BLOCK_HEIGHT)
            .testTag(CLI_HOME_BUTTONS_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CliLoadingRow(text = stringResource(R.string.cli_common_loading_interface))
    }
}

private val CLI_HOME_BUTTONS_BLOCK_HEIGHT = 48.dp * 2 + CliSpacing.sm

@Composable
private fun CliHomeNarrationEffects(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
    torIdentityProbe: com.foxhole.guard.ui.TorIdentityProbeState,
) {
    val pendingFirewallPackages = home.settings.expert.pendingQuarantinePackages
    val pendingFirewallMessage = androidx.compose.ui.res.pluralStringResource(
        R.plurals.cli_firewall_action_required_status,
        pendingFirewallPackages.size,
        pendingFirewallPackages.size,
    )
    val torOnlyLive = isTorOnlyLive(home)
    val activeRuntimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val currentStatusWord = cliStatusWordFor(
        state = home.connection.routeState(),
        runtimes = activeRuntimes,
        i2pConnected = activeRuntimes.i2p && home.i2pPhase.phase.networkUp,
        firewallLive = cliFirewallLive(
            settings = home.settings,
            connection = home.connection,
            runtimes = activeRuntimes,
        ),
    )
    val currentStatusText = cliStatusWordText(currentStatusWord)
    LaunchedEffect(Unit) { viewModel.loadInstalledApps() }
    LaunchedEffect(Unit) { terminal.welcome(com.foxhole.guard.BuildConfig.VERSION_NAME) }
    val bootstrapReady = cliHomeButtonsReady(home.profilesLoaded, home.settingsHydrated)
    LaunchedEffect(bootstrapReady, currentStatusText, currentStatusWord) {
        terminal.updateCurrentStatus(currentStatusText, cliStatusWordTone(currentStatusWord))
        terminal.onBootStage(bootstrapReady)
    }
    LaunchedEffect(home.settingsHydrated, pendingFirewallPackages) {
        if (home.settingsHydrated) {
            terminal.onPendingFirewallActions(pendingFirewallPackages, pendingFirewallMessage)
        }
    }
    LaunchedEffect(
        home.connection,
        home.torPhase,
        torIdentityProbe,
        home.i2pPhase,
        home.ipInfo,
        home.torIpInfo,
        home.ipInfoLoading,
    ) {
        terminal.onConnection(home.connection)
        terminal.onTorPhase(home.torPhase)
        terminal.onTorIdentityProbe(torIdentityProbe)
        terminal.onI2pPhase(home.i2pPhase)
        terminal.onRouteIpInfo(
            vpnInfo = terminalVpnIdentity(home, terminal.vpnIdentityNotBeforeMs),
            torInfo = home.torIpInfo.takeIf { home.torPhase.phase == TorNetworkPhase.CONNECTED },
        )
    }
    val torNarrationRevision = terminal.torNarrationRevision
    LaunchedEffect(torNarrationRevision) {
        if (terminal.torNarrationPending) {
            delay(TOR_TERMINAL_STAGE_VISIBLE_MS)
            terminal.advanceTorNarration()
        }
    }
}

private const val TOR_TERMINAL_STAGE_VISIBLE_MS = 650L
private const val GEO_TERMINAL_PROGRESS_VISIBLE_MS = 450L

internal fun terminalVpnIdentity(
    home: com.foxhole.guard.ui.HomeRouteUiState,
    notBeforeMs: Long? = home.connection.lastChangeAt,
): IpInfo? =
    distinctVpnRouteIdentity(
        vpnInfo = home.ipInfo,
        torInfo = home.torIpInfo,
        torRouteObserved = home.connection.torActive && home.connection.appliedTorRoute != null,
    )?.takeIf { info ->
        home.connection.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED) &&
            home.connection.isPrimaryConnectionRuntime() &&
            notBeforeMs != null &&
            info.fetchedAt >= notBeforeMs
    }

@Immutable
private data class CliStatusPrinters(
    val tap: () -> Unit,
    val hold: () -> Unit,
)

@Composable
private fun rememberCliStatusPrinters(
    viewModel: HomeViewModel,
    terminal: CliTerminalState,
    home: com.foxhole.guard.ui.HomeRouteUiState,
): CliStatusPrinters {
    val torOnlyLive = isTorOnlyLive(home)
    val statusRows = cliStatusRows(home = home, torOnlyLive = torOnlyLive)
    var pendingExtras by remember { mutableStateOf<CliStatusExtras?>(null) }
    val extendedRows = pendingExtras?.let { extras ->
        cliExtendedStatusRows(home = home, torOnlyLive = torOnlyLive, extras = extras)
    }
    LaunchedEffect(pendingExtras) {
        if (extendedRows != null) {
            terminal.emitBlock(extendedRows)
            pendingExtras = null
        }
    }
    val scope = rememberCoroutineScope()
    val statusCommand = stringResource(R.string.cli_cmd_status)
    val statusNote = stringResource(R.string.cli_home_status_note)
    val statusFullCommand = stringResource(R.string.cli_cmd_status_full)
    val statusFullNote = stringResource(R.string.cli_home_status_full_note)
    return CliStatusPrinters(
        tap = {
            terminal.command(statusCommand)
            terminal.footnote(statusNote)
            terminal.emitBlock(statusRows)
        },
        hold = {
            terminal.command(statusFullCommand)
            terminal.footnote(statusFullNote)
            viewModel.refreshIpInfo()
            scope.launch { pendingExtras = viewModel.cliStatusExtras() }
        },
    )
}

internal enum class CliConnectMode(
    val label: String,
    val preset: RoutingModePreset,
) {
    VPN("VPN", RoutingModePreset.VPN),
    TOR("TOR", RoutingModePreset.TOR),
    VPN_TOR("VPN+TOR", RoutingModePreset.VPN_TOR),
}
