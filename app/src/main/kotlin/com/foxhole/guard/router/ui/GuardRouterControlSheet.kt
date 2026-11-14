package com.foxhole.guard.router.ui

import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.graphics.graphicsLayer
import com.foxhole.guard.router.routerDisplayCountryCode

import androidx.compose.foundation.layout.Spacer
import com.foxhole.guard.router.RouterUpdatePollBudget
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import com.foxhole.guard.ui.cli.components.CliButton
import com.foxhole.guard.ui.cli.components.CliMetricSpinner
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.foxhole.guard.router.RouterEngineOperation
import com.foxhole.guard.router.RouterEngineImpact
import com.foxhole.guard.router.RouterEngineJobState
import com.foxhole.guard.router.RouterInstallProtocol
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxhole.guard.R
import com.foxhole.guard.ui.cli.CliSpacing
import com.foxhole.guard.router.ui.shareGuardRouterProfile
import com.foxhole.guard.router.RouterControlCoordinator
import com.foxhole.guard.router.RouterControlStage
import com.foxhole.guard.router.RouterManagementLanding
import com.foxhole.guard.router.RouterWizardError
import com.foxhole.guard.router.RouterControlState
import com.foxhole.guard.router.RouterLanDeviceKind
import com.foxhole.guard.router.routerInstalledProtocols
import com.foxhole.guard.router.routerLanDeviceKind
import com.foxhole.guard.router.visibleRouterLanDevices
import com.foxhole.guard.router.RouterUpdateAvailability
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.foxhole.guard.router.routerVpnServerEnabled
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private data class RouterActionTarget(val id: String, val remote: Boolean = false, val device: Boolean = false)
private enum class ControlPage { LOADING, HUB, ROUTER_VPN, CLIENTS, DEVICE, ACTIONS, CREATE, PROFILE, SETTINGS, PROTOCOLS, ENGINE_PLAN, ENGINE_JOB, UPDATES, PHONE_PROFILES, HOME_SERVER, ABOUT }

@Composable
internal fun GuardRouterControlSheet(
    coordinator: RouterControlCoordinator,
    onPair: () -> Unit,
    onUnpaired: () -> Unit = onPair,
    requestAccess: ((() -> Unit) -> Unit),
    requestHomeEnable: ((() -> Unit) -> Unit),
    enabled: Boolean = true,
    onClose: (() -> Unit)? = null,
    onAddRouter: () -> Unit = onPair,
    profileProbe: com.foxhole.guard.router.RouterProfileProbeState? = null,
    canInstallRouter: Boolean = false,
    canInspectRouter: Boolean = true,
    onRetryPairing: () -> Unit = onPair,
    phoneProfileRequests: List<com.foxhole.guard.router.RouterPhoneTransferRequest> = emptyList(),
    exportPhoneProfile: (suspend (com.foxhole.guard.router.RouterPhoneTransferRequest) -> com.foxhole.guard.router.RouterProfileSelection?)? = null,
) {
    val state by coordinator.state.collectAsStateWithLifecycle()
    val networkGeneration by rememberRouterNetworkGeneration()
    LaunchedEffect(coordinator, networkGeneration) { coordinator.checkHomeVpnTransport() }
    val scope = rememberCoroutineScope()
    val phonePicker = remember(coordinator, scope) { RouterPhonePickerState(coordinator, scope) }
    DisposableEffect(phonePicker) { onDispose { phonePicker.cancel() } }
    val resources = LocalResources.current
    val context = LocalContext.current
    val notices = requireNotNull(LocalGuardRouterNotices.current)
    val current by rememberUpdatedState(enabled)
    var settledLanding by remember(coordinator, state.selectedRouterId) { mutableStateOf<ControlPage?>(null) }
    val landing = if (state.preferences != null || state.savedRouters.isNotEmpty()) ControlPage.HUB
        else settledLanding ?: ControlPage.LOADING
    var landingResolved by remember(coordinator, state.selectedRouterId) { mutableStateOf(false) }
    var page by remember(coordinator) { mutableStateOf(if (state.preferences != null) ControlPage.HUB else ControlPage.LOADING) }
    var settingsRouterRequested by remember(coordinator) { mutableStateOf<String?>(null) }
    var target by remember(coordinator) { mutableStateOf<RouterActionTarget?>(null) }
    var removalRevision by remember(coordinator) { mutableStateOf<Long?>(null) }
    var unpairId by remember(coordinator, state.selectedRouterId) { mutableStateOf<String?>(null) }
    var removalTarget by remember(coordinator, state.selectedRouterId) { mutableStateOf<RouterActionTarget?>(null) }
    var deletePackage by remember(coordinator) { mutableStateOf(false) }
    var clientName by remember(coordinator) { mutableStateOf("") }
    var qrProfile by remember(coordinator) { mutableStateOf<String?>(null) }
    var devicesExpanded by remember(coordinator) { mutableStateOf(false) }
    var profilesExpanded by remember(coordinator) { mutableStateOf(false) }
    val clientsListState = rememberLazyListState()
    val profilesListState = rememberLazyListState()
    val enginesListState = rememberLazyListState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var engineSelection by remember(coordinator) { mutableStateOf<Set<RouterInstallProtocol>>(emptySet()) }
    var notifiedEngineJob by remember(coordinator) { mutableStateOf<String?>(null) }
    var opened by remember(coordinator, enabled) { mutableStateOf(false) }
    var discoveryPending by remember(coordinator, enabled) { mutableStateOf(enabled) }
    var discoveryAttempt by remember(coordinator) { mutableStateOf(0) }
    var discoveryToken by remember(coordinator) { mutableStateOf(0L) }
    var consumedDiscoveryAttempt by remember(coordinator) { mutableStateOf(0) }
    var devicesPending by remember(coordinator) { mutableStateOf(false) }
    var devicesFailed by remember(coordinator) { mutableStateOf(false) }
    var devicesAttempt by remember(coordinator) { mutableStateOf(0) }
    var devicesOwner by remember(coordinator) { mutableStateOf<String?>(null) }
    var devicesToken by remember(coordinator) { mutableStateOf(0L) }
    fun retryDiscovery() { discoveryAttempt++ }
    fun requestDevices() {
        devicesOwner = coordinator.state.value.selectedRouterId
        devicesPending = true
        devicesFailed = false
        devicesAttempt++
    }
    var countryConfirmation by remember(coordinator, state.selectedRouterId) { mutableStateOf<com.foxhole.guard.router.RouterCountryDirectConfirmation?>(null) }
    var profileChooserOpen by remember(coordinator, state.selectedRouterId) { mutableStateOf(false) }
    var profileChoice by remember(coordinator, state.selectedRouterId) { mutableStateOf<String?>(null) }
    var profileChoiceRevision by remember(coordinator, state.selectedRouterId) { mutableStateOf<Long?>(null) }
    var serverPublicIpAck by remember { mutableStateOf(false) }
    var serverDisableConfirmation by remember(coordinator, state.selectedRouterId) { mutableStateOf(false) }
    var serverDisableRevision by remember(coordinator, state.selectedRouterId) { mutableStateOf<Long?>(null) }
    var serverRemoveComponent by remember(coordinator, state.selectedRouterId) { mutableStateOf(false) }
    LaunchedEffect(page, state.selectedRouterId, state.stage, state.lastAuthenticatedPackageVersion) {
        if (page == ControlPage.ABOUT && current && state.stage == RouterControlStage.CONNECTED) coordinator.refreshRouterAbout()
    }
    LaunchedEffect(state.vpnServer.job?.jobId, state.stage) {
        val id = state.vpnServer.job?.jobId ?: return@LaunchedEffect
        while (currentCoroutineContext().isActive && current) {
            val latest = coordinator.state.value
            if (latest.vpnServer.job?.jobId != id || latest.vpnServer.job?.running != true || latest.stage != RouterControlStage.CONNECTED) break
            kotlinx.coroutines.delay(1000)
            coordinator.refreshVpnServerJob()
        }
    }
    LaunchedEffect(state.snapshot, page) {
        if (routerVpnServerEnabled(state.snapshot) == false && (page == ControlPage.CLIENTS || page == ControlPage.PROFILE && target?.remote == true)) {
            page = ControlPage.HUB
            target = null
        }
    }
    fun networkAction(action: suspend () -> Unit) {
        requestAccess { if (current && scope.isActive) scope.launch { action() } }
    }
    fun dashboard() {
        val latest = coordinator.state.value
        page = if (latest.preferences != null || latest.savedRouters.isNotEmpty()) ControlPage.HUB
            else settledLanding ?: ControlPage.LOADING
        target = null
        qrProfile = null
        clientName = ""
    }
    LaunchedEffect(coordinator, enabled, discoveryAttempt, networkGeneration) {
        val token = ++discoveryToken
        if (!enabled) { discoveryPending = false; coordinator.close(); return@LaunchedEffect }
        discoveryPending = true
        val forceLocal = discoveryAttempt > consumedDiscoveryAttempt
        consumedDiscoveryAttempt = discoveryAttempt
        val access = coordinator.prepareDiscovery(forceLocal)
        if (!access.canDiscover) { discoveryPending = false; opened = true; return@LaunchedEffect }
        discoveryPending = true
        val settled = CompletableDeferred<Unit>()
        var task: Job? = null
        var live = true
        try {
            requestAccess {
                if (live && current && scope.isActive && token == discoveryToken && task == null) {
                    task = scope.launch {
                        try {
                            coordinator.openUserManagement { current && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                        } finally { settled.complete(Unit) }
                    }
                }
            }
            val completed = withTimeoutOrNull(45_000) { settled.await(); true } == true
            if (token == discoveryToken && current) {
                if (!completed) coordinator.close()
                opened = true
            }
        } finally {
            live = false; task?.cancel(); settled.cancel()
            if (token == discoveryToken) discoveryPending = false
        }
    }
    LaunchedEffect(coordinator, enabled, devicesAttempt, state.selectedRouterId) {
        val token = ++devicesToken
        val selected = devicesOwner
        if (!enabled || devicesAttempt == 0 || selected == null || selected != state.selectedRouterId) return@LaunchedEffect
        devicesPending = true
        devicesFailed = false
        val settled = CompletableDeferred<Unit>()
        var task: Job? = null
        var live = true
        try {
            requestAccess {
                if (live && current && scope.isActive && token == devicesToken && task == null &&
                    coordinator.state.value.selectedRouterId == selected) {
                    task = scope.launch {
                        try {
                            if (coordinator.state.value.controlsAvailable) coordinator.refreshDevices()
                        } finally { settled.complete(Unit) }
                    }
                }
            }
            val completed = withTimeoutOrNull(45_000) { settled.await(); true } == true
            if (token == devicesToken && coordinator.state.value.selectedRouterId == selected) {
                devicesFailed = !completed || !coordinator.state.value.lanDevicesLoaded
            }
        } finally {
            live = false; task?.cancel(); settled.cancel()
            if (token == devicesToken) devicesPending = false
        }
    }
    LaunchedEffect(state.selectedRouterId) {
        val openSettings = settingsRouterRequested == state.selectedRouterId && state.selectedRouterId != null
        dashboard()
        if (openSettings) page = ControlPage.SETTINGS
        devicesExpanded = false
        devicesPending = false
        devicesFailed = false
        devicesOwner = null
        profilesExpanded = false
        clientsListState.scrollToItem(0)
        profilesListState.scrollToItem(0)
        enginesListState.scrollToItem(0)
        engineSelection = emptySet()
        notifiedEngineJob = null
    }
    DisposableEffect(coordinator) { onDispose { coordinator.close() } }
    DisposableEffect(Unit) { onDispose { qrProfile = null } }
    LaunchedEffect(state.error, state.entryAccess) {
        val cause = if (state.entryAccess == com.foxhole.guard.router.RouterManagementAccess.VPN_ACTIVE) "VPN_ACTIVE" else state.error?.name
        if (!coordinator.claimUiNotice("connection", cause)) return@LaunchedEffect
        if (state.error == RouterWizardError.VPN_ACTIVE ||
            state.entryAccess == com.foxhole.guard.router.RouterManagementAccess.VPN_ACTIVE) {
            notices.show(resources.getString(R.string.router_management_disconnect_vpn), error = false)
        } else state.error?.let { notices.show(resources.getString(
            if (state.stage == RouterControlStage.PAIRING_REQUIRED) R.string.guard_router_router_control_pairing_required
            else routerWizardErrorLabel(it),
        ), error = true) }
    }
    LaunchedEffect(state.entryAccess, state.selectedRouterId, page) {
        if (state.preferences != null && state.managementAccessUnavailable &&
            page !in setOf(ControlPage.HUB, ControlPage.LOADING, ControlPage.UPDATES)) dashboard()
    }
    LaunchedEffect(state.stage) {
        if (state.stage in setOf(RouterControlStage.UNPAIRED, RouterControlStage.PAIRING_REQUIRED) &&
            page !in setOf(ControlPage.UPDATES)) dashboard()
    }
    LaunchedEffect(state.hasConfirmedDirectIp, state.snapshot != null, state.selectedRouterId) {
        if (state.snapshot != null || state.preferences?.publicIpConfirmed == true) {
            settledLanding = ControlPage.HUB
            if (!landingResolved && page == ControlPage.LOADING) {
                dashboard()
                landingResolved = true
            }
        }
    }
    LaunchedEffect(coordinator, opened, enabled, state.stage, state.busy) {
        if (opened && enabled && !discoveryPending && !state.busy && state.stage == RouterControlStage.UNPAIRED && state.savedRouters.isEmpty()) {
            val latest = coordinator.state.value
            if (!latest.busy && latest.stage == RouterControlStage.UNPAIRED && latest.savedRouters.isEmpty()) {
                unpairId = null; onUnpaired()
            }
        }
    }
    LaunchedEffect(coordinator, opened, enabled, state.selectedRouterId, state.stage) {
        if (opened && enabled && state.stage == RouterControlStage.CONNECTED && state.engines.supported == null && !state.updates.running) coordinator.refreshEngines()
    }
    LaunchedEffect(coordinator, opened, enabled, state.selectedRouterId, page) {
        if (opened && enabled && page == ControlPage.SETTINGS &&
            coordinator.state.value.updates.supported == null && coordinator.state.value.stage == RouterControlStage.CONNECTED)
            coordinator.refreshUpdates()
    }
    val desiredRuntimeProfile = state.snapshot?.desiredServerId
    LaunchedEffect(coordinator, enabled, state.selectedRouterId, page, desiredRuntimeProfile, state.snapshot?.connected, state.stage) {
        val routerId = state.selectedRouterId
        if (!enabled || page !in setOf(ControlPage.HUB, ControlPage.LOADING) || routerId == null ||
            desiredRuntimeProfile == null || state.snapshot?.connected == true || state.stage != RouterControlStage.CONNECTED) return@LaunchedEffect
        var attempts = 0
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (attempts < 15 && currentCoroutineContext().isActive) {
                delay(1000)
                val latest = coordinator.state.value
                if (latest.selectedRouterId != routerId || latest.snapshot?.desiredServerId != desiredRuntimeProfile ||
                    latest.snapshot?.connected == true || latest.stage != RouterControlStage.CONNECTED) return@repeatOnLifecycle
                attempts++
                if (!latest.busy) coordinator.refreshRuntimeStatus()
            }
        }
    }
    val updatePollBudget = remember(coordinator, state.selectedRouterId, state.updates.pollEpoch) {
        RouterUpdatePollBudget(selectedRouterId = state.selectedRouterId)
    }
    LaunchedEffect(coordinator, page, enabled, state.selectedRouterId, state.updates.pollEpoch) {
        if (enabled && page == ControlPage.UPDATES && state.updates.pollEpoch > 0) {
            val started = updatePollBudget.begin(android.os.SystemClock.elapsedRealtime())
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() - started < 300_000L) {
                    if (coordinator.state.value.busy) { delay(250); continue }
                    val next = updatePollBudget.next(coordinator.state.value, android.os.SystemClock.elapsedRealtime()) ?: break
                    delay(next.delayMs)
                    try { withTimeout(next.timeoutMs) { coordinator.refreshUpdates(statusOnly = next.statusOnly) } }
                    catch (_: TimeoutCancellationException) { break }
                }
            }
        }
    }
    LaunchedEffect(state.engines.status?.catalogSha256, state.engines.status?.revision) {
        val available = state.engines.status?.entries.orEmpty().filter { it.canAdd }.map { it.protocol }.toSet()
        engineSelection = engineSelection.intersect(available)
    }
    LaunchedEffect(state.engines.failure, state.engines.job?.jobId) {
        state.engines.failure?.let { notices.show(resources.getString(routerEngineFailureLabel(it)), error = true) }
    }
    LaunchedEffect(page, state.engines.job?.jobId, enabled) {
        val id = state.engines.job?.jobId
        if (enabled && page == ControlPage.ENGINE_JOB && id != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val started = android.os.SystemClock.elapsedRealtime()
            while (currentCoroutineContext().isActive && coordinator.state.value.engines.job?.takeIf { it.jobId == id }?.running == true &&
                android.os.SystemClock.elapsedRealtime() - started < 300_000L) {
                delay(1500)
                coordinator.refreshEngineJob(id)
            }
        }
    }
    LaunchedEffect(page, state.engines.job?.jobId, state.engines.job?.state) {
        val job = state.engines.job
        if (page == ControlPage.ENGINE_JOB && job?.state == RouterEngineJobState.SUCCEEDED && notifiedEngineJob != job.jobId) {
            notifiedEngineJob = job.jobId
            notices.show(resources.getString(R.string.guard_router_router_engines_done))
        }
    }
    var phoneProfilesParent by remember { mutableStateOf(ControlPage.ROUTER_VPN) }
    fun openPhoneProfiles() {
        val export = exportPhoneProfile ?: return
        if (!current || !coordinator.state.value.controlsAvailable || coordinator.state.value.engines.running) return
        if (page != ControlPage.PHONE_PROFILES) phoneProfilesParent = page
        page = ControlPage.PHONE_PROFILES
        phonePicker.load(phoneProfileRequests, export, requestAccess)
    }
    fun back() {
        if (page == ControlPage.PHONE_PROFILES) phonePicker.cancel()
        settingsRouterRequested = null
        if (page == ControlPage.ENGINE_PLAN) coordinator.discardEnginePlan()
        page = when (page) {
            ControlPage.DEVICE -> ControlPage.ROUTER_VPN
            ControlPage.PHONE_PROFILES -> phoneProfilesParent
            ControlPage.HOME_SERVER, ControlPage.ABOUT -> ControlPage.SETTINGS
            ControlPage.SETTINGS -> landing
            ControlPage.PROTOCOLS -> ControlPage.SETTINGS
            ControlPage.UPDATES -> ControlPage.SETTINGS
            ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB -> ControlPage.PROTOCOLS
            ControlPage.CREATE, ControlPage.PROFILE -> ControlPage.CLIENTS
            ControlPage.ACTIONS -> ControlPage.ROUTER_VPN
            else -> landing
        }
        if (page != ControlPage.PROFILE) qrProfile = null
    }
    LaunchedEffect(state.selectedRouterId) {
        val pairing = phonePicker.catalog?.pairingId
        if (pairing != null && pairing != state.selectedRouterId) {
            phonePicker.cancel()
            if (page == ControlPage.PHONE_PROFILES) page = ControlPage.ROUTER_VPN
        }
    }
    BackHandler(enabled && page != landing) { back() }
    val item = target?.let { selected ->
        (if (selected.remote) state.snapshot?.clients else state.snapshot?.servers)?.singleOrNull { it.text("id") == selected.id }
    }
    val device = target?.takeIf { it.device }?.let { selected -> state.lanDevices.singleOrNull { it.id == selected.id } }
    val canAct = enabled && !discoveryPending && state.controlsAvailable
    val discovering = discoveryPending
    val discoveryReady = !discoveryPending && state.stage == RouterControlStage.CONNECTED && state.snapshot != null
    val rootPage = page == landing
    fun openClient(id: String) {
        val owner = coordinator.state.value.selectedRouterId
        target = RouterActionTarget(id, remote = true)
        qrProfile = null
        page = ControlPage.PROFILE
        networkAction {
            coordinator.showRemoteClient(id) { profile ->
                if (current && coordinator.state.value.selectedRouterId == owner && target?.id == id && page == ControlPage.PROFILE) qrProfile = profile
            }
        }
    }
    fun selectRouter(id: String) { settingsRouterRequested = null; if (id != state.selectedRouterId) networkAction { coordinator.selectRouter(id) } }
    fun routerSettings(id: String) {
        settingsRouterRequested = id
        if (id == state.selectedRouterId) page = ControlPage.SETTINGS
        else networkAction {
            coordinator.selectRouter(id)
            if (current && settingsRouterRequested == id && coordinator.state.value.selectedRouterId == id) page = ControlPage.SETTINGS
        }
    }
    fun removeRouter(id: String) {
        settingsRouterRequested = null
        deletePackage = false
        unpairId = id
    }
    fun renameRouter(id: String, label: String) {
        scope.launch { if (current) coordinator.renameRouter(id, label) }
    }
    fun chooseRouterProfile() {
        val latest = coordinator.state.value
        if (!current || !latest.controlsAvailable) return
        profileChoice = latest.snapshot?.desiredServerId ?: latest.selectedServerId ?: latest.snapshot?.servers?.firstOrNull()?.text("id")
        profileChoiceRevision = latest.snapshot?.revision
        profileChooserOpen = true
    }
    fun toggleRouterVpn() {
        val latest = coordinator.state.value
        if (latest.snapshot?.desiredServerId != null) networkAction { coordinator.setVpn(false) }
        else if (latest.selectedServerId == null) chooseRouterProfile()
        else networkAction { coordinator.setVpn(true) }
    }
    val title = if (rootPage) R.string.guard_router_router_review_management else when (page) {
        ControlPage.CREATE -> R.string.guard_router_router_review_create_profile
        ControlPage.PROFILE -> R.string.guard_router_router_control_client_details
        ControlPage.ACTIONS -> R.string.guard_router_vpn_profile_actions_title
        ControlPage.LOADING, ControlPage.HUB -> R.string.guard_router_router_review_management
        ControlPage.ABOUT -> R.string.router_about_title
        ControlPage.HOME_SERVER -> if (state.vpnServer.job == null) R.string.router_server_enable_title else R.string.router_home_server
        ControlPage.PHONE_PROFILES -> R.string.router_transfer_title
        ControlPage.ROUTER_VPN -> R.string.guard_router_router_review_router_vpn
        ControlPage.CLIENTS -> R.string.guard_router_router_review_vpn_clients
        ControlPage.DEVICE -> R.string.guard_router_router_review_device_routing
        ControlPage.UPDATES -> R.string.guard_router_router_updates_title
        ControlPage.SETTINGS -> R.string.guard_router_router_review_settings
        ControlPage.PROTOCOLS, ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB -> R.string.guard_router_router_engines_title
    }
    GuardRouterSheetContent(
        title,
        R.drawable.lin_router,
        busy = state.busy && page != ControlPage.PHONE_PROFILES && !discovering,
        actions = {
            if (page == ControlPage.ROUTER_VPN && exportPhoneProfile != null && phoneProfileRequests.isNotEmpty()) {
                IconButton(onClick = ::openPhoneProfiles, enabled = enabled && state.controlsAvailable && !state.engines.running,
                    modifier = Modifier.size(48.dp).testTag("router-add-phone-profiles")) {
                    Icon(painterResource(if (rootPage) R.drawable.lin_profiles else R.drawable.lin_add), stringResource(R.string.router_transfer_add), Modifier.size(28.dp))
                }
            }
            if (page == ControlPage.PHONE_PROFILES) IconButton(onClick = ::openPhoneProfiles,
                enabled = enabled && !phonePicker.busy && state.controlsAvailable) {
                Icon(painterResource(R.drawable.lin_refresh), stringResource(R.string.router_transfer_retry), Modifier.size(28.dp))
            }
            if (page == ControlPage.ROUTER_VPN) GuardRouterHelpButton(R.string.guard_router_router_review_router_vpn,
                R.string.guard_router_router_control_vpn_help, helpContent = { RouterVpnCombinedHelp() })
            if (rootPage) {
                IconButton(onClick = onAddRouter, modifier = Modifier.size(48.dp), enabled = enabled && state.canAddRouter) {
                    Icon(painterResource(R.drawable.lin_add), stringResource(R.string.guard_router_router_review_add_router), Modifier.size(28.dp))
                }
            }
            if (rootPage && !state.managementAccessUnavailable) {
                if (page != ControlPage.ROUTER_VPN) GuardRouterHelpButton(R.string.help_router_title, R.string.guard_router_router_help_setup_pair,
                    canInstallRouter = canInstallRouter, canInspectRouter = canInspectRouter)
            } else if (!(page == ControlPage.PROFILE && target?.remote == true) && page !in setOf(ControlPage.ROUTER_VPN, ControlPage.CLIENTS, ControlPage.HOME_SERVER, ControlPage.ABOUT)) GuardRouterHelpButton(title,
                if (page in setOf(ControlPage.PROTOCOLS, ControlPage.ENGINE_PLAN, ControlPage.ENGINE_JOB)) R.string.guard_router_router_engines_help
                else if (page == ControlPage.UPDATES) R.string.guard_router_router_updates_scope
                else if (page == ControlPage.SETTINGS) R.string.guard_router_router_review_settings_help
                else if (page == ControlPage.CREATE) R.string.guard_router_router_control_create_help
                else if (target?.remote == true) R.string.guard_router_router_control_clients_help else R.string.guard_router_router_control_vpn_help,
                helpContent = if (page == ControlPage.SETTINGS) ({ RouterSettingsCombinedHelp() }) else null)
            if (rootPage && !state.managementAccessUnavailable) IconButton(onClick = { page = ControlPage.SETTINGS },
                enabled = enabled && !discoveryPending && !state.busy && state.preferences != null, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.lin_settings), stringResource(R.string.guard_router_router_review_settings), Modifier.size(28.dp))
            }
        },
        scrollable = false,
        footerTopPadding = if (page in setOf(ControlPage.HUB, ControlPage.LOADING) && onClose == null) 8.dp else 16.dp,
        showAddonSettings = page == ControlPage.SETTINGS,
        addonSettingsLast = true,
        addonSettingsEnabled = enabled && !discoveryPending,
        footer = {
            // Transient recovery actions stay above the permanent dock-aligned navigation row.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {

                if (page !in setOf(ControlPage.HUB, ControlPage.LOADING) && !discoveryPending && state.stage != RouterControlStage.CONNECTED && (state.stage == RouterControlStage.PAIRING_REQUIRED ||
                        profileProbe?.error in setOf(RouterWizardError.PIN, RouterWizardError.IDENTITY))) {
                    Text(stringResource(R.string.guard_router_router_profile_probe_failed), modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CliButton(
                        label = stringResource(R.string.guard_router_router_profile_retry_pairing),
                        onClick = onRetryPairing,
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = enabled && !discoveryPending && !state.busy,
                        filled = false,
                    )
                }
                when (page) {
                    ControlPage.ABOUT -> Unit
                    ControlPage.HOME_SERVER -> RouterServerActionFooter(state, serverPublicIpAck,
                        onConfirm = {
                            val expected = state.vpnServer.plan?.planSha256
                            networkAction {
                                val latest = coordinator.state.value
                                if (current && page == ControlPage.HOME_SERVER && expected != null && latest.vpnServer.plan?.planSha256 == expected) {
                                    coordinator.setPublicIpConfirmed(true)
                                    if (current && page == ControlPage.HOME_SERVER) coordinator.setVpnServer(true)
                                    if (current && coordinator.state.value.vpnServer.job == null && routerVpnServerEnabled(coordinator.state.value.snapshot) == true)
                                        page = ControlPage.SETTINGS
                                }
                            }
                        }, onBack = { page = ControlPage.SETTINGS }, onClose = onClose)

                    ControlPage.LOADING, ControlPage.HUB -> Unit
                    ControlPage.PHONE_PROFILES -> RouterPhoneProfilesFooter(
                        enabled = enabled && !phonePicker.busy,
                        canSave = canAct && phonePicker.current && phonePicker.selected.isNotEmpty(),
                        onCancel = ::back,
                        onSave = {
                            phonePicker.save(requestAccess) { result ->
                                if (result.complete) {
                                    notices.show(resources.getString(R.string.router_transfer_done, result.confirmedChoiceIds.size))
                                    profilesExpanded = true
                                    page = ControlPage.ROUTER_VPN
                                } else if (result.confirmedChoiceIds.isNotEmpty()) {
                                    notices.show(resources.getString(R.string.router_transfer_partial,
                                        result.confirmedChoiceIds.size, result.requested), error = true)
                                }
                            }
                        })
                    ControlPage.ROUTER_VPN -> Unit
                    ControlPage.CLIENTS -> if (state.hasConfirmedDirectIp) CliButton(
                        label = stringResource(R.string.guard_router_router_review_create_profile),
                        onClick = { clientName = ""; page = ControlPage.CREATE },
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = canAct && !state.clientCreationNeedsSetup,
                        icon = R.drawable.lin_add,
                        filled = false,
                    )
                    ControlPage.SETTINGS -> CliButton(
                        label = stringResource(if (!state.updates.deviceCheckedCurrent && !state.updates.running &&
                            state.updates.status?.availability == RouterUpdateAvailability.AVAILABLE)
                            R.string.guard_router_router_updates_available else R.string.guard_router_router_updates_check_entry),
                        onClick = {
                        page = ControlPage.UPDATES
                        networkAction {
                            coordinator.refreshUpdates()
                            val latest = coordinator.state.value.updates
                            if (latest.canCheck && latest.status?.availability != RouterUpdateAvailability.AVAILABLE) coordinator.checkUpdates()
                        }
                    },
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = canAct && !state.updates.running,
                        icon = R.drawable.lin_restart,
                        filled = false,
                    )
                    ControlPage.DEVICE -> Unit
                    ControlPage.UPDATES -> GuardRouterPackageUpdateFooter(state.updates,
                        enabled && !discoveryPending && !state.busy && state.preferences?.enabled == true && state.stage != RouterControlStage.PAIRING_REQUIRED,
                        onCheck = { networkAction { coordinator.checkUpdates() } },
                        onApply = { state.updates.plan?.let { plan -> networkAction { coordinator.applyUpdate(plan.planId, plan.planSha256) } } },
                        onRefresh = { networkAction { coordinator.refreshUpdates() } })
                    ControlPage.PROTOCOLS -> if (engineSelection.isNotEmpty() && !state.engines.running) CliButton(
                        label = stringResource(R.string.guard_router_vpn_continue),
                        onClick = { networkAction {
                            coordinator.planEngines(RouterEngineOperation.ADD, engineSelection)
                            if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                        } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = canAct,
                        filled = false,
                    )
                    else CliButton(
                        label = stringResource(R.string.guard_router_router_engines_check_status),
                        onClick = { networkAction { coordinator.refreshEngines() } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = canAct,
                        filled = false,
                    )
                    ControlPage.ENGINE_PLAN -> {
                        val plan = state.engines.plan
                        val apply: () -> Unit = { if (plan != null) networkAction {
                            coordinator.applyEnginePlan(plan.planId, plan.planSha256)
                            if (coordinator.state.value.engines.job?.planId == plan.planId && coordinator.state.value.engines.failure == null && coordinator.state.value.error == null) {
                                engineSelection = emptySet()
                                page = ControlPage.ENGINE_JOB
                            }
                        } }
                        if (plan?.operation == RouterEngineOperation.REMOVE) CliButton(
                            label = stringResource(R.string.guard_router_vpn_remove),
                            onClick = apply,
                            modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                            enabled = canAct,
                            color = guardRouterDeleteColor(),
                            icon = R.drawable.lin_trash,
                            filled = false,
                        ) else CliButton(
                            label = stringResource(if (plan?.operation == RouterEngineOperation.ADD) R.string.guard_router_vpn_router_install
                                else if (plan?.impact == RouterEngineImpact.RECONNECT_REQUIRED) R.string.guard_router_router_engines_update_reconnect else R.string.guard_router_router_engines_update),
                            onClick = apply,
                            modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                            enabled = canAct && plan != null,
                            filled = false,
                        )
                    }
                    ControlPage.ENGINE_JOB -> CliButton(
                        label = stringResource(R.string.guard_router_router_engines_check_status),
                        onClick = { networkAction {
                        val job = coordinator.state.value.engines.job
                        if (job?.running == true) coordinator.refreshEngineJob(job.jobId) else coordinator.refreshEngines()
                    } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                        enabled = canAct,
                        filled = false,
                    )
                    ControlPage.CREATE -> ControlCreateFooter(canAct && state.hasConfirmedDirectIp, clientName,
                        showCancel = false, onCancel = ::back, onCreate = { networkAction {
                            coordinator.createRemoteClient(clientName)
                            if (coordinator.state.value.error == null) {
                                page = ControlPage.CLIENTS
                                coordinator.refreshClientStatus()
                            }
                        } })
                    ControlPage.ACTIONS, ControlPage.PROFILE -> GuardRouterProfileActionsFooter(canAct && item != null,
                        onShare = { target?.let { selected -> networkAction {
                            if (selected.remote) coordinator.showRemoteClient(selected.id) { shareGuardRouterProfile(context, it) }
                            else coordinator.showServer(selected.id) { shareGuardRouterProfile(context, it) }
                        } } }, onRemove = { removalRevision = state.snapshot?.revision; removalTarget = target })
                }
                if (page !in setOf(ControlPage.PHONE_PROFILES, ControlPage.HOME_SERVER)) GuardRouterNavigationFooter(enabled && !discoveryPending && !state.busy, if (rootPage) null else ::back, onClose)
            }
        },
    ) {
        AnimatedContent(page, modifier = Modifier.fillMaxSize(), contentKey = { if (it == ControlPage.LOADING) ControlPage.HUB else it }, transitionSpec = {
            if (initialState == ControlPage.LOADING || targetState == ControlPage.LOADING)
                androidx.compose.animation.ContentTransform(androidx.compose.animation.EnterTransition.None, androidx.compose.animation.ExitTransition.None, sizeTransform = null)
            else GuardRouterSheetMotion.pageTransition(animateSize = false)
        }, label = "router-control-navigation") { shownPage ->
            when (shownPage) {
                ControlPage.ABOUT -> RouterAboutContent(state)
                ControlPage.HOME_SERVER -> RouterServerActionContent(state, serverPublicIpAck,
                    onPublicIpAck = { serverPublicIpAck = it }, onReadStatus = { networkAction { coordinator.refreshVpnServerJob() } })

                ControlPage.LOADING, ControlPage.HUB -> ControlHub(state, enabled, ::selectRouter, ::renameRouter, ::routerSettings, ::removeRouter,
                    discovering, ::chooseRouterProfile, ::toggleRouterVpn, onLocalTraffic = {
                        val latest = coordinator.state.value
                        val traffic = com.foxhole.guard.router.routerCountryDirectControl(latest.preferences, latest.routerCountryCode, latest.snapshot)
                        countryConfirmation = coordinator.requestCountryDirectChange(!traffic.checked)
                    }) {
                    Box(Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight), contentAlignment = Alignment.Center) {
                        if (state.stage == RouterControlStage.PAIRING_REQUIRED || profileProbe?.error in setOf(RouterWizardError.PIN, RouterWizardError.IDENTITY)) {
                            CliButton(label = stringResource(R.string.guard_router_router_profile_retry_pairing), onClick = onRetryPairing,
                                enabled = enabled && !discovering && !state.busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight), filled = false)
                        } else if (discoveryReady || discovering) {
                            if (state.remoteClientHubAvailable || discovering) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ControlManagementButton(R.string.guard_router_router_review_router_vpn, R.drawable.lin_router,
                                    enabled && discoveryReady && !state.busy, Modifier.weight(1f)) { page = ControlPage.ROUTER_VPN }
                                ControlManagementButton(R.string.guard_router_router_review_vpn_clients, R.drawable.router_device_phone,
                                    enabled && discoveryReady && !state.busy, Modifier.weight(1f)) {
                                    page = ControlPage.CLIENTS
                                    if (canAct) networkAction { coordinator.refreshClientStatus() }
                                }
                            } else ControlManagementButton(R.string.guard_router_router_review_router_vpn, R.drawable.lin_router,
                                enabled && discoveryReady && !state.busy, Modifier.widthIn(min = 220.dp)) { page = ControlPage.ROUTER_VPN }
                        } else ControlHubFooter(state, enabled && !discovering, onPair, ::retryDiscovery)
                    }
                }
                ControlPage.PHONE_PROFILES -> RouterPhoneProfilesContent(phonePicker,
                    enabled && shownPage == page && state.controlsAvailable, ::openPhoneProfiles)
                ControlPage.ROUTER_VPN -> ControlRouterVpn(if (enabled && shownPage == page) state else state.copy(busy = true),
                    profilesListState, profilesExpanded, devicesExpanded,
                    onDevicesExpanded = { value ->
                        devicesExpanded = value
                        if (value) {
                            if (canAct) requestDevices() else { devicesPending = false; devicesFailed = true }
                        }
                    },
                    devicesPending = devicesPending, devicesFailed = devicesFailed,
                    onRetryDevices = {
                        if (canAct) requestDevices()
                        else { devicesExpanded = false; dashboard(); retryDiscovery() }
                    },
                    onDevice = { id -> target = RouterActionTarget(id, device = true); page = ControlPage.DEVICE },
                    onDeviceRoute = { id, vpn -> networkAction { coordinator.setLanClient(id, vpn) } },
                    onProfilesExpanded = { profilesExpanded = it }, showRouterSelector = landing != ControlPage.HUB,
                    onAddProfiles = if (exportPhoneProfile != null && phoneProfileRequests.isNotEmpty()) ::openPhoneProfiles else null,
                    onRouterSelect = ::selectRouter,
                    onSelect = coordinator::selectServer,
                    onActions = { id -> target = RouterActionTarget(id); page = ControlPage.ACTIONS })
                ControlPage.PROTOCOLS -> GuardRouterProtocolsList(state.engines, engineSelection, enginesListState,
                    canAct && shownPage == page, onSelect = { engineSelection = it },
                    onUpdate = { protocol -> networkAction {
                        coordinator.planEngines(RouterEngineOperation.UPDATE, setOf(protocol))
                        if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                    } }, onRemove = { protocol -> networkAction {
                        coordinator.planEngines(RouterEngineOperation.REMOVE, setOf(protocol))
                        if (coordinator.state.value.engines.plan != null) page = ControlPage.ENGINE_PLAN
                    } }, onShowJob = { page = ControlPage.ENGINE_JOB })
                ControlPage.CLIENTS -> ControlClients(state, canAct && shownPage == page, clientsListState,
                    onViewClient = ::openClient, onClientActions = ::openClient)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    when (shownPage) {
                        ControlPage.SETTINGS -> ControlRouterSettings(
                            if (enabled && shownPage == page) state else state.copy(busy = true), coordinator.smartHomeAvailable,
                            onEnabled = { value -> if (value) networkAction { coordinator.setEnabled(true) } else scope.launch { coordinator.setEnabled(false) } },
                            onCountry = { code, direct ->
                                    val latest = coordinator.state.value
                                    val actual = com.foxhole.guard.router.routerCountryDirectControl(latest.preferences, latest.routerCountryCode, latest.snapshot)
                                    if (!direct && !actual.checked) networkAction { coordinator.setCountry(code, false) }
                                    else countryConfirmation = coordinator.requestCountryDirectChange(direct, code)
                                },
                            onCountryDirect = { direct -> countryConfirmation = coordinator.requestCountryDirectChange(direct) },
                            onCountryAuto = { networkAction { coordinator.setCountryAuto(true) } },
                            onHome = { value ->
                                val expectedRouter = state.selectedRouterId
                                if (value) requestHomeEnable {
                                    if (current && scope.isActive && coordinator.state.value.selectedRouterId == expectedRouter)
                                        scope.launch { coordinator.setSmartHome(true) { current && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) } }
                                } else scope.launch { coordinator.setSmartHome(false) }
                            },
                            onVpnServer = { value -> networkAction {
                                if (value) {
                                    coordinator.prepareVpnServerPlan()
                                    if (current && coordinator.state.value.vpnServer.plan != null) {
                                        serverPublicIpAck = coordinator.state.value.preferences?.publicIpConfirmed == true
                                        page = ControlPage.HOME_SERVER
                                    }
                                } else {
                                    // Disabling an installed server does not depend on a downloadable catalog.
                                    // The optional plan is needed only for removing its installed component.
                                    serverRemoveComponent = false
                                    serverDisableRevision = coordinator.state.value.snapshot?.revision
                                    serverDisableConfirmation = true
                                }
                            } },
                            onAbout = { page = ControlPage.ABOUT },
                            onUnpair = { state.selectedRouterId?.let(::removeRouter) },
                            onHomeVpnManagement = { scope.launch { coordinator.setHomeVpnManagement(it) } },
                            onHideOffline = { hidden -> scope.launch { coordinator.setHideOfflineDevices(hidden) } },
                            onRouterVpn = { page = ControlPage.ROUTER_VPN },
                            onClients = {
                                page = ControlPage.CLIENTS
                                if (canAct) networkAction { coordinator.refreshClientStatus() }
                            },
                            onEngines = {
                                engineSelection = emptySet(); page = ControlPage.PROTOCOLS
                                networkAction { coordinator.refreshEngines() }
                            },
                        )
                        ControlPage.DEVICE -> {
                            Text(device?.name?.takeIf { it.isNotBlank() } ?: device?.address.orEmpty(), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.guard_router_router_review_current_route), style = MaterialTheme.typography.bodyMedium)
                            GuardRouterDeviceRouteDropdown(device?.route, canAct && device != null) { vpn ->
                                target?.let { selected -> networkAction { coordinator.setLanClient(selected.id, vpn) } }
                            }
                            state.snapshot?.servers?.singleOrNull { it.text("id") == state.snapshot?.desiredServerId }?.text("name")?.let { name ->
                                Text(stringResource(R.string.guard_router_router_review_active_profile, name), style = MaterialTheme.typography.bodySmall, color = LocalGuardRouterColors.current.muted)
                            }
                        }
                        ControlPage.UPDATES -> GuardRouterPackageUpdates(state.updates, enabled && !discoveryPending && !state.busy &&
                            state.stage == RouterControlStage.CONNECTED && shownPage == page,
                            onAuto = { value -> networkAction { coordinator.setUpdateAuto(value) } },
                            checking = state.busy && !state.updates.running)
                        ControlPage.ENGINE_PLAN -> GuardRouterProtocolPlan(state.engines.plan)
                        ControlPage.ENGINE_JOB -> GuardRouterProtocolJob(state.engines.job)
                        ControlPage.CREATE -> {
                            Text(stringResource(R.string.guard_router_router_control_create_help))
                            OutlinedTextField(clientName, onValueChange = { if (shownPage == page && it.toByteArray(Charsets.UTF_8).size <= 48) clientName = it },
                                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = canAct && shownPage == page,
                                label = { Text(stringResource(R.string.guard_router_router_review_profile_name)) })
                        }
                        ControlPage.PROFILE -> GuardRouterProfileManagementContent(item?.text("name").orEmpty(), "Hysteria2", qrProfile,
                            state.busy, item?.get("provisioned") == JsonPrimitive(true), canAct && item != null && shownPage == page,
                            onImport = { target?.let { selected -> networkAction {
                                coordinator.importRemoteClient(selected.id)
                                if (coordinator.state.value.error == null) notices.show(resources.getString(R.string.guard_router_router_control_imported))
                            } } })
                        ControlPage.ACTIONS -> ControlClientDetails(item)
                        ControlPage.LOADING, ControlPage.HUB, ControlPage.ROUTER_VPN, ControlPage.CLIENTS, ControlPage.PROTOCOLS -> Unit
                    }
                }
            }
        }
    }
    countryConfirmation?.let { pending ->
        RouterConfirmationModal(if (pending.turnOn) R.string.router_local_traffic_enable_title else R.string.router_local_traffic_disable_title,
            R.string.router_local_traffic_confirm, titleMaxLines = Int.MAX_VALUE, enabled = enabled && state.controlsAvailable, busy = state.busy,
            destructive = false, icon = R.drawable.lin_globe,
            onDismiss = { if (!state.busy) { coordinator.cancelCountryDirectChange(pending); countryConfirmation = null } },
            onConfirm = { networkAction {
                coordinator.confirmCountryDirectChange(pending)
                if (current) countryConfirmation = null
            } }) { }
    }
    if (profileChooserOpen) {
        val owner = state.selectedRouterId
        RouterConfirmationModal(R.string.router_choose_profile_title, R.string.router_switch_profile,
            enabled = enabled && state.controlsAvailable && profileChoice != null &&
                (profileChoice != state.snapshot?.desiredServerId || state.snapshot?.connected != true) &&
                profileChoiceRevision == state.snapshot?.revision && state.snapshot?.servers?.any { it.text("id") == profileChoice } == true,
            busy = state.busy, destructive = false, icon = R.drawable.lin_profiles,
            onDismiss = { if (!state.busy) profileChooserOpen = false }, onConfirm = {
                val chosen = profileChoice
                val revision = profileChoiceRevision
                networkAction {
                    val latest = coordinator.state.value
                    if (current && latest.selectedRouterId == owner && chosen != null && revision == latest.snapshot?.revision &&
                        latest.snapshot?.servers?.any { it.text("id") == chosen } == true) {
                        coordinator.selectServer(chosen)
                        coordinator.setVpn(true)
                        if (current && coordinator.state.value.error == null) profileChooserOpen = false
                    }
                }
            }) {
            RouterVpnProfileChoiceContent(state.snapshot?.servers.orEmpty(), profileChoice, !state.busy) { profileChoice = it }
        }
    }
    if (serverDisableConfirmation) {
        val plan = state.vpnServer.plan?.takeIf {
            it.ownerPairingId == state.selectedRouterId && it.revision == serverDisableRevision
        }
        RouterConfirmationModal(R.string.router_server_disable_title, R.string.router_server_disable,
            enabled = enabled && state.controlsAvailable && routerVpnServerEnabled(state.snapshot) == true &&
                state.snapshot?.revision == serverDisableRevision &&
                (!serverRemoveComponent || plan?.removalAvailable == true),
            busy = state.busy, icon = R.drawable.lin_power,
            onDismiss = { if (!state.busy) serverDisableConfirmation = false }, onConfirm = {
                val removeComponent = serverRemoveComponent
                val expectedPlanSha = plan?.planSha256
                val expectedPairingId = state.selectedRouterId
                val expectedRevision = serverDisableRevision
                networkAction {
                    val latest = coordinator.state.value
                    val removalPlanCurrent = !removeComponent || expectedPlanSha != null &&
                        latest.vpnServer.plan?.let { it.ownerPairingId == expectedPairingId &&
                            it.revision == expectedRevision && it.planSha256 == expectedPlanSha && it.removalAvailable } == true
                    if (current && latest.controlsAvailable && latest.selectedRouterId == expectedPairingId &&
                        expectedRevision != null && latest.snapshot?.revision == expectedRevision &&
                        routerVpnServerEnabled(latest.snapshot) == true && removalPlanCurrent) {
                        coordinator.setVpnServer(false, removeComponent)
                        if (current && coordinator.state.value.error == null) {
                            serverDisableConfirmation = false
                            if (coordinator.state.value.vpnServer.job != null) page = ControlPage.HOME_SERVER
                        }
                    }
                }
            }) {
            Text(stringResource(R.string.router_server_disable_body), style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(serverRemoveComponent, onCheckedChange = { checked ->
                    if (!checked || plan?.removalAvailable == true) serverRemoveComponent = checked
                    else if (plan == null) {
                        val owner = state.selectedRouterId
                        val revision = serverDisableRevision
                        networkAction {
                            coordinator.prepareVpnServerPlan()
                            val latest = coordinator.state.value
                            if (current && serverDisableConfirmation && latest.selectedRouterId == owner &&
                                latest.snapshot?.revision == revision && latest.vpnServer.plan?.let {
                                    it.ownerPairingId == owner && it.revision == revision && it.removalAvailable
                                } == true) serverRemoveComponent = true
                        }
                    }
                }, enabled = !state.busy && plan?.removalAvailable != false)
                Text(stringResource(R.string.router_server_remove_component), style = MaterialTheme.typography.bodyMedium)
            }
            if (plan?.available == true && !plan.removalAvailable) Text(stringResource(R.string.router_server_shared_component),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    unpairId?.let { id ->
        val router = state.savedRouters.singleOrNull { it.id == id }
        RouterConfirmationModal(R.string.router_pairing_remove, R.string.router_pairing_remove,
            enabled = enabled && !state.busy && router != null &&
                (!deletePackage || id == state.selectedRouterId && state.packageRemovalAvailable),
            busy = state.busy, onDismiss = { if (!state.busy) unpairId = null }, onConfirm = {
                val latest = coordinator.state.value
                if (current && !latest.busy && latest.savedRouters.any { it.id == id }) {
                    val removePackage = deletePackage
                    val action: suspend () -> Unit = {
                        coordinator.unpair(id, deletePackage = removePackage)
                        if (current && coordinator.state.value.savedRouters.none { it.id == id }) {
                            unpairId = null
                            dashboard()
                        }
                    }
                    if (removePackage) networkAction(action) else scope.launch { action() }
                }
            }) {
            router?.let { Text(it.label ?: stringResource(R.string.router_table_default_name, it.number),
                style = MaterialTheme.typography.titleMedium) }
            Text(stringResource(R.string.guard_router_router_review_unpair_description))
            if (id == state.selectedRouterId) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(deletePackage, onCheckedChange = { checked ->
                    if (!checked || state.packageRemovalAvailable) deletePackage = checked
                }, enabled = !state.busy && (deletePackage || state.packageRemovalAvailable))
                Text(stringResource(R.string.guard_router_router_review_delete_package), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    removalTarget?.let { selected ->
        val record = (if (selected.remote) state.snapshot?.clients else state.snapshot?.servers)
            ?.singleOrNull { it.text("id") == selected.id }
        val expectedPairingId = state.selectedRouterId
        RouterConfirmationModal(R.string.guard_router_router_control_client_remove_title, R.string.guard_router_vpn_remove,
            enabled = canAct && record != null && removalRevision == state.snapshot?.revision,
            busy = state.busy, onDismiss = { if (!state.busy) removalTarget = null }, onConfirm = {
                val expectedRevision = removalRevision
                networkAction {
                    val latest = coordinator.state.value
                    if (current && latest.selectedRouterId == expectedPairingId &&
                        expectedRevision != null && latest.snapshot?.revision == expectedRevision) {
                        if (selected.remote) coordinator.removeRemoteClient(selected.id, expectedRevision)
                        else coordinator.removeServer(selected.id, expectedRevision)
                        if (current && coordinator.state.value.error == null) {
                            removalTarget = null
                            qrProfile = null
                            page = if (selected.remote) ControlPage.CLIENTS else ControlPage.ROUTER_VPN
                        }
                    }
                }
            }) {
            Text(stringResource(if (!selected.remote) R.string.guard_router_router_control_remove_body
                else if (record?.get("provisioned") == JsonPrimitive(true)) R.string.guard_router_router_control_client_remove_body
                else R.string.guard_router_router_control_client_remove_record_body, record?.text("name").orEmpty()))
        }
    }

}

@Composable
private fun ControlHubFooter(state: RouterControlState, enabled: Boolean, onPair: () -> Unit, onRefresh: () -> Unit) {
    if (state.stage == RouterControlStage.PAIRING_REQUIRED) return
    val pair = state.stage == RouterControlStage.UNPAIRED && state.entryAccess?.canDiscover == true
    if (pair || state.stage != RouterControlStage.CONNECTED) CliButton(
        label = stringResource(if (pair) R.string.guard_router_vpn_router_pair else R.string.guard_router_vpn_refresh),
        onClick = if (pair) onPair else onRefresh,
        modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight), enabled = enabled && !state.busy, filled = false,
    )
}

@Composable
private fun ControlConfirmationFooter(enabled: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit,
    confirmEnabled: Boolean = true, confirmLabel: Int = R.string.guard_router_vpn_continue, showCancel: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCancel) CliButton(
            label = stringResource(R.string.guard_router_vpn_cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = enabled,
            filled = false,
        )
        CliButton(
            label = stringResource(confirmLabel),
            onClick = onConfirm,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            enabled = enabled && confirmEnabled,
            color = guardRouterDeleteColor(),
            icon = R.drawable.lin_trash,
            filled = false,
        )
    }
}

@Composable
private fun ControlCreateFooter(enabled: Boolean, name: String, onCancel: () -> Unit, onCreate: () -> Unit, showCancel: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (showCancel) CliButton(
            label = stringResource(R.string.guard_router_vpn_cancel),
            onClick = onCancel,
            modifier = Modifier.weight(1f).heightIn(min = GuardRouterActionHeight),
            enabled = enabled,
            filled = false,
        )
        CliButton(
            label = stringResource(R.string.guard_router_router_control_create),
            onClick = onCreate,
            modifier = Modifier.weight(1f).heightIn(min = GuardRouterActionHeight),
            enabled = enabled && name.trim().isNotEmpty() && name.none(Char::isISOControl),
            icon = R.drawable.lin_add,
            filled = false,
        )
    }
}

@Composable
private fun ControlActionLabel(label: Int, icon: Int) {
    Icon(painterResource(icon), null, Modifier.size(18.dp),
        tint = if (icon == R.drawable.lin_trash) guardRouterDeleteColor() else androidx.compose.material3.LocalContentColor.current)
    Text(stringResource(label), Modifier.padding(start = 8.dp))
}

@Composable
private fun ControlClientDetails(item: JsonObject?) {
    Text(item?.text("name").orEmpty(), style = MaterialTheme.typography.titleMedium)
    Text(item?.text("endpoint").orEmpty(), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ControlLoading(label: Int) {
    Box(Modifier.fillMaxWidth().height(64.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CliMetricSpinner()
            Text(stringResource(label), Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ControlHub(state: RouterControlState, enabled: Boolean, onSelect: (String) -> Unit,
    onRename: (String, String) -> Unit, onSettings: (String) -> Unit, onDelete: (String) -> Unit,
    discovering: Boolean, onProfileChoose: () -> Unit, onConnect: () -> Unit, onLocalTraffic: () -> Unit,
    management: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.savedRouters.size > 1) GuardRouterSavedSelector(state.savedRouters, state.selectedRouterId,
            enabled && !discovering && !state.busy, showIllustration = false,
            label = stringResource(R.string.router_saved_connections), onSelect = onSelect)
        GuardRouterIllustration(Modifier.fillMaxWidth().height(176.dp).testTag("router-management-vector"),
            searching = discovering, connected = state.stage == RouterControlStage.CONNECTED)
        val active = state.snapshot?.desiredServerId != null
        val actionEnabled = enabled && !discovering && !state.busy && (state.controlsAvailable || state.canStopVpn)
        GuardRouterConnectingContent(discovering, state.savedRouters.size > 1, Modifier.weight(1f), bottomContent = {
            if (!state.managementAccessUnavailable || state.preferences == null || discovering) {
                CliButton(label = stringResource(if (active) R.string.router_disconnect else R.string.router_connect),
                    onClick = onConnect, modifier = Modifier.fillMaxWidth().heightIn(min = GuardRouterActionHeight),
                    enabled = actionEnabled, icon = R.drawable.lin_power,
                    color = if (active) guardRouterDeleteColor() else routerConnectColor(), filled = false)
            }
        }) {
            if (state.managementAccessUnavailable && state.preferences != null && !discovering) {
                GuardRouterManagementUnavailableContent(state)
            } else {
                GuardRouterStatusCard(if (discovering) state.copy(busy = true) else state, onProfileClick = onProfileChoose, onLocalTrafficClick = onLocalTraffic)
                management()
            }
        }
    }
}

@Composable
private fun GuardRouterManagementUnavailableContent(state: RouterControlState) {
    Column(Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.router_management_unavailable_title),
            style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(if (state.managementAccessBlockedByVpn) R.string.router_management_unavailable_vpn
            else R.string.router_management_unavailable_home_network),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlInstalledProtocols(state: RouterControlState) {
    val protocols = routerInstalledProtocols(state.snapshot?.installedProtocols, state.engines.status?.entries)
    val text = when {
        protocols == null && state.snapshot == null && state.busy -> stringResource(R.string.guard_router_router_control_protocols_loading)
        protocols == null -> stringResource(R.string.guard_router_router_control_protocols_unknown)
        protocols.isEmpty() -> stringResource(R.string.guard_router_router_control_protocols_empty)
        else -> stringResource(R.string.guard_router_router_control_available_protocols, protocols.joinToString(", ") { it.label })
    }
    Text(text, Modifier.fillMaxWidth().heightIn(min = 40.dp), textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall, color = routerConnectColor())
}

@Composable
private fun ControlManagementButton(title: Int, icon: Int, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    CliButton(label = stringResource(title), icon = icon, onClick = onClick,
        enabled = enabled, modifier = modifier.heightIn(min = GuardRouterActionHeight), filled = false)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlRouterVpn(state: RouterControlState, listState: LazyListState,
    profilesExpanded: Boolean, devicesExpanded: Boolean, onDevicesExpanded: (Boolean) -> Unit,
    devicesPending: Boolean, devicesFailed: Boolean, onRetryDevices: () -> Unit,
    onDevice: (String) -> Unit, onDeviceRoute: (String, Boolean) -> Unit,
    onProfilesExpanded: (Boolean) -> Unit, showRouterSelector: Boolean, onAddProfiles: (() -> Unit)?,
    onRouterSelect: (String) -> Unit, onSelect: (String) -> Unit, onActions: (String) -> Unit) {
    val snapshot = state.snapshot
    val devices = visibleRouterLanDevices(state.lanDevices, state.preferences?.hideOfflineDevices != false)
    val devicesReady = !devicesPending && !devicesFailed && !state.lanDevicesLoading && state.lanDevicesLoaded
    val deviceRouteWidth = minOf(128.dp, LocalConfiguration.current.screenWidthDp.dp * 0.34f)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(0.dp)) {

        Column(Modifier.fillMaxWidth()) {
            if (!state.controlsAvailable && !state.busy) Text(stringResource(
                if (state.stage == RouterControlStage.DISABLED) R.string.guard_router_router_review_control_disabled else R.string.guard_router_router_review_unavailable))
            ControlExpandableSection(R.string.guard_router_router_review_profiles, R.drawable.lin_profiles, R.string.guard_router_router_control_vpn_help,
                profilesExpanded, R.string.guard_router_router_review_expand_profiles, R.string.guard_router_router_review_collapse_profiles,
                onAdd = null, addEnabled = false) {
                onProfilesExpanded(!profilesExpanded)
            }
        }
        androidx.compose.runtime.key("router-profiles-content") {
            androidx.compose.animation.AnimatedVisibility(profilesExpanded,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(100)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))) {
                Column(Modifier.fillMaxWidth()) {
                    if (snapshot?.servers.isNullOrEmpty()) {
                        Text(stringResource(R.string.guard_router_router_review_no_profiles), style = MaterialTheme.typography.bodySmall)
                    }
                    snapshot?.servers.orEmpty().forEach { server ->
                        val id = server.text("id").orEmpty()
                        androidx.compose.runtime.key(id) {
                            GuardRouterTableRow(Modifier
                                .fillMaxWidth().heightIn(min = 60.dp).combinedClickable(enabled = state.controlsAvailable && profilesExpanded,
                                    onClick = { onSelect(id) }, onLongClick = { onActions(id) })) {
                                Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(server.text("name").orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    com.foxhole.guard.ui.cli.components.CliProtocolBadge(server.text("protocol").orEmpty())
                                }
                                RadioButton(state.selectedServerId == id, onClick = null, enabled = state.controlsAvailable && profilesExpanded)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            ControlExpandableSection(R.string.guard_router_router_control_lan_clients, R.drawable.lin_device, R.string.guard_router_router_control_lan_help,
                devicesExpanded, R.string.guard_router_router_review_expand_devices, R.string.guard_router_router_review_collapse_devices) {
                onDevicesExpanded(!devicesExpanded)
            }
        }
        androidx.compose.runtime.key("router-devices-content") {
            androidx.compose.animation.AnimatedVisibility(devicesExpanded,
                enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(180)) +
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(100)),
                exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(140)) +
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(80))) {
                Column(Modifier.fillMaxWidth()) {
                    if (devicesPending || state.lanDevicesLoading) {
                        ControlLoading(R.string.router_loader_data)
                    } else if (!devicesReady) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.guard_router_router_review_unavailable), textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall)
                            CliButton(label = stringResource(R.string.guard_router_vpn_refresh), onClick = onRetryDevices,
                                enabled = !state.busy && !state.updates.running && !state.engines.running, filled = false)
                        }
                    } else if (devices.isEmpty()) {
                        Text(stringResource(if (state.preferences?.hideOfflineDevices != false) R.string.guard_router_router_review_no_online_devices
                            else R.string.guard_router_router_review_no_devices), style = MaterialTheme.typography.bodySmall)
                    }
                    if (devicesReady) devices.forEach { device ->
                        androidx.compose.runtime.key(device.id) {
                            GuardRouterTableRow(Modifier
                                .fillMaxWidth().heightIn(min = 60.dp).combinedClickable(enabled = state.controlsAvailable && devicesExpanded,
                                    onClick = { onDevice(device.id) }, onLongClick = { onDevice(device.id) })) {
                                Icon(painterResource(when (routerLanDeviceKind(device.name)) {
                                    RouterLanDeviceKind.PHONE -> R.drawable.router_device_phone
                                    RouterLanDeviceKind.TABLET -> R.drawable.router_device_tablet
                                    RouterLanDeviceKind.COMPUTER -> R.drawable.router_device_computer
                                    RouterLanDeviceKind.UNKNOWN -> R.drawable.router_device_generic
                                }), null, Modifier.size(20.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(device.name.takeIf { it.isNotBlank() } ?: device.address,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                Box(Modifier.widthIn(max = deviceRouteWidth)) {
                                    GuardRouterDeviceRouteDropdown(device.route, state.controlsAvailable && devicesExpanded) { vpn ->
                                        onDeviceRoute(device.id, vpn)
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun ControlRouterSettings(state: RouterControlState, supportsHome: Boolean,
    onEnabled: (Boolean) -> Unit, onCountry: (String, Boolean) -> Unit,
    onCountryDirect: (Boolean) -> Unit, onCountryAuto: () -> Unit, onHome: (Boolean) -> Unit,
    onVpnServer: (Boolean) -> Unit, onAbout: () -> Unit, onUnpair: () -> Unit, onHomeVpnManagement: (Boolean) -> Unit,
    onHideOffline: (Boolean) -> Unit, onRouterVpn: () -> Unit, onClients: () -> Unit, onEngines: () -> Unit) {
    val preferences = state.preferences
    var countryEnableRequested by remember(state.selectedRouterId) { mutableStateOf(false) }
    LaunchedEffect(state.controlsAvailable) {
        if (!state.controlsAvailable) countryEnableRequested = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ControlSwitch(R.string.router_home_server, R.string.router_home_server_help,
            routerVpnServerEnabled(state.snapshot) == true,
            state.controlsAvailable && routerVpnServerEnabled(state.snapshot) != null, onVpnServer)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        androidx.compose.animation.AnimatedVisibility(routerVpnServerEnabled(state.snapshot) == true,
            enter = androidx.compose.animation.expandVertically(GuardRouterSheetMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeIn(GuardRouterSheetMotion.fastEffectsSpec()),
            exit = androidx.compose.animation.shrinkVertically(GuardRouterSheetMotion.defaultSpatialSpec()) + androidx.compose.animation.fadeOut(GuardRouterSheetMotion.fastEffectsSpec())) {
            Column(Modifier.fillMaxWidth()) {
                ControlSwitch(R.string.guard_router_router_control_home_vpn, R.string.guard_router_router_control_home_vpn_help,
                    preferences?.homeVpnManagementEnabled == true,
                    !state.busy && state.controlsAvailable && routerVpnServerEnabled(state.snapshot) == true && preferences?.publicIpConfirmed == true,
                    onHomeVpnManagement)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ControlSwitch(R.string.router_smart_title, R.string.guard_router_router_control_home_help,
                    state.homeAutomationEffective,
                    !state.busy && preferences != null && supportsHome && state.controlsAvailable && routerVpnServerEnabled(state.snapshot) == true &&
                        preferences.homeVpnManagementEnabled && preferences.publicIpConfirmed, onHome)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        val automatic = preferences?.countryAuto != false
        val directControl = com.foxhole.guard.router.routerCountryDirectControl(preferences, state.routerCountryCode, state.snapshot)
        val displayCountry = if (directControl.checked) directControl.disableCode.orEmpty()
            else routerDisplayCountryCode(preferences, state.routerCountryCode, state.snapshot?.countries).orEmpty()
        GuardRouterTableRow(Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.lin_globe), null, Modifier.size(20.dp))
            Text(stringResource(R.string.guard_router_router_review_local_traffic), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            GuardRouterCountryFlag(displayCountry, state.controlsAvailable,
                onSelect = { code ->
                    val enable = countryEnableRequested || directControl.checked
                    countryEnableRequested = false
                    onCountry(code, enable)
                }, autoSelected = automatic, onAuto = if (countryEnableRequested) null else onCountryAuto,
                selectionRequested = countryEnableRequested, onSelectionDismiss = { countryEnableRequested = false })
            GuardRouterSwitch(directControl.checked,
                onCheckedChange = { checked ->
                    if (checked && directControl.enableCode == null) countryEnableRequested = true
                    else onCountryDirect(checked)
                }, enabled = state.controlsAvailable && (!directControl.checked || directControl.canChange))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlSwitch(R.string.guard_router_router_control_hide_offline, R.string.guard_router_router_control_hide_offline_help,
            preferences?.hideOfflineDevices != false, !state.busy && preferences != null, onHideOffline)
        // Temporarily hidden: router protocol settings. Keep engine management code for later qualification.
        /*
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (state.engines.supported == true) ControlNavigationRow(R.string.guard_router_router_engines_entry,
            R.drawable.lin_power, state.controlsAvailable, onEngines)
        */
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ControlNavigationRow(R.string.router_about_title, R.drawable.lin_info, !state.busy, onAbout)
        ControlNavigationRow(R.string.router_pairing_remove, R.drawable.lin_trash,
            !state.busy && state.selectedRouterId != null, onUnpair)

    }
}

@Composable
private fun ControlNavigationRow(label: Int, icon: Int, enabled: Boolean, onClick: () -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp),
            tint = if (icon == R.drawable.lin_trash) guardRouterDeleteColor() else androidx.compose.material3.LocalContentColor.current)
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Icon(painterResource(R.drawable.lin_arrow_right), null, Modifier.size(20.dp))
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControlClients(state: RouterControlState, enabled: Boolean, listState: LazyListState,
    onViewClient: (String) -> Unit, onClientActions: (String) -> Unit) {
    val clients = state.snapshot?.clients.orEmpty()
    val stackedStatus = LocalConfiguration.current.screenWidthDp <= 360 || LocalDensity.current.fontScale > 1.3f
    LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        item {
            if (!state.controlsAvailable && !state.busy) Text(stringResource(
                if (state.stage == RouterControlStage.DISABLED) R.string.guard_router_router_review_control_disabled else R.string.guard_router_router_review_unavailable))
            if (state.clientCreationNeedsSetup) Text(stringResource(R.string.guard_router_router_control_client_template), style = MaterialTheme.typography.bodySmall)
        }
        if (clients.isEmpty()) item { Text(stringResource(R.string.guard_router_router_review_no_clients), style = MaterialTheme.typography.bodySmall) }
        items(clients, key = { "remote:" + it.text("id") }) { client ->
            val id = client.text("id").orEmpty()
            val online = state.remoteClients.singleOrNull { it.id == id }?.online
            GuardRouterTableRow(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = GuardRouterSheetMotion.defaultSpatialSpec())
                .fillMaxWidth().heightIn(min = 60.dp).combinedClickable(enabled = enabled,
                    onClick = { onViewClient(id) }, onLongClick = { onClientActions(id) })) {
                Icon(painterResource(R.drawable.lin_profiles), null, Modifier.size(20.dp))
                val status = stringResource(when (online) {
                    true -> R.string.guard_router_router_review_online
                    false -> R.string.guard_router_router_review_offline
                    null -> R.string.guard_router_router_review_status_unknown
                })
                Column(Modifier.weight(1f)) {
                    Text(client.text("name").orEmpty(), style = MaterialTheme.typography.bodyMedium)
                    if (stackedStatus) Text(status, style = MaterialTheme.typography.labelSmall, color = LocalGuardRouterColors.current.muted)
                }
                if (!stackedStatus) Text(status, Modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelSmall,
                    color = LocalGuardRouterColors.current.muted, textAlign = TextAlign.End)
                Icon(painterResource(R.drawable.lin_arrow_right), null, Modifier.size(20.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun ControlExpandableSection(label: Int, icon: Int, help: Int, expanded: Boolean,
    expandDescription: Int, collapseDescription: Int, onAdd: (() -> Unit)? = null,
    addEnabled: Boolean = true, showHelp: Boolean = false, onToggle: () -> Unit) {
    val angle by animateFloatAsState(if (expanded) 180f else 0f, GuardRouterSheetMotion.defaultSpatialSpec(), label = "router-section-chevron")
    GuardRouterTableRow(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Icon(painterResource(icon), null, Modifier.size(20.dp))
        Text(stringResource(label), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        if (onAdd != null) IconButton(onClick = onAdd, enabled = addEnabled,
            modifier = Modifier.testTag("router-add-phone-profiles")) {
            Icon(painterResource(R.drawable.lin_add), stringResource(R.string.router_transfer_add), Modifier.size(22.dp))
        }
        if (showHelp) GuardRouterHelpButton(label, help)
        IconButton(onClick = onToggle) {
            Icon(painterResource(R.drawable.lin_arrow_down), stringResource(if (expanded) collapseDescription else expandDescription), Modifier.rotate(angle))
        }
    }
}

@Composable
private fun ControlSwitch(label: Int, help: Int, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    GuardRouterTableRow(Modifier.fillMaxWidth()) {
        val icon = when (label) {
            R.string.guard_router_router_control_vpn -> R.drawable.lin_power
            R.string.guard_router_router_control_enable -> R.drawable.lin_settings
            R.string.router_smart_title, R.string.guard_router_router_control_home_vpn -> R.drawable.lin_router
            R.string.router_home_server -> R.drawable.lin_globe
            R.string.guard_router_router_control_hide_offline -> R.drawable.lin_eye_off
            else -> null
        }
        if (icon != null) Icon(painterResource(icon), null, Modifier.size(20.dp))
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        GuardRouterSwitch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
