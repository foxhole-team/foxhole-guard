package com.foxhole.beta.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.getSystemService
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.FoxholeRuntimeDependencies
import com.foxhole.beta.R
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.ConnectivityHealthState
import com.foxhole.beta.core.model.IpInfo
import com.foxhole.beta.core.model.NotificationSnapshot
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSnapshot
import com.foxhole.beta.core.model.VpnSession
import com.foxhole.beta.core.network.HttpProxyAccess
import com.foxhole.beta.core.network.mergeIpInfo
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class FoxholeProxyService : Service(), RuntimeServiceHost {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val connectivityManager by lazy { getSystemService<ConnectivityManager>()!! }
    private val notificationManager by lazy { getSystemService<NotificationManager>()!! }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val container: FoxholeRuntimeDependencies by lazy { (applicationContext as FoxholeApplication).appGraph }
    private val runtime by lazy<VpnCoreRuntime> {
        createVpnRuntime(
            context = applicationContext,
            diagnosticsLogger = container.diagnosticsLogger,
            isNetworkActivityLoggingEnabled = { container.settingsRepository.settings.value.expert.networkActivityLogging },
        )
    }
    private val trafficSampler = TrafficStatsSampler()
    private var activeSession: VpnSession? = null
    private var trafficJob: Job? = null
    private var immediateTrafficSampleJob: Job? = null
    private var geoRefreshJob: Job? = null
    private var ipv4EnrichmentJob: Job? = null
    private var notificationHealthJob: Job? = null
    private var defaultNetworkCallbackRegistered = false
    private var notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
    private var consecutiveNotificationHealthFailures = 0
    private var defaultNetworkAvailable = true
    private var lastDefaultNetworkSummary: String? = null
    private val commandMutex = Mutex()
    private var commandJob: Job? = null

    private val defaultNetworkCallback =
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

    override val runtimeContext: Context
        get() = this

    override fun stopRuntimeService() {
        stopSelf()
    }

    override fun protectSocket(socket: Int): Boolean = true

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        handleForegroundRuntimeCommand(
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
            reload = ::reload,
        )

    override fun onDestroy() {
        super.onDestroy()
        stopTrafficUpdates()
        stopGeoRefresh()
        stopNotificationHealthMonitoring()
        runCatching { kotlinx.coroutines.runBlocking { runtime.stop() } }
        scope.cancel()
        if (defaultNetworkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(defaultNetworkCallback) }
            defaultNetworkCallbackRegistered = false
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private suspend fun connect(
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
        if (trafficMode != TrafficMode.PROXY) {
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
        FoxholeConnectionServiceContract.stopInactiveServices(context = this, activeMode = trafficMode)
        val session =
            runCatching { container.profileRepository.getSession(profileId, protocolOptionIdOverride) }
                .getOrElse {
                    fail(it.message ?: getString(R.string.error_profile_invalid), commandStartId)
                    return
                }
        currentCoroutineContext().ensureActive()
        activeSession = session
        container.diagnosticsLogger.recordStructured(
            "connection",
            "session started",
            "sessionId=${session.correlationId}",
            "mode=${trafficMode.name.lowercase()}",
        )
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.PROXY,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
                message = FoxholeVpnRuntimeBridge.snapshot.value.message,
            ),
        )
        updateNotification()
        registerDefaultNetworkCallbackIfNeeded()
        startNotificationHealthMonitoring()
        val result = runtime.start(session, this)
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) {
                container.diagnosticsLogger.record("connection", "runtime start cancelled after native return")
                disconnect(commandStartId = commandStartId)
            }
            return
        }
        if (result.isSuccess) {
            container.diagnosticsLogger.record("connection", "proxy runtime started, validation required")
            val validation = validateProxyConnectivity(session)
            if (validation.isSuccess) {
                container.connectionController.markCurrentRuntimeApplied()
                onConnectionStarted(session)
            } else {
                val message = validation.exceptionOrNull()?.message ?: getString(R.string.error_dns_probe_failed)
                container.diagnosticsLogger.record("connection", "proxy validation failed: $message")
                fail(getString(R.string.error_dns_probe_failed), commandStartId)
            }
        } else {
            val error = result.exceptionOrNull()
            fail(error?.let { describeVpnRuntimeFailure(it) } ?: getString(R.string.error_runtime_missing), commandStartId)
        }
    }

    private suspend fun disconnect(
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

    private fun fail(
        message: String,
        commandStartId: Int? = null,
    ) {
        container.diagnosticsLogger.record("connection", "runtime failure: $message")
        launchCommand { disconnect(message, commandStartId) }
    }

    private suspend fun reload(profileIdHint: Long) {
        val targetProfileId = activeSession?.profileId ?: profileIdHint.takeIf { it > 0L } ?: return
        val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
        if (snapshot.state !in setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING)) {
            return
        }
        val session =
            runCatching { container.profileRepository.getSession(targetProfileId) }
                .getOrElse {
                    container.diagnosticsLogger.record("connection", "proxy reload session failed: ${it.message.orEmpty()}")
                    return
                }
        val result = runtime.reload(session, this)
        if (result.isSuccess) {
            activeSession = session
            container.connectionController.markCurrentRuntimeApplied()
            container.diagnosticsLogger.record("connection", "proxy runtime reloaded")
            FoxholeVpnRuntimeBridge.requestImmediateTrafficSample()
            updateNotification()
        } else {
            val error = result.exceptionOrNull()
            container.diagnosticsLogger.record(
                "connection",
                "proxy reload failed: ${error?.let(::describeVpnRuntimeFailure) ?: "unknown"}",
            )
        }
    }

    private fun ensureNotificationChannel() = ensureConnectionNotificationChannel(notificationManager)

    private fun launchCommand(block: suspend () -> Unit) {
        commandJob = scope.launch(Dispatchers.Default) {
            commandMutex.withLock {
                block()
            }
        }
    }

    private fun launchPriorityCommand(block: suspend () -> Unit) {
        commandJob?.cancel()
        commandJob = null
        scope.launch(Dispatchers.Default) {
            block()
        }
    }

    private fun stopService(commandStartId: Int?) {
        if (commandStartId != null && commandStartId > 0) {
            stopSelfResult(commandStartId)
        } else {
            stopSelf()
        }
    }

    private fun buildNotification(snapshot: NotificationSnapshot): Notification =
        buildConnectionNotification(
            mode = TrafficMode.PROXY,
            snapshot = snapshot,
            collapsedText = ::notificationCollapsedText,
            expandedText = ::notificationExpandedText,
            stateLabel = ::notificationStateLabel,
        )

    private fun updateNotification() {
        updateConnectionNotification { buildNotification(currentNotificationSnapshot()) }
    }

    private fun registerDefaultNetworkCallbackIfNeeded() {
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

    private fun startTrafficUpdates() {
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

    private fun stopTrafficUpdates() {
        immediateTrafficSampleJob?.cancel()
        immediateTrafficSampleJob = null
        trafficJob?.cancel()
        trafficJob = null
    }

    private fun startGeoRefresh() {
        stopGeoRefresh()
        geoRefreshJob =
            scope.launch(Dispatchers.IO) {
                if (GEO_REFRESH_INITIAL_DELAY_MS > 0) {
                    delay(GEO_REFRESH_INITIAL_DELAY_MS)
                }
                repeat(GEO_REFRESH_ATTEMPTS) { attempt ->
                    val success =
                        runCatching {
                            refreshProxyIpInfo(callTimeoutMs = GEO_REFRESH_CALL_TIMEOUT_MS)
                        }
                            .onSuccess {
                                FoxholeVpnRuntimeBridge.updateIpInfo(it)
                                container.diagnosticsLogger.record("ip", "geo refreshed")
                                launch(Dispatchers.Main.immediate) { updateNotification() }
                                startIpv4EnrichmentIfNeeded(it)
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

    private fun stopGeoRefresh() {
        geoRefreshJob?.cancel()
        geoRefreshJob = null
        ipv4EnrichmentJob?.cancel()
        ipv4EnrichmentJob = null
    }

    private suspend fun validateProxyConnectivity(session: VpnSession): Result<Unit> =
        withContext(Dispatchers.IO) {
            container.diagnosticsLogger.recordStructured(
                "dns",
                "Proxy validation started",
                session.protocolHint.name.lowercase(),
                "timeout_ms=$PROXY_VALIDATION_TOTAL_TIMEOUT_MS",
            )
            TunnelConnectivityProbe.run(
                attempts = PROXY_VALIDATION_ATTEMPTS,
                initialDelayMs = PROXY_VALIDATION_INITIAL_DELAY_MS,
                retryDelayMs = PROXY_VALIDATION_RETRY_DELAY_MS,
                timeoutMs = PROXY_VALIDATION_TOTAL_TIMEOUT_MS,
                onFailure = { attemptIndex, error ->
                    container.diagnosticsLogger.record(
                        "dns",
                        "proxy validation attempt ${attemptIndex + 1}/$PROXY_VALIDATION_ATTEMPTS failed: ${error.message.orEmpty()}",
                    )
                },
            ) {
                val proxyAccess = container.settingsRepository.current().preferredAppProxyAccess() ?: error("proxy surface is unavailable")
                probeConnectivityEndpointsOverLocalProxy(
                    proxy = proxyAccess,
                    callTimeoutMs = PROXY_VALIDATION_CALL_TIMEOUT_MS,
                )
                container.diagnosticsLogger.record("dns", "proxy passed local proxy connectivity validation")
            }
        }

    private fun startIpv4EnrichmentIfNeeded(info: IpInfo) {
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
                        refreshProxyIpv4Info(callTimeoutMs = IPV4_ENRICHMENT_CALL_TIMEOUT_MS)
                    }.getOrNull() ?: return@launch
                val merged = mergeIpInfo(primary = FoxholeVpnRuntimeBridge.ipInfo.value ?: info, ipv4 = ipv4Info, ipv6 = null)
                FoxholeVpnRuntimeBridge.updateIpInfo(merged)
                container.diagnosticsLogger.record("ip", "ipv4 enriched")
                launch(Dispatchers.Main.immediate) { updateNotification() }
            }
    }

    private suspend fun refreshProxyIpInfo(callTimeoutMs: Long): IpInfo {
        val settings = container.settingsRepository.current()
        val proxyAccess = settings.preferredAppProxyAccess() ?: error("proxy surface is unavailable")
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
        return container.ipInfoRepository
            .fetch(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = proxyAccess,
            ).withDnsServers(
                localDnsServers = emptyList(),
                remoteDnsServers = remoteDnsServers,
            )
    }

    private suspend fun refreshProxyIpv4Info(callTimeoutMs: Long): IpInfo? {
        val settings = container.settingsRepository.current()
        val proxyAccess = settings.preferredAppProxyAccess() ?: return null
        val remoteDnsServers = VpnDnsServerSelector.remoteDnsServerAddresses(activeSession?.configJson)
        return container.ipInfoRepository
            .fetchIpv4(
                endpoint = settings.connection.ipInfoEndpoint,
                callTimeoutMs = callTimeoutMs,
                proxy = proxyAccess,
            )?.withDnsServers(
                localDnsServers = emptyList(),
                remoteDnsServers = remoteDnsServers,
            )
    }

    private fun startNotificationHealthMonitoring() {
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

    private fun stopNotificationHealthMonitoring() {
        notificationHealthJob?.cancel()
        notificationHealthJob = null
        consecutiveNotificationHealthFailures = 0
        notificationConnectivityHealthState = ConnectivityHealthState.CHECKING
    }

    private suspend fun runNotificationConnectivityProbe(session: VpnSession): Boolean =
        withContext(Dispatchers.IO) {
            val result =
                runCatching {
                    val proxyAccess =
                        container.settingsRepository.current().preferredAppProxyAccess()
                            ?: error("proxy surface is unavailable")
                    probeConnectivityEndpointsOverLocalProxy(
                        proxy = proxyAccess,
                        callTimeoutMs = NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS,
                    )
                }
            result
                .onFailure { error ->
                    container.diagnosticsLogger.record(
                        "health",
                        "proxy notification probe failed for ${session.profileName}: ${error.message.orEmpty()}",
                    )
                }.isSuccess
        }

    private suspend fun probeConnectivityEndpointsOverLocalProxy(
        proxy: HttpProxyAccess,
        callTimeoutMs: Long,
    ) {
        var lastFailure: Throwable? = null
        connectivityProbeEndpoints().forEach { endpoint ->
            val result =
                runCatching {
                    container.ipInfoRepository.probe(
                        endpoint = endpoint,
                        callTimeoutMs = callTimeoutMs,
                        proxy = proxy,
                    )
                }
            if (result.isSuccess) {
                container.diagnosticsLogger.record("health", "proxy probe ok: $endpoint")
                return
            }
            lastFailure = result.exceptionOrNull()
            container.diagnosticsLogger.record("health", "proxy probe failed: $endpoint")
            if (!currentCoroutineContext().isActive) {
                throw lastFailure ?: IllegalStateException("proxy probe cancelled")
            }
        }
        throw lastFailure ?: IllegalStateException("proxy probe failed")
    }

    private suspend fun connectivityProbeEndpoints(): List<String> {
        val preferredEndpoint = container.settingsRepository.current().connection.ipInfoEndpoint.trim()
        return proxyConnectivityProbeEndpoints(
            preferredEndpoint = preferredEndpoint,
            fallbackEndpoints = CONNECTIVITY_PROBE_ENDPOINTS,
        )
    }

    private fun refreshDefaultNetworkAvailability() {
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun onDefaultNetworkCapabilitiesChanged(
        capabilities: NetworkCapabilities?,
        reason: String,
    ) {
        recordDefaultNetworkCapabilities(reason, capabilities)
        defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        if (FoxholeVpnRuntimeBridge.snapshot.value.state !in NOTIFICATION_HEALTH_VISIBLE_STATES) {
            return
        }
        if (defaultNetworkAvailable) {
            updateNotificationConnectivityHealth(
                state = ConnectivityHealthState.CHECKING,
                resetFailures = true,
            )
        } else {
            markNotificationConnectivityOffline()
        }
    }

    private fun markNotificationConnectivityOffline() {
        updateNotificationConnectivityHealth(
            state = ConnectivityHealthState.OFFLINE,
            force = true,
        )
        consecutiveNotificationHealthFailures = NOTIFICATION_HEALTH_FAILURE_THRESHOLD
    }

    private fun updateNotificationConnectivityHealth(
        state: ConnectivityHealthState,
        resetFailures: Boolean = false,
        force: Boolean = false,
    ) {
        if (resetFailures) {
            consecutiveNotificationHealthFailures = 0
        }
        if (!force && notificationConnectivityHealthState == state) {
            return
        }
        notificationConnectivityHealthState = state
        updateNotification()
    }

    private fun recordDefaultNetworkCapabilities(
        reason: String,
        capabilities: NetworkCapabilities?,
    ) {
        val summary = describeNetworkCapabilities(capabilities)
        if (reason == "default network changed" && summary == lastDefaultNetworkSummary) {
            return
        }
        lastDefaultNetworkSummary = summary
        container.diagnosticsLogger.recordStructured(
            "network",
            reason.replaceFirstChar(Char::uppercaseChar),
            summary,
        )
    }

    private fun describeNetworkCapabilities(capabilities: NetworkCapabilities?): String {
        if (capabilities == null) {
            return "unavailable"
        }
        val transport =
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        val traits =
            buildList {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) add("internet")
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) add("validated")
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) add("unmetered")
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) add("not_restricted")
            }
        return buildString {
            append(transport)
            if (traits.isNotEmpty()) {
                append(" • ")
                append(traits.joinToString(separator = " • "))
            }
        }
    }

    private fun onConnectionStarted(session: VpnSession) {
        if (trafficJob == null) {
            trafficSampler.start()
            startTrafficUpdates()
        }
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.PROXY,
                profileId = session.profileId,
                profileName = session.profileName,
                protocolHint = session.protocolHint,
                protocolOptionId = session.protocolOptionId,
            ),
        )
        updateNotification()
        startGeoRefresh()
    }

    private fun currentNotificationSnapshot(): NotificationSnapshot {
        val connection = FoxholeVpnRuntimeBridge.snapshot.value
        val ipInfo = FoxholeVpnRuntimeBridge.ipInfo.value
        val traffic = FoxholeVpnRuntimeBridge.traffic.value
        return NotificationSnapshot(
            profileName = connection.profileName,
            state = connection.state,
            statusMessage = connection.message,
            connectivityHealthState = notificationConnectivityHealthState,
            ipAddress = ipInfo?.ipv4 ?: ipInfo?.ip,
            countryCode = ipInfo?.countryCode,
            countryName = ipInfo?.countryName,
            trafficAvailable = traffic.available,
            txRate = traffic.txBytesPerSec,
            rxRate = traffic.rxBytesPerSec,
            txTotal = traffic.txTotalBytes,
            rxTotal = traffic.rxTotalBytes,
            updatedAt = maxOf(connection.lastChangeAt, ipInfo?.fetchedAt ?: 0L, traffic.sampledAt),
        )
    }

    private fun notificationCollapsedText(snapshot: NotificationSnapshot): String =
        notificationHealthText(snapshot).orEmpty()

    private fun notificationExpandedText(snapshot: NotificationSnapshot): String? = notificationHealthText(snapshot)

    private fun notificationHealthText(snapshot: NotificationSnapshot): String? =
        notificationBodyRes(snapshot)?.let(::getString)

    private suspend fun persistProfileTraffic(
        session: VpnSession,
        traffic: TrafficSnapshot,
    ) {
        if (!traffic.available && traffic.rxTotalBytes <= 0L && traffic.txTotalBytes <= 0L) {
            return
        }
        container.settingsRepository.accumulateProfileTraffic(
            profileId = session.profileId,
            profileName = session.profileName,
            protocolHint = session.protocolHint,
            rxBytes = traffic.rxTotalBytes,
            txBytes = traffic.txTotalBytes,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun notificationStateLabel(snapshot: NotificationSnapshot): String =
        when {
            snapshot.state == ConnectionState.CONNECTED -> getString(R.string.notification_status_connected)
            snapshot.state == ConnectionState.CONNECTING &&
                snapshot.statusMessage == getString(R.string.notification_status_analysis) ->
                getString(R.string.notification_status_analysis)
            snapshot.state == ConnectionState.CONNECTING -> getString(R.string.notification_status_connecting)
            snapshot.state == ConnectionState.RECONNECTING -> getString(R.string.notification_status_reconnecting)
            snapshot.state == ConnectionState.ERROR -> getString(R.string.notification_status_error)
            else -> getString(R.string.notification_status_disconnected)
        }

    private fun notificationBodyRes(snapshot: NotificationSnapshot): Int? =
        when (snapshot.state) {
            ConnectionState.CONNECTED ->
                when (snapshot.connectivityHealthState) {
                    ConnectivityHealthState.CHECKING -> R.string.notification_body_validating
                    ConnectivityHealthState.ONLINE -> R.string.notification_body_connected
                    ConnectivityHealthState.OFFLINE -> R.string.notification_body_waiting
                }
            ConnectionState.CONNECTING ->
                if (snapshot.statusMessage == getString(R.string.notification_status_analysis)) {
                    R.string.notification_body_validating
                } else {
                    R.string.notification_body_waiting
                }
            ConnectionState.RECONNECTING -> R.string.notification_body_reconnecting
            ConnectionState.IDLE,
            ConnectionState.ERROR,
            -> null
        }

    private companion object {
        private const val GEO_REFRESH_ATTEMPTS = 6
        private const val GEO_REFRESH_INITIAL_DELAY_MS = 0L
        private const val GEO_REFRESH_RETRY_DELAY_MS = 2_000L
        private const val GEO_REFRESH_CALL_TIMEOUT_MS = 5_000L
        private const val IPV4_ENRICHMENT_CALL_TIMEOUT_MS = 4_000L
        private const val PROXY_VALIDATION_ATTEMPTS = 3
        private const val PROXY_VALIDATION_INITIAL_DELAY_MS = 500L
        private const val PROXY_VALIDATION_RETRY_DELAY_MS = 1_000L
        private const val PROXY_VALIDATION_CALL_TIMEOUT_MS = 5_000L
        private const val PROXY_VALIDATION_TOTAL_TIMEOUT_MS = 18_000L
        private val CONNECTIVITY_PROBE_ENDPOINTS =
            listOf(
                "https://cp.cloudflare.com/generate_204",
                "https://www.gstatic.com/generate_204",
            )
        private val NOTIFICATION_HEALTH_VISIBLE_STATES =
            setOf(
                ConnectionState.CONNECTING,
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
        private val NOTIFICATION_HEALTH_PROBE_STATES =
            setOf(
                ConnectionState.CONNECTED,
                ConnectionState.RECONNECTING,
            )
        private const val NOTIFICATION_HEALTH_PROBE_TIMEOUT_MS = 1_000L
        private const val NOTIFICATION_HEALTH_FAILURE_THRESHOLD = 3
    }
}

internal fun proxyConnectivityProbeEndpoints(
    preferredEndpoint: String,
    fallbackEndpoints: List<String>,
): List<String> {
    val preferred = preferredEndpoint.trim()
    return buildList {
        fallbackEndpoints.forEach(::add)
        if (preferred.isNotBlank() && fallbackEndpoints.none { it.equals(preferred, ignoreCase = true) }) {
            add(preferred)
        }
    }
}
