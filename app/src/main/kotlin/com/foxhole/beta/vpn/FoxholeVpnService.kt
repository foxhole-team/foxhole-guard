package com.foxhole.beta.vpn

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.quicksettings.TileService
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.MainActivity
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.mergeIpInfo
import com.foxhole.beta.core.settings.networkMemory
import com.foxhole.beta.core.settings.preferredLastKnownGoodOptionId
import com.foxhole.beta.core.settings.smartProfilePreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    internal val trafficSampler = TrafficStatsSampler()
    internal var activeSession: VpnSession? = null
    internal var trafficJob: Job? = null
    internal var immediateTrafficSampleJob: Job? = null
    internal var geoRefreshJob: Job? = null
    internal var ipv4EnrichmentJob: Job? = null
    internal var validationJob: Job? = null
    internal var notificationHealthJob: Job? = null
    internal var networkCallbackRegistered = false
    internal var defaultNetworkCallbackRegistered = false
    internal var notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
    internal var consecutiveNotificationHealthFailures = 0
    internal var defaultNetworkAvailable = true
    internal var lastDefaultNetworkSummary: String? = null
    internal val commandMutex = Mutex()

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
                if (!isUpstreamNetwork(network)) {
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
        ensureNotificationChannel()
        startForeground(
            FoxholeConnectionServiceContract.NOTIFICATION_ID,
            buildNotification(currentNotificationSnapshot()),
        )
        when (intent?.action) {
            FoxholeConnectionServiceContract.ACTION_CONNECT -> {
                val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
                val protocolOptionId = intent.getStringExtra(FoxholeConnectionServiceContract.EXTRA_PROTOCOL_OPTION_ID)
                val previousVpnNetworkHandle = intent.previousVpnNetworkHandleOrNull()
                launchCommand { connect(profileId, startId, protocolOptionId, previousVpnNetworkHandle) }
            }

            FoxholeConnectionServiceContract.ACTION_DISCONNECT -> {
                launchCommand { disconnect(commandStartId = startId) }
            }

            FoxholeConnectionServiceContract.ACTION_RELOAD -> {
                val profileId = intent.getLongExtra(FoxholeConnectionServiceContract.EXTRA_PROFILE_ID, -1L)
                launchCommand { reload(profileId) }
            }

            FoxholeConnectionServiceContract.ACTION_RESTORE -> {
                launchCommand {
                    val active = container.profileRepository.getActiveProfile()
                    if (active != null) {
                        val smartProfilePreference = container.settingsRepository.current().smartProfilePreference(active.id)
                        val networkFingerprint = container.networkFingerprintProvider.currentFingerprint()
                        val scopedLastKnownGoodOptionId = smartProfilePreference?.networkMemory(networkFingerprint?.key)?.lastKnownGoodOptionId
                        val restoredOptionId =
                            smartProfilePreference
                                ?.preferredLastKnownGoodOptionId(networkFingerprint?.key)
                                ?.takeIf { optionId ->
                                    optionId !in smartProfilePreference.excludedProtocolOptionIds &&
                                        active.protocolOptions.any { option -> option.id == optionId }
                                }
                        restoredOptionId?.let { optionId ->
                            val details =
                                buildList {
                                    add("profile_id=${active.id}")
                                    add("option=$optionId")
                                    add("reason=restored_last_good")
                                    add("scope=${if (optionId == scopedLastKnownGoodOptionId) "network" else "profile"}")
                                    networkFingerprint?.key?.take(12)?.let { fingerprint -> add("network_fp=$fingerprint") }
                                }
                            container.diagnosticsLogger.recordStructured(
                                "auto-connect",
                                "restore candidate",
                                *details.toTypedArray(),
                            )
                        }
                        connect(active.id, startId, restoredOptionId)
                    } else {
                        disconnect(commandStartId = startId)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTrafficUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        validationJob?.cancel()
        validationJob = null
        runCatching { kotlinx.coroutines.runBlocking { runtime.stop() } }
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
        launchCommand {
            disconnect(message = getString(R.string.vpn_permission_revoked))
        }
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override val runtimeContext: Context
        get() = this

    override fun stopRuntimeService() {
        stopSelf()
    }

    override fun protectSocket(socket: Int): Boolean = protect(socket)

    override fun hasVpnPermission(): Boolean = VpnService.prepare(this) == null

    override fun createTunBuilder(): Builder = Builder()

    internal suspend fun connect(
        profileId: Long,
        commandStartId: Int,
        protocolOptionIdOverride: String? = null,
        previousVpnNetworkHandle: Long? = null,
    ) {
        if (profileId <= 0) {
            disconnect(message = getString(R.string.error_profile_missing), commandStartId = commandStartId)
            return
        }
        val settings = container.settingsRepository.current()
        val trafficMode = settings.traffic.mode
        if (trafficMode != TrafficMode.TUNNEL) {
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
            return
        }
        if (trafficMode == TrafficMode.TUNNEL) {
            val privateDnsMode = PrivateDnsSettings.current(this)
            if (privateDnsMode == PrivateDnsMode.STRICT) {
                fail(getString(R.string.error_private_dns_strict_unsupported))
                return
            }
        }
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = trafficMode)
        val session =
            runCatching { container.profileRepository.getSession(profileId, protocolOptionIdOverride) }
                .getOrElse {
                    fail(it.message ?: getString(R.string.error_profile_invalid), commandStartId)
                    return
                }
        activeSession = session
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session started",
            "mode=${trafficMode.name.lowercase()}",
        )
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = trafficMode,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                message = FoxholeVpnRuntimeBridge.snapshot.value.message,
            ),
        )
        updateNotification()
        if (trafficMode == TrafficMode.TUNNEL) {
            registerNetworkCallbackIfNeeded()
        }
        registerDefaultNetworkCallbackIfNeeded()
        startNotificationHealthMonitoring()
        val result = runtime.start(session, this)
        if (result.isSuccess) {
            container.connectionController.markCurrentRuntimeApplied()
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

    internal suspend fun disconnect(
        message: String? = null,
        commandStartId: Int? = null,
    ) {
        val session = activeSession
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
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        validationJob?.cancel()
        validationJob = null
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session ended",
            "mode=${container.settingsRepository.current().traffic.mode.name.lowercase()}",
            message?.let { "result=error" } ?: "result=stopped",
            message?.takeIf(String::isNotBlank)?.let { "reason=$it" },
        )
        runtime.stop()
        activeSession = null
        container.connectionController.clearAppliedRuntime()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.reset())
        // Keep the last IP visible until the disconnected-side refresh replaces it.
        FoxholeVpnRuntimeBridge.clearTransientState(clearIpInfo = false)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = if (message == null) ConnectionState.IDLE else ConnectionState.ERROR,
                trafficMode = container.settingsRepository.current().traffic.mode,
                message = message,
            ),
        )
        updateNotification()
        detachForegroundNotification()
        stopService(commandStartId)
    }

    internal fun fail(
        message: String,
        commandStartId: Int? = null,
    ) {
        container.diagnosticsLogger.record("connection", "runtime failure: $message")
        launchCommand { disconnect(message, commandStartId) }
    }

    internal suspend fun reload(profileIdHint: Long) {
        val targetProfileId = activeSession?.profileId ?: profileIdHint.takeIf { it > 0L } ?: return
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
            return
        }
        val session =
            runCatching { container.profileRepository.getSession(targetProfileId) }
                .getOrElse {
                    container.diagnosticsLogger.record("connection", "runtime reload session failed: ${it.message.orEmpty()}")
                    return
                }
        val result = runtime.reload(session, this)
        if (result.isSuccess) {
            activeSession = session
            container.connectionController.markCurrentRuntimeApplied()
            container.diagnosticsLogger.record("connection", "runtime reloaded")
            FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
            updateNotification()
        } else {
            val error = result.exceptionOrNull()
            container.diagnosticsLogger.record(
                "connection",
                "runtime reload failed: ${error?.let(::describeVpnRuntimeFailure) ?: "unknown"}",
            )
        }
    }

    internal fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        setAllowBubbles(false)
                    }
                },
            )
        }
    }

    internal fun launchCommand(block: suspend () -> Unit) {
        scope.launch {
            commandMutex.withLock {
                block()
            }
        }
    }

    internal fun stopService(commandStartId: Int?) {
        if (commandStartId != null && commandStartId > 0) {
            stopSelfResult(commandStartId)
        } else {
            stopSelf()
        }
    }

    internal fun buildNotification(snapshot: NotificationSnapshot): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val action = notificationActionForState(snapshot.state)
        val actionIntent =
            PendingIntent.getService(
                this,
                action.requestCode,
                FoxholeConnectionServiceContract.serviceIntent(this, TrafficMode.TUNNEL, action.serviceAction),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, FoxholeConnectionServiceContract.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.notification_icon)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setContentTitle(notificationStateLabel(snapshot))
                .setOngoing(action.ongoing)
                .setContentIntent(openIntent)
                .addAction(0, getString(action.labelRes), actionIntent)
        notificationCollapsedText(snapshot).takeIf { it.isNotBlank() }?.let(builder::setContentText)
        notificationExpandedText(snapshot)?.let { expanded ->
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        }
        if (!snapshot.isRedacted) {
            builder.setPublicVersion(
                buildNotification(
                    snapshot.copy(
                        profileName = null,
                        ipAddress = null,
                        countryCode = null,
                        countryName = null,
                        txRate = 0L,
                        rxRate = 0L,
                        txTotal = 0L,
                        rxTotal = 0L,
                        updatedAt = 0L,
                        isRedacted = true,
                    ),
                ),
            )
        }
        return builder.build()
    }

    internal fun updateNotification() {
        TileService.requestListeningState(this, ComponentName(this, FoxholeTileService::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            NotificationManagerCompat.from(this).notify(
                FoxholeConnectionServiceContract.NOTIFICATION_ID,
                buildNotification(currentNotificationSnapshot()),
            )
        }
    }

    internal fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
        val registration =
            runCatching {
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                        connectivityManager.registerBestMatchingNetworkCallback(trackedNetworkRequest, networkCallback, mainHandler)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
                        connectivityManager.requestNetwork(trackedNetworkRequest, networkCallback, mainHandler)
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                        connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
                    else -> connectivityManager.registerDefaultNetworkCallback(networkCallback)
                }
            }.recoverCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler)
                } else {
                    connectivityManager.registerDefaultNetworkCallback(networkCallback)
                }
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback, mainHandler)
                } else {
                    connectivityManager.registerDefaultNetworkCallback(defaultNetworkCallback)
                }
            }
        registration
            .onSuccess { defaultNetworkCallbackRegistered = true }
            .onFailure { container.diagnosticsLogger.record("connection", "default network callback registration failed") }
    }

    internal fun startTrafficUpdates() {
        stopTrafficUpdates()
        FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.sample(resetRateBaseline = true))
        immediateTrafficSampleJob =
            scope.launch(Dispatchers.Default) {
                FoxholeVpnRuntimeBridge.immediateTrafficSampleRequests.collect {
                    if (activeSession != null) {
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
                    FoxholeVpnRuntimeBridge.updateTraffic(trafficSampler.sample())
                }
            }
    }

    internal fun stopTrafficUpdates() {
        immediateTrafficSampleJob?.cancel()
        immediateTrafficSampleJob = null
        trafficJob?.cancel()
        trafficJob = null
    }

    internal fun startGeoRefresh(initialNetwork: Network? = null) {
        stopGeoRefresh()
        geoRefreshJob =
            scope.launch(Dispatchers.IO) {
                if (GEO_REFRESH_INITIAL_DELAY_MS > 0) {
                    delay(GEO_REFRESH_INITIAL_DELAY_MS)
                }
                repeat(GEO_REFRESH_ATTEMPTS) { attempt ->
                    val success =
                        runCatching {
                            refreshConnectionIpInfo(
                                callTimeoutMs = GEO_REFRESH_CALL_TIMEOUT_MS,
                                network = if (attempt == 0) initialNetwork else null,
                            )
                        }
                            .onSuccess {
                                FoxholeVpnRuntimeBridge.updateIpInfo(it)
                                container.diagnosticsLogger.record("ip", "geo refreshed")
                                launch(Dispatchers.Main.immediate) { updateNotification() }
                                startIpv4EnrichmentIfNeeded(
                                    info = it,
                                    network = if (attempt == 0) initialNetwork else null,
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
                        refreshConnectionIpv4Info(
                            callTimeoutMs = IPV4_ENRICHMENT_CALL_TIMEOUT_MS,
                            network = network,
                        )
                    }.getOrNull() ?: return@launch
                val merged = mergeIpInfo(primary = FoxholeVpnRuntimeBridge.ipInfo.value ?: info, ipv4 = ipv4Info, ipv6 = null)
                FoxholeVpnRuntimeBridge.updateIpInfo(merged)
                container.diagnosticsLogger.record("ip", "ipv4 enriched")
                launch(Dispatchers.Main.immediate) { updateNotification() }
            }
    }

    internal fun startNotificationHealthMonitoring() {
        stopNotificationHealthMonitoring()
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.CHECKING,
            resetFailures = true,
            force = true,
        )
        notificationHealthJob =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val session = activeSession
                    val connectionState = FoxholeVpnRuntimeBridge.snapshot.value.state
                    if (session == null || connectionState !in NOTIFICATION_HEALTH_PROBE_STATES) {
                        updateNotificationConnectivityHealth(
                            state = ConnectivityHealthState.CHECKING,
                            resetFailures = true,
                        )
                        delay(RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(notificationConnectivityHealthState))
                        continue
                    }
                    if (!defaultNetworkAvailable) {
                        markNotificationConnectivityOffline()
                        delay(RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(notificationConnectivityHealthState))
                        continue
                    }
                    val probeSucceeded = runNotificationConnectivityProbe(session)
                    if (probeSucceeded) {
                        updateNotificationConnectivityHealth(
                            state = ConnectivityHealthState.ONLINE,
                            resetFailures = true,
                        )
                    } else {
                        consecutiveNotificationHealthFailures += 1
                        if (consecutiveNotificationHealthFailures >= NOTIFICATION_HEALTH_FAILURE_THRESHOLD) {
                            markNotificationConnectivityOffline()
                        } else if (notificationConnectivityHealthState != ConnectivityHealthState.ONLINE) {
                            updateNotificationConnectivityHealth(ConnectivityHealthState.CHECKING)
                        }
                    }
                    delay(RuntimeUpdatePolicy.notificationHealthProbeIntervalMs(notificationConnectivityHealthState))
                }
            }
    }

    internal fun stopNotificationHealthMonitoring() {
        notificationHealthJob?.cancel()
        notificationHealthJob = null
        consecutiveNotificationHealthFailures = 0
        notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
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

    internal suspend fun refreshVpnIpv4Info(
        callTimeoutMs: Long,
        network: Network? = null,
    ): IpInfo? = refreshVpnIpv4InfoInternal(callTimeoutMs, network)

    internal suspend fun refreshProxyIpv4Info(callTimeoutMs: Long): IpInfo? = refreshProxyIpv4InfoInternal(callTimeoutMs)

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
    ): Result<Unit> = retryValidatedTunnelConnectivityWithGraceInternal(vpnNetwork, policy)

    internal suspend fun probeDnsIndependentConnectivityFallback(callTimeoutMs: Long) =
        probeDnsIndependentConnectivityFallbackInternal(callTimeoutMs)

    internal suspend fun refreshValidatedTunnelIpInfoBestEffort(vpnNetwork: Network) =
        refreshValidatedTunnelIpInfoBestEffortInternal(vpnNetwork)

    internal suspend fun probeConnectivityEndpoints(
        callTimeoutMs: Long = CONNECTIVITY_PROBE_CALL_TIMEOUT_MS,
        network: Network? = null,
    ) = probeConnectivityEndpointsInternal(callTimeoutMs, network)

    internal suspend fun connectivityProbeEndpoints(): List<String> = connectivityProbeEndpointsInternal()

    internal suspend fun runNotificationConnectivityProbe(session: VpnSession): Boolean =
        runNotificationConnectivityProbeInternal(session)

    internal suspend fun probeConnectivityEndpointsOverLocalProxy(
        proxy: com.foxhole.beta.core.network.HttpProxyAccess,
        callTimeoutMs: Long,
    ) = probeConnectivityEndpointsOverLocalProxyInternal(proxy, callTimeoutMs)

    internal fun probeSessionTarget(target: VpnHealthProbeTarget) = probeSessionTargetInternal(target)

    internal fun resolveProbeAddress(host: String): InetAddress = resolveProbeAddressInternal(host)

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
        internal const val VPN_NETWORK_WAIT_TIMEOUT_MS = 5_000L
        internal const val CONNECTIVITY_PROBE_NETWORK_WAIT_TIMEOUT_MS = 5_000L
        internal const val VPN_NETWORK_WAIT_POLL_DELAY_MS = 150L
        internal const val IPV4_ENRICHMENT_CALL_TIMEOUT_MS = 4_000L
        internal val CONNECTIVITY_PROBE_ENDPOINTS =
            listOf(
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
        internal const val NOTIFICATION_HEALTH_FAILURE_THRESHOLD = 3
        internal val UDP_HEALTH_PROBE_PAYLOAD = byteArrayOf(0x66)
        internal const val CONNECTIVITY_PROBE_ATTEMPTS = 15
        internal const val CONNECTIVITY_PROBE_INITIAL_DELAY_MS = 2_000L
        internal const val CONNECTIVITY_PROBE_RETRY_DELAY_MS = 2_000L
        internal const val CONNECTIVITY_PROBE_CALL_TIMEOUT_MS = 2_500L
        internal const val CONNECTIVITY_PROBE_TOTAL_TIMEOUT_MS = 30_000L
        internal const val CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS = com.foxhole.beta.vpn.CONNECTIVITY_PROBE_GRACE_MAX_TIMEOUT_MS
    }
}

internal interface VpnCoreRuntime {
    suspend fun start(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun reload(session: VpnSession, host: RuntimeServiceHost): Result<Unit>

    suspend fun stop()

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

    override suspend fun stop() {
        diagnosticsLogger.record("runtime", "stop requested")
    }
}
