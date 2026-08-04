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
 * The I2P network counts as up: the router is building tunnels and `.i2p` starts to resolve.
 *
 * Hence the threshold is [I2pNetworkPhase.BUILDING_TUNNELS] rather than CONNECTED: CONNECTED has a
 * single writer, the SOCKS probe inside `I2pdProcessManager.awaitReady()`, which nothing calls on
 * the production path — so the phase never reaches it. This predicate is the one contract for every
 * consumer, so the terminal and the status block cannot contradict each other.
 */
val I2pNetworkPhase.networkUp: Boolean
    get() = this == I2pNetworkPhase.BUILDING_TUNNELS || this == I2pNetworkPhase.CONNECTED

fun torPhaseForBootstrap(progress: Int?): TorNetworkPhase =
    when {
        progress == null -> TorNetworkPhase.CONNECTING
        progress >= TOR_BOOTSTRAP_DONE_PROGRESS -> TorNetworkPhase.CONNECTED
        progress >= TOR_BOOTSTRAP_CIRCUIT_PROGRESS -> TorNetworkPhase.BUILDING_CIRCUITS
        else -> TorNetworkPhase.CONNECTING
    }

const val TOR_BOOTSTRAP_DONE_PROGRESS = 100
const val TOR_BOOTSTRAP_CIRCUIT_PROGRESS = 80
