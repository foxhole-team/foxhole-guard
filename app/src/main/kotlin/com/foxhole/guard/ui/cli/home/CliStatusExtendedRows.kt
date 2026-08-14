package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.userFacingMessage
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.cli.CliFormat
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import java.util.Locale

// Three of each: the block is a status readout, not the journals screen.
private const val MAX_EXTENDED_EVENTS = 3

// Intermediate lines print indented, like the connection steps above them.
private const val EVENT_INDENT = "  "

/**
 * The long-press `status` answer: the full readout. Mode and scenario, the routing rules in force,
 * the modules under their own header, the identity of this session (ip, geo, provider, resolver),
 * encryption, informational notes, and the tail of the two journals.
 *
 * Rows whose data is missing are not printed at all — an empty "provider" row would claim the
 * lookup happened and returned nothing, which is a different fact from "not looked up".
 */
@Composable
internal fun cliExtendedStatusRows(
    home: HomeRouteUiState,
    torOnlyLive: Boolean,
    extras: CliStatusExtras,
): List<CliTerminalRow> {
    val runtimes = activeRuntimes(home = home, torOnlyLive = torOnlyLive)
    val moduleRows = cliStatusModuleRows(home = home, runtimes = runtimes)
    val moduleBlock = if (moduleRows.isEmpty()) {
        emptyList()
    } else {
        listOf(
            CliTerminalRow(key = stringResource(R.string.cli_home_status_modules_header), tone = CliLineTone.DIM),
        ) + moduleRows
    }
    return cliStatusModeRows(home = home, runtimes = runtimes) +
        cliStatusRouteIdentityRows(home = home, runtimes = runtimes) +
        statusRouteRows(home = home, runtimes = runtimes) +
        moduleBlock +
        extendedIdentityRows(home = home, runtimes = runtimes) +
        cliStatusInfoRows(home = home, runtimes = runtimes, includePendingPackages = true) +
        extendedJournalRows(extras = extras)
}

/**
 * Who this device looks like right now, plus the two security facts the user asks about most.
 * The address is the app's own probe — the Tor exit when Tor carries it, otherwise the tunnel's.
 */
@Composable
private fun extendedIdentityRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    val ipInfo = extendedRouteIdentityInfo(home.ipInfo, home.torIpInfo, runtimes.tor)
    val ipRow = ipInfo?.ip?.takeIf { it.isNotBlank() }?.let { ip ->
        CliTerminalRow(key = stringResource(R.string.cli_home_term_key_ip), value = ip, tone = CliLineTone.INFO)
    }
    val geo = listOfNotNull(
        ipInfo?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        ipInfo?.city?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val geoRow = geo.takeIf { it.isNotEmpty() }?.let { value ->
        CliTerminalRow(
            key = stringResource(R.string.cli_home_term_key_geo),
            value = value,
            tone = CliLineTone.INFO,
            flagCountry = ipInfo?.countryCode,
        )
    }
    val ispRow = ipInfo?.isp?.takeIf { it.isNotBlank() }?.let { isp ->
        CliTerminalRow(key = stringResource(R.string.cli_home_term_key_isp), value = isp, tone = CliLineTone.INFO)
    }
    val dnsRow = dnsServerRow(home = home, runtimes = runtimes)
    return listOfNotNull(ipRow, geoRow, ispRow, dnsRow, encryptionRow(home.settings))
}

/** Pure fallback policy shared with tests: an active Tor route never borrows the VPN identity. */
internal fun extendedRouteIdentityInfo(
    vpnInfo: IpInfo?,
    torInfo: IpInfo?,
    torLive: Boolean,
): IpInfo? = if (torLive) torInfo?.confirmedTorIdentityOrNull() else vpnInfo

/**
 * The resolver row, always printed. While a FoxHole runtime is up the value is the configured
 * remote resolver with its transport; without one the device resolves through whatever the
 * network handed it, so the row says "system" instead of rendering a setting as state.
 *
 * Two overrides the UI cannot observe are deliberately not guessed at: a strict Private DNS
 * hostname set in Android replaces the resolver, and a selected WireGuard endpoint may push its
 * own.
 */
@Composable
private fun dnsServerRow(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): CliTerminalRow {
    val live = runtimes.any || home.connection.isLocalGuardLive()
    val server = home.settings.dns.server.trim().takeIf { it.isNotBlank() }
    val value = if (live && server != null) {
        "$server · ${cliSecureDnsLabel(home.settings.dns.secureMode)}"
    } else {
        stringResource(R.string.cli_home_dns_system)
    }
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_dns_server),
        value = value,
        tone = CliLineTone.INFO,
    )
}

/** Transport label of the configured resolver: protocol names, never raw enum words. */
internal fun cliSecureDnsLabel(mode: SecureDnsMode): String = when (mode) {
    SecureDnsMode.DOH -> "DoH"
    SecureDnsMode.DOT -> "DoT"
    SecureDnsMode.PLAIN -> "UDP"
}

/**
 * Default (the Android keystore alone) or enhanced (the user's own password derives the database
 * key). Biometrics and the lock timeout do not change which of the two is in force.
 */
@Composable
private fun encryptionRow(settings: Settings): CliTerminalRow {
    val strong = settings.appLock.mode == AppLockMode.PASSWORD
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_encryption),
        value = if (strong) {
            stringResource(R.string.cli_home_status_encryption_strong)
        } else {
            stringResource(R.string.cli_home_status_encryption_default)
        },
        tone = if (strong) CliLineTone.OK else CliLineTone.DIM,
    )
}

/**
 * The tail of both journals as full-width lines: a log message does not fit the narrow value
 * column, and truncating one to a key/value row loses exactly the part that says what happened.
 * A header prints only when its list has something under it.
 */
@Composable
private fun extendedJournalRows(extras: CliStatusExtras): List<CliTerminalRow> {
    val context = LocalContext.current
    val critical = extras.journal
        .filter(::isCriticalDiagnostic)
        .takeLast(MAX_EXTENDED_EVENTS)
    val anomalies = extras.anomalies
        .sortedByDescending(AnomalyEvent::createdAtMs)
        .take(MAX_EXTENDED_EVENTS)
    val journalRows = if (critical.isEmpty()) {
        emptyList()
    } else {
        listOf(CliTerminalRow(key = stringResource(R.string.cli_home_status_journal_critical), tone = CliLineTone.DIM)) +
            critical.map { entry ->
                CliTerminalRow(
                    key = EVENT_INDENT + "[${CliFormat.clock(entry.timestamp)}] ${entry.tag} · ${entry.message}",
                    keyTone = CliLineTone.ERR,
                    tone = CliLineTone.ERR,
                )
            }
    }
    val anomalyRows = if (anomalies.isEmpty()) {
        emptyList()
    } else {
        listOf(CliTerminalRow(key = stringResource(R.string.cli_home_status_anomalies), tone = CliLineTone.DIM)) +
            anomalies.map { event ->
                CliTerminalRow(
                    key = EVENT_INDENT + "[${CliFormat.clock(event.createdAtMs)}] ${event.userFacingMessage(context)}",
                    keyTone = CliLineTone.WARN,
                    tone = CliLineTone.WARN,
                )
            }
    }
    return journalRows + anomalyRows
}

/**
 * A failure is a failure because the code that recorded it said so.
 *
 * This used to be a vocabulary match over the message, and it was wrong in the way such matches
 * always are: `scheduled refresh completed … retryableFailures=0` — a successful run reporting a
 * counter of zero — was promoted to a critical event. The level now travels with the entry from the
 * failure path that wrote it ([DiagnosticsLogger.recordFailure]), so this block reports what was
 * recorded rather than what the wording suggests.
 */
internal fun isCriticalDiagnostic(entry: DiagnosticEntry): Boolean =
    entry.severity == DiagnosticSeverity.FAILURE
