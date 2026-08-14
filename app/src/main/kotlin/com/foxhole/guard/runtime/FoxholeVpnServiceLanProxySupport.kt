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

// The LAN proxy's Android half: it decides WHETHER the surface may be published (network, session,
// credentials, carrier protocol) and hands the core a request; the core decides whether the bind
// succeeds and reports the state back. Nothing here writes a status — the runtime publishes what
// the core said, so the screen can never show a Ready the core never gave.

internal fun FoxholeVpnService.startLanProxyUpdates() {
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

/**
 * The device-local proxy, on the same pass as the LAN one and for the same reason: only the core
 * knows which port it bound, so the screen is fed from the core's answer rather than from the
 * settings that asked for it.
 *
 * The scenario is entered by the traffic mode rather than by a switch of its own — «прокси сервер»
 * in the VPN connection control — so the listener follows the mode: it comes up with the session
 * and goes down when the mode changes back.
 */
internal fun FoxholeVpnService.syncLocalProxy() {
    val settings = container.settingsRepository.settings.value
    val surfaces = settings.expert.localSurfaces
    val request = when {
        !surfaces.http.enabled -> null
        else -> LocalProxyRequest(
            // Zero means "ask the kernel", which is what the settings row offers as its default:
            // a fixed port on a phone is a coin flip against every other app on the device.
            port = surfaces.http.port,
            username = surfaces.auth.username.takeIf { surfaces.auth.enabled },
            password = surfaces.auth.password.takeIf { surfaces.auth.enabled },
            upstream = LocalProxyUpstream.PROFILE,
        )
    }
    val status = runCatching { runtime.syncLocalProxy(request) }.getOrNull() ?: return
    FoxholeVpnRuntimeBridge.updateLocalProxyStatus(status)
}

/**
 * Service-owned Tor identity surface. It is never derived from user proxy settings: the request is
 * always authenticated, loopback-only, Tor-upstream and correlated with the live session plus the
 * supervisor generation. The runtime reuses it on unchanged ticker passes.
 */
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
    val result = runtime.syncTorProbeProxy(owner)
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

/**
 * Teardown. The listener is taken down explicitly rather than left to the handle's destructor: the
 * session may be replaced (protocol switch, reconnect) while the process lives on, and a listener
 * that survives into the next session would be relaying into a tunnel nobody asked it to.
 */
internal fun FoxholeVpnService.stopLanProxyUpdates() {
    sessionTicker.unregister(FoxholeVpnService.TICKER_TASK_LAN_PROXY)
    torProbeOwnerEnabled = false
    runCatching { runtime.syncLanProxy(request = null) }
        .onFailure { container.diagnosticsLogger.recordFailure("lan_proxy", "lan proxy teardown failed") }
    FoxholeVpnRuntimeBridge.updateLanProxyStatus(LanProxyStatusSnapshot())
    // The local listener is taken down by the same rule: a session may be replaced while the
    // process lives on, and a listener that outlived its session would forward into a tunnel
    // nobody asked it to.
    runCatching { runtime.syncLocalProxy(request = null) }
        .onFailure { container.diagnosticsLogger.recordFailure("local_proxy", "local proxy teardown failed") }
    runtime.syncTorProbeProxy(owner = null)
        .onFailure { container.diagnosticsLogger.recordFailure("tor", "Tor IP probe teardown failed") }
    lastTorProbeFailure = null
    FoxholeVpnRuntimeBridge.updateLocalProxyStatus(LocalProxyStatusSnapshot())
}

/**
 * One pass of "what the user asked for" against "what this device can honestly publish".
 *
 * Runs on the session ticker, so it is also the re-arm path: a Wi-Fi change produces a new binding
 * and the next pass rebinds on it, while a network the core refuses keeps reporting why.
 */
internal fun FoxholeVpnService.syncLanProxy() {
    val settings = container.settingsRepository.settings.value
    val status = requestedLanProxyStatus(settings)
    FoxholeVpnRuntimeBridge.updateLanProxyStatus(status)
    lastLanProxyReason
        .takeIf { it != status.reason }
        ?.let { container.diagnosticsLogger.record("lan_proxy", "lan proxy state ${status.phase.name.lowercase()}") }
    lastLanProxyReason = status.reason
}

private fun FoxholeVpnService.requestedLanProxyStatus(settings: Settings): LanProxyStatusSnapshot {
    val lan = settings.expert.localSurfaces
    val blocked = lanProxyBlockedReason(settings)
    val binding = container.lanProxyAddressProvider.currentLanBinding()
    val request = binding?.let { current -> lan.lanProxyRequest(FoxholeVpnRuntimeBridge.snapshot.value, current) }
    return when {
        !lan.allowLanAccess -> runtime.syncLanProxy(request = null)
        blocked != null -> runtime.syncLanProxy(request = null, blocked = blocked)
        binding == null -> runtime.syncLanProxy(request = null, blocked = LanProxyUnavailableReason.NO_WIFI)
        request == null -> runtime.syncLanProxy(request = null, blocked = LanProxyUnavailableReason.NO_CREDENTIALS)
        else -> runtime.syncLanProxy(request = request)
    }
}

/**
 * Reasons the app knows about before the core is ever asked. Each one is a refusal the user can act
 * on, which is why they are typed and reported instead of being folded into a generic failure.
 */
private fun FoxholeVpnService.lanProxyBlockedReason(settings: Settings): LanProxyUnavailableReason? {
    val lan = settings.expert.localSurfaces
    return when {
        // A firewall/journal guard carries no traffic: there is no tunnel to share with the LAN.
        activeLocalGuardMode != null -> LanProxyUnavailableReason.NO_SESSION
        activeSession == null -> LanProxyUnavailableReason.NO_SESSION
        lan.lanAuth.password.isBlank() -> LanProxyUnavailableReason.NO_CREDENTIALS
        // WireGuard/AmneziaWG are L3 packet tunnels: they have no stream outbound for a relayed
        // SOCKS/HTTP session to enter, so the honest answer is "not with this profile" rather than
        // a listener that accepts connections and then drops them.
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

/**
 * The request itself. BOTH is two listeners on two ports — the SOCKS one and the HTTP one — rather
 * than a single surface described twice, so a client that can only speak one of them still knows
 * which port is its own.
 */
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

/**
 * Which tunnel the LAN clients ride:
 *  - a Tor-only session has nothing else to offer;
 *  - BOTH on a session that also carries Tor is the core's MIXED preset — SOCKS goes to the VPN,
 *    HTTP goes to Tor, so a client picks its route by picking a port;
 *  - everything else rides the VPN.
 */
private fun LocalSurfaceSettings.lanProxyUpstream(snapshot: ConnectionSnapshot): LanProxyUpstream =
    when {
        snapshot.profileId == TOR_ONLY_PROFILE_ID -> LanProxyUpstream.TOR
        lanProxyMode == ProxySurfaceMode.ALL && snapshot.torActive -> LanProxyUpstream.MIXED
        else -> LanProxyUpstream.VPN
    }

// Mirrors SettingsRepository.DEFAULT_PROXY_LOGIN: the login the settings layer writes for an empty
// field, repeated here so a half-filled form cannot produce a different user on the wire.
private const val LAN_PROXY_DEFAULT_USERNAME = "foxhole"
