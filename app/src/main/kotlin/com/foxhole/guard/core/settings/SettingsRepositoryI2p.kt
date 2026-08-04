package com.foxhole.guard.core.settings

import com.foxhole.core.model.I2P_TRANSIT_TUNNELS_MAX
import com.foxhole.core.model.I2P_TRANSIT_TUNNELS_MIN
import com.foxhole.core.model.I2pAddressBookEntry
import com.foxhole.core.model.I2pTransitBandwidth
import java.util.Locale

// Independent I2P (i2pd) toggle + local addressbook. Extracted from SettingsRepository (class
// split by domain).

suspend fun SettingsRepository.updateI2pEnabled(value: Boolean) =
    update { current ->
        current.copy(
            // Enabling from settings re-engages the router (a prior dashboard pause does not survive
            // an explicit re-enable); disabling leaves engaged as-is (it is gated behind enabled).
            i2p = current.i2p.copy(enabled = value, engaged = if (value) true else current.i2p.engaged),
            // Enable-order stamp, mirroring updatePrivacyRoutePermitted: the TOR/I2P settings
            // entry names/orders itself by whichever core came on first.
            ui =
            current.ui.copy(
                i2pEnabledAtMs =
                when {
                    !value -> 0L
                    current.ui.i2pEnabledAtMs != 0L -> current.ui.i2pEnabledAtMs
                    else -> System.currentTimeMillis()
                },
            ),
        )
    }

/** Runtime pause/resume from the dashboard window — flips engagement without touching the
 * persisted permission, so the pill stays and the router resumes on the next toggle. */
suspend fun SettingsRepository.updateI2pEngaged(value: Boolean) =
    update { current ->
        current.copy(i2p = current.i2p.copy(engaged = value))
    }

suspend fun SettingsRepository.updateI2pAllowOutsideTunnel(value: Boolean) =
    update { current ->
        current.copy(i2p = current.i2p.copy(allowOutsideTunnel = value))
    }

suspend fun SettingsRepository.updateI2pRelayTransitTraffic(value: Boolean) =
    update { current ->
        current.copy(i2p = current.i2p.copy(relayTransitTraffic = value))
    }

suspend fun SettingsRepository.updateI2pAllowRelayOnCellular(value: Boolean) =
    update { current ->
        current.copy(i2p = current.i2p.copy(allowRelayOnCellular = value))
    }

suspend fun SettingsRepository.updateI2pTransitBandwidth(value: I2pTransitBandwidth) =
    update { current ->
        current.copy(i2p = current.i2p.copy(transitBandwidth = value))
    }

suspend fun SettingsRepository.updateI2pTransitTunnelsLimit(value: Int) =
    update { current ->
        val clamped = value.coerceIn(I2P_TRANSIT_TUNNELS_MIN, I2P_TRANSIT_TUNNELS_MAX)
        current.copy(i2p = current.i2p.copy(transitTunnelsLimit = clamped))
    }

/**
 * Adds or replaces one local addressbook entry. [originalHost] carries the pre-edit host so a
 * rename replaces the old row instead of duplicating it; hosts are unique keys.
 */
suspend fun SettingsRepository.upsertI2pAddressBookEntry(
    originalHost: String?,
    entry: I2pAddressBookEntry,
) = update { current ->
    val normalized =
        entry.copy(
            host = entry.host.trim().lowercase(Locale.ROOT),
            destination = entry.destination.trim(),
        )
    val remaining =
        current.i2p.addressBook.filterNot { existing ->
            existing.host == originalHost || existing.host == normalized.host
        }
    current.copy(
        i2p = current.i2p.copy(addressBook = (remaining + normalized).sortedBy(I2pAddressBookEntry::host)),
    )
}

suspend fun SettingsRepository.removeI2pAddressBookEntry(host: String) =
    update { current ->
        current.copy(
            i2p = current.i2p.copy(addressBook = current.i2p.addressBook.filterNot { it.host == host }),
        )
    }
