package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

// I2P router settings: what the app exposes of i2pd's transit/relay configuration.

// Independent I2P (i2pd) integration, run in its own child process parallel to Tor. When enabled
// and a tunnel/tor-only session is active, i2pd starts and only `.i2p` names are routed to it;
// everything else is unaffected. Default off — the user opts in explicitly.
@Serializable
enum class I2pTransitBandwidth {
    // i2pd bandwidth classes: L=32 KB/s, O=256 KB/s, P=2048 KB/s, X=unlimited.
    LOW,
    STANDARD,
    HIGH,
    UNLIMITED,
}

/** i2pd `bandwidth` config letter for the class. */
fun I2pTransitBandwidth.i2pdBandwidthChar(): String =
    when (this) {
        I2pTransitBandwidth.LOW -> "L"
        I2pTransitBandwidth.STANDARD -> "O"
        I2pTransitBandwidth.HIGH -> "P"
        I2pTransitBandwidth.UNLIMITED -> "X"
    }

@Serializable
@Immutable
data class I2pSettings(
    // Persisted PERMISSION (the settings-screen master, like privacyRoute.permitted for TOR). Full
    // disable happens only from the I2P settings screen.
    val enabled: Boolean = false,
    // Runtime ENGAGEMENT (the dashboard/window pause, like privacyRoute.mode for TOR). Default true
    // so an enabled core runs; the window toggle flips this to pause the router without revoking the
    // permission, so the quick-access pill stays and the router resumes on the next toggle.
    val engaged: Boolean = true,
    // Legacy serialized preference retained for settings compatibility. The final architecture
    // always raises the transparent carrier TUN when I2P is engaged without a profile.
    val allowOutsideTunnel: Boolean = false,
    // Keep an engaged I2P router available when its VPN carrier disconnects by moving it to the
    // transparent firewall carrier. Default true preserves the established behaviour for settings
    // documents written before this explicit user preference existed.
    val autoReconnectAfterVpnDisconnect: Boolean = true,
    // Relay other routers' traffic through this node (i2pd transit tunnels; `notransit` off). Off by
    // default — a mobile node stays client-only to spare battery/bandwidth; turning it on donates
    // cover traffic to the network. A change forces an i2pd cold restart (notransit is start-only).
    val relayTransitTraffic: Boolean = false,
    // Whether relaying is allowed while on a metered (cellular) network. Off by default: relaying
    // other routers' traffic over mobile data is costly, so on cellular the node drops to
    // client-only (notransit) even with the relay toggle on — client `.i2p` access is unaffected.
    val allowRelayOnCellular: Boolean = false,
    // Transit bandwidth class advertised to the network (i2pd `bandwidth` L/O/P/X). Only bounds
    // relayed traffic when relaying is on; LOW keeps a mobile node modest.
    val transitBandwidth: I2pTransitBandwidth = I2pTransitBandwidth.LOW,
    // Cap on concurrent transit tunnels this node hosts (i2pd `limits.transittunnels`). i2pd's own
    // default (25000) targets servers; a mobile relay stays far lower. Only matters while relaying.
    val transitTunnelsLimit: Int = 250,
    // User-managed local addressbook: written to hosts.txt in the i2pd data dir and applied by
    // restarting the i2pd child (it has no hot reload). Lives in settings (not Room) so edits
    // flip the runtime fingerprint and reload a live session automatically.
    val addressBook: List<I2pAddressBookEntry> = emptyList(),
)

/** Whether a profile-carried I2P router should move to the transparent local-guard carrier. */
fun I2pSettings.shouldAutoReconnectAfterVpnDisconnect(): Boolean =
    enabled && engaged && autoReconnectAfterVpnDisconnect

// One local ".i2p name → full base64 destination" mapping (516..616 chars; *.b32.i2p names need
// no addressbook entry and are rejected by input validation).
@Serializable
@Immutable
data class I2pAddressBookEntry(
    val host: String,
    val destination: String,
)
