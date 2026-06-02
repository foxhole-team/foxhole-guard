package com.foxhole.beta.vpn

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.anomaly.AndroidNetworkTypeProvider
import com.foxhole.beta.core.anomaly.DnsRuntimeStats
import com.foxhole.beta.core.anomaly.TrafficAggregationContext
import com.foxhole.beta.core.anomaly.TrafficWindowAggregator
import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.model.dnsRuleSetFilteringEnabled
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.mergeIpInfo
import com.foxhole.beta.core.settings.AppTrafficStatsRecorder
import com.foxhole.beta.core.traffic.LibboxDnsRuntimeStatsTracker
import com.foxhole.beta.core.traffic.RuntimeNetworkActivityContext
import com.foxhole.beta.core.traffic.TorGeoIpCountryResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

@Suppress("TooGenericExceptionCaught")
private suspend inline fun <T> runCatchingUnlessCancelled(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }

class FoxholeVpnService : VpnService(), RuntimeServiceHost {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
    private val runtimeInstanceStore =
        RuntimeInstanceStore {
            createVpnRuntime(
                context = applicationContext,
                diagnosticsLogger = container.diagnosticsLogger,
                isNetworkActivityLoggingEnabled = {
                    container.settingsRepository.settings.value.expert.networkActivityLogging &&
                        !runtimeNetworkActivityLoggingSuspended
                },
                networkActivityContext = {
                    activeSession
                        ?.let { session ->
                            NetworkActivityContext(
                                profileId = session.profileId,
                                sessionId = session.correlationId,
                                trafficMode = TrafficMode.TUNNEL,
                                runtimeProxyPort = container.settingsRepository.settings.value.tunnelRuntimeProxyAccess().port,
                            )
                        }
                        ?: NetworkActivityContext()
                },
            )
        }
    internal val runtime: VpnCoreRuntime
        get() = runtimeInstanceStore.get()
    private val runtimeSupervisorLock = Any()
    private var runtimeSupervisorInstance: RuntimeSupervisor? = null
    internal val runtimeSupervisor: RuntimeSupervisor
        get() =
            runtimeSupervisorInstance ?: synchronized(runtimeSupervisorLock) {
                runtimeSupervisorInstance ?: RuntimeSupervisor(
                    scope = scope,
                    diagnosticsLogger = container.diagnosticsLogger,
                    emergencyKill = { reason ->
                        runtimeInstanceStore.current()?.forceKill(reason)
                            ?: RuntimeKillResult(reason = reason, tunClosed = true, serverDetached = false)
                    },
                ).also { runtimeSupervisorInstance = it }
            }

    @Volatile
    private var lastRuntimeStopResourceEvent: String? = null

    internal val trafficSampler = TrafficStatsSampler()
    internal val anomalyTrafficAggregator = TrafficWindowAggregator()
    internal val anomalyNetworkTypeProvider by lazy { AndroidNetworkTypeProvider(applicationContext) }
    internal val dnsRuntimeStatsTracker by lazy { LibboxDnsRuntimeStatsTracker(container.diagnosticsLogger) }
    private val runtimeCountryResolver by lazy { TorGeoIpCountryResolver(applicationContext) }
    internal val appTrafficStatsRecorder by lazy {
        AppTrafficStatsRecorder(
            anomalyRepository = container.anomalyRepository,
            context = applicationContext,
        )
    }
    internal var activeSession: VpnSession?
        get() = runtimeSupervisor.ownership.value.activeSession
        set(value) {
            runtimeSupervisor.setActiveSession(value)
        }
    internal var activeLocalGuardMode: LocalGuardMode?
        get() = runtimeSupervisor.ownership.value.activeLocalGuardMode
        set(value) {
            runtimeSupervisor.setActiveLocalGuardMode(value)
        }
    internal var trafficJob: Job? = null
    internal var trafficMapCountryTrackingJob: Job? = null
    internal var dnsRuntimeStatsJob: Job? = null
    internal var immediateTrafficSampleJob: Job? = null
    internal var geoRefreshJob: Job? = null
    internal var ipv4EnrichmentJob: Job? = null
    private var validationJobBacking: Job? = null
    internal var validationJob: Job?
        get() = validationJobBacking
        set(value) {
            validationJobBacking = value
            runtimeSupervisor.setValidationActive(value != null)
        }
    private var validationEpoch: Long = 0L
    internal var notificationHealthJob: Job? = null
    internal var appTrafficStatsJob: Job? = null
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
    internal var notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
    internal var consecutiveNotificationHealthFailures = 0
    internal var defaultNetworkAvailable = true
    internal var lastDefaultNetworkSummary: String? = null
    internal val upstreamNetworkHandles = mutableSetOf<Long>()
    internal var activeVpnNetworkHandle: Long?
        get() = runtimeSupervisor.ownership.value.activeVpnNetworkHandle
        set(value) {
            runtimeSupervisor.setActiveVpnNetworkHandle(value)
        }
    internal val ignoredVpnNetworkLossHandles = mutableSetOf<Long>()
    internal var runtimeNetworkActivityLoggingSuspended = false

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
                    FoxholeVpnRuntimeBridge.update(
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

    override fun onDestroy() {
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val hadActiveRuntime =
            activeSession != null ||
                activeLocalGuardMode != null ||
                snapshot.isActiveRuntimeFor(TrafficMode.TUNNEL)
        super.onDestroy()
        stopTrafficUpdates()
        anomalyTrafficAggregator.reset()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        cancelScheduledAutoReconnect(resetAttempts = true)
        invalidateValidationEpoch("service_destroy")
        runtimeSupervisorInstance?.close()
        runtimeInstanceStore.current()?.let { runtime ->
            if (!runtime.nativeSnapshot().isIdleWithoutAttachedRuntimeResources()) {
                stopRuntimeAfterServiceDestroy(
                    runtime = runtime,
                    diagnosticsLogger = container.diagnosticsLogger,
                    owner = "vpn",
                )
            }
        }
        if (activeSession?.profileId == TOR_ONLY_PROFILE_ID) {
            container.torManager.kill("service_destroy")
        }
        releaseRuntimeWakeLock()
        if (hadActiveRuntime) {
            container.diagnosticsLogger.record(
                "connection",
                "vpn service destroyed while runtime was active; failing closed",
            )
            publishUnexpectedRuntimeStopSnapshot()
        }
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
    }

    override fun onRevoke() {
        launchPriorityCommand(RuntimeCommandPriority.STOP, "permission_revoked") {
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

    private suspend fun startRuntimeWithHealthMetrics(
        session: VpnSession,
        owner: String,
    ): Result<Unit> {
        val runtimeStartAtMs = SystemClock.elapsedRealtime()
        val runtimeStartTimeoutMs = runtimeStartTimeoutMsForSession(session)
        val result =
            runtime.startFailClosed(
                session = session,
                host = this,
                owner = owner,
                diagnosticsLogger = container.diagnosticsLogger,
                timeoutMessage = getString(R.string.error_runtime_start_timeout),
                timeoutMs = runtimeStartTimeoutMs,
            )
        RuntimeHealthMetrics.recordStart(
            owner = owner,
            success = result.isSuccess,
            elapsedMs = SystemClock.elapsedRealtime() - runtimeStartAtMs,
            diagnosticsLogger = container.diagnosticsLogger,
        )
        recordRuntimeResourceSnapshot(
            event = if (result.isSuccess) "start_success" else "start_failure",
        )
        return result
    }

    private fun recordRuntimeResourceSnapshot(
        event: String,
        async: Boolean = true,
    ) {
        val runtimeGeneration = runtimeSupervisorInstance?.currentGeneration() ?: 0L
        val commandQueue = runtimeSupervisorInstance?.queueSnapshot() ?: RuntimeCommandQueueSnapshot.EMPTY
        val nativeSnapshot = runtimeInstanceStore.nativeSnapshot()
        val activeNetworkCallbacks = activeNetworkCallbackCount()
        if (async) {
            RuntimeHealthMetrics.recordResourceSnapshotAsync(
                scope = scope,
                dispatcher = Dispatchers.IO,
                owner = "vpn",
                event = event,
                runtimeGeneration = runtimeGeneration,
                commandQueue = commandQueue,
                nativeSnapshot = nativeSnapshot,
                activeNetworkCallbacks = activeNetworkCallbacks,
                diagnosticsLogger = container.diagnosticsLogger,
            )
        } else {
            RuntimeHealthMetrics.recordResourceSnapshot(
                owner = "vpn",
                event = event,
                runtimeGeneration = runtimeGeneration,
                commandQueue = commandQueue,
                nativeSnapshot = nativeSnapshot,
                activeNetworkCallbacks = activeNetworkCallbacks,
                diagnosticsLogger = container.diagnosticsLogger,
            )
        }
    }

    private fun activeNetworkCallbackCount(): Int =
        listOf(
            networkCallbackRegistered,
            vpnNetworkCallbackRegistered,
            defaultNetworkCallbackRegistered,
        ).count { registered -> registered }

    private fun delegateConnectToForegroundService(
        profileId: Long,
        trafficMode: TrafficMode,
        commandStartId: Int,
        protocolOptionIdOverride: String?,
        previousVpnNetworkHandle: Long?,
    ) {
        FoxholeConnectionServiceContract.startForegroundService(
            context = this,
            mode = trafficMode,
            action = FoxholeConnectionServiceContract.ACTION_CONNECT,
            profileId = profileId,
            protocolOptionId = protocolOptionIdOverride,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
        )
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopService(commandStartId)
    }

    private suspend fun validatePrivateDnsState(
        privateDnsState: PrivateDnsState?,
        commandStartId: Int,
    ): Boolean {
        val privateDnsMode = privateDnsState?.mode
        val supported = privateDnsMode?.isSupportedForTunnelMode() != false
        if (!supported) {
            container.diagnosticsLogger.record("dns", "unsupported android private dns state: $privateDnsState")
            fail(getString(R.string.error_private_dns_unknown_unsupported), commandStartId)
        } else if (privateDnsState != null) {
            container.diagnosticsLogger.record(
                "dns",
                "android private dns mode: ${privateDnsState.mode} hostname=${privateDnsState.hostname.orEmpty()}",
            )
        }
        return supported
    }

    private suspend fun handleRuntimeStartResult(
        result: Result<Unit>,
        session: VpnSession,
        trafficMode: TrafficMode,
        tcpReadinessTarget: VpnHealthProbeTarget?,
        previousVpnNetworkHandle: Long?,
        commandStartId: Int,
        transitionGeneration: Long,
    ) {
        if (!isCurrentRuntimeTransition(transitionGeneration, "runtime_start_result")) {
            return
        }
        if (result.isSuccess) {
            container.connectionController.markCurrentRuntimeApplied()
            requestPostHandoffRuntimeNetworkReset(previousVpnNetworkHandle)
            requestTcpRuntimeNetworkReset(tcpReadinessTarget)
            when (trafficMode) {
                TrafficMode.TUNNEL -> {
                    container.diagnosticsLogger.record("connection", "runtime started, tunnel validation required")
                    scheduleValidation(
                        session = session,
                        failOnFailure = true,
                        expectedFreshVpnNetworkHandle = previousVpnNetworkHandle,
                        onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
                    )
                }
                TrafficMode.PROXY -> {
                    container.diagnosticsLogger.record("connection", "proxy runtime started")
                    onConnectionStarted(session, trafficMode)
                }
            }
        } else {
            val error = result.exceptionOrNull()
            fail(error?.let { describeVpnRuntimeFailure(it) } ?: getString(R.string.error_runtime_missing), commandStartId)
        }
    }

    internal suspend fun connect(
        profileId: Long,
        commandStartId: Int,
        protocolOptionIdOverride: String? = null,
        previousVpnNetworkHandle: Long? = null,
    ) {
        val torOnlyConnect = profileId == TOR_ONLY_PROFILE_ID
        if (profileId <= 0 && !torOnlyConnect) {
            disconnect(message = getString(R.string.error_profile_missing), commandStartId = commandStartId)
            return
        }
        val settings = container.settingsRepository.current()
        val trafficMode = if (torOnlyConnect) TrafficMode.TUNNEL else settings.traffic.mode
        if (trafficMode != TrafficMode.TUNNEL) {
            delegateConnectToForegroundService(
                profileId = profileId,
                trafficMode = trafficMode,
                commandStartId = commandStartId,
                protocolOptionIdOverride = protocolOptionIdOverride,
                previousVpnNetworkHandle = previousVpnNetworkHandle,
            )
            return
        }
        val privateDnsState =
            if (trafficMode == TrafficMode.TUNNEL) {
                PrivateDnsSettings.currentState(this)
            } else {
                null
            }
        if (!validatePrivateDnsState(privateDnsState, commandStartId)) {
            return
        }
        val transitionGeneration = beginRuntimeTransition("connect")
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = trafficMode)
        val session =
            runCatching {
                if (torOnlyConnect) {
                    container.profileRepository.getTorOnlySession(privateDnsState = privateDnsState)
                } else {
                    container.profileRepository.getSession(
                        profileId = profileId,
                        protocolOptionIdOverride = protocolOptionIdOverride,
                        privateDnsState = privateDnsState,
                    )
                }
            }
                .getOrElse {
                    fail(it.message ?: getString(R.string.error_profile_invalid), commandStartId)
                    return
                }
        currentCoroutineContext().ensureActive()
        if (!isCurrentRuntimeTransition(transitionGeneration, "connect_session_loaded")) {
            return
        }
        val localGuardVpnNetworkHandle = stopActiveLocalGuardBeforeTunnelConnect()
        val validationExcludedVpnNetworkHandle = previousVpnNetworkHandle ?: localGuardVpnNetworkHandle
        activeSession = session
        activeLocalGuardMode = null
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session started",
            "sessionId=${session.correlationId}",
            "mode=${trafficMode.name.lowercase()}",
        )
        val tcpReadinessTarget = tcpRuntimeReadinessTarget(session)
        val tcpReadiness = prepareTcpRuntimeReadiness(tcpReadinessTarget)
            .onFailure {
                fail(getString(R.string.error_tcp_runtime_readiness_failed), commandStartId)
                return
            }
            .getOrNull()
        FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(
            activeServerPingTarget(session, tcpReadinessTarget, tcpReadiness),
        )
        FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = trafficMode,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
                message = FoxholeVpnRuntimeBridge.snapshot.value.message,
                isSmartStartConnection = FoxholeVpnRuntimeBridge.snapshot.value.isSmartStartConnection,
            ),
        )
        updateNotification()
        if (trafficMode == TrafficMode.TUNNEL) {
            registerNetworkCallbackIfNeeded()
            registerVpnNetworkCallbackIfNeeded()
        }
        registerDefaultNetworkCallbackIfNeeded()
        acquireRuntimeWakeLock()
        startNotificationHealthMonitoring()
        val result = startRuntimeWithHealthMetrics(session = session, owner = "vpn")
        if (!currentCoroutineContext().isActive) {
            container.diagnosticsLogger.record("connection", "runtime start cancelled after native return")
            return
        }
        handleRuntimeStartResult(
            result = result,
            session = session,
            trafficMode = trafficMode,
            tcpReadinessTarget = tcpReadinessTarget,
            previousVpnNetworkHandle = validationExcludedVpnNetworkHandle,
            commandStartId = commandStartId,
            transitionGeneration = transitionGeneration,
        )
    }

    private fun beginRuntimeTransition(reason: String): Long {
        return runtimeSupervisor.beginTransition(reason)
    }

    private fun isCurrentRuntimeTransition(
        generation: Long,
        owner: String,
    ): Boolean =
        runtimeSupervisor.isCurrentTransition(generation = generation, owner = owner)

    private fun requestPostHandoffRuntimeNetworkReset(previousVpnNetworkHandle: Long?) {
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

    private suspend fun stopActiveLocalGuardBeforeTunnelConnect(): Long? {
        val localGuardMode = activeLocalGuardMode ?: return null
        val localGuardVpnNetworkHandle = activeVpnNetworkHandle ?: currentVpnNetworkOrNull()?.networkHandle
        container.diagnosticsLogger.record(
            "connection",
            "local guard handoff to tunnel mode=${localGuardMode.name.lowercase()}",
        )
        runtimeNetworkActivityLoggingSuspended = true
        localGuardVpnNetworkHandle?.let(ignoredVpnNetworkLossHandles::add)
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        invalidateValidationEpoch("local_guard_handoff")
        stopRuntimeFailClosed(reason = "local_guard_handoff")
        runtimeInstanceStore.clear()
        container.diagnosticsLogger.record("runtime", "vpn runtime instance reset after local guard handoff")
        container.diagnosticsLogger.record("runtime", "network activity logging suspended for vpn handoff validation")
        releaseRuntimeWakeLock()
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
        return localGuardVpnNetworkHandle
    }

    private suspend fun stopActiveTunnelBeforeLocalGuard(mode: LocalGuardMode) {
        val session = activeSession ?: return
        container.diagnosticsLogger.record(
            "connection",
            "tunnel handoff to local guard mode=${mode.name.lowercase()}",
        )
        val finalTraffic = trafficSampler.sample()
        persistProfileTraffic(session, finalTraffic)
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        invalidateValidationEpoch("tunnel_handoff_to_local_guard")
        stopRuntimeFailClosed(reason = "local_guard_handoff_from_tunnel")
        releaseRuntimeWakeLock()
        activeSession = null
        FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(null)
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        runtimeNetworkActivityLoggingSuspended = false
        RuntimeResumeStateStore.clear(this)
        container.connectionController.clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
    }

    private suspend fun stopRuntimeFailClosed(reason: String): RuntimeStopResult {
        val currentRuntime = runtimeInstanceStore.current()
        if (currentRuntime == null) {
            container.diagnosticsLogger.recordStructured(
                "runtime",
                "vpn runtime stop skipped without native owner",
                "reason=$reason",
            )
            val result =
                RuntimeStopResult(
                    closeServiceOk = true,
                    closeServerOk = true,
                    tunClosed = true,
                    escalatedToKill = false,
                    elapsedMs = 0L,
                )
            val event = "stop_skipped_no_owner:$reason"
            lastRuntimeStopResourceEvent = event
            recordRuntimeResourceSnapshot(event = event)
            return result
        }
        val result = currentRuntime.stopFailClosed(
            owner = "vpn",
            reason = reason,
            diagnosticsLogger = container.diagnosticsLogger,
        )
        val event = if (result.graceful) "stop_success:$reason" else "stop_escalated:$reason"
        lastRuntimeStopResourceEvent = event
        recordRuntimeResourceSnapshot(
            event = event,
            async = false,
        )
        return result
    }

    private suspend fun stopTorProcessIfNeeded(
        session: VpnSession?,
        reason: String,
    ) {
        if (session?.profileId != TOR_ONLY_PROFILE_ID) {
            return
        }
        val result = container.torManager.stop()
        container.diagnosticsLogger.recordStructured(
            "runtime",
            "tor process stopped",
            "reason=$reason",
            "graceful=${result.graceful}",
            "killed=${result.killed}",
        )
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
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        cancelScheduledAutoReconnect(resetAttempts = true)
        invalidateValidationEpoch("disconnect")
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
        stopRuntimeFailClosed(reason = "disconnect")
        stopTorProcessIfNeeded(session, reason = "disconnect")
        releaseRuntimeWakeLock()
        activeSession = null
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        runtimeNetworkActivityLoggingSuspended = false
        RuntimeResumeStateStore.clear(this)
        container.connectionController.clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
        FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = true)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
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

    internal suspend fun startLocalGuard(
        mode: LocalGuardMode,
        commandStartId: Int,
    ) {
        val settings = container.settingsRepository.current()
        val desiredMode = settings.localGuardModeOrNull()
        if (
            handleLocalGuardPreflight(
                desiredMode = desiredMode,
                mode = mode,
                commandStartId = commandStartId,
            )
        ) {
            return
        }
        if (isSameLocalGuardRuntimeActive(mode) && isLocalGuardRuntimeCurrent()) {
            runtimeNetworkActivityLoggingSuspended = false
            container.diagnosticsLogger.record(
                "connection",
                "local guard already active mode=${mode.name.lowercase()}",
            )
            updateNotification()
        } else {
            startNewLocalGuard(mode, commandStartId, settings)
        }
    }

    private suspend fun startNewLocalGuard(
        mode: LocalGuardMode,
        commandStartId: Int,
        settings: Settings,
    ) {
        val transitionGeneration = beginRuntimeTransition("local_guard:${mode.name.lowercase()}")
        stopActiveTunnelBeforeLocalGuard(mode)
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        cancelScheduledAutoReconnect(resetAttempts = true)
        invalidateValidationEpoch("local_guard_start")
        if (activeLocalGuardMode != null) {
            container.diagnosticsLogger.record(
                "connection",
                "local guard restarting mode=${activeLocalGuardMode?.name?.lowercase().orEmpty()}",
            )
            stopRuntimeFailClosed(reason = "local_guard_restart")
            releaseRuntimeWakeLock()
            activeLocalGuardMode = null
            activeVpnNetworkHandle = null
            runtimeNetworkActivityLoggingSuspended = false
        }
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = TrafficMode.TUNNEL)
        val dnsFilterRuntimePaths = settings.prepareLocalGuardDnsFilterRuntimePaths()
        val runtimeSettings = settings.disableUnverifiedLocalGuardDnsFiltering(dnsFilterRuntimePaths)
        val session =
            VpnSession(
                profileId = LOCAL_GUARD_PROFILE_ID,
                profileName = mode.runtimeProfileName(),
                protocolHint = com.foxhole.beta.core.model.ProtocolHint.SING_BOX,
                configJson =
                    container.runtimeConfigAssembler.assembleLocalGuard(
                        settings = runtimeSettings,
                        mode = mode,
                        dnsFilterRuntimePaths = dnsFilterRuntimePaths,
                    ),
                correlationId = "local-guard-${System.currentTimeMillis()}",
            )
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val analysisStatus = getString(R.string.notification_status_analysis)
        val analysisMessage =
            previousSnapshot.message
                .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus }
        activeSession = null
        activeLocalGuardMode = mode
        runtimeNetworkActivityLoggingSuspended = false
        FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = LOCAL_GUARD_PROFILE_ID,
                profileName = mode.runtimeProfileName(),
                protocolHint = com.foxhole.beta.core.model.ProtocolHint.SING_BOX,
                message = analysisMessage,
                isSmartStartConnection = analysisMessage != null,
            ),
        )
        registerNetworkCallbackIfNeeded()
        registerDefaultNetworkCallbackIfNeeded()
        acquireRuntimeWakeLock()
        startNotificationHealthMonitoring()
        val result = startRuntimeWithHealthMetrics(session = session, owner = "local_guard")
        if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_start_result")) {
            return
        }
        if (result.isSuccess) {
            registerVpnNetworkCallbackIfNeeded()
            val localGuardVpnNetwork = awaitLocalGuardVpnNetworkReady(mode)
            if (!isCurrentRuntimeTransition(transitionGeneration, "local_guard_network_ready")) {
                return
            }
            updateActiveVpnUnderlyingNetwork(currentUpstreamNetworkOrNull())
            if (mode == LocalGuardMode.DNS) {
                FoxholeVpnRuntimeBridge.updateTraffic(TrafficSnapshot())
            } else {
                trafficSampler.start()
                startTrafficUpdates()
                startAppTrafficStatsUpdates()
            }
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.CONNECTED,
                    trafficMode = TrafficMode.TUNNEL,
                    profileId = LOCAL_GUARD_PROFILE_ID,
                    profileName = mode.runtimeProfileName(),
                    protocolHint = com.foxhole.beta.core.model.ProtocolHint.SING_BOX,
                    message = analysisMessage,
                    isSmartStartConnection = analysisMessage != null,
                ),
            )
            startGeoRefresh(localGuardVpnNetwork)
            container.diagnosticsLogger.record("connection", "local guard started mode=${mode.name.lowercase()}")
            RuntimeResumeStateStore.markLocalGuardRuntime(this, mode)
            updateNotification()
        } else {
            activeLocalGuardMode = null
            stopNotificationHealthMonitoring()
            releaseRuntimeWakeLock()
            val error = result.exceptionOrNull()
            fail(error?.let { describeVpnRuntimeFailure(it) } ?: getString(R.string.error_runtime_missing), commandStartId)
        }
    }

    private suspend fun awaitLocalGuardVpnNetworkReady(mode: LocalGuardMode): Network? {
        val initialNetwork = awaitVpnNetworkOrNull(LOCAL_GUARD_VPN_NETWORK_WAIT_TIMEOUT_MS)
        if (initialNetwork == null) {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "local guard vpn network wait timeout",
                "mode=${mode.name.lowercase()}",
            )
            delay(LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS)
            return null
        }
        activeVpnNetworkHandle = initialNetwork.networkHandle
        if (isVpnNetworkValidated(initialNetwork)) {
            container.diagnosticsLogger.recordStructured(
                "connection",
                "local guard vpn network validated",
                "mode=${mode.name.lowercase()}",
                "handle=${initialNetwork.networkHandle}",
            )
            return initialNetwork
        }

        var latestNetwork: Network = initialNetwork
        val deadline = SystemClock.elapsedRealtime() + LOCAL_GUARD_NETWORK_VALIDATION_TIMEOUT_MS
        while (currentCoroutineContext().isActive && SystemClock.elapsedRealtime() < deadline) {
            currentVpnNetworkOrNull()?.let { network ->
                latestNetwork = network
                activeVpnNetworkHandle = network.networkHandle
                if (isVpnNetworkValidated(network)) {
                    container.diagnosticsLogger.recordStructured(
                        "connection",
                        "local guard vpn network validated",
                        "mode=${mode.name.lowercase()}",
                        "handle=${network.networkHandle}",
                    )
                    return network
                }
            }
            delay(VPN_NETWORK_WAIT_POLL_DELAY_MS)
        }
        container.diagnosticsLogger.recordStructured(
            "connection",
            "local guard vpn network validation timeout",
            "mode=${mode.name.lowercase()}",
            "handle=${latestNetwork.networkHandle}",
            "settle_ms=$LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS",
        )
        delay(LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS)
        return latestNetwork
    }

    private suspend fun handleLocalGuardPreflight(
        desiredMode: LocalGuardMode?,
        mode: LocalGuardMode,
        commandStartId: Int,
    ): Boolean =
        when {
            desiredMode == null || desiredMode != mode -> {
                disconnect(commandStartId = commandStartId, suppressLocalGuard = true)
                true
            }
            !hasVpnPermission() -> {
                container.diagnosticsLogger.record("connection", "local guard skipped: missing vpn permission")
                stopService(commandStartId)
                true
            }
            shouldBlockSystemDnsLocalGuard(mode) -> {
                container.diagnosticsLogger.record(
                    "dns",
                    "system dns protection skipped: android private dns active",
                )
                container.settingsRepository.updateSystemDnsProtectionEnabled(false)
                disconnect(
                    message = getString(R.string.error_system_dns_private_dns_conflict),
                    commandStartId = commandStartId,
                    suppressLocalGuard = true,
                )
                true
            }
            else -> false
        }

    private fun shouldBlockSystemDnsLocalGuard(mode: LocalGuardMode): Boolean =
        mode == LocalGuardMode.DNS &&
            !PrivateDnsSettings.current(this).isSupportedForSystemDnsProtection()

    private suspend fun Settings.prepareLocalGuardDnsFilterRuntimePaths(): DnsFilterRuntimePaths? {
        if (!dns.dnsRuleSetFilteringEnabled() || expert.systemDnsProtectionEnabled) {
            return null
        }
        return container.dnsFilterAssetInstaller.prepareVerifiedOrNull()
            ?: run {
                container.diagnosticsLogger.record("dns", "dns rule-set runtime disabled: verified filter unavailable")
                null
            }
    }

    private fun Settings.disableUnverifiedLocalGuardDnsFiltering(
        dnsFilterRuntimePaths: DnsFilterRuntimePaths?,
    ): Settings =
        if (dns.dnsRuleSetFilteringEnabled() && !expert.systemDnsProtectionEnabled && dnsFilterRuntimePaths == null) {
            copy(dns = dns.copy(filteringEnabled = false))
        } else {
            this
        }

    internal fun fail(
        message: String,
        commandStartId: Int? = null,
        reasonCode: AutoConnectReasonCode? = null,
    ) {
        container.diagnosticsLogger.record("connection", "runtime failure: $message")
        launchPriorityCommand(RuntimeCommandPriority.STOP, "fail_disconnect") {
            disconnect(message, commandStartId, reasonCode)
        }
    }

    internal suspend fun failClosedTeardown(
        commandStartId: Int,
        action: String?,
    ) {
        beginRuntimeTransition("fail_closed")
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val session = activeSession
        val hadActiveRuntime =
            session != null ||
                activeLocalGuardMode != null ||
                snapshot.isActiveRuntimeFor(TrafficMode.TUNNEL)
        if (!hadActiveRuntime && snapshot.isActiveRuntimeForAnotherMode(TrafficMode.TUNNEL)) {
            container.diagnosticsLogger.record(
                "connection",
                "runtime command ignored by inactive tunnel service while another mode is active",
            )
            removeForegroundNotification()
            stopService(commandStartId)
            return
        }
        val finalTraffic =
            if (session != null) {
                trafficSampler.sample()
            } else {
                TrafficSnapshot()
            }
        if (session != null) {
            persistProfileTraffic(session, finalTraffic)
        }
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        cancelScheduledAutoReconnect(resetAttempts = true)
        invalidateValidationEpoch("runtime_command_fail_closed")
        container.diagnosticsLogger.recordStructured(
            "connection",
            "runtime command fail-closed teardown",
            action?.let { "action=$it" } ?: "action=null",
        )
        stopRuntimeFailClosed(reason = "runtime_command_fail_closed")
        stopTorProcessIfNeeded(session, reason = "runtime_command_fail_closed")
        releaseRuntimeWakeLock()
        activeSession = null
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        runtimeNetworkActivityLoggingSuspended = false
        RuntimeResumeStateStore.clear(this)
        val failClosedMessage =
            if (action == ACTION_NATIVE_RUNTIME_STOP && hadActiveRuntime) {
                getString(R.string.error_runtime_stopped)
            } else {
                null
            }
        container.connectionController.clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = if (hadActiveRuntime) ConnectionState.ERROR else ConnectionState.IDLE,
                trafficMode = container.settingsRepository.current().traffic.mode,
                message = failClosedMessage,
            ),
        )
        removeForegroundNotification()
        stopService(commandStartId)
    }

    private fun publishUnexpectedRuntimeStopSnapshot() {
        activeSession = null
        activeLocalGuardMode = null
        activeVpnNetworkHandle = null
        runtimeNetworkActivityLoggingSuspended = false
        container.connectionController.clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.ERROR,
                trafficMode = container.settingsRepository.settings.value.traffic.mode,
                message = getString(R.string.error_runtime_stopped),
            ),
        )
    }

    internal suspend fun reload(profileIdHint: Long) {
        val targetProfileId =
            activeSession?.profileId
                ?: profileIdHint.takeIf { it > 0L || it == TOR_ONLY_PROFILE_ID }
                ?: return
        val previousSession = activeSession
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
            return
        }
        val session =
            runCatching {
                if (targetProfileId == TOR_ONLY_PROFILE_ID) {
                    container.profileRepository.getTorOnlySession(
                        privateDnsState = PrivateDnsSettings.currentState(this),
                    )
                } else {
                    container.profileRepository.getSession(
                        profileId = targetProfileId,
                        privateDnsState = PrivateDnsSettings.currentState(this),
                    )
                }
            }
                .getOrElse {
                    container.diagnosticsLogger.record("connection", "runtime reload session failed: ${it.message.orEmpty()}")
                    fail(it.message ?: getString(R.string.error_profile_invalid))
                    return
                }
        val transitionGeneration = beginRuntimeTransition("reload")
        val result = runtime.reload(session, this)
        if (!isCurrentRuntimeTransition(transitionGeneration, "reload_result")) {
            return
        }
        if (result.isSuccess) {
            activeSession = session
            FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(activeServerPingTarget(session))
            container.connectionController.markCurrentRuntimeApplied()
            container.diagnosticsLogger.record("connection", "runtime reloaded, tunnel validation required")
            val connectedSnapshot = snapshot.state == ConnectionState.CONNECTED
            val nextState = if (connectedSnapshot) ConnectionState.CONNECTED else ConnectionState.RECONNECTING
            val nextMessage =
                if (connectedSnapshot) {
                    snapshot.message
                } else {
                    getString(R.string.status_reconnecting)
                }
            FoxholeVpnRuntimeBridge.update(
                snapshot.copy(
                    state = nextState,
                    profileId = session.profileId,
                    profileName = session.profileName,
                    protocolHint = session.protocolHint,
                    protocolOptionId = session.protocolOptionId,
                    message = nextMessage,
                ),
                refreshLastChangeAt = !connectedSnapshot,
            )
            FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
            updateNotification()
            scheduleValidation(
                session = session,
                failOnFailure = true,
                onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
            )
        } else {
            val error = result.exceptionOrNull()
            val message = error?.let(::describeVpnRuntimeFailure) ?: "unknown"
            container.diagnosticsLogger.record("connection", "runtime reload failed: $message")
            if (
                restorePreviousRuntimeAfterReloadFailure(
                    previousSession = previousSession,
                    failedSession = session,
                    previousSnapshot = snapshot,
                    message = message,
                    transitionGeneration = transitionGeneration,
                )
            ) {
                return
            }
            if (!recoverRuntimeAfterReloadFailure(session, snapshot, message, transitionGeneration)) {
                fail(message)
            }
        }
    }

    private suspend fun restorePreviousRuntimeAfterReloadFailure(
        previousSession: VpnSession?,
        failedSession: VpnSession,
        previousSnapshot: ConnectionSnapshot,
        message: String,
        transitionGeneration: Long,
    ): Boolean {
        var restored = false
        val restoreSession = previousSession
        if (shouldAttemptRuntimeReloadRestore(previousSession, failedSession) && restoreSession != null) {
            restored =
                restorePreviousRuntimeConfigAfterReloadFailure(
                    restoreSession = restoreSession,
                    previousSnapshot = previousSnapshot,
                    message = message,
                    transitionGeneration = transitionGeneration,
                )
        }
        return restored
    }

    private suspend fun restorePreviousRuntimeConfigAfterReloadFailure(
        restoreSession: VpnSession,
        previousSnapshot: ConnectionSnapshot,
        message: String,
        transitionGeneration: Long,
    ): Boolean {
        var restored = true
        if (isCurrentRuntimeTransition(transitionGeneration, "reload_restore")) {
            container.diagnosticsLogger.record(
                "connection",
                "runtime reload restore previous config requested after: $message",
            )
            val restoreResult = runtime.reload(restoreSession, this)
            val staleRestore = !isCurrentRuntimeTransition(transitionGeneration, "reload_restore_result")
            restored =
                staleRestore ||
                handlePreviousRuntimeConfigRestoreResult(restoreResult, restoreSession, previousSnapshot)
        }
        return restored
    }

    private fun handlePreviousRuntimeConfigRestoreResult(
        restoreResult: Result<Unit>,
        restoreSession: VpnSession,
        previousSnapshot: ConnectionSnapshot,
    ): Boolean {
        var restored = false
        if (restoreResult.isFailure) {
            val restoreMessage = restoreResult.exceptionOrNull()?.let(::describeVpnRuntimeFailure) ?: "unknown"
            container.diagnosticsLogger.record(
                "connection",
                "runtime reload restore previous config failed: $restoreMessage",
            )
        } else {
            publishPreviousRuntimeConfigRestoreSuccess(restoreSession, previousSnapshot)
            restored = true
        }
        return restored
    }

    private fun publishPreviousRuntimeConfigRestoreSuccess(
        restoreSession: VpnSession,
        previousSnapshot: ConnectionSnapshot,
    ) {
        activeSession = restoreSession
        FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(activeServerPingTarget(restoreSession))
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload restored previous config, tunnel validation required",
        )
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        FoxholeVpnRuntimeBridge.update(
            previousSnapshot.copy(
                state = ConnectionState.RECONNECTING,
                profileId = restoreSession.profileId,
                profileName = restoreSession.profileName,
                protocolHint = restoreSession.protocolHint,
                protocolOptionId = restoreSession.protocolOptionId,
                message = getString(R.string.status_reconnecting),
            ),
        )
        updateNotification()
        scheduleValidation(
            session = restoreSession,
            failOnFailure = true,
            onSuccess = { vpnNetwork -> onTunnelValidated(restoreSession, vpnNetwork) },
        )
    }

    private suspend fun recoverRuntimeAfterReloadFailure(
        session: VpnSession,
        previousSnapshot: ConnectionSnapshot,
        message: String,
        transitionGeneration: Long,
    ): Boolean {
        var recovered = true
        if (isCurrentRuntimeTransition(transitionGeneration, "reload_recovery")) {
            container.diagnosticsLogger.record(
                "connection",
                "runtime reload recovery restart requested after: $message",
            )
            invalidateValidationEpoch("reload_recovery")
            stopTrafficUpdates()
            stopAppTrafficStatsUpdates()
            stopGeoRefresh()
            stopRuntimeFailClosed(reason = "reload_recovery")
            activeVpnNetworkHandle = null
            activeSession = session
            FoxholeVpnRuntimeBridge.updateActiveServerPingTarget(activeServerPingTarget(session))
            FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
            FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_UPDATE)
            FoxholeVpnRuntimeBridge.update(
                previousSnapshot.copy(
                    state = ConnectionState.RECONNECTING,
                    profileId = session.profileId,
                    profileName = session.profileName,
                    protocolHint = session.protocolHint,
                    protocolOptionId = session.protocolOptionId,
                    message = getString(R.string.status_reconnecting),
                ),
            )
            updateNotification()
            val restartResult =
                startRuntimeWithHealthMetrics(
                    session = session,
                    owner = "vpn_reload_recovery",
                )
            val staleRecovery = !isCurrentRuntimeTransition(transitionGeneration, "reload_recovery_start_result")
            recovered = staleRecovery || handleRuntimeReloadRecoveryRestartResult(restartResult, session)
        }
        return recovered
    }

    private suspend fun handleRuntimeReloadRecoveryRestartResult(
        restartResult: Result<Unit>,
        session: VpnSession,
    ): Boolean {
        if (restartResult.isFailure) {
            val restartMessage = restartResult.exceptionOrNull()?.let(::describeVpnRuntimeFailure) ?: "unknown"
            container.diagnosticsLogger.record(
                "connection",
                "runtime reload recovery restart failed: $restartMessage",
            )
            return false
        }
        container.connectionController.markCurrentRuntimeApplied()
        container.diagnosticsLogger.record(
            "connection",
            "runtime reload recovery restarted, tunnel validation required",
        )
        FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
        scheduleValidation(
            session = session,
            failOnFailure = true,
            onSuccess = { vpnNetwork -> onTunnelValidated(session, vpnNetwork) },
        )
        return true
    }

    internal fun ensureNotificationChannel() = ensureConnectionNotificationChannel(notificationManager)

    internal fun launchCommand(
        reason: String,
        block: suspend () -> Unit,
    ) {
        runtimeSupervisor.launch(RuntimeCommandPriority.NORMAL, reason = reason, block = block)
    }

    internal fun launchPriorityCommand(
        priority: RuntimeCommandPriority,
        reason: String,
        block: suspend () -> Unit,
    ) {
        runtimeSupervisor.launch(priority, reason = reason, block = block)
    }

    internal fun dispatchRuntimeCommand(
        command: RuntimeCommand,
        block: suspend (RuntimeCommand) -> Unit,
    ) {
        runtimeSupervisor.dispatch(command, block)
    }

    internal fun stopService(commandStartId: Int?) {
        if (commandStartId != null && commandStartId > 0) {
            stopSelfResult(commandStartId)
        } else {
            stopSelf()
        }
    }

    internal fun buildNotification(snapshot: NotificationSnapshot): Notification =
        buildConnectionNotification(
            mode = TrafficMode.TUNNEL,
            snapshot = snapshot,
            collapsedText = ::notificationCollapsedText,
            expandedText = ::notificationExpandedText,
            stateLabel = ::notificationStateLabel,
            smallIconRes = notificationSmallIconRes(snapshot),
            showAction = activeLocalGuardMode == null,
        )

    internal fun updateNotification() {
        updateConnectionNotification { buildNotification(currentNotificationSnapshot()) }
    }

    internal fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        upstreamNetworkHandles.clear()
        val registration =
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        connectivityManager.registerBestMatchingNetworkCallback(trackedNetworkRequest, networkCallback, mainHandler)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                        connectivityManager.registerNetworkCallback(trackedNetworkRequest, networkCallback, mainHandler)
                    else -> connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
                }
            }.recoverCatching {
                connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
            }
        registration
            .onSuccess { networkCallbackRegistered = true }
            .onFailure { container.diagnosticsLogger.record("connection", "network callback registration failed") }
    }

    internal fun updateActiveVpnUnderlyingNetwork(network: Network?) {
        if (activeSession == null && activeLocalGuardMode == null) {
            return
        }
        runCatching {
            setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())
        }.onSuccess { updated ->
            container.diagnosticsLogger.recordStructured(
                "network",
                "VPN underlying network updated",
                "available=${network != null}",
                "updated=$updated",
                "network=${describeNetworkCapabilities(connectivityManager.getNetworkCapabilities(network))}",
            )
        }.onFailure {
            container.diagnosticsLogger.record("network", "vpn underlying network update failed")
        }
    }

    private fun publishUpstreamNetworkChange(
        network: Network?,
        reason: String,
    ) {
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (!snapshot.shouldPublishUpstreamNetworkChange()) {
            return
        }
        val nextRevision = snapshot.upstreamNetworkRevision + 1L
        FoxholeVpnRuntimeBridge.update(
            snapshot.copy(upstreamNetworkRevision = nextRevision),
            refreshLastChangeAt = false,
        )
        container.diagnosticsLogger.recordStructured(
            "network",
            "upstream network refresh signal",
            "reason=$reason",
            "available=${network != null}",
            "revision=$nextRevision",
        )
    }

    private fun ConnectionSnapshot.shouldPublishUpstreamNetworkChange(): Boolean =
        state in setOf(ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            trafficMode == TrafficMode.TUNNEL &&
            profileId != null &&
            profileId != FoxholeVpnService.LOCAL_GUARD_PROFILE_ID

    internal fun registerVpnNetworkCallbackIfNeeded() {
        if (vpnNetworkCallbackRegistered) {
            return
        }
        val registration =
            runCatching {
                connectivityManager.registerNetworkCallback(trackedVpnNetworkRequest, vpnNetworkCallback, mainHandler)
            }.recoverCatching {
                connectivityManager.registerNetworkCallback(trackedVpnNetworkRequest, vpnNetworkCallback)
            }
        registration
            .onSuccess { vpnNetworkCallbackRegistered = true }
            .onFailure { container.diagnosticsLogger.record("connection", "vpn network callback registration failed") }
    }

    internal fun handleVpnNetworkLost(
        lostHandle: Long,
        reason: String,
    ) {
        val session = activeSession
        val localGuardMode = activeLocalGuardMode
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (!shouldHandleVpnNetworkLoss(session, localGuardMode, snapshot)) {
            return
        }
        val currentVpnHandle = currentVpnNetworkOrNull()?.networkHandle
        if (currentVpnHandle != null && currentVpnHandle != lostHandle) {
            activeVpnNetworkHandle = currentVpnHandle
            return
        }
        activeVpnNetworkHandle = null
        invalidateValidationEpoch("vpn_network_lost")
        stopGeoRefresh()
        markNotificationConnectivityOffline()
        if (session != null) {
            handleActiveTunnelVpnNetworkLost(
                session = session,
                snapshot = snapshot,
                lostHandle = lostHandle,
                reason = reason,
            )
        } else if (localGuardMode != null) {
            handleLocalGuardVpnNetworkLost(
                mode = localGuardMode,
                snapshot = snapshot,
                lostHandle = lostHandle,
                reason = reason,
            )
        }
    }

    private fun shouldHandleVpnNetworkLoss(
        session: VpnSession?,
        localGuardMode: LocalGuardMode?,
        snapshot: ConnectionSnapshot,
    ): Boolean =
        snapshot.trafficMode == TrafficMode.TUNNEL &&
            snapshot.state in ACTIVE_CONNECTION_STATES &&
            (session != null || localGuardMode != null)

    private fun handleActiveTunnelVpnNetworkLost(
        session: VpnSession,
        snapshot: ConnectionSnapshot,
        lostHandle: Long,
        reason: String,
    ) {
        container.diagnosticsLogger.recordStructured(
            "connection",
            "active vpn network disappeared",
            "reason=$reason",
            "lost_handle=$lostHandle",
            "sessionId=${session.correlationId}",
        )
        FoxholeVpnRuntimeBridge.update(
            snapshot.copy(
                state = ConnectionState.RECONNECTING,
                message = getString(R.string.status_reconnecting),
            ),
        )
        updateNotification()
        if (container.settingsRepository.settings.value.connection.autoReconnect) {
            scheduleAutoReconnect(reason = reason)
        } else {
            fail(
                message = getString(R.string.error_runtime_stopped),
                reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
            )
        }
    }

    private fun handleLocalGuardVpnNetworkLost(
        mode: LocalGuardMode,
        snapshot: ConnectionSnapshot,
        lostHandle: Long,
        reason: String,
    ) {
        val message = getString(R.string.error_runtime_stopped)
        container.diagnosticsLogger.recordStructured(
            "connection",
            "local guard vpn network disappeared",
            "reason=$reason",
            "lost_handle=$lostHandle",
            "mode=${mode.name.lowercase()}",
        )
        FoxholeVpnRuntimeBridge.update(
            snapshot.copy(
                state = ConnectionState.ERROR,
                message = message,
                reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
            ),
        )
        updateNotification()
        fail(
            message = message,
            reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
        )
    }

    internal fun registerDefaultNetworkCallbackIfNeeded() {
        if (defaultNetworkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) }
            defaultNetworkCallbackRegistered = false
        }
        refreshDefaultNetworkAvailability()
        val registration =
            runCatching {
                connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback, mainHandler)
            }
        registration
            .onSuccess { defaultNetworkCallbackRegistered = true }
            .onFailure { container.diagnosticsLogger.record("connection", "default network callback registration failed") }
    }

    internal fun startTrafficUpdates() {
        stopTrafficUpdates()
        DnsRuntimeStats.reset()
        val settings = container.settingsRepository.settings.value
        if (destinationCountryTrackingRuntimeEnabled(settings)) {
            trafficMapCountryTrackingJob =
                container.trafficMapRepository.startDestinationCountryTracking(
                    scope = scope,
                    runtimeAvailable = flowOf(true),
                )
        }
        if (dnsRuntimeStatsRuntimeEnabled(settings) || networkActivityStatsRuntimeEnabled(settings)) {
            dnsRuntimeStatsJob =
                dnsRuntimeStatsTracker.start(
                    scope = scope,
                    enabled = {
                        dnsRuntimeStatsRuntimeEnabled(container.settingsRepository.settings.value) ||
                            networkActivityStatsRuntimeEnabled(container.settingsRepository.settings.value)
                    },
                    networkActivityEnabled = {
                        networkActivityStatsRuntimeEnabled(container.settingsRepository.settings.value)
                    },
                    networkActivityContext = {
                        RuntimeNetworkActivityContext(
                            profileId = activeSession?.profileId,
                            sessionId = activeSession?.correlationId,
                        )
                    },
                    countryCodeForDestination = runtimeCountryResolver::countryCodeForDestination,
                    onNetworkActivityEvents = { events ->
                        scope.launch(Dispatchers.IO) {
                            container.anomalyRepository.recordNetworkActivityEvents(events)
                        }
                    },
                )
        }
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.sample(resetRateBaseline = true))
        immediateTrafficSampleJob =
            scope.launch(Dispatchers.Default) {
                FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.collect {
                    if (activeSession != null || activeLocalGuardMode != null) {
                        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.sample(resetRateBaseline = true))
                    }
                }
            }
        trafficJob =
            scope.launch(Dispatchers.Default) {
                while (currentCoroutineContext().isActive) {
                    delay(
                        RuntimeUpdatePolicy.trafficUpdateIntervalMs(
                            highFrequencyUiActive = FoxholeVpnRuntimeBridge.highFrequencyTrafficUpdates.value,
                        ),
                    )
                    val sample = trafficSampler.sample()
                    FoxholeVpnRuntimeBridge.updateTraffic(sample)
                    recordAnomalyTrafficWindow(sample)
                }
            }
    }

    internal fun stopTrafficUpdates() {
        immediateTrafficSampleJob?.cancel()
        immediateTrafficSampleJob = null
        trafficJob?.cancel()
        trafficJob = null
        trafficMapCountryTrackingJob?.cancel()
        trafficMapCountryTrackingJob = null
        dnsRuntimeStatsJob?.cancel()
        dnsRuntimeStatsJob = null
        container.trafficMapRepository.clearDestinationCountryBytes()
        anomalyTrafficAggregator.reset()
        DnsRuntimeStats.reset()
    }

    internal fun startAppTrafficStatsUpdates() {
        stopAppTrafficStatsUpdates()
        val settings = container.settingsRepository.settings.value
        if (!appTrafficStatsRuntimeEnabled(settings)) {
            return
        }
        appTrafficStatsJob =
            scope.launch(Dispatchers.Default) {
                while (currentCoroutineContext().isActive) {
                    val currentSettings = container.settingsRepository.settings.value
                    if (!appTrafficStatsRuntimeEnabled(currentSettings)) {
                        break
                    }
                    runCatching { appTrafficStatsRecorder.recordSnapshot() }
                        .onFailure { container.diagnosticsLogger.record("traffic", "app traffic stats sample failed") }
                    delay(APP_TRAFFIC_SAMPLE_INTERVAL_MS)
                }
            }
    }

    internal fun stopAppTrafficStatsUpdates() {
        appTrafficStatsJob?.cancel()
        appTrafficStatsJob = null
    }

    internal fun recordAnomalyTrafficWindow(sample: TrafficSnapshot) {
        val connection = FoxholeVpnRuntimeBridge.snapshot.value
        val settings = container.settingsRepository.settings.value
        val dnsDelta = DnsRuntimeStats.snapshot()
        val aggregationContext =
            TrafficAggregationContext(
                connection = connection,
                settings = settings,
                networkType = anomalyNetworkTypeProvider.current(),
                destinationCountries = container.trafficMapRepository.currentDestinationCountryBytes(),
                blockedDns = dnsDelta.blocked,
                allowedDns = dnsDelta.allowed,
                blockedDnsDomains = dnsDelta.blockedDomains,
            )
        val window =
            anomalyTrafficAggregator.aggregate(
                snapshot = sample,
                context = aggregationContext,
            ) ?: return
        DnsRuntimeStats.drain()
        scope.launch(Dispatchers.IO) {
            val appWindows =
                if (appTrafficStatsRuntimeEnabled(settings)) {
                    runCatching {
                        appTrafficStatsRecorder.sampleWindows(maxCacheAgeMs = APP_TRAFFIC_SAMPLE_CACHE_MAX_AGE_MS)
                    }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            runCatching {
                container.anomalyRepository.recordTrafficWindow(window, appWindows)
            }.onFailure {
                container.diagnosticsLogger.record("anomaly", "traffic window analysis failed")
            }
        }
    }

    @Suppress("UnusedParameter")
    internal fun startGeoRefresh(initialNetwork: Network? = null) {
        stopGeoRefresh()
        geoRefreshJob =
            scope.launch(Dispatchers.IO) {
                if (GEO_REFRESH_INITIAL_DELAY_MS > 0) {
                    delay(GEO_REFRESH_INITIAL_DELAY_MS)
                }
                repeat(GEO_REFRESH_ATTEMPTS) { attempt ->
                    val requestNetwork = boundNetworkForAppOwnedRequest(currentUpstreamNetworkOrNull())
                    val success =
                        runCatchingUnlessCancelled {
                            refreshAppOwnedIpInfo(
                                callTimeoutMs = GEO_REFRESH_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                            )
                        }
                            .onSuccess {
                                val allowNewDeviceAddress = shouldAcceptNewDeviceIpInfoFromAppOwnedRefresh()
                                val deviceInfoAccepted =
                                    FoxholeVpnRuntimeBridge.updateDeviceIpInfo(
                                        value = it,
                                        allowNewAddress = allowNewDeviceAddress,
                                    )
                                if (shouldPublishAppOwnedIpInfo()) {
                                    FoxholeVpnRuntimeBridge.updateIpInfo(it)
                                }
                                container.diagnosticsLogger.record("ip", "geo refreshed")
                                launch(Dispatchers.Main.immediate) { updateNotification() }
                                if (deviceInfoAccepted) {
                                    startIpv4EnrichmentIfNeeded(
                                        info = it,
                                        network = requestNetwork,
                                        allowNewDeviceAddress = allowNewDeviceAddress,
                                    )
                                } else {
                                    container.diagnosticsLogger.record("ip", "device geo refresh ignored during active tunnel")
                                }
                            }
                            .onFailure { error ->
                                container.diagnosticsLogger.record("ip", "geo refresh failed: ${error.message.orEmpty()}")
                            }.isSuccess
                    if (success) {
                        return@launch
                    }
                    if (attempt < GEO_REFRESH_ATTEMPTS - 1) {
                        delay(GEO_REFRESH_RETRY_DELAY_MS)
                    }
                }
            }
    }

    internal fun stopGeoRefresh() {
        geoRefreshJob?.cancel()
        geoRefreshJob = null
        ipv4EnrichmentJob?.cancel()
        ipv4EnrichmentJob = null
    }

    internal fun startIpv4EnrichmentIfNeeded(
        info: IpInfo,
        network: Network? = null,
        allowNewDeviceAddress: Boolean = true,
    ) {
        if (info.ipv4 != null) {
            ipv4EnrichmentJob?.cancel()
            ipv4EnrichmentJob = null
            return
        }
        ipv4EnrichmentJob?.cancel()
        ipv4EnrichmentJob =
            scope.launch(Dispatchers.IO) {
                val ipv4Info =
                    runCatching {
                        refreshAppOwnedIpv4Info(
                            callTimeoutMs = IPV4_ENRICHMENT_CALL_TIMEOUT_MS,
                            network = network,
                        )
                    }.getOrNull() ?: return@launch
                val merged =
                    mergeIpInfo(
                        primary = FoxholeVpnRuntimeBridge.deviceIpInfo.value ?: info,
                        ipv4 = ipv4Info,
                        ipv6 = null,
                    )
                val deviceInfoAccepted =
                    FoxholeVpnRuntimeBridge.updateDeviceIpInfo(
                        value = merged,
                        allowNewAddress = allowNewDeviceAddress,
                    )
                if (shouldPublishAppOwnedIpInfo()) {
                    FoxholeVpnRuntimeBridge.updateIpInfo(merged)
                }
                container.diagnosticsLogger.record(
                    "ip",
                    if (deviceInfoAccepted) "ipv4 enriched" else "ipv4 enrichment ignored during active tunnel",
                )
                launch(Dispatchers.Main.immediate) { updateNotification() }
            }
    }

    private suspend fun refreshAppOwnedIpInfo(
        callTimeoutMs: Long,
        network: Network?,
    ): IpInfo {
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        val appOwnedNetwork = boundNetworkForAppOwnedRequest(network)
        return container.ipInfoRepository
            .fetch(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = appOwnedNetwork,
                resolverNetwork = appOwnedNetwork,
                mode = IpInfoFetchMode.ENTRY_QUICK,
            ).withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(appOwnedNetwork),
                remoteDnsServers = emptyList(),
            )
    }

    private suspend fun refreshAppOwnedIpv4Info(
        callTimeoutMs: Long,
        network: Network?,
    ): IpInfo? {
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        val appOwnedNetwork = boundNetworkForAppOwnedRequest(network)
        return container.ipInfoRepository
            .fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = appOwnedNetwork,
                resolverNetwork = appOwnedNetwork,
            )?.withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(appOwnedNetwork),
                remoteDnsServers = emptyList(),
            )
    }

    internal suspend fun refreshVpnIpInfo(
        callTimeoutMs: Long,
        network: Network? = null,
        expectedFreshVpnNetworkHandle: Long? = null,
    ): IpInfo = refreshVpnIpInfoInternal(callTimeoutMs, network, expectedFreshVpnNetworkHandle)

    internal suspend fun refreshConnectionIpInfo(
        callTimeoutMs: Long,
        network: Network? = null,
    ): IpInfo = refreshConnectionIpInfoInternal(callTimeoutMs, network)

    internal suspend fun refreshConnectionIpv4Info(
        callTimeoutMs: Long,
        network: Network? = null,
    ): IpInfo? = refreshConnectionIpv4InfoInternal(callTimeoutMs, network)

    internal suspend fun refreshProxyIpInfo(callTimeoutMs: Long): IpInfo = refreshProxyIpInfoInternal(callTimeoutMs)

    internal suspend fun refreshTunnelRuntimeProxyIpInfo(callTimeoutMs: Long): IpInfo =
        refreshTunnelRuntimeProxyIpInfoInternal(callTimeoutMs)

    internal suspend fun refreshVpnIpv4Info(
        callTimeoutMs: Long,
        network: Network? = null,
    ): IpInfo? = refreshVpnIpv4InfoInternal(callTimeoutMs, network)

    internal suspend fun refreshProxyIpv4Info(callTimeoutMs: Long): IpInfo? = refreshProxyIpv4InfoInternal(callTimeoutMs)

    internal suspend fun refreshTunnelRuntimeProxyIpv4Info(callTimeoutMs: Long): IpInfo? =
        refreshTunnelRuntimeProxyIpv4InfoInternal(callTimeoutMs)

    internal fun scheduleValidation(
        session: VpnSession,
        failOnFailure: Boolean,
        expectedFreshVpnNetworkHandle: Long? = null,
        onSuccess: (Network) -> Unit,
    ) = scheduleValidationInternal(session, failOnFailure, expectedFreshVpnNetworkHandle, onSuccess)

    internal fun beginValidationEpoch(reason: String): Long {
        validationJob?.cancel()
        validationJob = null
        validationEpoch += 1L
        container.diagnosticsLogger.record("dns", "validation epoch started reason=$reason epoch=$validationEpoch")
        return validationEpoch
    }

    internal fun invalidateValidationEpoch(reason: String) {
        validationJob?.cancel()
        validationJob = null
        validationEpoch += 1L
        container.diagnosticsLogger.record("dns", "validation epoch invalidated reason=$reason epoch=$validationEpoch")
    }

    internal fun isCurrentValidationEpoch(epoch: Long): Boolean = validationEpoch == epoch

    internal suspend fun validateTunnelConnectivity(
        expectedFreshVpnNetworkHandle: Long? = null,
        session: VpnSession? = null,
    ): Result<Network> =
        validateTunnelConnectivityInternal(expectedFreshVpnNetworkHandle, session)

    internal fun inspectValidatedTunnelEvidence(validationStartedAt: Long): TunnelValidationEvidence? =
        inspectValidatedTunnelEvidenceInternal(validationStartedAt)

    internal suspend fun retryValidatedTunnelConnectivityWithGrace(
        vpnNetwork: Network,
        policy: TunnelValidationGracePolicy,
        preferIpv4: Boolean = false,
        session: VpnSession? = null,
    ): Result<Unit> = retryValidatedTunnelConnectivityWithGraceInternal(vpnNetwork, policy, preferIpv4, session)

    internal suspend fun probeDnsIndependentConnectivityFallback(
        callTimeoutMs: Long,
        network: Network? = null,
    ) = probeDnsIndependentConnectivityFallbackInternal(callTimeoutMs, network)

    internal suspend fun refreshValidatedTunnelIpInfoBestEffort(
        vpnNetwork: Network,
        session: VpnSession? = null,
    ) = refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork, session)

    internal suspend fun probeConnectivityEndpoints(
        callTimeoutMs: Long = CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
        network: Network? = null,
        resolverNetwork: Network? = null,
        preferIpv4: Boolean = false,
    ) = probeConnectivityEndpointsInternal(callTimeoutMs, network, resolverNetwork, preferIpv4)

    internal suspend fun connectivityProbeEndpoints(): List<String> = connectivityProbeEndpointsInternal()

    internal suspend fun runNotificationConnectivityProbe(): Boolean =
        runNotificationConnectivityProbeInternal()

    internal suspend fun probeConnectivityEndpointsOverLocalProxy(
        proxy: com.foxhole.beta.core.network.HttpProxyAccess,
        callTimeoutMs: Long,
    ) = probeConnectivityEndpointsOverLocalProxyInternal(proxy, callTimeoutMs)

    internal fun probeSessionTarget(
        target: VpnHealthProbeTarget,
        network: Network? = null,
        timeoutMs: Long = NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
    ) = probeSessionTargetInternal(target, network, timeoutMs)

    internal fun resolveProbeAddress(
        host: String,
        network: Network? = null,
    ): InetAddress = resolveProbeAddressInternal(host, network)

    internal fun refreshDefaultNetworkAvailability() = refreshDefaultNetworkAvailabilityInternal()

    internal fun onDefaultNetworkCapabilitiesChanged(
        capabilities: NetworkCapabilities?,
        reason: String,
    ) = onDefaultNetworkCapabilitiesChangedInternal(capabilities, reason)

    internal fun markNotificationConnectivityOffline() = markNotificationConnectivityOfflineInternal()

    internal fun updateNotificationConnectivityHealth(
        state: ConnectivityHealthState,
        resetFailures: Boolean = false,
        force: Boolean = false,
    ) = updateNotificationConnectivityHealthInternal(state, resetFailures, force)

    internal fun isUpstreamNetwork(network: Network): Boolean = isUpstreamNetworkInternal(network)

    internal fun recordDefaultNetworkCapabilities(
        reason: String,
        capabilities: NetworkCapabilities?,
    ) = recordDefaultNetworkCapabilitiesInternal(reason, capabilities)

    internal fun recordNetworkEvent(
        message: String,
        capabilities: NetworkCapabilities?,
    ) = recordNetworkEventInternal(message, capabilities)

    internal fun describeNetworkCapabilities(capabilities: NetworkCapabilities?): String =
        describeNetworkCapabilitiesInternal(capabilities)

    internal fun currentVpnNetwork(excludedHandle: Long? = null): Network = currentVpnNetworkInternal(excludedHandle)

    internal fun currentVpnNetworkOrNull(excludedHandle: Long? = null): Network? =
        currentVpnNetworkOrNullInternal(excludedHandle)

    internal fun currentUpstreamNetworkOrNull(excludedHandle: Long? = null): Network? =
        currentUpstreamNetworkOrNullInternal(excludedHandle)

    internal fun shouldPublishAppOwnedIpInfo(): Boolean {
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        return shouldPublishAppOwnedIpInfoForSnapshot(
            snapshot = snapshot,
            analysisStatus = getString(R.string.notification_status_analysis),
        )
    }

    internal fun shouldAcceptNewDeviceIpInfoFromAppOwnedRefresh(): Boolean = shouldPublishAppOwnedIpInfo()

    internal fun isVpnNetworkValidated(network: Network): Boolean = isVpnNetworkValidatedInternal(network)

    internal suspend fun awaitVpnNetworkOrNull(
        timeoutMs: Long,
        excludedHandle: Long? = null,
    ): Network? = awaitVpnNetworkOrNullInternal(timeoutMs, excludedHandle)

    internal fun onConnectionStarted(
        session: VpnSession,
        trafficMode: TrafficMode,
    ) = onConnectionStartedInternal(session, trafficMode)

    internal fun onTunnelValidated(
        session: VpnSession,
        vpnNetwork: Network,
    ) = onTunnelValidatedInternal(session, vpnNetwork)

    internal fun currentNotificationSnapshot(): NotificationSnapshot = currentNotificationSnapshotInternal()

    internal fun notificationCollapsedText(snapshot: NotificationSnapshot): String =
        notificationCollapsedTextInternal(snapshot)

    internal fun notificationExpandedText(snapshot: NotificationSnapshot): String? =
        notificationExpandedTextInternal(snapshot)

    internal fun notificationHealthText(snapshot: NotificationSnapshot): String? =
        notificationHealthTextInternal(snapshot)

    internal suspend fun persistProfileTraffic(
        session: VpnSession,
        traffic: TrafficSnapshot,
    ) = persistProfileTrafficInternal(session, traffic)

    internal fun notificationStateLabel(snapshot: NotificationSnapshot): String = notificationStateLabelInternal(snapshot)

    companion object {
        internal const val GEO_REFRESH_ATTEMPTS = 6
        internal const val GEO_REFRESH_INITIAL_DELAY_MS = 0L
        internal const val GEO_REFRESH_RETRY_DELAY_MS = 2_000L
        internal const val GEO_REFRESH_CALL_TIMEOUT_MS = 5_000L
        internal const val VPN_NETWORK_WAIT_TIMEOUT_MS = 3_000L
        internal const val VPN_NETWORK_LOST_SETTLE_MS = 350L
        private const val LOCAL_GUARD_VPN_NETWORK_WAIT_TIMEOUT_MS = 5_000L
        private const val LOCAL_GUARD_NETWORK_VALIDATION_TIMEOUT_MS = 12_000L
        private const val LOCAL_GUARD_NETWORK_FALLBACK_SETTLE_MS = 1_500L
        internal const val CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS = 3_000L
        internal const val VPN_NETWORK_WAIT_POLL_DELAY_MS = 100L
        internal const val IPV4_ENRICHMENT_CALL_TIMEOUT_MS = 4_000L
        internal val CONNECTIVITY_PROBE_ENDPOINTS =
            listOf(
                "https://www.google.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
            )
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
        internal const val NOTIFICATION_HEALTH_FAILURE_THRESHOLD = 5
        internal val UDP_HEALTH_PROBE_PAYLOAD = byteArrayOf(0x66)
        internal const val CONNECTIVITY_PROBE_ATTEMPTS = 4
        internal const val CONNECTIVITY_PROBE_INITIAL_DELAY_MS = 150L
        internal const val CONNECTIVITY_PROBE_RETRY_DELAY_MS = 500L
        internal const val CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 2_500L
        internal const val CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 24_000L
        internal const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = com.foxhole.beta.vpn.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
        internal const val CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS = 900L
        internal const val CONNECTIVITY_LITERAL_PROBE_POLL_MS = 100L
        internal const val CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS = 1_200L
        internal const val LOCAL_GUARD_PROFILE_ID = -10L
        internal const val TOR_ONLY_PROFILE_ID = -20L
        internal const val APP_TRAFFIC_SAMPLE_INTERVAL_MS = 60_000L
        internal const val APP_TRAFFIC_SAMPLE_CACHE_MAX_AGE_MS = APP_TRAFFIC_SAMPLE_INTERVAL_MS + 15_000L
        private const val ACTION_NATIVE_RUNTIME_STOP = "libbox_service_stop"
    }
}

internal fun shouldAttemptRuntimeReloadRestore(
    previousSession: VpnSession?,
    failedSession: VpnSession,
): Boolean =
    previousSession != null &&
        previousSession.profileId == failedSession.profileId &&
        previousSession.configJson != failedSession.configJson

private fun FoxholeVpnService.isSameLocalGuardRuntimeActive(mode: LocalGuardMode): Boolean {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    return activeLocalGuardMode == mode &&
        snapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID &&
        snapshot.state == ConnectionState.CONNECTED
}

private suspend fun FoxholeVpnService.isLocalGuardRuntimeCurrent(): Boolean =
    container.connectionController.appliedRuntimeSignature.value ==
        container.connectionController.currentRuntimeFingerprint()

private fun FoxholeVpnService.notificationSmallIconRes(snapshot: NotificationSnapshot): Int =
    when {
        activeLocalGuardMode != null -> R.drawable.ic_notification_firewall
        snapshot.state in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING) &&
            notificationTorRouteActive() -> R.drawable.ic_notification_tor
        else -> R.drawable.ic_notification_vpn
    }

private fun FoxholeVpnService.notificationTorRouteActive(): Boolean {
    val settings = container.settingsRepository.settings.value
    return activeSession?.profileId == FoxholeVpnService.TOR_ONLY_PROFILE_ID ||
        settings.privacyRoute.mode == PrivacyRouteMode.TOR_OVER_VPN &&
        settings.traffic.mode == TrafficMode.TUNNEL &&
        (settings.privacyRoute.bypassVpnTunnel || activeSession?.protocolHint?.isUdpTransport() != true)
}

private fun FoxholeVpnService.appTrafficStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled &&
        settings.statistics.appTrafficEnabled &&
        settings.appTrafficStatsEnabled &&
        appTrafficStatsRecorder.hasUsageAccess()

private fun destinationCountryTrackingRuntimeEnabled(settings: Settings): Boolean =
    settings.ui.trafficMapEnabled ||
        settings.statistics.enabled &&
        (
            settings.statistics.countryTrafficEnabled ||
                (settings.statistics.anomalyMetricsEnabled && settings.anomaly.analyzeDestinationCountries)
            )

private fun dnsRuntimeStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.statistics.enabled && settings.statistics.dnsFilteringEnabled

private fun networkActivityStatsRuntimeEnabled(settings: Settings): Boolean =
    settings.expert.networkActivityLogging

internal interface VpnCoreRuntime {
    suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun stop(policy: RuntimeStopPolicy = RuntimeStopPolicy()): RuntimeStopResult

    suspend fun forceKill(reason: String): RuntimeKillResult =
        RuntimeKillResult(
            reason = reason,
            tunClosed = true,
            serverDetached = false,
        )

    fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot.NONE

    fun currentDnsServerAddress(): String? = null

    fun onDefaultNetworkAvailable() {
    }

    fun onDefaultNetworkLost() {
    }
}

internal class PlaceholderVpnRuntime(
    internal val diagnosticsLogger: com.foxhole.beta.core.diagnostics.DiagnosticsLogger,
) : VpnCoreRuntime {
    override suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit> {
        diagnosticsLogger.record("runtime", "libbox bridge missing")
        return Result.failure(IllegalStateException(host.runtimeContext.getString(R.string.error_runtime_missing)))
    }

    override suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit> {
        diagnosticsLogger.record("runtime", "reload requested without libbox bridge")
        return Result.failure(IllegalStateException(host.runtimeContext.getString(R.string.error_runtime_missing)))
    }

    override suspend fun stop(policy: RuntimeStopPolicy): RuntimeStopResult {
        diagnosticsLogger.record("runtime", "stop requested")
        return RuntimeStopResult(
            closeServiceOk = true,
            closeServerOk = true,
            tunClosed = true,
            escalatedToKill = false,
            elapsedMs = 0L,
        )
    }
}
