package com.foxhole.core.model

/**
 * Honest Tor connection phases, driven by the real bootstrap feed (log tail /
 * control port) instead of timers. CONNECTING covers everything below the circuit
 * stage; BUILDING_CIRCUITS starts at the ap_* / circuit_create tags (progress 80+).
 */
enum class TorNetworkPhase {
    OFFLINE,
    CONNECTING,
    BUILDING_CIRCUITS,
    CONNECTED,
}

data class TorPhaseSnapshot(
    val phase: TorNetworkPhase = TorNetworkPhase.OFFLINE,
    val progress: Int? = null,
    val bootstrapTag: String? = null,
    val summary: String? = null,
    val startedAt: Long = 0L,
    val connectedAt: Long = 0L,
)

/** I2P router phases, read from the i2pd process output. */
enum class I2pNetworkPhase {
    OFFLINE,
    STARTING,
    DISCOVERING_PEERS,
    BUILDING_TUNNELS,
    CONNECTED,
}

data class I2pPhaseSnapshot(
    val phase: I2pNetworkPhase = I2pNetworkPhase.OFFLINE,
    val startedAt: Long = 0L,
    val connectedAt: Long = 0L,
    val tunnelsBuilt: Int = 0,
)

/**
 * The I2P lane is usable only after both the authenticated i2pd proxy and its service-owned Android
 * carrier are confirmed. Router tunnel construction alone says nothing about whether the device has
 * an applied TUN that can divert `.i2p` traffic, so it must never render as connected.
 */
val I2pNetworkPhase.networkUp: Boolean
    get() = this == I2pNetworkPhase.CONNECTED

/**
 * What the LAN proxy is *actually* doing, read back from the core's own listener state.
 *
 * The dashboard pill and the extras screen used to render `allowLanAccess` — the saved switch — as
 * if it were live state, so a proxy that never bound (no Wi-Fi, no password, a packet-tunnel profile
 * that cannot carry a stream upstream) still read as ON to everyone on the network. Every field here
 * is a fact the core reported, never a preference: [ARMING] is the switch waiting for a session,
 * [READY] means the listeners are bound and answering, and every not-serving case carries a
 * [LanProxyUnavailableReason] so the screen can say *why* instead of showing a green light.
 */
enum class LanProxyPhase {
    /** The switch is off. Nothing was asked for, nothing is bound. */
    OFF,

    /** Asked for, not serving yet: no session, no Wi-Fi address, or the listeners are still binding. */
    ARMING,

    /** Bound and serving on the addresses in the snapshot. */
    READY,

    /** Serving, but not everything asked for came up (one of two listeners, or the upstream is flapping). */
    DEGRADED,

    /** The Wi-Fi the listener was pinned to went away; credentials were invalidated with it. */
    NETWORK_LOST,

    /** The core refused or the listener died. [LanProxyStatusSnapshot.reason] carries which. */
    FAILED,

    /** This build/profile cannot publish a LAN proxy at all (see the reason). */
    UNAVAILABLE,
}

/** Which tunnel the LAN clients ride. MIXED is SOCKS→VPN and HTTP→Tor, per the core's preset. */
enum class LanProxyUpstream {
    VPN,
    TOR,
    MIXED,
}

/** Why the LAN proxy is not serving. Typed so the UI never has to parse a message. */
enum class LanProxyUnavailableReason {
    /** No tunnel is up: the LAN proxy shares a live session, it does not create one. */
    NO_SESSION,

    /** Not on a Wi-Fi network with a usable IPv4 address (beta is Wi-Fi IPv4 only). */
    NO_WIFI,

    /** Auth is mandatory for the LAN leg and the password is empty. */
    NO_CREDENTIALS,

    /** WireGuard/AmneziaWG carry L3 packets and have no stream outbound to relay into. */
    PACKET_TUNNEL,

    /** The core refused the network as untrusted (cellular/unconfirmed/impossible address). */
    NETWORK_REFUSED,

    /** The port is taken or the address vanished between resolve and bind. */
    BIND_FAILED,

    /** The native core in this build has no LAN proxy entry point. */
    CORE_UNSUPPORTED,

    UNKNOWN,
}

/**
 * A LAN proxy state readout. [updatedAt] is when the core was last asked, so a stale screen can be
 * told apart from a proxy that is genuinely idle.
 */
data class LanProxyStatusSnapshot(
    val phase: LanProxyPhase = LanProxyPhase.OFF,
    val socksAddress: String? = null,
    val httpAddress: String? = null,
    val upstream: LanProxyUpstream? = null,
    val reason: LanProxyUnavailableReason? = null,
    val updatedAt: Long = 0L,
)

/** Bound and answering. DEGRADED is deliberately excluded: half a surface is not a green light. */
val LanProxyPhase.serving: Boolean
    get() = this == LanProxyPhase.READY

/** The switch is on and the core is working on it — amber, not green, and not off either. */
val LanProxyPhase.armed: Boolean
    get() = this == LanProxyPhase.ARMING || this == LanProxyPhase.DEGRADED || this == LanProxyPhase.NETWORK_LOST

fun torPhaseForBootstrap(progress: Int?): TorNetworkPhase =
    when {
        progress == null -> TorNetworkPhase.CONNECTING
        progress >= TOR_BOOTSTRAP_DONE_PROGRESS -> TorNetworkPhase.CONNECTED
        progress >= TOR_BOOTSTRAP_CIRCUIT_PROGRESS -> TorNetworkPhase.BUILDING_CIRCUITS
        else -> TorNetworkPhase.CONNECTING
    }

const val TOR_BOOTSTRAP_DONE_PROGRESS = 100
const val TOR_BOOTSTRAP_CIRCUIT_PROGRESS = 80

/**
 * The device-local proxy's state, as the core reports it.
 *
 * Deliberately smaller than the LAN one: this listener binds loopback, so there is no network to
 * confirm and no second protocol to come up half-way. Either it is listening on an address the core
 * hands back, or it is not — and the port matters, because it may have been ephemeral.
 */
enum class LocalProxyPhase {
    /** Not asked for. */
    OFF,

    /** Asked for, not listening yet: no session, or the listener is still binding. */
    ARMING,

    /** Listening on [LocalProxyStatusSnapshot.address]. */
    SERVING,

    /** The core refused it — a port that will not bind, or a build without the entry point. */
    UNAVAILABLE,
}

/** Where the device-local proxy sends what it accepts. Named rather than inferred, like the LAN one. */
enum class LocalProxyUpstream {
    /** The active profile's outbound — the same one the tunnel's own flows use. */
    PROFILE,

    /** The Tor lane. Refused rather than downgraded when Tor is not up. */
    TOR,

    /** Out through the physical network even while the tunnel is up. */
    DIRECT,
    ;

    val wireName: String
        get() = name.lowercase()

    companion object {
        fun fromWireName(value: String?): LocalProxyUpstream? =
            entries.firstOrNull { entry -> entry.wireName.equals(value?.trim(), ignoreCase = true) }
    }
}

data class LocalProxyStatusSnapshot(
    val phase: LocalProxyPhase = LocalProxyPhase.OFF,
    /** `host:port` exactly as the core bound it. Never assembled from settings. */
    val address: String? = null,
    val upstream: LocalProxyUpstream? = null,
    val updatedAt: Long = 0L,
)

/** Listening and answering — the one phase in which the address may be shown as usable. */
val LocalProxyPhase.serving: Boolean
    get() = this == LocalProxyPhase.SERVING
