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
import androidx.core.content.getSystemService
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
import com.foxhole.beta.core.model.isUdpTransport
import com.foxhole.beta.core.network.IpInfoFetchMode
import com.foxhole.beta.core.network.mergeIpInfo
import com.foxhole.beta.core.settings.AppTrafficStatsRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

class FoxholeVpnService : VpnService(), RuntimeServiceHost {
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val connectivityManager by lazy { getSystemService<ConnectivityManager>()!! }
    internal val notificationManager by lazy { getSystemService<NotificationManager>()!! }
    internal val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    internal val trackedNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }
    internal val container: FoxholeRuntimeDependencies by lazy { (applicationContext as FoxholeApplication).appGraph }
    internal val runtime by lazy<VpnCoreRuntime> {
        createVpnRuntime(
            context = applicationContext,
            diagnosticsLogger = container.diagnosticsLogger,
            isNetworkActivityLoggingEnabled = { container.settingsRepository.settings.value.expert.networkActivityLogging },
        )
    }
    internal val commandActor by lazy {
        RuntimeCommandActor(
            scope = scope,
            diagnosticsLogger = container.diagnosticsLogger,
            emergencyKill = runtime::forceKill,
        )
    }
    internal val trafficSampler = TrafficStatsSampler()
    internal val anomalyTrafficAggregator = TrafficWindowAggregator()
    internal val anomalyNetworkTypeProvider by lazy { AndroidNetworkTypeProvider(applicationContext) }
    internal val appTrafficStatsRecorder by lazy {
        AppTrafficStatsRecorder(
            anomalyRepository = container.anomalyRepository,
            context = applicationContext,
        )
    }
    internal var activeSession: VpnSession? = null
    internal var activeLocalGuardMode: LocalGuardMode? = null
    internal var trafficJob: Job? = null
    internal var trafficMapCountryTrackingJob: Job? = null
    internal var immediateTrafficSampleJob: Job? = null
    internal var geoRefreshJob: Job? = null
    internal var ipv4EnrichmentJob: Job? = null
    internal var validationJob: Job? = null
    internal var notificationHealthJob: Job? = null
    internal var appTrafficStatsJob: Job? = null
    internal var networkCallbackRegistered = false
    internal var defaultNetworkCallbackRegistered = false
    internal var notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
    internal var consecutiveNotificationHealthFailures = 0
    internal var defaultNetworkAvailable = true
    internal var lastDefaultNetworkSummary: String? = null
    internal val upstreamNetworkHandles = mutableSetOf<Long>()

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
                recordNetworkEvent(
                    message = "upstream lost",
                    capabilities = connectivityManager.getNetworkCapabilities(network),
                )
                runtime.onDefaultNetworkLost()
                validationJob?.cancel()
                validationJob = null
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
            notificationManager = notificationManager,
            currentNotificationSnapshot = ::currentNotificationSnapshot,
            buildNotification = ::buildNotification,
            container = container,
            launchCommand = ::launchCommand,
            launchPriorityCommand = ::launchPriorityCommand,
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
        validationJob?.cancel()
        validationJob = null
        commandActor.close()
        stopRuntimeAfterServiceDestroy(
            runtime = runtime,
            diagnosticsLogger = container.diagnosticsLogger,
            owner = "vpn",
        )
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
        if (defaultNetworkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) }
            defaultNetworkCallbackRegistered = false
        }
    }

    override fun onRevoke() {
        launchPriorityCommand {
            disconnect(message = getString(R.string.vpn_permission_revoked))
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override val runtimeContext: Context
        get() = this

    override fun stopRuntimeService() {
        container.diagnosticsLogger.record("connection", "native runtime requested vpn service stop; failing closed")
        launchPriorityCommand {
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
        val result = runtime.start(session, this)
        RuntimeHealthMetrics.recordStart(
            owner = owner,
            success = result.isSuccess,
            elapsedMs = SystemClock.elapsedRealtime() - runtimeStartAtMs,
            diagnosticsLogger = container.diagnosticsLogger,
        )
        return result
    }

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

    private suspend fun validatePrivateDnsMode(
        privateDnsMode: PrivateDnsMode?,
        commandStartId: Int,
    ): Boolean {
        val supported = privateDnsMode?.isSupportedForTunnelMode() != false
        if (!supported) {
            container.diagnosticsLogger.record("dns", "unsupported android private dns mode: $privateDnsMode")
            fail(getString(R.string.error_private_dns_strict_unsupported), commandStartId)
        } else if (privateDnsMode != null) {
            container.diagnosticsLogger.record("dns", "android private dns mode: $privateDnsMode")
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
    ) {
        if (result.isSuccess) {
            container.connectionController.markCurrentRuntimeApplied()
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
        val privateDnsMode =
            if (trafficMode == TrafficMode.TUNNEL) {
                PrivateDnsSettings.current(this)
            } else {
                null
            }
        if (!validatePrivateDnsMode(privateDnsMode, commandStartId)) {
            return
        }
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = trafficMode)
        val session =
            runCatching {
                if (torOnlyConnect) {
                    container.profileRepository.getTorOnlySession(privateDnsMode)
                } else {
                    container.profileRepository.getSession(profileId, protocolOptionIdOverride, privateDnsMode)
                }
            }
                .getOrElse {
                    fail(it.message ?: getString(R.string.error_profile_invalid), commandStartId)
                    return
                }
        currentCoroutineContext().ensureActive()
        stopActiveLocalGuardBeforeTunnelConnect()
        activeSession = session
        activeLocalGuardMode = null
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session started",
            "sessionId=${session.correlationId}",
            "mode=${trafficMode.name.lowercase()}",
        )
        val tcpReadinessTarget = tcpRuntimeReadinessTarget(session)
        prepareTcpRuntimeReadiness(tcpReadinessTarget)
            .onFailure {
                fail(getString(R.string.error_tcp_runtime_readiness_failed), commandStartId)
                return
            }
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
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
        }
        registerDefaultNetworkCallbackIfNeeded()
        acquireRuntimeWakeLock()
        startNotificationHealthMonitoring()
        val result = startRuntimeWithHealthMetrics(session = session, owner = "vpn")
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                container.diagnosticsLogger.record("connection", "runtime start cancelled after native return")
                disconnect(commandStartId = commandStartId)
            }
            return
        }
        handleRuntimeStartResult(
            result = result,
            session = session,
            trafficMode = trafficMode,
            tcpReadinessTarget = tcpReadinessTarget,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            commandStartId = commandStartId,
        )
    }

    private suspend fun stopActiveLocalGuardBeforeTunnelConnect() {
        val localGuardMode = activeLocalGuardMode ?: return
        container.diagnosticsLogger.record(
            "connection",
            "local guard handoff to tunnel mode=${localGuardMode.name.lowercase()}",
        )
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        validationJob?.cancel()
        validationJob = null
        runtime.stop()
        releaseRuntimeWakeLock()
        activeLocalGuardMode = null
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
    }

    @Suppress("CyclomaticComplexMethod")
    internal suspend fun disconnect(
        message: String? = null,
        commandStartId: Int? = null,
        reasonCode: AutoConnectReasonCode? = null,
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ) {
        val session = activeSession
        val localGuardMode = activeLocalGuardMode
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
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
        validationJob?.cancel()
        validationJob = null
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
        runtime.stop()
        releaseRuntimeWakeLock()
        activeSession = null
        activeLocalGuardMode = null
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
        if (desiredMode == null || desiredMode != mode) {
            disconnect(commandStartId = commandStartId, suppressLocalGuard = true)
            return
        }
        if (!hasVpnPermission()) {
            container.diagnosticsLogger.record("connection", "local guard skipped: missing vpn permission")
            stopService(commandStartId)
            return
        }
        stopTrafficUpdates()
        stopAppTrafficStatsUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        cancelScheduledAutoReconnect(resetAttempts = true)
        validationJob?.cancel()
        validationJob = null
        if (activeLocalGuardMode != null) {
            container.diagnosticsLogger.record(
                "connection",
                "local guard restarting mode=${activeLocalGuardMode?.name?.lowercase().orEmpty()}",
            )
            runtime.stop()
            releaseRuntimeWakeLock()
            activeLocalGuardMode = null
        }
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = TrafficMode.TUNNEL)
        val session =
            VpnSession(
                profileId = LOCAL_GUARD_PROFILE_ID,
                profileName = mode.runtimeProfileName(),
                protocolHint = com.foxhole.beta.core.model.ProtocolHint.SING_BOX,
                configJson = container.runtimeConfigAssembler.assembleLocalGuard(settings, mode),
                correlationId = "local-guard-${System.currentTimeMillis()}",
            )
        val previousSnapshot = FoxholeVpnRuntimeBridge.snapshot.value
        val analysisStatus = getString(R.string.notification_status_analysis)
        val analysisMessage =
            previousSnapshot.message
                .takeIf { previousSnapshot.isSmartStartConnection && it == analysisStatus }
        activeSession = null
        activeLocalGuardMode = mode
        FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.IDLE,
                trafficMode = TrafficMode.TUNNEL,
                profileId = LOCAL_GUARD_PROFILE_ID,
                profileName = mode.runtimeProfileName(),
                protocolHint = com.foxhole.beta.core.model.ProtocolHint.SING_BOX,
                message = analysisMessage,
                isSmartStartConnection = analysisMessage != null,
            ),
        )
        acquireRuntimeWakeLock()
        val result = startRuntimeWithHealthMetrics(session = session, owner = "local_guard")
        if (result.isSuccess) {
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
            container.diagnosticsLogger.record("connection", "local guard started mode=${mode.name.lowercase()}")
            RuntimeResumeStateStore.markLocalGuardRuntime(this, mode)
            updateNotification()
        } else {
            activeLocalGuardMode = null
            releaseRuntimeWakeLock()
            val error = result.exceptionOrNull()
            fail(error?.let { describeVpnRuntimeFailure(it) } ?: getString(R.string.error_runtime_missing), commandStartId)
        }
    }

    internal fun fail(
        message: String,
        commandStartId: Int? = null,
        reasonCode: AutoConnectReasonCode? = null,
    ) {
        container.diagnosticsLogger.record("connection", "runtime failure: $message")
        launchCommand { disconnect(message, commandStartId, reasonCode) }
    }

    internal suspend fun failClosedTeardown(
        commandStartId: Int,
        action: String?,
    ) {
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
        validationJob?.cancel()
        validationJob = null
        container.diagnosticsLogger.recordStructured(
            "connection",
            "runtime command fail-closed teardown",
            action?.let { "action=$it" } ?: "action=null",
        )
        runtime.stop()
        releaseRuntimeWakeLock()
        activeSession = null
        activeLocalGuardMode = null
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
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
            return
        }
        val session =
            runCatching {
                if (targetProfileId == TOR_ONLY_PROFILE_ID) {
                    container.profileRepository.getTorOnlySession(PrivateDnsSettings.current(this))
                } else {
                    container.profileRepository.getSession(targetProfileId)
                }
            }
                .getOrElse {
                    container.diagnosticsLogger.record("connection", "runtime reload session failed: ${it.message.orEmpty()}")
                    fail(it.message ?: getString(R.string.error_profile_invalid))
                    return
                }
        val result = runtime.reload(session, this)
        if (result.isSuccess) {
            activeSession = session
            container.connectionController.markCurrentRuntimeApplied()
            container.diagnosticsLogger.record("connection", "runtime reloaded, tunnel validation required")
            FoxholeVpnRuntimeBridge.update(
                snapshot.copy(
                    state = ConnectionState.RECONNECTING,
                    profileId = session.profileId,
                    profileName = session.profileName,
                    protocolHint = session.protocolHint,
                    protocolOptionId = session.protocolOptionId,
                    message = getString(R.string.status_reconnecting),
                ),
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
            fail(message)
        }
    }

    internal fun ensureNotificationChannel() = ensureConnectionNotificationChannel(notificationManager)

    internal fun launchCommand(block: suspend () -> Unit) {
        commandActor.launch(RuntimeCommandPriority.NORMAL, reason = "service_command", block = block)
    }

    internal fun launchPriorityCommand(block: suspend () -> Unit) {
        commandActor.launch(RuntimeCommandPriority.STOP, reason = "priority_service_command", block = block)
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
        trafficMapCountryTrackingJob =
            container.trafficMapRepository.startDestinationCountryTracking(
                scope = scope,
                runtimeAvailable = flowOf(true),
            )
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
        container.trafficMapRepository.clearDestinationCountryBytes()
        anomalyTrafficAggregator.reset()
        DnsRuntimeStats.reset()
    }

    internal fun startAppTrafficStatsUpdates() {
        stopAppTrafficStatsUpdates()
        val settings = container.settingsRepository.settings.value
        if (!settings.appTrafficStatsRuntimeEnabled()) {
            return
        }
        appTrafficStatsJob =
            scope.launch(Dispatchers.Default) {
                while (currentCoroutineContext().isActive) {
                    val currentSettings = container.settingsRepository.settings.value
                    if (!currentSettings.appTrafficStatsRuntimeEnabled()) {
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
            )
        val window =
            anomalyTrafficAggregator.aggregate(
                snapshot = sample,
                context = aggregationContext,
            ) ?: return
        DnsRuntimeStats.drain()
        scope.launch(Dispatchers.IO) {
            val appWindows =
                if (settings.appTrafficStatsRuntimeEnabled()) {
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
                    val requestNetwork = currentUpstreamNetworkOrNull()
                    val success =
                        runCatching {
                            refreshAppOwnedIpInfo(
                                callTimeoutMs = GEO_REFRESH_CALL_TIMEOUT_MS,
                                network = requestNetwork,
                            )
                        }
                            .onSuccess {
                                FoxholeVpnRuntimeBridge.updateDeviceIpInfo(it)
                                if (shouldPublishAppOwnedIpInfo()) {
                                    FoxholeVpnRuntimeBridge.updateIpInfo(it)
                                }
                                container.diagnosticsLogger.record("ip", "geo refreshed")
                                launch(Dispatchers.Main.immediate) { updateNotification() }
                                startIpv4EnrichmentIfNeeded(
                                    info = it,
                                    network = requestNetwork,
                                )
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
                FoxholeVpnRuntimeBridge.updateDeviceIpInfo(merged)
                if (shouldPublishAppOwnedIpInfo()) {
                    FoxholeVpnRuntimeBridge.updateIpInfo(merged)
                }
                container.diagnosticsLogger.record("ip", "ipv4 enriched")
                launch(Dispatchers.Main.immediate) { updateNotification() }
            }
    }

    private suspend fun refreshAppOwnedIpInfo(
        callTimeoutMs: Long,
        network: Network?,
    ): IpInfo {
        val requestNetwork = network ?: error("upstream network unavailable")
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        return container.ipInfoRepository
            .fetch(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = requestNetwork,
                mode = IpInfoFetchMode.ENTRY_QUICK,
            ).withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(requestNetwork),
                remoteDnsServers = emptyList(),
            )
    }

    private suspend fun refreshAppOwnedIpv4Info(
        callTimeoutMs: Long,
        network: Network?,
    ): IpInfo? {
        val requestNetwork = network ?: return null
        val endpoint = container.settingsRepository.current().connection.ipInfoEndpoint
        return container.ipInfoRepository
            .fetchIpv4(
                endpoint = endpoint,
                callTimeoutMs = callTimeoutMs,
                network = requestNetwork,
                resolverNetwork = requestNetwork,
            )?.withDnsServers(
                localDnsServers = connectivityManager.dnsServerAddresses(requestNetwork),
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

    internal suspend fun validateTunnelConnectivity(expectedFreshVpnNetworkHandle: Long? = null): Result<Network> =
        validateTunnelConnectivityInternal(expectedFreshVpnNetworkHandle)

    internal fun inspectValidatedTunnelEvidence(validationStartedAt: Long): TunnelValidationEvidence? =
        inspectValidatedTunnelEvidenceInternal(validationStartedAt)

    internal suspend fun retryValidatedTunnelConnectivityWithGrace(
        vpnNetwork: Network,
        policy: TunnelValidationGracePolicy,
        preferIpv4: Boolean = false,
    ): Result<Unit> = retryValidatedTunnelConnectivityWithGraceInternal(vpnNetwork, policy, preferIpv4)

    internal suspend fun probeDnsIndependentConnectivityFallback(
        callTimeoutMs: Long,
        network: Network? = null,
    ) = probeDnsIndependentConnectivityFallbackInternal(callTimeoutMs, network)

    internal suspend fun refreshValidatedTunnelIpInfoBestEffort(vpnNetwork: Network) =
        refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork)

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

    internal fun currentUpstreamNetworkOrNull(): Network? =
        currentUpstreamNetworkOrNullInternal()

    internal fun shouldPublishAppOwnedIpInfo(): Boolean {
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        return snapshot.state !in ACTIVE_CONNECTION_STATES ||
            snapshot.trafficMode != TrafficMode.TUNNEL ||
            snapshot.profileId == LOCAL_GUARD_PROFILE_ID
    }

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
        internal const val CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 10_000L
        internal const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = com.foxhole.beta.vpn.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
        internal const val CONNECTIVITY_LITERAL_PROBE_EARLY_WINDOW_MS = 900L
        internal const val CONNECTIVITY_LITERAL_PROBE_POLL_MS = 100L
        internal const val CONNECTIVITY_LITERAL_PROBE_CALL_TIMEOUT_MS = 1_200L
        internal const val LOCAL_GUARD_PROFILE_ID = -10L
        internal const val TOR_ONLY_PROFILE_ID = -20L
        internal const val APP_TRAFFIC_SAMPLE_INTERVAL_MS = 60_000L
        internal const val APP_TRAFFIC_SAMPLE_CACHE_MAX_AGE_MS = 15_000L
        private const val ACTION_NATIVE_RUNTIME_STOP = "libbox_service_stop"
    }
}

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

private fun Settings.appTrafficStatsRuntimeEnabled(): Boolean =
    statistics.enabled &&
        statistics.appTrafficEnabled &&
        appTrafficStatsEnabled

internal interface VpnCoreRuntime {
    suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun stop(policy: RuntimeStopPolicy = RuntimeStopPolicy()): RuntimeStopResult

    fun forceKill(reason: String): RuntimeKillResult =
        RuntimeKillResult(
            reason = reason,
            tunClosed = true,
            serverDetached = false,
        )

    fun nativeSnapshot(): NativeRuntimeSnapshot =
        NativeRuntimeSnapshot(
            hasCommandServer = false,
            hasTunFileDescriptor = false,
            hasHost = false,
            hasConfig = false,
            dnsServerAddress = null,
        )

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
