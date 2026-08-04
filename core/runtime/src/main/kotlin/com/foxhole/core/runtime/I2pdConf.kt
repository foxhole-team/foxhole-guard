package com.foxhole.core.runtime

import com.foxhole.core.model.I2pAddressBookEntry
import java.util.Locale

/**
 * Pure builder for the generated i2pd.conf. Kept top-level and Android-free so the port templating
 * and the disabled-surface set are unit-tested on the JVM. Only the authenticated local SOCKS
 * client proxy is exposed on loopback; HTTP proxy/SAM/BOB/I2CP/i2pcontrol/UPnP stay off. The webconsole is the one status
 * surface left on: loopback-only, Basic-auth with a per-start random password, and read only by the
 * I2P window's router table (any other local app hits the auth wall). NTCP2 (TCP) and SSU2 (UDP)
 * are both enabled — i2pd falls back to NTCP2 by itself when the tunnel drops i2pd's own UDP.
 */
@Suppress("UnusedParameter")
fun buildI2pdConfLines(
    httpProxyPort: Int,
    socksPort: Int,
    relayTransitTraffic: Boolean = false,
    transitBandwidth: String = "L",
    transitTunnelsLimit: Int = 250,
    webConsolePort: Int = 0,
    webConsolePassword: String = "",
    socksUsername: String = "",
    socksPassword: String = "",
): List<String> =
    buildList {
        add("daemon = false")
        add("log = stdout")
        // info-level output feeds the Журнал I2P and the router phase detection; the
        // drain filters it down to the meaningful categories before journaling.
        add("loglevel = info")
        add("ipv4 = true")
        add("ipv6 = false")
        // notransit=true keeps this a client-only node (no relaying of others' traffic); the relay
        // toggle flips it so i2pd accepts transit tunnels and donates cover traffic to the network.
        add("notransit = ${!relayTransitTraffic}")
        // Bandwidth class advertised to the network; only bounds relayed traffic while relaying is on.
        add("bandwidth = $transitBandwidth")
        add("")
        add("[httpproxy]")
        add("enabled = false")
        add("")
        add("[socksproxy]")
        add("enabled = true")
        add("address = 127.0.0.1")
        add("port = $socksPort")
        add("username = ${socksUsername.filter(Char::isLetterOrDigit)}")
        add("password = ${socksPassword.filter(Char::isLetterOrDigit)}")
        add("")
        add("[sam]")
        add("enabled = false")
        add("")
        add("[bob]")
        add("enabled = false")
        add("")
        add("[i2cp]")
        add("enabled = false")
        add("")
        add("[i2pcontrol]")
        add("enabled = false")
        add("")
        add("[http]")
        if (webConsolePort > 0 && webConsolePassword.isNotEmpty()) {
            add("enabled = true")
            add("address = 127.0.0.1")
            add("port = $webConsolePort")
            add("auth = true")
            add("user = $I2PD_WEB_CONSOLE_USER")
            add("pass = ${webConsolePassword.filter(Char::isLetterOrDigit)}")
        } else {
            add("enabled = false")
        }
        add("")
        add("[upnp]")
        add("enabled = false")
        add("")
        add("[ntcp2]")
        add("enabled = true")
        add("")
        add("[ssu2]")
        add("enabled = true")
        add("")
        // Cap on concurrent transit tunnels hosted for other routers (only used while relaying).
        add("[limits]")
        add("transittunnels = ${transitTunnelsLimit.coerceIn(2, 25_000)}")
        add("")
        // No remote subscriptions: name resolution uses only the user-managed hosts.txt (plus
        // direct *.b32.i2p, which needs no addressbook). Empty values suppress the compiled-in
        // reg.i2p defaults.
        add("[addressbook]")
        add("defaulturl =")
        add("subscriptions =")
    }

const val I2PD_SOCKS_PROXY_USER = "foxhole"

/**
 * Renders the user-managed local addressbook as i2pd hosts.txt lines (`host=base64destination`),
 * lowercased and sorted so the output — and therefore [i2pAddressBookFingerprint] — is stable
 * regardless of entry order.
 *
 * Values are stripped of CR/LF/`=` before rendering so a malformed host or destination cannot
 * inject extra lines or key/value pairs into hosts.txt. UI validation is the first gate, but the
 * runtime writer must not trust it: an entry whose host or destination is empty after sanitising is
 * dropped rather than emitting a broken `=...` / `host=` line.
 */
fun buildI2pdHostsLines(entries: List<I2pAddressBookEntry>): List<String> =
    entries
        .mapNotNull { entry ->
            val host = entry.host.sanitizeI2pAddressBookField().lowercase(Locale.ROOT)
            val destination = entry.destination.sanitizeI2pAddressBookField()
            if (host.isEmpty() || destination.isEmpty()) null else "$host=$destination"
        }
        .sorted()

private fun String.sanitizeI2pAddressBookField(): String =
    trim().filterNot { char -> char == '\n' || char == '\r' || char == '=' }

const val I2PD_WEB_CONSOLE_USER = "foxhole"

/** Change marker for the applied addressbook; a mismatch forces an i2pd cold restart. */
fun i2pAddressBookFingerprint(entries: List<I2pAddressBookEntry>): Int = buildI2pdHostsLines(entries).hashCode()

/**
 * Change marker for the whole start-only i2pd config (addressbook + relay/notransit + bandwidth +
 * transit-tunnel cap); a mismatch on re-entry forces a cold restart because i2pd reads all of these
 * only at startup.
 */
fun i2pStartupConfigFingerprint(
    entries: List<I2pAddressBookEntry>,
    relayTransitTraffic: Boolean,
    transitBandwidth: String = "L",
    transitTunnelsLimit: Int = 250,
): Int {
    var result = i2pAddressBookFingerprint(entries)
    result = result * 31 + relayTransitTraffic.hashCode()
    result = result * 31 + transitBandwidth.hashCode()
    result = result * 31 + transitTunnelsLimit
    return result
}
