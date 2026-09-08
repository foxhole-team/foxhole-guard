package com.foxhole.guard.runtime

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.LanProxyStatusSnapshot
import com.foxhole.core.model.LanProxyUnavailableReason
import com.foxhole.core.model.LanProxyUpstream
import com.foxhole.core.model.LocalProxyStatusSnapshot
import com.foxhole.core.model.LocalProxyUpstream
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TOR_ONLY_PROFILE_ID
import com.foxhole.core.runtime.FoxholeVpnRuntimeBridge
import com.foxhole.core.runtime.LanNetworkBinding
import com.foxhole.core.runtime.LanProxyRequest
import com.foxhole.core.runtime.LocalProxyRequest
import com.foxhole.core.runtime.TorProbeProxyOwner
import com.foxhole.core.runtime.TorProbeProxyUnavailableException
import com.foxhole.core.runtime.updateLanProxyStatus
import com.foxhole.core.runtime.updateLocalProxyStatus
import kotlinx.coroutines.Dispatchers
import java.util.WeakHashMap

internal sealed interface ProxySurfaceAsk {
    data object Live : ProxySurfaceAsk

    data class Released(
        val reason: LanProxyUnavailableReason? = null,
    ) : ProxySurfaceAsk
}

internal class ProxySurfaceSyncLatch {
    @Volatile
    private var lastCompleted: ProxySurfaceAsk? = null

    fun shouldSync(ask: ProxySurfaceAsk): Boolean = ask is ProxySurfaceAsk.Live || lastCompleted != ask

    fun recordSynced(ask: ProxySurfaceAsk) {
        lastCompleted = ask
    }

    fun reset() {
        lastCompleted = null
    }
}

private class ProxySurfaceSyncState {
    val lan = ProxySurfaceSyncLatch()
    val local = ProxySurfaceSyncLatch()
    val torProbe = ProxySurfaceSyncLatch()

    fun reset() {
        lan.reset()
        local.reset()
        torProbe.reset()
    }
}

private val proxySurfaceSyncStates = WeakHashMap<FoxholeVpnService, ProxySurfaceSyncState>()

private fun FoxholeVpnService.proxySurfaceSyncState(): ProxySurfaceSyncState =
    synchronized(proxySurfaceSyncStates) {
        proxySurfaceSyncStates.getOrPut(this) { ProxySurfaceSyncState() }
    }

internal fun FoxholeVpnService.startLanProxyUpdates() {
    stopLanProxyUpdates()
    if (!proxySurfaceTickerNeeded(container.settingsRepository.settings.value)) {
        return
    }
    sessionTicker.register(
        id = FoxholeVpnService.TICKER_TASK_LAN_PROXY,
        fireImmediately = true,
        intervalMs = { FoxholeVpnService.LAN_PROXY_SYNC_INTERVAL_MS },
        // Native calls that bind sockets: never on the control-plane Main dispatcher.
        runOn = Dispatchers.IO,
    ) {
        syncLanProxy()
        syncLocalProxy()
        syncTorProbeProxy()
    }
}

internal fun FoxholeVpnService.syncLocalProxy() {
    val settings = container.settingsRepository.settings.value
    val surfaces = settings.expert.localSurfaces
    val request = when {
        !surfaces.http.enabled -> null
        else -> LocalProxyRequest(

            port = surfaces.http.port,
            username = surfaces.auth.username.takeIf { surfaces.auth.enabled },
            password = surfaces.auth.password.takeIf { surfaces.auth.enabled },
            upstream = LocalProxyUpstream.PROFILE,
            allowAnonymous = !surfaces.auth.enabled,
        )
    }
    val latch = proxySurfaceSyncState().local
    val ask = if (request == null) ProxySurfaceAsk.Released() else ProxySurfaceAsk.Live
    if (!latch.shouldSync(ask)) {
        return
    }
    val status = runCatching { runtime.syncLocalProxy(request) }.getOrNull() ?: return
    latch.recordSynced(ask)
    FoxholeVpnRuntimeBridge.updateLocalProxyStatus(status)
}

internal fun FoxholeVpnService.syncTorProbeProxy() {
    val session = activeSession
    val owner = session
        ?.takeIf { active -> torProbeOwnerEnabled && active.torActive }
        ?.let { active ->
            TorProbeProxyOwner(
                sessionId = active.correlationId,
                runtimeGeneration = runtimeSupervisor.currentGeneration(),
            )
        }
    val latch = proxySurfaceSyncState().torProbe
    val ask = if (owner == null) ProxySurfaceAsk.Released() else ProxySurfaceAsk.Live
    if (!latch.shouldSync(ask)) {
        return
    }
    val result = runtime.syncTorProbeProxy(owner)
    latch.recordSynced(ask)
    val failure = (result.exceptionOrNull() as? TorProbeProxyUnavailableException)?.failure
    when {
        owner == null -> lastTorProbeFailure = null
        result.isSuccess -> lastTorProbeFailure = null
        failure != null && failure != lastTorProbeFailure -> {
            container.diagnosticsLogger.recordFailure(
                "tor",
                "Tor IP probe unavailable: ${failure.name.lowercase()}",
            )
            lastTorProbeFailure = failure
        }
    }
}

internal fun FoxholeVpnService.stopLanProxyUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_LAN_PROXY)
    torProbeOwnerEnabled = false
    runCatching { runtime.syncLanProxy(request = null) }
        .onFailure { container.diagnosticsLogger.recordFailure("lan_proxy", "lan proxy teardown failed") }
    FoxholeVpnRuntimeBridge.updateLanProxyStatus(LanProxyStatusSnapshot())

    runCatching { runtime.syncLocalProxy(request = null) }
        .onFailure { container.diagnosticsLogger.recordFailure("local_proxy", "local proxy teardown failed") }
    runtime.syncTorProbeProxy(owner = null)
        .onFailure { container.diagnosticsLogger.recordFailure("tor", "Tor IP probe teardown failed") }
    lastTorProbeFailure = null
    FoxholeVpnRuntimeBridge.updateLocalProxyStatus(LocalProxyStatusSnapshot())
    proxySurfaceSyncState().reset()
}

internal fun FoxholeVpnService.syncLanProxy() {
    val settings = container.settingsRepository.settings.value
    val plan = lanProxyPlan(settings)
    val latch = proxySurfaceSyncState().lan
    if (!latch.shouldSync(plan.ask)) {
        return
    }
    val status =
        if (plan.request != null) {
            runtime.syncLanProxy(request = plan.request)
        } else {
            runtime.syncLanProxy(request = null, blocked = plan.blocked)
        }
    latch.recordSynced(plan.ask)
    FoxholeVpnRuntimeBridge.updateLanProxyStatus(status)
    lastLanProxyReason
        .takeIf { it != status.reason }
        ?.let { container.diagnosticsLogger.record("lan_proxy", "lan proxy state ${status.phase.name.lowercase()}") }
    lastLanProxyReason = status.reason
}

private data class LanProxyPlan(
    val request: LanProxyRequest? = null,
    val blocked: LanProxyUnavailableReason? = null,
) {
    val ask: ProxySurfaceAsk
        get() = if (request != null) ProxySurfaceAsk.Live else ProxySurfaceAsk.Released(blocked)
}

private fun FoxholeVpnService.lanProxyPlan(settings: Settings): LanProxyPlan {
    val lan = settings.expert.localSurfaces
    if (!lan.allowLanAccess) {
        return LanProxyPlan()
    }
    val blocked = lanProxyBlockedReason(settings)
    if (blocked != null) {
        return LanProxyPlan(blocked = blocked)
    }
    val binding = container.lanProxyAddressProvider.currentLanBinding()
    val request = binding?.let { current -> lan.lanProxyRequest(FoxholeVpnRuntimeBridge.snapshot.value, current) }
    return when {
        binding == null -> LanProxyPlan(blocked = LanProxyUnavailableReason.NO_WIFI)
        request == null -> LanProxyPlan(blocked = LanProxyUnavailableReason.NO_CREDENTIALS)
        else -> LanProxyPlan(request = request)
    }
}

private fun FoxholeVpnService.lanProxyBlockedReason(settings: Settings): LanProxyUnavailableReason? {
    val lan = settings.expert.localSurfaces
    return when {
        activeLocalGuardMode != null -> LanProxyUnavailableReason.NO_SESSION
        activeSession == null -> LanProxyUnavailableReason.NO_SESSION
        lan.lanAuth.password.isBlank() -> LanProxyUnavailableReason.NO_CREDENTIALS

        lanProxyCarrierIsPacketTunnel() -> LanProxyUnavailableReason.PACKET_TUNNEL
        else -> null
    }
}

private fun FoxholeVpnService.lanProxyCarrierIsPacketTunnel(): Boolean {
    val snapshot = FoxholeVpnRuntimeBridge.snapshot.value
    if (snapshot.profileId == TOR_ONLY_PROFILE_ID) {
        return false
    }
    val hint = snapshot.protocolHint ?: activeSession?.protocolHint
    return hint == ProtocolHint.WIREGUARD
}

internal fun LocalSurfaceSettings.lanProxyRequest(
    snapshot: ConnectionSnapshot,
    binding: LanNetworkBinding,
): LanProxyRequest? {
    val password = lanAuth.password.takeIf(String::isNotBlank) ?: return null
    val socksPort = if (lanProxyMode == ProxySurfaceMode.HTTP) 0 else socks.port
    val httpPort = if (lanProxyMode == ProxySurfaceMode.SOCKS5) 0 else http.port
    val request =
        LanProxyRequest(
            upstream = lanProxyUpstream(snapshot),
            socksPort = socksPort,
            httpPort = httpPort,
            username = lanAuth.username.ifBlank { LAN_PROXY_DEFAULT_USERNAME },
            password = password,
            binding = binding,
        )
    return request.takeIf { it.offersAnything }
}

private fun LocalSurfaceSettings.lanProxyUpstream(snapshot: ConnectionSnapshot): LanProxyUpstream =
    when {
        snapshot.profileId == TOR_ONLY_PROFILE_ID -> LanProxyUpstream.TOR
        lanProxyMode == ProxySurfaceMode.ALL && snapshot.torActive -> LanProxyUpstream.MIXED
        else -> LanProxyUpstream.VPN
    }

private const val LAN_PROXY_DEFAULT_USERNAME = "foxhole"

internal fun proxySurfaceTickerNeeded(settings: Settings): Boolean {
    val surfaces = settings.expert.localSurfaces
    return surfaces.allowLanAccess || surfaces.http.enabled || settings.privacyRoute.enabled
}
