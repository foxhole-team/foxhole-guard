package com.foxhole.core.model

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

val I2pNetworkPhase.networkUp: Boolean
    get() = this == I2pNetworkPhase.CONNECTED

enum class LanProxyPhase {
    OFF,

    ARMING,

    READY,

    DEGRADED,

    /** The Wi-Fi the listener was pinned to went away; credentials were invalidated with it. */
    NETWORK_LOST,

    FAILED,

    UNAVAILABLE,
}

enum class LanProxyUpstream {
    VPN,
    TOR,
    MIXED,
}

/** Why the LAN proxy is not serving. Typed so the UI never has to parse a message. */
enum class LanProxyUnavailableReason {
    NO_SESSION,

    NO_WIFI,

    NO_CREDENTIALS,

    PACKET_TUNNEL,

    NETWORK_REFUSED,

    BIND_FAILED,

    CORE_UNSUPPORTED,

    UNKNOWN,
}

data class LanProxyStatusSnapshot(
    val phase: LanProxyPhase = LanProxyPhase.OFF,
    val socksAddress: String? = null,
    val httpAddress: String? = null,
    val upstream: LanProxyUpstream? = null,
    val reason: LanProxyUnavailableReason? = null,
    val updatedAt: Long = 0L,
)

val LanProxyPhase.serving: Boolean
    get() = this == LanProxyPhase.READY

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

enum class LocalProxyPhase {
    OFF,

    ARMING,

    SERVING,

    UNAVAILABLE,
}

enum class LocalProxyUpstream {
    PROFILE,

    TOR,

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

val LocalProxyPhase.serving: Boolean
    get() = this == LocalProxyPhase.SERVING
