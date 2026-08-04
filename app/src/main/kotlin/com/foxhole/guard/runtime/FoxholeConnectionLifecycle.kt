package com.foxhole.guard.runtime

import android.content.Context
import com.foxhole.core.model.ACTIVE_CONNECTION_STATES
import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.Profile
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LocalGuardMode
import com.foxhole.core.runtime.PrivateDnsSettings
import com.foxhole.core.runtime.RuntimeConfigAssembler
import com.foxhole.core.runtime.RuntimeDiagnosticsSink
import com.foxhole.core.runtime.RuntimeIpRefreshReason
import com.foxhole.core.runtime.RuntimeResumeStateStore
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
    /**
     * The master TUN descriptor this process holds on purpose, or null.
     *
     * Excluded from the stale-tunnel probe: it is open by design across an
     * outbound change, so counting it makes a protocol switch look like a stale
     * tunnel that has to be disconnected first.
     *
     * Left unwired for now. The value lives on the runtime instance, this class
     * has no route to it, and the two ways to build one — the legacy bridge or
     * a new constructor dependency — are respectively fenced off by
     * `RuntimeStaticSafetyGuardTest` and larger than this defect. The path that
     * actually kills the process reads the same value from `nativeSnapshot()`
     * and is fixed; this one only waits.
     */
    private val ownMasterTunFd: () -> Int? = { null },
) {
    suspend fun connect(
        profileId: Long,
        protocolOptionId: String?,
        statusMessage: String?,
        isSmartStartConnection: Boolean,
        previousVpnNetworkHandle: Long?,
    ) {
        RuntimeResumeStateStore.clearRecentUserStop(context)
        val profile = profileRepository.getProfile(profileId) ?: error("profile not found")
        val settings = settingsRepository.current()
        val runtimeProtocolOption = profile.runtimeProtocolOption(protocolOptionId)
        awaitCrossModeRuntimeReleaseBeforeConnect(settings.traffic.mode)
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            abortIfStaleVpnCannotBeReleasedBeforeConnect()
            FoxholeConnectionServiceContract.stopInactiveServices(
                context = context,
                activeMode = settings.traffic.mode,
            )
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
        )
    }

    suspend fun connectTorOnly(statusMessage: String?) {
        RuntimeResumeStateStore.clearRecentUserStop(context)
        val settings = settingsRepository.current()
        require(settings.privacyRoute.enabled) { "TOR route is disabled" }
        awaitCrossModeRuntimeReleaseBeforeConnect(TrafficMode.TUNNEL)
        if (snapshot.value.state !in ACTIVE_CONNECTION_STATES) {
            abortIfStaleVpnCannotBeReleasedBeforeConnect()
            FoxholeConnectionServiceContract.stopInactiveServices(
                context = context,
                activeMode = TrafficMode.TUNNEL,
            )
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

    // A connect that targets one runtime mode while the OTHER mode still owns an active session
    // (e.g. Start VPN-proxy while a Tor-only tunnel is engaged) must release the old runtime
    // through its own graceful disconnect and wait for the state to settle. Skipping this let the
    // new service brute-stop the other one (stopInactiveServices -> stopService -> onDestroy), and
    // the dying service's fail-closed ERROR snapshot landed AFTER the new CONNECTING snapshot —
    // the connect looked dead while the Tor core kept a tor-only tunnel engaged.
    private suspend fun awaitCrossModeRuntimeReleaseBeforeConnect(targetMode: TrafficMode) {
        if (!snapshot.value.isActiveRuntimeForAnotherMode(targetMode)) {
            return
        }
        val releasedMode = snapshot.value.trafficMode
        diagnosticsLogger.record(
            "connection",
            "cross-mode connect: releasing active ${releasedMode.name.lowercase()} runtime " +
                "before ${targetMode.name.lowercase()} connect",
        )
        FoxholeConnectionServiceContract.startForegroundService(
            context = context,
            mode = releasedMode,
            action = FoxholeConnectionServiceContract.ACTION_DISCONNECT,
            suppressLocalGuard = true,
        )
        val settled =
            withTimeoutOrNull(CROSS_MODE_RELEASE_TIMEOUT_MS) {
                snapshot.first { it.state !in ACTIVE_CONNECTION_STATES }
                true
            } == true
        if (!settled) {
            diagnosticsLogger.record(
                "connection",
                "cross-mode connect: ${releasedMode.name.lowercase()} runtime did not settle in time; continuing",
            )
        }
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
        // Rapid reconnect may replace a framework handle that is still settling after its tun
        // closed; only a handle backed by a process-local tun descriptor blocks the new establish.
        // Disconnect acceptance is stricter and waits for Android to remove the old handle, but
        // connect admission must not turn that short framework lag into an unnecessary kill.
        fun hasRealStaleVpnTunnel(): Boolean =
            hasActiveVpnNetwork() && processHasOpenTunFileDescriptor(ownMasterTunFd())

        suspend fun waitForStaleVpnNetworkToClear(): Boolean {
            val deadline = System.currentTimeMillis() + STALE_VPN_DISCONNECT_TIMEOUT_MS
            while (hasRealStaleVpnTunnel() && System.currentTimeMillis() < deadline) {
                delay(STALE_VPN_DISCONNECT_POLL_MS)
            }
            return !hasRealStaleVpnTunnel()
        }

        var vpnCleared = !hasRealStaleVpnTunnel()
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
            // This branch kills the live service with suppressLocalGuard=true, so the service will
            // not raise guard back itself. The user stopped only the VPN, so a configured firewall
            // is raised explicitly rather than waiting for the reconcile tick.
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
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                stoppedRuntimeSnapshot(
                    previous = currentSnapshot,
                    trafficMode = settingsRepository.settings.value.traffic.mode,
                ),
            )
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
            FoxholeVpnRuntimeBridge.clearTransientState()
            FoxholeVpnRuntimeBridge.update(
                stoppedRuntimeSnapshot(
                    previous = currentSnapshot,
                    trafficMode = settingsRepository.settings.value.traffic.mode,
                ),
            )
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

    // Local-guard sessions must compare against the guard fingerprint (which also tracks the guard
    // mode, persistent-blocking and activity-logging toggles) — the general fingerprint zeroes those
    // fields, which let a live firewall ignore those toggles entirely (they no-op'd until restart).
    suspend fun currentLocalGuardRuntimeFingerprint(mode: LocalGuardMode): Int =
        runtimeConfigAssembler.localGuardRuntimeFingerprint(
            settingsRepository.current(),
            mode,
            routingRepository.currentPresetForRuntime(),
            privateDnsState = PrivateDnsSettings.currentState(context),
        )

    suspend fun markCurrentRuntimeApplied() {
        appliedRuntimeSignature.value = currentRuntimeFingerprint()
    }

    suspend fun markCurrentLocalGuardRuntimeApplied(mode: LocalGuardMode) {
        appliedRuntimeSignature.value = currentLocalGuardRuntimeFingerprint(mode)
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
            stoppedRuntimeSnapshot(
                previous = snapshot.value,
                trafficMode = settingsRepository.settings.value.traffic.mode,
            ),
        )
    }
}

private const val STALE_VPN_DISCONNECT_TIMEOUT_MS = 1_500L
private const val STALE_VPN_DISCONNECT_POLL_MS = 250L
private const val CROSS_MODE_RELEASE_TIMEOUT_MS = 6_000L

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
