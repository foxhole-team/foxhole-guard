package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.I2pNetworkPhase
import com.foxhole.core.model.LOCAL_GUARD_PROFILE_ID
import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.RuntimeTeardownPhase
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.model.TorNetworkPhase
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.RuntimeCommandQueueSnapshot
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.RuntimeResumeStateStore
import com.foxhole.core.runtime.RuntimeState
import com.foxhole.core.runtime.isActiveRuntimeForAnotherMode
import com.foxhole.core.runtime.localGuardModeOrNull
import com.foxhole.core.runtime.stoppedRuntimeSnapshot
import com.foxhole.guard.R
import com.foxhole.guard.core.data.ProfileRepository
import com.foxhole.guard.core.data.RoutingRepository
import com.foxhole.guard.core.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@Suppress("TooManyFunctions")
internal class FoxholeConnectionLifecycle(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val diagnosticsLogger: RuntimeDiagnosticsSink,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val appliedRuntimeSignature: MutableStateFlow<Int?>,
    private val hasActiveVpnNetwork: () -> Boolean,

    private val ownMasterTunFd: () -> Int? = { null },
    private val runtimeQueueSnapshot: () -> RuntimeCommandQueueSnapshot,
    private val nativeRuntimeSnapshot: () -> NativeRuntimeSnapshot,
) {
    suspend fun connect(
        profileId: Long,
        protocolOptionId: String?,
        statusMessage: String?,
        isSmartStartConnection: Boolean,
        previousVpnNetworkHandle: Long?,
        subscriptionRefreshPrepared: Boolean = false,
        protocolTestTrafficFreeze: Boolean = false,
        replaceActiveTunnel: Boolean = false,
    ) {
        RuntimeResumeStateStore.clearRecentUserStop(context)
        val profile = profileRepository.getProfile(profileId) ?: error("profile not found")
        val settings = settingsRepository.current()

        val runtimeProtocolOption =
            if (profile.sourceType == ProfileSourceType.SUBSCRIPTION_URL) {
                profile.previewRuntimeProtocolOption(protocolOptionId)
            } else {
                profile.runtimeProtocolOption(protocolOptionId)
            }
        if (!awaitCrossModeRuntimeReleaseBeforeConnect(settings.traffic.mode)) return
        if (!awaitRuntimeTeardownBeforeConnect()) return
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            if (!releaseStaleVpnBeforeConnect()) return
        }
        diagnosticsLogger.record("connection", "connect requested")
        FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = settings.traffic.mode,
                profileId = profile.id,
                profileName = profile.name,
                protocolHint = runtimeProtocolOption?.protocolHint ?: profile.protocolHint,
                protocolOptionId = runtimeProtocolOption?.id,
                message = statusMessage,
                isSmartStartConnection = isSmartStartConnection,
            ),
        )
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = settings.traffic.mode,
            action = FoxholeConnectionServiceContract.ACTION_CONNECT,
            profileId = profileId,
            protocolOptionId = protocolOptionId,
            previousVpnNetworkHandle = previousVpnNetworkHandle,
            subscriptionRefreshPrepared = subscriptionRefreshPrepared,
            protocolTestTrafficFreeze = protocolTestTrafficFreeze,
            replaceActiveTunnel = replaceActiveTunnel,
        )
    }

    suspend fun connectTorOnly(statusMessage: String?) {
        RuntimeResumeStateStore.clearRecentUserStop(context)
        val settings = settingsRepository.current()
        require(settings.privacyRoute.permitted && settings.privacyRoute.enabled) { "TOR route is disabled" }
        if (!awaitCrossModeRuntimeReleaseBeforeConnect(TrafficMode.TUNNEL)) return
        if (!awaitRuntimeTeardownBeforeConnect()) return
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            if (!releaseStaleVpnBeforeConnect()) return
        }
        diagnosticsLogger.record("connection", "direct TOR connect requested")
        FoxholeVpnRuntimeBridge.markIpInfoRefreshPending(RuntimeIpRefreshReason.POST_CONNECT)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                profileName = "TOR",
                message = statusMessage,
            ),
        )
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = TrafficMode.TUNNEL,
            action = FoxholeConnectionServiceContract.ACTION_CONNECT,
            profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
        )
    }

    private suspend fun awaitCrossModeRuntimeReleaseBeforeConnect(targetMode: TrafficMode): Boolean {
        if (!snapshot.value.isActiveRuntimeForAnotherMode(targetMode)) {
            return true
        }
        val releasedMode = snapshot.value.trafficMode
        diagnosticsLogger.record(
            "connection",
            "cross-mode connect: releasing active ${releasedMode.name.lowercase()} runtime " +
                "before ${targetMode.name.lowercase()} connect",
        )
        publishActiveConnectionsStopping()
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = releasedMode,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        val settled =
            withTimeoutOrNull(CROSS_MODE_RELEASE_TIMEOUT_MS) {
                snapshot.first { current ->
                    current.state !in ACTIVE_CONNECTION_STATES &&
                        current.state != ConnectionState.DISCONNECTING
                }
                true
            } == true
        if (!settled) {
            diagnosticsLogger.record(
                "connection",
                "cross-mode connect refused: ${releasedMode.name.lowercase()} runtime did not settle in time",
            )
            publishTeardownPendingError()
        }
        return settled
    }

    private suspend fun awaitRuntimeTeardownBeforeConnect(): Boolean {
        fun teardownPending(): Boolean =
            shouldWaitForRuntimeTeardownBeforeConnect(
                connectionState = snapshot.value.state,
                activeVpnNetwork = hasActiveVpnNetwork(),
                commandQueue = runtimeQueueSnapshot(),
                nativeRuntime = nativeRuntimeSnapshot(),
            )

        if (!teardownPending()) return true
        publishActiveConnectionsStopping()
        val settled =
            withTimeoutOrNull(PRECONNECT_RUNTIME_SETTLE_TIMEOUT_MS) {
                while (teardownPending()) {
                    delay(PRECONNECT_RUNTIME_SETTLE_POLL_MS)
                }
                true
            } == true
        if (!settled) {
            diagnosticsLogger.record(
                "connection",
                "connect refused: previous runtime teardown did not settle",
            )
            publishTeardownPendingError()
        }
        return settled
    }

    private suspend fun releaseStaleVpnBeforeConnect(): Boolean {
        if (disconnectStaleVpnBeforeConnectIfNeeded()) {
            return true
        }
        diagnosticsLogger.record(
            "connection",
            "stale vpn network still active before connect; refusing until teardown completes",
        )
        publishTeardownPendingError()
        return false
    }

    private suspend fun disconnectStaleVpnBeforeConnectIfNeeded(): Boolean {
        fun hasRealStaleVpnTunnel(): Boolean =
            shouldReleaseStaleVpnTunnelBeforeConnect(
                activeVpnNetwork = hasActiveVpnNetwork(),
                tunDescriptorProbe = processTunFileDescriptorProbe(ownMasterTunFd()),
            )

        suspend fun waitForStaleVpnNetworkToClear(): Boolean {
            val deadline = System.currentTimeMillis() + STALE_VPN_DISCONNECT_TIMEOUT_MS
            while (hasRealStaleVpnTunnel() && System.currentTimeMillis() < deadline) {
                delay(STALE_VPN_DISCONNECT_POLL_MS)
            }
            return !hasRealStaleVpnTunnel()
        }

        var vpnCleared = !hasRealStaleVpnTunnel()
        if (!vpnCleared) {
            publishActiveConnectionsStopping()
            diagnosticsLogger.record("connection", "stale vpn network found before connect; disconnecting")
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = true,
            )
            vpnCleared = waitForStaleVpnNetworkToClear()
        }

        if (!vpnCleared) {
            diagnosticsLogger.record(
                "connection",
                "stale vpn network still active before connect; issuing fail-closed kill",
            )
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = TrafficMode.TUNNEL,
                action = FoxholeConnectionServiceContract.ACTION_KILL,
                suppressLocalGuard = true,
            )
            vpnCleared = waitForStaleVpnNetworkToClear()
        }
        return vpnCleared
    }

    private fun publishActiveConnectionsStopping() {
        val current = snapshot.value
        FoxholeVpnRuntimeBridge.update(
            current.copy(
                state = ConnectionState.DISCONNECTING,
                teardownPhase = RuntimeTeardownPhase.ANDROID_TUNNEL,
                message = null,
                reasonCode = null,
            ),
        )
    }

    private fun publishTeardownPendingError() {
        FoxholeVpnRuntimeBridge.update(
            snapshot.value.copy(
                state = ConnectionState.ERROR,
                teardownPhase = null,
                message = context.getString(R.string.error_vpn_teardown_pending),
            ),
        )
    }

    fun disconnect(
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
        userInitiated: Boolean = true,
    ) {
        clearAppliedRuntime()
        diagnosticsLogger.record("connection", "disconnect requested")
        val currentSnapshot = snapshot.value
        if (userInitiated) {
            RuntimeResumeStateStore.markUserStop(context)
        }
        val activeVpnNetworkAvailable = hasActiveVpnNetwork()
        if (shouldClearDetachedTunnelReconnect(currentSnapshot, activeVpnNetworkAvailable)) {
            diagnosticsLogger.record("connection", "disconnect clearing detached reconnect snapshot")
            stopAllServicesAndPublishIdle()

            if (!suppressLocalGuard) {
                settingsRepository.settings.value.localGuardModeOrNull()?.let { mode ->
                    diagnosticsLogger.record("connection", "detached reconnect stop re-raising local guard")
                    FoxholeConnectionServiceContract.startForegroundService(
                        context = context,
                        mode = TrafficMode.TUNNEL,
                        action = FoxholeConnectionServiceContract.ACTION_START_LOCAL_GUARD,
                        localGuardMode = mode,
                    )
                }
            }
            return
        }
        val disconnectModes =
            disconnectDispatchModes(
                snapshot = currentSnapshot,
                activeVpnNetworkAvailable = activeVpnNetworkAvailable,
            )
        if (disconnectModes.isEmpty()) {
            diagnosticsLogger.record("connection", "disconnect skipped: no active runtime")
            stopAllServicesAndPublishIdle()
            return
        }
        if (userInitiated) {
            FoxholeVpnRuntimeBridge.update(
                currentSnapshot.disconnectingSnapshot(
                    torPhase = FoxholeVpnRuntimeBridge.torPhase.value.phase,
                    i2pPhase = FoxholeVpnRuntimeBridge.i2pPhase.value.phase,
                ),
            )
            FoxholeVpnRuntimeBridge.clearTransientState()
        }
        disconnectModes.forEach { disconnectMode ->
            FoxholeConnectionServiceContract.startForegroundService(
                context = context,
                mode = disconnectMode,
                action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
                suppressLocalGuard = suppressLocalGuard,
                preserveSmartStartAnalysis = preserveSmartStartAnalysis,
            )
        }
    }

    fun disconnectTorOnly(userInitiated: Boolean = true) {
        clearAppliedRuntime()
        diagnosticsLogger.record("connection", "direct TOR disconnect requested")
        val currentSnapshot = snapshot.value
        if (userInitiated) {
            RuntimeResumeStateStore.markUserStop(context)
            FoxholeVpnRuntimeBridge.update(
                currentSnapshot.disconnectingSnapshot(
                    torPhase = FoxholeVpnRuntimeBridge.torPhase.value.phase,
                    i2pPhase = FoxholeVpnRuntimeBridge.i2pPhase.value.phase,
                ),
            )
            FoxholeVpnRuntimeBridge.clearTransientState()
        }
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = TrafficMode.TUNNEL,
            action = FoxholeConnectionServiceContract.ACTION_KILL_TOR,
            suppressLocalGuard = true,
        )
    }

    suspend fun currentRuntimeFingerprint(): Int =
        runtimeConfigAssembler.runtimeFingerprint(
            settingsRepository.current(),
            routingRepository.currentPresetForRuntime(),
            privateDnsState = PrivateDnsSettings.currentState(context),
        )

    suspend fun currentLocalGuardRuntimeFingerprint(mode: LocalGuardMode): Int =
        runtimeConfigAssembler.localGuardRuntimeFingerprint(
            settingsRepository.current(),
            mode,
            routingRepository.currentPresetForRuntime(),
            privateDnsState = PrivateDnsSettings.currentState(context),
        )

    fun clearAppliedRuntime() {
        appliedRuntimeSignature.value = null
    }

    fun reload(profileId: Long?): Boolean {
        val targetProfileId = profileId ?: return false
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES || snapshot.value.profileId != targetProfileId) {
            return false
        }
        diagnosticsLogger.record("connection", "runtime reload requested")
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode =
            FoxholeConnectionServiceContract.serviceMode(
                snapshot = snapshot.value,
                fallbackMode = settingsRepository.settings.value.traffic.mode,
            ),
            action = FoxholeConnectionServiceContract.ACTION_RELOAD,
            profileId = targetProfileId,
        )
        return true
    }

    private fun stopAllServicesAndPublishIdle() {
        FoxholeConnectionServiceContract.stopAllServices(context)
        FoxholeVpnRuntimeBridge.clearTransientState()
        FoxholeVpnRuntimeBridge.update(
            stoppedRuntimeSnapshot(
                previous = snapshot.value,
                trafficMode = settingsRepository.settings.value.traffic.mode,
            ),
        )
    }
}

internal fun ConnectionSnapshot.disconnectingSnapshot(
    torPhase: TorNetworkPhase,
    i2pPhase: I2pNetworkPhase,
): ConnectionSnapshot =
    copy(
        state = ConnectionState.DISCONNECTING,
        teardownPhase = initialRuntimeTeardownPhase(this, torPhase, i2pPhase),
        message = null,
        reasonCode = null,
    )

internal fun initialRuntimeTeardownPhase(
    snapshot: ConnectionSnapshot,
    torPhase: TorNetworkPhase,
    i2pPhase: I2pNetworkPhase,
): RuntimeTeardownPhase =
    when {
        snapshot.profileId != null &&
            snapshot.profileId != TOR_ONLY_PROFILE_ID &&
            snapshot.profileId != LOCAL_GUARD_PROFILE_ID -> RuntimeTeardownPhase.VPN
        snapshot.torActive || snapshot.profileId == TOR_ONLY_PROFILE_ID || torPhase != TorNetworkPhase.OFFLINE ->
            RuntimeTeardownPhase.TOR
        i2pPhase != I2pNetworkPhase.OFFLINE -> RuntimeTeardownPhase.I2P
        else -> RuntimeTeardownPhase.ANDROID_TUNNEL
    }

internal fun shouldReleaseStaleVpnTunnelBeforeConnect(
    activeVpnNetwork: Boolean,
    tunDescriptorProbe: ProcessTunDescriptorProbe,
): Boolean = activeVpnNetwork && tunDescriptorProbe == ProcessTunDescriptorProbe.OPEN

internal fun shouldWaitForRuntimeTeardownBeforeConnect(
    connectionState: ConnectionState,
    activeVpnNetwork: Boolean,
    commandQueue: RuntimeCommandQueueSnapshot,
    nativeRuntime: NativeRuntimeSnapshot,
): Boolean {
    val teardownPriority = commandQueue.runningPriority in RUNTIME_TEARDOWN_PRIORITIES
    val nativeTeardown = nativeRuntime.teardownInProgress()
    val disconnectStillOwned =
        connectionState == ConnectionState.DISCONNECTING &&
            (activeVpnNetwork || commandQueue.commandQueueDepth > 0 || nativeTeardown)
    val failedCleanupStillOwned =
        connectionState == ConnectionState.ERROR &&
            (commandQueue.commandQueueDepth > 0 || nativeTeardown)
    return teardownPriority || nativeTeardown || disconnectStillOwned || failedCleanupStillOwned
}

private fun NativeRuntimeSnapshot.teardownInProgress(): Boolean =
    cleanupDraining ||
        nativeState == RuntimeState.STOPPING ||
        nativeState == RuntimeState.KILLING ||
        (nativeState == RuntimeState.ERROR && hasAttachedRuntimeResourcesForConnectFence())

private fun NativeRuntimeSnapshot.hasAttachedRuntimeResourcesForConnectFence(): Boolean =
    hasEngineHandle || hasTunFileDescriptor || hasHost || hasConfig

private const val STALE_VPN_DISCONNECT_TIMEOUT_MS = 1_500L
private const val STALE_VPN_DISCONNECT_POLL_MS = 250L
private const val CROSS_MODE_RELEASE_TIMEOUT_MS = 6_000L
private const val PRECONNECT_RUNTIME_SETTLE_TIMEOUT_MS = 15_000L
private const val PRECONNECT_RUNTIME_SETTLE_POLL_MS = 50L

private val RUNTIME_TEARDOWN_PRIORITIES =
    setOf(
        RuntimeCommandPriority.STOP.name.lowercase(),
        RuntimeCommandPriority.USER_STOP.name.lowercase(),
        RuntimeCommandPriority.KILL.name.lowercase(),
    )

internal fun Profile.runtimeProtocolOption(protocolOptionId: String?) =
    run {
        val requestedOptionId = protocolOptionId.normalizedProtocolOptionId()
        val storedSelectedOptionId = selectedProtocolOptionId.normalizedProtocolOptionId()
        if (protocolOptions.isEmpty()) {
            require(requestedOptionId == null && storedSelectedOptionId == null) {
                "protocol option is missing"
            }
            return@run null
        }
        requestedOptionId?.let { requestedId ->
            return@run protocolOptions.firstOrNull { option -> option.id == requestedId }
                ?: error("protocol option is missing")
        }
        storedSelectedOptionId?.let { selectedId ->
            return@run protocolOptions.firstOrNull { option -> option.id == selectedId }
                ?: error("selected protocol option is missing")
        }
        protocolOptions.firstOrNull()
    }

private fun Profile.previewRuntimeProtocolOption(protocolOptionId: String?) =
    protocolOptionId
        ?.trim()
        ?.takeIf(String::isNotBlank)
        ?.let { requestedId -> protocolOptions.firstOrNull { option -> option.id == requestedId } }
        ?: selectedProtocolOptionId
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?: protocolOptions.firstOrNull { option -> option.enabled }
        ?: protocolOptions.firstOrNull()

private fun String?.normalizedProtocolOptionId(): String? = this?.trim()?.takeIf(String::isNotBlank)

internal fun disconnectDispatchModeOrNull(snapshot: ConnectionSnapshot): TrafficMode? =
    TrafficMode.TUNNEL.takeIf { snapshot.state in ACTIVE_CONNECTION_STATES }

internal fun disconnectDispatchModes(
    snapshot: ConnectionSnapshot,
    activeVpnNetworkAvailable: Boolean,
): List<TrafficMode> =
    disconnectDispatchModeOrNull(snapshot)?.let(::listOf)
        ?: if (activeVpnNetworkAvailable) listOf(TrafficMode.TUNNEL) else emptyList()

internal fun shouldClearDetachedTunnelReconnect(
    snapshot: ConnectionSnapshot,
    activeVpnNetworkAvailable: Boolean,
): Boolean =
    snapshot.state == ConnectionState.RECONNECTING &&
        !activeVpnNetworkAvailable
