package com.foxhole.guard.ui.cli.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.AppLockMode
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSeverity
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.SecureDnsMode
import com.foxhole.core.model.Settings
import com.foxhole.guard.R
import com.foxhole.guard.core.sentinel.anomaly.userFacingMessage
import com.foxhole.guard.ui.HomeRouteUiState
import com.foxhole.guard.ui.confirmedTorIdentityOrNull
import com.foxhole.guard.ui.defaultDnsServerFor
import java.util.Locale

private const val MAX_EXTENDED_EVENTS = 3

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

@Composable
private fun extendedIdentityRows(
    home: HomeRouteUiState,
    runtimes: CliActiveRuntimes,
): List<CliTerminalRow> {
    val ipInfo = extendedRouteIdentityInfo(home.ipInfo, home.torIpInfo, runtimes.tor)
    val routeIdentityShown = runtimes.vpn || runtimes.tor
    val ipRow = ipInfo?.ip?.takeIf { it.isNotBlank() && !routeIdentityShown }?.let { ip ->
        CliTerminalRow(key = stringResource(R.string.cli_home_term_key_ip), value = ip, tone = CliLineTone.INFO)
    }
    val geo = listOfNotNull(
        ipInfo?.countryCode?.takeIf { it.isNotBlank() }?.uppercase(Locale.US),
        ipInfo?.city?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val geoRow = geo.takeIf { it.isNotEmpty() && !routeIdentityShown }?.let { value ->
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
    val dnsRow = dnsServerRow(home = home)
    return listOfNotNull(ipRow, geoRow, ispRow, dnsRow, encryptionRow(home.settings))
}

internal fun extendedRouteIdentityInfo(
    vpnInfo: IpInfo?,
    torInfo: IpInfo?,
    torLive: Boolean,
): IpInfo? = if (torLive) torInfo?.confirmedTorIdentityOrNull() else vpnInfo

@Composable
private fun dnsServerRow(
    home: HomeRouteUiState,
): CliTerminalRow {
    val server = home.settings.dns.server.trim().takeIf { it.isNotBlank() }
        ?: defaultDnsServerFor(home.settings.dns.secureMode)
    val value = "$server · ${cliSecureDnsLabel(home.settings.dns.secureMode)}"
    return CliTerminalRow(
        key = stringResource(R.string.cli_home_status_dns_server),
        value = value,
        tone = CliLineTone.INFO,
    )
}

internal fun cliSecureDnsLabel(mode: SecureDnsMode): String = when (mode) {
    SecureDnsMode.DOH -> "DoH"
    SecureDnsMode.DOT -> "DoT"
    SecureDnsMode.PLAIN -> "UDP"
}

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
                cliEventColumnRow(event = entry.tag, text = entry.message, tone = CliLineTone.ERR)
            }
    }
    val anomalyRows = if (anomalies.isEmpty()) {
        emptyList()
    } else {
        listOf(CliTerminalRow(key = stringResource(R.string.cli_home_status_anomalies), tone = CliLineTone.DIM)) +
            anomalies.map { event ->
                cliEventColumnRow(
                    event = stringResource(event.type.cliSentinelTypeLabelRes()),
                    text = event.userFacingMessage(context),
                    tone = CliLineTone.WARN,
                )
            }
    }
    return journalRows + anomalyRows
}

/** Event details stay on one wrapping line; the terminal already provides their timestamp. */
internal fun cliEventColumnRow(
    event: String,
    text: String,
    tone: CliLineTone,
): CliTerminalRow =
    CliTerminalRow(
        key = event,
        value = text,
        tone = tone,
        keyTone = tone,
        valueLeading = true,
        inlineValue = true,
    )

internal fun AnomalyType.cliSentinelTypeLabelRes(): Int = when (this) {
    AnomalyType.APP_UPLOAD_SPIKE -> R.string.cli_sentinel_type_upload
    AnomalyType.APP_BACKGROUND_TRAFFIC -> R.string.cli_sentinel_type_background
    AnomalyType.TOTAL_TRAFFIC_SPIKE -> R.string.cli_sentinel_type_total
    AnomalyType.NEW_DESTINATION_COUNTRY -> R.string.cli_sentinel_type_country
    AnomalyType.DNS_BLOCK_RATIO_SPIKE -> R.string.cli_sentinel_type_dns
    AnomalyType.RECONNECT_STORM -> R.string.cli_sentinel_type_reconnect
    AnomalyType.LATENCY_SHIFT -> R.string.cli_sentinel_type_latency
    AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH -> R.string.cli_sentinel_type_legacy_route
    AnomalyType.DORMANT_APP_NETWORK_ACTIVITY -> R.string.cli_sentinel_type_dormant
    AnomalyType.KNOWN_THREAT_DESTINATION -> R.string.cli_sentinel_type_threat
}

internal fun isCriticalDiagnostic(entry: DiagnosticEntry): Boolean =
    entry.severity == DiagnosticSeverity.FAILURE
