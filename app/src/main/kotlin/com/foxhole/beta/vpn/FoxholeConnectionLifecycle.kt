package com.foxhole.beta.vpn

import android.content.Context
import com.foxhole.beta.R
import com.foxhole.beta.core.data.ProfileRepository
import com.foxhole.beta.core.data.RoutingRepository
import com.foxhole.beta.core.diagnostics.DiagnosticsLogger
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal class FoxholeConnectionLifecycle(
    private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val routingRepository: RoutingRepository,
    private val diagnosticsLogger: DiagnosticsLogger,
    private val runtimeConfigAssembler: RuntimeConfigAssembler,
    private val snapshot: StateFlow<ConnectionSnapshot>,
    private val appliedRuntimeSignature: MutableStateFlow<Int?>,
    private val hasActiveVpnNetwork: () -> Boolean,
) {
    suspend fun connect(
        profileId: Long,
        protocolOptionId: String?,
        statusMessage: String?,
        isSmartStartConnection: Boolean,
        previousVpnNetworkHandle: Long?,
    ) {
        val profile = profileRepository.getProfile(profileId) ?: error("profile not found")
        val settings = settingsRepository.current()
        val runtimeProtocolOption = profile.runtimeProtocolOption(protocolOptionId)
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            abortIfStaleVpnCannotBeReleasedBeforeConnect()
            FoxholeConnectionServiceContract.stopInactiveServices(
                context = context,
                activeMode = settings.traffic.mode,
            )
        }
        diagnosticsLogger.record("connection", "connect requested")
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
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
        )
    }

    suspend fun connectTorOnly(statusMessage: String?) {
        val settings = settingsRepository.current()
        require(settings.privacyRoute.enabled) { "Tor route is disabled" }
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            abortIfStaleVpnCannotBeReleasedBeforeConnect()
            FoxholeConnectionServiceContract.stopInactiveServices(
                context = context,
                activeMode = TrafficMode.TUNNEL,
            )
        }
        diagnosticsLogger.record("connection", "direct Tor connect requested")
        FoxholeVpnRuntimeBridge.updateIpInfo(null)
        FoxholeVpnRuntimeBridge.update(
            ConnectionSnapshot(
                state = ConnectionState.CONNECTING,
                trafficMode = TrafficMode.TUNNEL,
                profileId = FoxholeVpnService.TOR_ONLY_PROFILE_ID,
                profileName = "Tor",
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

    private suspend fun abortIfStaleVpnCannotBeReleasedBeforeConnect() {
        if (!disconnectStaleVpnBeforeConnectIfNeeded()) {
            val message = context.getString(R.string.error_runtime_stopped)
            diagnosticsLogger.record("connection", "connect aborted: stale vpn network still active")
            clearAppliedRuntime()
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                ConnectionSnapshot(
                    state = ConnectionState.ERROR,
                    trafficMode = settingsRepository.current().traffic.mode,
                    message = message,
                ),
            )
            error(message)
        }
    }

    private suspend fun disconnectStaleVpnBeforeConnectIfNeeded(): Boolean {
        suspend fun waitForStaleVpnNetworkToClear(): Boolean {
            val deadline = System.currentTimeMillis() + STALE_VPN_DISCONNECT_TIMEOUT_MS
            while (hasActiveVpnNetwork() && System.currentTimeMillis() < deadline) {
                delay(STALE_VPN_DISCONNECT_POLL_MS)
            }
            return !hasActiveVpnNetwork()
        }

        var vpnCleared = !hasActiveVpnNetwork()
        if (!vpnCleared) {
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

    fun disconnect(
        suppressLocalGuard: Boolean = false,
        preserveSmartStartAnalysis: Boolean = false,
    ) {
        clearAppliedRuntime()
        diagnosticsLogger.record("connection", "disconnect requested")
        val currentSnapshot = snapshot.value
        val activeVpnNetworkAvailable = hasActiveVpnNetwork()
        if (shouldClearDetachedTunnelReconnect(currentSnapshot, activeVpnNetworkAvailable)) {
            diagnosticsLogger.record("connection", "disconnect clearing detached reconnect snapshot")
            stopAllServicesAndPublishIdle()
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

    suspend fun currentRuntimeFingerprint(): Int =
        runtimeConfigAssembler.runtimeFingerprint(
            settingsRepository.current(),
            routingRepository.currentPresetForRuntime(),
            privateDnsState = PrivateDnsSettings.currentState(context),
        )

    suspend fun markCurrentRuntimeApplied() {
        appliedRuntimeSignature.value = currentRuntimeFingerprint()
    }

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
            ConnectionSnapshot(
                trafficMode = settingsRepository.settings.value.traffic.mode,
            ),
        )
    }
}

private const val STALE_VPN_DISCONNECT_TIMEOUT_MS = 1_500L
private const val STALE_VPN_DISCONNECT_POLL_MS = 250L

private fun Profile.runtimeProtocolOption(protocolOptionId: String?) =
    protocolOptionId
        ?.takeIf(String::isNotBlank)
        ?.let { requestedId -> protocolOptions.firstOrNull { option -> option.id == requestedId } }
        ?: selectedProtocolOptionId
            ?.takeIf(String::isNotBlank)
            ?.let { selectedId -> protocolOptions.firstOrNull { option -> option.id == selectedId } }
        ?: protocolOptions.firstOrNull()

internal fun disconnectDispatchModeOrNull(snapshot: ConnectionSnapshot): TrafficMode? =
    snapshot.trafficMode.takeIf { snapshot.state in ACTIVE_CONNECTION_STATES }

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
    snapshot.trafficMode == TrafficMode.TUNNEL &&
        snapshot.state == ConnectionState.RECONNECTING &&
        !activeVpnNetworkAvailable
