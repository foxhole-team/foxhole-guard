package com.foxhole.guard.runtime

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.foxhole.core.model.AutoConnectReasonCode
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.FoxholeRuntime
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.RuntimeNetworkCallbackKind
import com.foxhole.core.runtime.RuntimeServiceHost
import com.foxhole.core.runtime.RuntimeWakeLock
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.core.runtime.TrafficStatsSampler
import com.foxhole.core.runtime.isActiveRuntimeFor
import com.foxhole.core.runtime.isActiveRuntimeForAnotherMode
import com.foxhole.core.runtime.isIdleWithoutAttachedRuntimeResources
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.requireSystemServiceSafe
import com.foxhole.core.runtime.resolveSmartStartAnalysisPreservation
import com.foxhole.core.runtime.stopRuntimeAfterServiceDestroy
import com.foxhole.core.runtime.stoppedRuntimeSnapshot
import com.foxhole.core.sentinel.detection.TrafficWindowAggregator
import com.foxhole.guard.FoxholeApplication
import com.foxhole.guard.FoxholeRuntimeDependencies
import com.foxhole.guard.R
import com.foxhole.guard.core.diagnostics.DiagnosticsLoggerRuntimeDiagnosticsSink
import com.foxhole.guard.core.sentinel.anomaly.AndroidNetworkTypeProvider
import com.foxhole.guard.core.settings.AppTrafficStatsRecorder
import com.foxhole.guard.processLifetimeScope
import com.foxhole.guard.traffic.FoxCoreRuntimeConnectionObserver
import com.foxhole.guard.traffic.FoxCoreRuntimeStatsTracker
import com.foxhole.guard.traffic.RuntimeConnectionSnapshot
import com.foxhole.guard.withStoredAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

// The service body is the shared-state surface for the FoxholeVpnService*Support extension
// files (split by responsibility): framework callbacks plus thin cross-file entry points.
// Splitting further would only scatter the Android Service contract.
@Suppress("TooManyFunctions")
class FoxholeVpnService : VpnService(), RuntimeServiceHost {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withStoredAppLocale())
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Renewed per session, NOT once per service. The bridge gate fences by epoch on every terminal
     * publish (IDLE/ERROR) and permanently rejects tokens minted at or before that fence — that is
     * deliberate, so a superseded writer can never resurrect a dead runtime. But this service lives
     * across many sessions: an in-service teardown (the stop half of a profile switch or reconnect)
     * fenced its own long-lived writer, and every later write was rejected as stale — the runtime
     * connected for real while the UI sat on "connecting" until the process was force-stopped.
     * [renewBridgeWriter] mints a fresh claim as each new session begins.
     */
    @Volatile
    internal var bridgeWriter = FoxholeVpnRuntimeBridge.writer(TrafficMode.TUNNEL)
        private set

    internal fun renewBridgeWriter(mode: TrafficMode) {
        bridgeWriter = FoxholeVpnRuntimeBridge.writer(mode)
    }
    internal val connectivityManager by lazy {
        requireSystemServiceSafe<ConnectivityManager>("connectivity")
    }
    internal val notificationManager by lazy {
        requireSystemServiceSafe<NotificationManager>("notification")
    }
    internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    internal val trackedNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }
    internal val trackedVpnNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
    internal val container: FoxholeRuntimeDependencies by lazy { (applicationContext as FoxholeApplication).appGraph }
    internal val runtimeInstanceStore
        get() = container.runtimeInstanceStore
    internal val runtime: FoxholeRuntime
        get() = runtimeInstanceStore.get()
    internal val runtimeSupervisor
        get() = container.runtimeSupervisor
    internal val runtimeCommandOwner by lazy {
        runtimeSupervisor.openCommandOwner(
            mode = TrafficMode.TUNNEL,
            label = "vpn-service",
        )
    }

    internal val runtimeWakeLock by lazy {
        RuntimeWakeLock(
            context = applicationContext,
            diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
            tag = "Foxhole:VpnRuntime",
            scope = scope,
            shouldRemainHeld = { activeSession != null || activeLocalGuardMode != null },
        )
    }

    @Volatile
    internal var lastRuntimeStopResourceEvent: String? = null

    internal val trafficSampler = TrafficStatsSampler()
    internal val anomalyTrafficAggregator = TrafficWindowAggregator()
    internal val anomalyNetworkTypeProvider by lazy { AndroidNetworkTypeProvider(applicationContext) }
    internal val runtimeConnectionObserver by lazy {
        FoxCoreRuntimeConnectionObserver(
            runtimeProvider = { runtime },
            diagnosticsLogger = container.diagnosticsLogger,
        )
    }
    internal val dnsRuntimeStatsTracker by lazy { FoxCoreRuntimeStatsTracker(container.diagnosticsLogger) }
    internal val runtimeCountryResolver by lazy { TorGeoIpCountryResolver(applicationContext) }
    internal val appTrafficStatsRecorder by lazy {
        AppTrafficStatsRecorder(
            anomalyRepository = container.anomalyRepository,
            context = applicationContext,
            diagnosticsLogger = container.diagnosticsLogger,
        )
    }
    internal var activeSession: VpnSession?
        get() = runtimeSupervisor.activeSessionFor(TrafficMode.TUNNEL)
        set(value) {
            runtimeSupervisor.setActiveSession(value, TrafficMode.TUNNEL)
        }
    internal var activeLocalGuardMode: LocalGuardMode?
        get() =
            runtimeSupervisor.ownership.value
                .takeIf { state -> state.activeMode == TrafficMode.TUNNEL }
                ?.activeLocalGuardMode
        set(value) {
            runtimeSupervisor.setActiveLocalGuardMode(value)
        }
    internal var trafficMapCountryTrackingJob: Job? = null
    internal var dnsRuntimeStatsJob: Job? = null
    internal var runtimeConnectionSnapshotFlow: SharedFlow<RuntimeConnectionSnapshot>? = null
    internal var immediateTrafficSampleJob: Job? = null

    // One timer coroutine for every periodic session task (traffic samplers, health probe, child
    // watchdog fallback), replacing their separate delay-loops (Ф3e). Tasks register/unregister on
    // the same lifecycle points the old jobs were launched/cancelled.
    internal val sessionTicker: RuntimeSessionTicker =
        RuntimeSessionTicker(
            scope = scope,
            onError = { taskId, _ ->
                val category =
                    when (taskId) {
                        TICKER_TASK_NOTIFICATION_HEALTH, TICKER_TASK_CHILD_WATCHDOG -> "connection"
                        else -> "traffic"
                    }
                container.diagnosticsLogger.record(category, "$taskId periodic task failed")
            },
        )
    internal var geoRefreshJob: Job? = null
    internal var ipv4EnrichmentJob: Job? = null

    // @Volatile: the epoch is invalidated by main callbacks, command coroutines on Default and the
    // health tick on IO; without the barrier a stale validation could miss it in the post-check.
    @Volatile
    private var validationJobBacking: Job? = null
    internal var validationJob: Job?
        get() = validationJobBacking
        set(value) {
            validationJobBacking = value
            runtimeSupervisor.setValidationActive(value != null)
        }

    @Volatile
    internal var validationEpoch: Long = 0L

    // The freshest startId: stopService(null) must yield to a newer intent — a CONNECT arriving
    // alongside an error teardown, say — or stopSelf() kills the service together with a user
    // command already queued.
    @Volatile
    internal var latestServiceStartId: Int = -1
    private var networkCallbackRegisteredBacking = false
    internal var networkCallbackRegistered: Boolean
        get() = networkCallbackRegisteredBacking
        set(value) {
            networkCallbackRegisteredBacking = value
            runtimeSupervisor.setNetworkCallbackRegistered(RuntimeNetworkCallbackKind.UPSTREAM, value)
        }
    private var vpnNetworkCallbackRegisteredBacking = false
    internal var vpnNetworkCallbackRegistered: Boolean
        get() = vpnNetworkCallbackRegisteredBacking
        set(value) {
            vpnNetworkCallbackRegisteredBacking = value
            runtimeSupervisor.setNetworkCallbackRegistered(RuntimeNetworkCallbackKind.VPN, value)
        }
    private var defaultNetworkCallbackRegisteredBacking = false
    internal var defaultNetworkCallbackRegistered: Boolean
        get() = defaultNetworkCallbackRegisteredBacking
        set(value) {
            defaultNetworkCallbackRegisteredBacking = value
            runtimeSupervisor.setNetworkCallbackRegistered(RuntimeNetworkCallbackKind.DEFAULT, value)
        }

    // Mutated by the IO health tick, read/reset by Default-dispatcher teardown commands.
    @Volatile
    internal var notificationConnectivityHealthState = ConnectivityHealthState.CHECKING

    @Volatile
    internal var consecutiveNotificationHealthFailures = 0
    internal var defaultNetworkAvailable = true
    internal var lastDefaultNetworkSummary: String? = null

    // Thread-safe: structurally mutated from the main-thread network callbacks (add/remove/clear)
    // but also iterated from the command/validation coroutines on Dispatchers.Default/IO
    // (currentUpstreamNetworkOrNull -> mapNotNull), which raced a plain LinkedHashSet.
    internal val upstreamNetworkHandles: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    internal var activeVpnNetworkHandle: Long?
        get() = runtimeSupervisor.ownership.value.activeVpnNetworkHandle
        set(value) {
            runtimeSupervisor.setActiveVpnNetworkHandle(value)
        }

    // Thread-safe for the same reason as upstreamNetworkHandles: touched from both the network
    // callbacks and the teardown/loss coroutines.
    internal val ignoredVpnNetworkLossHandles: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    internal var runtimeNetworkActivityLoggingSuspended: Boolean
        get() = runtimeSupervisor.ownership.value.networkActivityLoggingSuspended
        set(value) {
            runtimeSupervisor.setNetworkActivityLoggingSuspended(value)
        }

    // VPN-first Tor ordering: correlationId of the session whose in-tunnel Tor route was deferred
    // at connect time and is still waiting for the post-validation upgrade reload.
    // Written by the connect command (Default), consumed by the validation hook (Main).
    @Volatile
    internal var pendingTorRouteUpgradeSessionId: String? = null

    internal val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!isUpstreamNetwork(network)) {
                    return
                }
                recordNetworkEvent(
                    message = "upstream available",
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                )
                upstreamNetworkHandles += network.networkHandle
                updateActiveVpnUnderlyingNetwork(network)
                publishUpstreamNetworkChange(network, reason = "available")
                runtime.onDefaultNetworkAvailable()
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (snapshot.state == ConnectionState.RECONNECTING) {
                    val session = activeSession
                    if (session != null) {
                        scheduleValidation(
                            session = session,
                            failOnFailure = false,
                            onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
                        )
                    }
                }
                updateNotification()
            }

            override fun onLost(network: Network) {
                val wasAcceptedUpstream = upstreamNetworkHandles.remove(network.networkHandle)
                if (!wasAcceptedUpstream && !isUpstreamNetwork(network)) {
                    return
                }
                val fallbackUpstream = currentUpstreamNetworkOrNull(excludedHandle = network.networkHandle)
                if (fallbackUpstream != null) {
                    upstreamNetworkHandles += fallbackUpstream.networkHandle
                    recordNetworkEvent(
                        message = "upstream switched",
                        capabilities = connectivityManager.getNetworkCapabilities(fallbackUpstream),
                    )
                    updateActiveVpnUnderlyingNetwork(fallbackUpstream)
                    publishUpstreamNetworkChange(fallbackUpstream, reason = "switched")
                    runtime.onDefaultNetworkAvailable()
                    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                    if (snapshot.state == ConnectionState.RECONNECTING) {
                        val session = activeSession
                        if (session != null) {
                            scheduleValidation(
                                session = session,
                                failOnFailure = false,
                                onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
                            )
                        }
                    }
                    updateNotification()
                    return
                }
                recordNetworkEvent(
                    message = "upstream lost",
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                )
                updateActiveVpnUnderlyingNetwork(null)
                publishUpstreamNetworkChange(null, reason = "lost")
                runtime.onDefaultNetworkLost()
                invalidateValidationEpoch("upstream_lost")
                val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
                if (snapshot.state == ConnectionState.CONNECTED) {
                    stopGeoRefresh()
                    bridgeWriter.update(
                        snapshot.copy(
                            state = ConnectionState.RECONNECTING,
                            message = getString(R.string.status_reconnecting),
                        ),
                    )
                }
                updateNotification()
            }
        }

    internal val vpnNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) {
                    return
                }
                recordNetworkEvent(
                    message = "vpn network available",
                    capabilities = capabilities,
                )
                if (activeSession != null || activeLocalGuardMode != null) {
                    activeVpnNetworkHandle = network.networkHandle
                }
            }

            override fun onLost(network: Network) {
                val lostHandle = network.networkHandle
                val trackedHandle = activeVpnNetworkHandle
                recordNetworkEvent(
                    message = "vpn network lost",
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                )
                if (ignoredVpnNetworkLossHandles.remove(lostHandle)) {
                    container.diagnosticsLogger.recordStructured(
                        "connection",
                        "ignored expected vpn network loss",
                        "lost_handle=$lostHandle",
                    )
                    return
                }
                if (trackedHandle == null || trackedHandle == lostHandle) {
                    handleVpnNetworkLost(lostHandle, reason = "vpn_network_lost")
                    return
                }
                scope.launch(Dispatchers.Main.immediate) {
                    delay(VPN_NETWORK_LOST_SETTLE_MS)
                    if (currentVpnNetworkOrNull()?.networkHandle == null) {
                        handleVpnNetworkLost(lostHandle, reason = "vpn_network_lost")
                    }
                }
            }
        }

    internal val defaultNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onDefaultNetworkCapabilitiesChanged(
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                    reason = "default network available",
                )
            }

            override fun onLost(network: Network) {
                defaultNetworkAvailable = false
                lastDefaultNetworkSummary = null
                container.diagnosticsLogger.record("network", "default network lost")
                if (FoxholeVpnRuntimeBridge.snapshot.value.state in NOTIFICATION_HEALTH_VISIBLE_STATES) {
                    markNotificationConnectivityOffline()
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                onDefaultNetworkCapabilitiesChanged(
                    capabilities = networkCapabilities,
                    reason = "default network changed",
                )
            }
        }

    override fun onCreate() {
        super.onCreate()
        FoxholeConnectionServiceContract.markServiceCreated(TrafficMode.TUNNEL)
        claimForegroundSlotEarly(notificationManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestServiceStartId = startId
        FoxholeVpnRuntimeBridge.updateSocketProtector { socket -> protect(socket) }
        attachGuardDaemon("vpn-service")
        return handleForegroundRuntimeCommand(
            intent = intent,
            startId = startId,
            trafficMode = TrafficMode.TUNNEL,
            notificationManager = notificationManager,
            currentNotificationSnapshot = ::currentNotificationSnapshot,
            buildNotification = ::buildNotification,
            container = container,
            dispatchRuntimeCommand = ::dispatchRuntimeCommand,
            connect = ::connect,
            disconnect = { commandStartId -> disconnect(commandStartId = commandStartId) },
            disconnectWithOptions = { commandStartId, suppressLocalGuard, preserveSmartStartAnalysis ->
                disconnect(
                    commandStartId = commandStartId,
                    suppressLocalGuard = suppressLocalGuard,
                    preserveSmartStartAnalysis = preserveSmartStartAnalysis,
                )
            },
            reload = ::reload,
            startLocalGuard = ::startLocalGuard,
            failClosedTeardown = ::failClosedTeardown,
        )
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        guardDaemonOrNull()?.journalTaskRemoved("vpn-service")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FoxholeConnectionServiceContract.markServiceDestroyed(TrafficMode.TUNNEL)
        detachGuardDaemon("vpn-service")
        FoxholeVpnRuntimeBridge.updateSocketProtector(null)
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val activeMode = runtimeSupervisor.ownership.value.activeMode
        val runtimeOwnedByAnotherMode =
            activeMode?.let { mode -> mode != TrafficMode.TUNNEL } == true ||
                snapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL)
        val ownsNativeRuntime = !runtimeOwnedByAnotherMode
        val hadActiveRuntime =
            activeMode == TrafficMode.TUNNEL ||
                activeMode == null && snapshot.isActiveRuntimeFor(TrafficMode.TUNNEL)
        super.onDestroy()
        stopTrafficUpdates()
        anomalyTrafficAggregator.reset()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        stopChildProcessWatchdog()
        cancelScheduledAutoReconnect(resetAttempts = true)
        // Leave the safety net alone: an error teardown always ends here, and the one-shot worker
        // is the only thing that carries the heal past the service's death.
        cancelLocalGuardHeal(resetAttempts = true, cancelSafetyNet = false)
        invalidateValidationEpoch("service_destroy")
        runtimeSupervisor.closeCommandOwner(runtimeCommandOwner)
        if (ownsNativeRuntime) {
            runtimeInstanceStore.current()?.let { runtime ->
                if (!runtime.nativeSnapshot().isIdleWithoutAttachedRuntimeResources()) {
                    stopRuntimeAfterServiceDestroy(
                        // Not this service's scope: it is cancelled at the end of onDestroy().
                        scope = processLifetimeScope,
                        runtime = runtime,
                        diagnosticsLogger = DiagnosticsLoggerRuntimeDiagnosticsSink(container.diagnosticsLogger),
                        owner = "vpn",
                    )
                }
            }
            // Fail-closed: i2pd is the only app-managed child process. Arti and its managed
            // transports are owned by the native runtime and are torn down with that handle.
            container.i2pdManager.kill("service_destroy")
        }
        releaseRuntimeWakeLock()
        if (hadActiveRuntime) {
            // Cross-mode handoff safety now lives in the bridge writer: a stale mode's ERROR
            // publication is rejected centrally, so this call is safe to make unconditionally —
            // the return value only picks the right diagnostics line.
            if (publishUnexpectedRuntimeStopSnapshot()) {
                container.diagnosticsLogger.record(
                    "connection",
                    "vpn service destroyed while runtime was active; failing closed",
                )
            } else {
                container.diagnosticsLogger.record(
                    "connection",
                    "vpn service destroyed during cross-mode handoff; snapshot left to the active mode",
                )
            }
        }
        // Belt-and-braces: the stop*Updates/stop*Monitoring calls above already unregistered every
        // task; this guarantees an emptied ticker even if a future task forgets its stop hook.
        sessionTicker.stop()
        scope.cancel()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        if (vpnNetworkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(vpnNetworkCallback) }
            vpnNetworkCallbackRegistered = false
        }
        if (defaultNetworkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) }
            defaultNetworkCallbackRegistered = false
        }
        lastRuntimeStopResourceEvent?.let { event ->
            recordRuntimeResourceSnapshot(event = event, async = false)
            lastRuntimeStopResourceEvent = null
        }
        recordRuntimeResourceSnapshot(event = "service_destroy_after_callbacks_unregistered", async = false)
        runtimeSupervisor.clearRuntimeOwnership(TrafficMode.TUNNEL)
    }

    override fun onRevoke() {
        // Permission loss must invalidate the in-flight transition synchronously, then jump ahead
        // of START/SWITCH work. KILL preemption also invokes the supervisor's emergency native
        // teardown before this orderly disconnect publishes the final revoked state.
        beginRuntimeTransition("permission_revoked")
        launchPriorityCommand(RuntimeCommandPriority.KILL, "permission_revoked") {
            disconnect(message = getString(R.string.vpn_permission_revoked))
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override val runtimeContext: Context
        get() = this

    override fun stopRuntimeService() {
        container.diagnosticsLogger.record("connection", "native runtime requested vpn service stop; failing closed")
        launchPriorityCommand(RuntimeCommandPriority.KILL, "native_stop") {
            failClosedTeardown(commandStartId = 0, action = ACTION_NATIVE_RUNTIME_STOP)
        }
    }

    override fun protectSocket(socket: Int): Boolean = protect(socket)

    override fun hasVpnPermission(): Boolean = VpnService.prepare(this) == null

    override fun createTunBuilder(): Builder = Builder()

    override fun currentUnderlyingNetwork(): Network? = currentUpstreamNetworkOrNull()

    internal fun beginRuntimeTransition(reason: String): Long {
        return runtimeSupervisor.beginTransition(reason)
    }

    internal fun isCurrentRuntimeTransition(
        generation: Long,
        owner: String,
    ): Boolean =
        runtimeSupervisor.isCurrentTransition(generation = generation, owner = owner)

    internal fun requestPostHandoffRuntimeNetworkReset(previousVpnNetworkHandle: Long?) {
        if (previousVpnNetworkHandle == null) {
            return
        }
        container.diagnosticsLogger.record(
            "runtime",
            "runtime default network reset requested after vpn handoff",
        )
        updateActiveVpnUnderlyingNetwork(currentUpstreamNetworkOrNull())
        runtime.onDefaultNetworkAvailable()
    }

    @Suppress("CyclomaticComplexMethod")
    internal suspend fun disconnect(
        message: String? = null,
        commandStartId: Int? = null,
        reasonCode: AutoConnectReasonCode? = null,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ) {
        beginRuntimeTransition(if (message == null) "disconnect" else "disconnect_error")
        val session = activeSession
        val localGuardMode = activeLocalGuardMode
        val previousVpnNetworkHandle = activeVpnNetworkHandle ?: currentVpnNetworkOrNull()?.networkHandle
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (
            session == null &&
            localGuardMode == null &&
            previousSnapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL)
        ) {
            container.diagnosticsLogger.record(
                "connection",
                "disconnect ignored by inactive tunnel service while another mode is active",
            )
            removeForegroundNotification()
            stopService(commandStartId)
            return
        }
        val analysisStatus = getString(R.string.notification_status_analysis)
        val smartStartAnalysis =
            resolveSmartStartAnalysisPreservation(
                previousSnapshot = previousSnapshot,
                analysisStatus = analysisStatus,
                disconnectMessage = message,
                preserveSmartStartAnalysis = preserveSmartStartAnalysis,
            )
        val finalTraffic =
            if (session != null) {
                trafficSampler.sample()
            } else {
                TrafficSnapshot()
            }
        if (session != null) {
            persistProfileTraffic(session, finalTraffic)
        }
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session ended",
            "mode=${container.settingsRepository.current().traffic.mode.name.lowercase()}",
            message?.let { "result=error" } ?: "result=stopped",
            message?.takeIf(String::isNotBlank)?.let { "reason=$it" },
        )
        if (session != null && message != null) {
            recordSmartStartProtocolDown(
                session = session,
                reasonCode = reasonCode ?: AutoConnectReasonCode.CONNECT_ERROR,
                headline = "runtime error protocol marked down",
                detail = message.takeIf(String::isNotBlank)?.let { "message=$it" },
            )
        }
        closeRuntimeSession(
            reason = "disconnect",
            // Only a clean stop cancels the guard heal. An error teardown (message != null) — e.g.
            // a guard start that failed because the device booted with no network — must leave a
            // scheduled heal intact so it can retry once connectivity returns.
            cancelHeal = message == null,
        )
        awaitStoppedVpnNetworkTeardown(
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            reason = "disconnect",
        )
        bridgeWriter.clearTransientState(clearIpInfo = true)
        bridgeWriter.update(
            stoppedRuntimeSnapshot(
                session = session,
                state = if (message == null) ConnectionState.IDLE else ConnectionState.ERROR,
                trafficMode = container.settingsRepository.current().traffic.mode,
                message = message ?: smartStartAnalysis.message,
                reasonCode = reasonCode,
                isSmartStartConnection = smartStartAnalysis.isSmartStartConnection,
            ),
        )
        updateNotification()
        if (session != null && message == null && !suppressLocalGuard) {
            val nextGuardMode = container.settingsRepository.current().localGuardModeOrNull()
            if (nextGuardMode != null) {
                startLocalGuard(nextGuardMode, commandStartId ?: 0)
                return
            }
        }
        if (localGuardMode != null && session == null) {
            container.diagnosticsLogger.record(
                "connection",
                "local guard stopped mode=${localGuardMode.name.lowercase()}",
            )
        }
        removeForegroundNotification()
        stopService(commandStartId)
    }

    companion object {
        internal const val GEO_REFRESH_ATTEMPTS = 6
        internal const val GEO_REFRESH_INITIAL_DELAY_MS = 0L
        internal const val GEO_REFRESH_RETRY_DELAY_MS = 2_000L
        internal const val GEO_REFRESH_CALL_TIMEOUT_MS = 5_000L
        internal const val VPN_NETWORK_WAIT_TIMEOUT_MS = 3_000L
        internal const val VPN_NETWORK_LOST_SETTLE_MS = 350L
        internal const val LOCAL_GUARD_VPN_NETWORK_WAIT_TIMEOUT_MS = 5_000L
        internal const val LOCAL_GUARD_NETWORK_VALIDATION_TIMEOUT_MS = 12_000L
        internal const val LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS = 1_500L
        internal const val LOCAL_GUARD_CONNECTIVITY_PROBE_TIMEOUT_MS = 3_000L
        internal const val LOCAL_GUARD_CONNECTIVITY_DNS_PROBE_HOST = "example.com"
        internal const val CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS = 3_000L

        // Tor is at 100% by validation time (startup already awaited it), so this control-port ready
        // check normally returns on the first poll; the budget only covers a rare still-bootstrapping
        // edge before falling through to the ordinary exit-IP probe.
        internal const val TOR_VALIDATION_READY_TIMEOUT_MS = 8_000L
        internal const val VPN_NETWORK_WAIT_POLL_DELAY_MS = 100L
        internal const val IPV4_ENRICHMENT_CALL_TIMEOUT_MS = 4_000L
        internal val NOTIFICATION_HEALTH_VISIBLE_STATES =
            setOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
        internal val NOTIFICATION_HEALTH_PROBE_STATES =
            setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
        internal const val NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS = 1_000L

        // Aligned with PROXY_NOTIFICATION_HEALTH_FAILURE_THRESHOLD: with the 15s->120s probe
        // backoff, 5 consecutive failures meant minutes before the tunnel reacted to dead egress.
        internal const val NOTIFICATION_HEALTH_FAILURE_THRESHOLD = 3
        internal val UDP_HEALTH_PROBE_PAYLOAD = byteArrayOf(0x66)
        internal const val CONNECTIVITY_PROBE_ATTEMPTS = 4
        internal const val CONNECTIVITY_PROBE_INITIAL_DELAY_MS = 150L
        internal const val CONNECTIVITY_PROBE_RETRY_DELAY_MS = 500L
        internal const val CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 2_500L
        internal const val CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 24_000L
        internal const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = com.foxhole.core.runtime.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
        internal const val CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS = 900L
        internal const val CONNECTIVITY_LITERAL_PROBE_POLL_MS = 100L
        internal const val CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS = 1_200L
        internal const val LOCAL_GUARD_PROFILE_ID = com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
        internal const val TOR_ONLY_PROFILE_ID = com.foxhole.core.model.TOR_ONLY_PROFILE_ID
        internal const val APP_TRAFFIC_SAMPLE_INTERVAL_MS = 60_000L
        internal const val APP_TRAFFIC_SAMPLE_CACHE_MAX_AGE_MS = APP_TRAFFIC_SAMPLE_INTERVAL_MS + 15_000L

        // Session-ticker task ids (Ф3e). Shared constants: the registration sites and the
        // isRegistered gates live in different files, and a drifted literal would silently
        // disable a gate instead of failing to compile.
        internal const val TICKER_TASK_TRAFFIC = "traffic"
        internal const val TICKER_TASK_APP_TRAFFIC = "app_traffic"
        internal const val TICKER_TASK_DNS_GUARD_WINDOW = "dns_guard_window"
        internal const val TICKER_TASK_NOTIFICATION_HEALTH = "notification_health"
        internal const val TICKER_TASK_CHILD_WATCHDOG = "child_watchdog"
        internal const val MAX_TRAFFIC_MAP_RUNTIME_CONNECTIONS = 512
        internal const val RUNTIME_CONNECTION_OBSERVER_STOP_MS = 1_000L

        // ConnectivityManager routinely lags several seconds behind the real tun close on a busy
        // system; too-tight windows here made the fail-closed escalation kill the process on a
        // healthy stop (seen by the user as a crash at Stop).
        internal const val VPN_NETWORK_TEARDOWN_SETTLE_TIMEOUT_MS = 4_000L
        internal const val VPN_NETWORK_TEARDOWN_SETTLE_POLL_MS = 100L
        internal const val VPN_NETWORK_TEARDOWN_ESCALATION_TIMEOUT_MS = 4_000L
        internal const val ACTION_NATIVE_RUNTIME_STOP = "foxcore_runtime_stop"
    }
}
