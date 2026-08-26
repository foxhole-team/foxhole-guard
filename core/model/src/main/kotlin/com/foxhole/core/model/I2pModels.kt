package com.foxhole.core.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class I2pTransitBandwidth {
    LOW,
    STANDARD,
    HIGH,
    UNLIMITED,
}

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

    val enabled: Boolean = false,

    val engaged: Boolean = true,
    val allowOutsideTunnel: Boolean = true,

    val autoReconnectAfterVpnDisconnect: Boolean = true,

    val relayTransitTraffic: Boolean = false,

    val allowRelayOnCellular: Boolean = false,

    val transitBandwidth: I2pTransitBandwidth = I2pTransitBandwidth.LOW,

    val transitTunnelsLimit: Int = 250,

    val addressBook: List<I2pAddressBookEntry> = emptyList(),
)

fun I2pSettings.shouldAutoReconnectAfterVpnDisconnect(): Boolean =
    enabled && engaged && autoReconnectAfterVpnDisconnect

@Serializable
@Immutable
data class I2pAddressBookEntry(
    val host: String,
    val destination: String,
)
