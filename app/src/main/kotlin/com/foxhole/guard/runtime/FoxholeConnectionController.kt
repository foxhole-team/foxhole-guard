package com.foxhole.guard.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ConnectivityHealthState
import com.foxhole.core.model.I2pPhaseSnapshot
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TorPhaseSnapshot
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSnapshot
import com.foxhole.core.runtime.ConnectionTelemetryProbe
import com.foxhole.core.runtime.ConnectivityNetworkRegistry
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeInstanceStore
import com.foxhole.core.runtime.RuntimeSupervisor
import com.foxhole.core.runtime.RuntimeUiState
import com.foxhole.core.runtime.TunnelValidationGateway
import com.foxhole.core.runtime.isFoxholeVpnNetwork
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.network.IpInfoFetchMode
import com.foxhole.core.runtime.network.IpInfoRepository
import com.foxhole.core.runtime.preferredNonVpnInternetNetwork
import com.foxhole.core.runtime.protectDirectSocket
import com.foxhole.core.runtime.requireSystemServiceSafe
import com.foxhole.core.runtime.runtimeProfileName
import com.foxhole.core.runtime.shouldDeferLocalGuardStartForActiveProfileRuntime
import com.foxhole.core.runtime.shouldStopRuntimeAfterLocalGuardDisabled
import com.foxhole.guard.R
import com.foxhole.guard.applyGuardReconcileSchedule
import com.foxhole.guard.core.data.ProfileRefreshResult
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.core.data.asRuntimeProfiles
import com.foxhole.guard.core.data.refreshProfile
import com.foxhole.guard.core.data.refreshProfileWithReport
import com.foxhole.guard.core.settings.SettingsRepository
import com.foxhole.guard.core.settings.asRuntimeSettings
import com.foxhole.guard.core.webapps.isWebAppTunTransportReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class FoxholeConnectionController internal constructor(
    internal val context: Context,
    internal val profileRepository: ProfileRepository,
    internal val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    internal val ipInfoRepository: IpInfoRepository,
    internal val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val runtimeInstanceStore: RuntimeInstanceStore,
    private val runtimeSupervisor: RuntimeSupervisor,
    private val quarantineEnforcementTracker: QuarantineRuntimeEnforcementTracker,
    /** The TUN descriptor this process holds on purpose; see [FoxholeConnectionLifecycle]. */
    private val ownMasterTunFd: () -> Int? = { null },
) {
    internal val connectivityManager by lazy {
        context.requireSystemServiceSafe<ConnectivityManager>("connectivity")
    }
    val snapshot: StateFlow<ConnectionSnapshot> = FoxholeVpnRuntimeBridge.snapshot
    val ipInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.ipInfo
    val deviceIpInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.deviceIpInfo
    val torRouteIpInfo: StateFlow<IpInfo?> = FoxholeVpnRuntimeBridge.torRouteIpInfo
    val traffic: StateFlow<TrafficSnapshot> = FoxholeVpnRuntimeBridge.traffic
    val torPhase: StateFlow<TorPhaseSnapshot> = FoxholeVpnRuntimeBridge.torPhase
    val i2pPhase: StateFlow<I2pPhaseSnapshot> = FoxholeVpnRuntimeBridge.i2pPhase

    /** What the core says the LAN proxy is doing. Fronted here for the same reason as [i2pPhase]. */
    val lanProxyStatus: StateFlow<LanProxyStatusSnapshot> = FoxholeVpnRuntimeBridge.lanProxyStatus
    internal val runtimeUiState: StateFlow<RuntimeUiState> = FoxholeVpnRuntimeBridge.runtimeUiState
    private val appliedRuntimeSignatureMutable = MutableStateFlow<Int?>(null)
    val appliedRuntimeSignature: StateFlow<Int?> = appliedRuntimeSignatureMutable
    private val lifecycle =
        FoxholeConnectionLifecycle(
            context = context,
            profileRepository = profileRepository,
            settingsRepository = settingsRepository,
            routingRepository = routingRepository,
            diagnosticsLogger = diagnosticsLogger,
            runtimeConfigAssembler = runtimeConfigAssembler,
            snapshot = snapshot,
            appliedRuntimeSignature = appliedRuntimeSignatureMutable,
            hasActiveVpnNetwork = { hasActiveVpnNetwork() },
            ownMasterTunFd = ownMasterTunFd,
            runtimeQueueSnapshot = runtimeSupervisor::queueSnapshot,
            nativeRuntimeSnapshot = runtimeInstanceStore::nativeSnapshot,
        )
    private val quarantineEnforcementCoordinator =
        QuarantineEnforcementCoordinator(
            snapshot = {
                quarantineRuntimeEnforcementSnapshot(runtimeSupervisor, runtimeInstanceStore)
            },
            currentReceipt = { quarantineEnforcementTracker.applied.value },
            dispatch = { revision ->
                FoxholeConnectionServiceContract.startForegroundService(
                    context = context,
                    mode = TrafficMode.TUNNEL,
                    action = FoxholeConnectionServiceContract.ACTION_ENFORCE_QUARANTINE,
                    quarantinePolicyRevision = revision,
                ) is ForegroundRuntimeStartResult.Started
            },
            awaitReceipt = { revision ->
                quarantineEnforcementTracker.awaitCurrent(
                    revision = revision,
                    timeoutMs = QUARANTINE_ENFORCEMENT_ACK_TIMEOUT_MS,
                    snapshot = {
                        quarantineRuntimeEnforcementSnapshot(runtimeSupervisor, runtimeInstanceStore)
                    },
                )
            },
        )
    private val validationGateway =
        TunnelValidationGateway(
            context = context,
            profileRepository = profileRepository.asRuntimeProfiles(),
            settingsRepository = settingsRepository.asRuntimeSettings(),
            ipInfoRepository = ipInfoRepository,
            diagnosticsLogger = diagnosticsLogger,
            snapshot = snapshot,
            currentVpnNetwork = { currentVpnNetwork() },
            currentUpstreamNetwork = { currentUpstreamNetwork() },
            currentTorProbeProxy = {
                val lease = runtimeInstanceStore.current()?.torProbeProxyLease()
                val activeSession = runtimeSupervisor.ownership.value.activeSession
                lease?.takeIf { current ->
                    activeSession != null &&
                        current.owner.sessionId == activeSession.correlationId &&
                        current.owner.runtimeGeneration == runtimeSupervisor.currentGeneration()
                }
            },
            currentTorProbeIssue = {
                val issue = runtimeInstanceStore.current()?.torProbeProxyIssue()
                val activeSession = runtimeSupervisor.ownership.value.activeSession
                issue?.takeIf { current ->
                    activeSession != null &&
                        current.owner.sessionId == activeSession.correlationId &&
                        current.owner.runtimeGeneration == runtimeSupervisor.currentGeneration()
                }
            },
        )

    private val telemetryProbe =
        ConnectionTelemetryProbe(
            settingsRepository = settingsRepository.asRuntimeSettings(),
            ipInfoRepository = ipInfoRepository,
            snapshot = snapshot,
            currentVpnNetwork = { currentVpnNetwork() },
            currentUpstreamNetwork = { currentUpstreamNetwork() },
            currentVpnInterfaceName = { network -> connectivityManager.getLinkProperties(network)?.interfaceName },
            isVpnNetworkValidated = { network ->
                connectivityManager
                    .getNetworkCapabilities(network)
                    ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            },
            activeServerPingTarget = { FoxholeVpnRuntimeBridge.activeServerPingTarget.value },
            protectDirectSocket = { socket -> FoxholeVpnRuntimeBridge.protectDirectSocket(socket) },
        )

    suspend fun connect(
        profileId: Long,
        protocolOptionId: String? = null,
        statusMessage: String? = null,
        isSmartStartConnection: Boolean = false,
        previousVpnNetworkHandle: Long? = null,
        subscriptionRefreshPrepared: Boolean = false,
        protocolTestTrafficFreeze: Boolean = false,
        replaceActiveTunnel: Boolean = false,
    ) = lifecycle.connect(
        profileId = profileId,
        protocolOptionId = protocolOptionId,
        statusMessage = statusMessage,
        isSmartStartConnection = isSmartStartConnection,
        previousVpnNetworkHandle = previousVpnNetworkHandle,
        subscriptionRefreshPrepared = subscriptionRefreshPrepared,
        protocolTestTrafficFreeze = protocolTestTrafficFreeze,
        replaceActiveTunnel = replaceActiveTunnel,
    )

    suspend fun connectTorOnly(statusMessage: String? = null) = lifecycle.connectTorOnly(statusMessage)

    fun disconnect(
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
        userInitiated: Boolean = true,
    ) {
        lifecycle.disconnect(
            suppressLocalGuard = suppressLocalGuard,
            preserveSmartStartAnalysis = preserveSmartStartAnalysis,
            userInitiated = userInitiated,
        )
    }

    fun disconnectTorOnly(userInitiated: Boolean = true) {
        lifecycle.disconnectTorOnly(userInitiated = userInitiated)
    }

    suspend fun currentRuntimeFingerprint(): Int = lifecycle.currentRuntimeFingerprint()

    suspend fun currentLocalGuardRuntimeFingerprint(mode: com.foxhole.core.runtime.LocalGuardMode): Int =
        lifecycle.currentLocalGuardRuntimeFingerprint(mode)

    /** Publishes only the fingerprint/revision carried by the session native code actually applied. */
    fun markRuntimeApplied(
        session: com.foxhole.core.model.VpnSession,
        runtimeGeneration: Long,
        localGuardMode: com.foxhole.core.runtime.LocalGuardMode? = null,
    ) {
        val fingerprint = requireNotNull(session.runtimeConfigFingerprint) {
            "applied runtime session is missing its assembled fingerprint"
        }
        appliedRuntimeSignatureMutable.value = fingerprint
        quarantineEnforcementTracker.acknowledge(
            session = session,
            runtimeGeneration = runtimeGeneration,
            localGuardMode = localGuardMode,
        )
    }

    internal suspend fun enforceQuarantinePolicy(revision: Long): QuarantineEnforcementOutcome =
        withContext(Dispatchers.Default) {
            quarantineEnforcementCoordinator.enforce(revision)
        }

    fun clearAppliedRuntime() {
        lifecycle.clearAppliedRuntime()
    }

    fun clearSmartStartAnalysisStatus() {
        val currentSnapshot = snapshot.value
        val analysisStatus = context.getString(R.string.notification_status_analysis)
        if (!currentSnapshot.isSmartStartConnection && currentSnapshot.message != analysisStatus) {
            return
        }
        FoxholeVpnRuntimeBridge.update(
            currentSnapshot.copy(
                message = null,
                isSmartStartConnection = false,
            ),
            refreshLastChangeAt = false,
        )
    }

    fun reload(profileId: Long? = snapshot.value.profileId): Boolean = lifecycle.reload(profileId)

    // The whole sync runs off the main thread: it is launched from UI toggles
    // (viewModelScope is Main.immediate), and its settings read, fingerprint hash,
    // connectivity binder checks and startForegroundService binder calls starved input
    // dispatch for >5s twice in the field (dropbox ANRs 07-12 20:13, 07-13 01:43).
    suspend fun syncLocalGuard(forceRestart: Boolean = false) = withContext(Dispatchers.Default) {
        val currentSnapshot = snapshot.value
        val localGuardSnapshot = currentSnapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID
        // Every guard-relevant settings change funnels through here, so this is also where the
        // out-of-process reconciler (the only thing that survives a process kill) is armed/disarmed.
        val desiredMode = settingsRepository.current().localGuardModeOrNull()
        context.applyGuardReconcileSchedule(enabled = desiredMode != null)
        if (
            shouldDeferLocalGuardStartForActiveProfileRuntime(
                snapshot = currentSnapshot,
                activeProfileSessionPresent = false,
                activeProfileVpnNetworkPresent = hasActiveVpnNetwork(),
            )
        ) {
            diagnosticsLogger.record("connection", "local guard sync deferred: active profile runtime")
            return@withContext
        }
        val mode = desiredMode
        if (mode != null) {
            val localGuardRuntimeCurrent =
                localGuardSnapshot &&
                    appliedRuntimeSignature.value == currentLocalGuardRuntimeFingerprint(mode)
            val localGuardNetworkServing =
                hasActiveVpnNetwork() &&
                    currentSnapshot.profileName == mode.runtimeProfileName()
            if (localGuardRuntimeCurrent && localGuardNetworkServing) {
                diagnosticsLogger.recordStructured(
                    "connection",
                    "local guard sync no-op",
                    "mode=${mode.name.lowercase()}",
                    "state=${currentSnapshot.state.name.lowercase()}",
                    "reason=already_current",
                )
                return@withContext
            }
            if (localGuardSnapshot && localGuardNetworkServing && !forceRestart) {
                // Guard already live in the same mode and only its config/rules changed (blocked
                // apps, DNS filter, activity logging …). Hot-reload the FoxCore policy in place —
                // no tun teardown, no CONNECTING flash — instead of a full START_LOCAL_GUARD restart.
                // A mode change (FIREWALL<->DNS) fails the network-serving check above and still
                // restarts, because its tun parameters differ. reload() returns false when the live
                // service can't take the reload (e.g. mid-transition), so we fall through to restart.
                diagnosticsLogger.recordStructured(
                    "connection",
                    "local guard sync reload",
                    "mode=${mode.name.lowercase()}",
                    "state=${currentSnapshot.state.name.lowercase()}",
                )
                if (reload(FoxholeVpnService.LOCAL_GUARD_PROFILE_ID)) {
                    return@withContext
                }
            }
            diagnosticsLogger.recordStructured(
                "connection",
                "local guard sync start",
                "mode=${mode.name.lowercase()}",
                "state=${currentSnapshot.state.name.lowercase()}",
                "runtime_current=$localGuardRuntimeCurrent",
                "network_serving=$localGuardNetworkServing",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                localGuardMode = mode,
            )
        } else if (shouldStopRuntimeAfterLocalGuardDisabled(currentSnapshot)) {
            diagnosticsLogger.recordStructured(
                "connection",
                "local guard sync stop",
                "state=${currentSnapshot.state.name.lowercase()}",
                "profile_id=${currentSnapshot.profileId}",
                "reason=local_guard_disabled",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = true,
            )
        } else {
            diagnosticsLogger.recordStructured(
                "connection",
                "local guard sync skipped",
                "state=${currentSnapshot.state.name.lowercase()}",
                "profile_id=${currentSnapshot.profileId}",
                "reason=disabled_no_active_local_guard",
            )
        }
    }

    /** WebView may run only after the exact settings-derived runtime was applied. */
    suspend fun isConnectedRuntimeCurrent(): Boolean {
        val currentSnapshot = snapshot.value
        if (!isWebAppTunTransportReady(currentSnapshot)) return false
        val expected =
            if (currentSnapshot.profileId == FoxholeVpnService.LOCAL_GUARD_PROFILE_ID) {
                val mode = settingsRepository.current().localGuardModeOrNull() ?: return false
                currentLocalGuardRuntimeFingerprint(mode)
            } else {
                currentRuntimeFingerprint()
            }
        return appliedRuntimeSignature.value == expected
    }

    suspend fun refreshProfile(
        profileId: Long,
        excludeInsecureTlsOptions: Boolean = false,
        allowInsecureTlsForProfile: Boolean = false,
    ): Profile =
        profileRepository.refreshProfile(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )

    internal suspend fun refreshProfileWithReport(
        profileId: Long,
        excludeInsecureTlsOptions: Boolean = false,
        allowInsecureTlsForProfile: Boolean = false,
    ): ProfileRefreshResult =
        profileRepository.refreshProfileWithReport(
            profileId = profileId,
            excludeInsecureTlsOptions = excludeInsecureTlsOptions,
            allowInsecureTlsForProfile = allowInsecureTlsForProfile,
        )

    suspend fun setActiveProfile(profileId: Long) {
        profileRepository.setActiveProfile(profileId)
    }

    suspend fun refreshIpInfo(fetchMode: IpInfoFetchMode = IpInfoFetchMode.FULL): IpInfo =
        validationGateway.refreshIpInfo(fetchMode)

    suspend fun refreshDeviceIpInfo(fetchMode: IpInfoFetchMode = IpInfoFetchMode.FULL): IpInfo =
        validationGateway.refreshDeviceIpInfo(fetchMode)

    suspend fun refreshTorRouteIpInfo(fetchMode: IpInfoFetchMode = IpInfoFetchMode.FULL): IpInfo =
        validationGateway.refreshTorRouteIpInfo(fetchMode)

    suspend fun measureCurrentConnectionLatency(timeoutMs: Long = LATENCY_PROBE_TIMEOUT_MS): Long =
        telemetryProbe.measureCurrentConnectionLatency(timeoutMs)

    suspend fun measureCurrentVpnServerPing(
        profileId: Long,
        protocolOptionId: String? = null,
        timeoutMs: Long = SERVER_PING_TIMEOUT_MS,
    ): Long =
        telemetryProbe.measureCurrentVpnServerPing(
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            timeoutMs = timeoutMs,
        )

    fun hasActiveVpnNetwork(): Boolean = currentVpnNetwork() != null

    /**
     * Ticks whenever a network (ours included) comes or goes. [hasActiveVpnNetwork] is a poll, so
     * a flow that folds it in only re-reads when one of its other sources emits — fold this in too
     * and the answer follows the tunnel appearing, not the next unrelated state change.
     */
    val networkRevision: StateFlow<Long> = ConnectivityNetworkRegistry.revision(context)

    internal fun currentVpnNetwork(): Network? =
        ConnectivityNetworkRegistry.snapshot(context).firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)?.isFoxholeVpnNetwork(context) == true
        }

    internal fun currentUpstreamNetwork(): Network? =
        connectivityManager.preferredNonVpnInternetNetwork(
            candidates = ConnectivityNetworkRegistry.snapshot(context),
        )
}

/** Stable handle projection kept outside the controller's already broad orchestration surface. */
internal fun FoxholeConnectionController.currentVpnNetworkHandle(): Long? =
    currentVpnNetwork()?.networkHandle

internal fun failClosedMissingActiveVpnNetworkSnapshot(
    trafficMode: TrafficMode,
    message: String,
): ConnectionSnapshot =
    ConnectionSnapshot(
        state = ConnectionState.ERROR,
        trafficMode = trafficMode,
        message = message,
    )

internal val STALE_VPN_SNAPSHOT_STATES =
    setOf(
        ConnectionState.CONNECTED,
        ConnectionState.RECONNECTING,
    )

internal const val LATENCY_PROBE_TIMEOUT_MS = 2_500L
internal const val SERVER_PING_TIMEOUT_MS = 2_500L

internal fun FoxholeVpnService.refreshDefaultNetworkAvailabilityInternal() {
    val activeNetwork = connectivityManager.activeNetwork
    val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
}

internal fun FoxholeVpnService.onDefaultNetworkCapabilitiesChangedInternal(
    capabilities: NetworkCapabilities?,
    reason: String,
) {
    defaultNetworkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    recordDefaultNetworkCapabilities(reason = reason, capabilities = capabilities)
    if (FoxholeVpnRuntimeBridge.snapshot.value.state !in FoxholeVpnService.NOTIFICATION_HEALTH_VISIBLE_STATES) {
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

internal fun FoxholeVpnService.markNotificationConnectivityOfflineInternal() {
    updateNotificationConnectivityHealth(
        state = ConnectivityHealthState.OFFLINE,
        force = true,
    )
    consecutiveNotificationHealthFailures =
        maxOf(consecutiveNotificationHealthFailures, FoxholeVpnService.NOTIFICATION_HEALTH_FAILURE_THRESHOLD)
}

internal fun FoxholeVpnService.updateNotificationConnectivityHealthInternal(
    state: ConnectivityHealthState,
    resetFailures: Boolean = false,
    force: Boolean = false,
) {
    if (resetFailures) {
        consecutiveNotificationHealthFailures = 0
    }
    if (state == ConnectivityHealthState.ONLINE && resetFailures) {
        resetAutoReconnectState()
    }
    if (!force && notificationConnectivityHealthState == state) {
        return
    }
    notificationConnectivityHealthState = state
    updateNotification()
}

internal fun FoxholeVpnService.isUpstreamNetworkInternal(network: Network): Boolean {
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
}

internal fun FoxholeVpnService.recordDefaultNetworkCapabilitiesInternal(
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

internal fun FoxholeVpnService.recordNetworkEventInternal(
    message: String,
    capabilities: NetworkCapabilities?,
) {
    container.diagnosticsLogger.recordStructured(
        "network",
        message.replaceFirstChar(Char::uppercaseChar),
        describeNetworkCapabilities(capabilities),
    )
}

internal fun FoxholeVpnService.describeNetworkCapabilitiesInternal(capabilities: NetworkCapabilities?): String {
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
        }
    return if (traits.isEmpty()) {
        transport
    } else {
        "$transport • ${traits.joinToString(separator = " • ")}"
    }
}

internal fun FoxholeVpnService.currentVpnNetworkInternal(
    excludedHandle: Long? = null,
    excludedInterfaceName: String? = null,
): Network = currentVpnNetworkOrNull(excludedHandle, excludedInterfaceName) ?: error("vpn network unavailable")

internal fun FoxholeVpnService.currentVpnNetworkOrNullInternal(
    excludedHandle: Long? = null,
    excludedInterfaceName: String? = null,
): Network? =
    currentNetworkSnapshot().firstOrNull { network ->
        connectivityManager.getNetworkCapabilities(network)?.isFoxholeVpnNetwork(this) == true &&
            vpnNetworkIdentityDiffersFrom(
                currentHandle = network.networkHandle,
                currentInterfaceName = connectivityManager.getLinkProperties(network)?.interfaceName,
                previousHandle = excludedHandle,
                previousInterfaceName = excludedInterfaceName,
            )
    }

/**
 * Android may reuse one VPN [Network] handle while atomically replacing `tun0` with `tun1` inside
 * the same [android.net.VpnService]. The interface name is therefore part of handover identity:
 * rejecting by handle alone tears down a healthy replacement after the validation timeout.
 */
internal fun vpnNetworkIdentityDiffersFrom(
    currentHandle: Long,
    currentInterfaceName: String?,
    previousHandle: Long?,
    previousInterfaceName: String?,
): Boolean =
    when {
        previousHandle == null -> true
        currentHandle != previousHandle -> true
        previousInterfaceName == null -> false
        currentInterfaceName == null -> false
        else -> currentInterfaceName != previousInterfaceName
    }

internal fun FoxholeVpnService.currentUpstreamNetworkOrNullInternal(excludedHandle: Long? = null): Network? {
    val snapshot = currentNetworkSnapshot()
    val trackedUpstreamNetworks =
        upstreamNetworkHandles.mapNotNull { handle ->
            snapshot.firstOrNull { network -> network.networkHandle == handle }
        }
    return connectivityManager.preferredNonVpnInternetNetwork(
        candidates = trackedUpstreamNetworks,
        excludedHandle = excludedHandle,
    ) ?: connectivityManager.preferredNonVpnInternetNetwork(
        candidates = snapshot,
        excludedHandle = excludedHandle,
    )
}

@Suppress("DEPRECATION")
private fun FoxholeVpnService.currentNetworkSnapshot(): List<Network> =
    linkedSetOf<Network>().apply {
        connectivityManager.activeNetwork?.let(::add)
        addAll(connectivityManager.allNetworks)
        addAll(ConnectivityNetworkRegistry.snapshot(this@currentNetworkSnapshot))
    }.toList()

internal fun FoxholeVpnService.isVpnNetworkValidatedInternal(network: Network): Boolean =
    connectivityManager
        .getNetworkCapabilities(network)
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

internal suspend fun FoxholeVpnService.awaitVpnNetworkOrNullInternal(
    timeoutMs: Long,
    excludedHandle: Long? = null,
    excludedInterfaceName: String? = null,
): Network? =
    withTimeoutOrNull(timeoutMs) {
        while (currentCoroutineContext().isActive) {
            currentVpnNetworkOrNull(excludedHandle, excludedInterfaceName)?.let { return@withTimeoutOrNull it }
            delay(FoxholeVpnService.VPN_NETWORK_WAIT_POLL_DELAY_MS)
        }
        null
    }
