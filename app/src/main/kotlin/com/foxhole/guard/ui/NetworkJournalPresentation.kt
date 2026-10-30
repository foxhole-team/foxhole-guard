package com.foxhole.guard.ui

import android.content.Context
import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.DiagnosticSanitizer
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.core.runtime.TorGeoIpCountryResolver
import com.foxhole.guard.R
import com.foxhole.guard.guardian.JournalText
import com.foxhole.guard.guardian.journalText
import com.foxhole.guard.ui.cli.logs.CliJournalRow
import com.foxhole.guard.ui.cli.logs.CliJournalRowTone
import java.util.Locale

internal data class NetworkJournalPresentation(
    val row: CliJournalRow,
    val exportEntry: DiagnosticEntry,
)

internal fun networkJournalPresentations(
    events: List<NetworkActivityEvent>,
    fallbackEntries: List<DiagnosticEntry>,
    context: Context,
    countryResolver: TorGeoIpCountryResolver,
    ipInfo: IpInfo?,
): List<NetworkJournalPresentation> {
    val text: JournalText = context::journalText
    return events.map { event ->
        event.toNetworkJournalPresentation(
            text = text,
            formatBytes = { bytes -> formatBytes(context, bytes) },
            countryCodeForDestination = countryResolver::countryCodeForDestination,
            ipInfo = ipInfo,
            countryName = { code ->
                Locale.Builder().setRegion(
                    code
                ).build().getDisplayCountry(context.resources.configuration.locales[0])
            },
        )
    }.ifEmpty {
        fallbackNetworkDiagnosticEntries(fallbackEntries).map { entry -> legacyNetworkJournalPresentation(entry, text) }
    }
}

internal fun NetworkActivityEvent.toNetworkJournalPresentation(
    text: JournalText,
    formatBytes: (Long) -> String,
    countryCodeForDestination: (String) -> String? = { null },
    ipInfo: IpInfo? = null,
    countryName: (String) -> String = { it },
): NetworkJournalPresentation {
    val country = networkActivityCountryCode(countryCodeForDestination, ipInfo)
    val packages = packageNames.asSequence().map(String::trim)
        .filter { it.length <= MAX_NETWORK_VALUE_LENGTH && NETWORK_PACKAGE.matches(it) }
        .distinct().take(MAX_NETWORK_PACKAGES).toList()
    val transport = protocol.uppercase(Locale.ROOT).takeIf { it == "TCP" || it == "UDP" }
        ?: text(R.string.journal_unknown, emptyList())
    val endpoint = remoteHost.takeIf { it.length <= MAX_NETWORK_VALUE_LENGTH && NETWORK_ENDPOINT.matches(it) }
        ?: text(R.string.journal_unknown, emptyList())
    val rx = bytesRx.coerceAtLeast(0L)
    val tx = bytesTx.coerceAtLeast(0L)
    val total = if (Long.MAX_VALUE - rx < tx) Long.MAX_VALUE else rx + tx
    val title = text(R.string.journal_network_title, emptyList())
    val fields =
        networkFields(text, formatBytes, packages, transport, endpoint, country?.let(countryName), rx, tx, total)
    val exported = networkFields(
        text, formatBytes, packages.map { text(R.string.journal_redacted, emptyList()) }, transport,
        text(R.string.journal_redacted, emptyList()),
        country?.let {
            text(R.string.journal_redacted, emptyList())
        },
        rx, tx, total,
        forExport = true,
    )
    return NetworkJournalPresentation(
        row = CliJournalRow(timestampMs, title, fields, CliJournalRowTone.INFO, country?.lowercase(Locale.ROOT)),
        exportEntry = DiagnosticEntry(
            timestamp = timestampMs,
            tag = NETWORK_ACTIVITY_TAG,
            message = (listOf(title) + exported).joinToString(" · ")
        ),
    )
}

private fun NetworkActivityEvent.networkFields(
    text: JournalText,
    formatBytes: (Long) -> String,
    packages: List<String>,
    transport: String,
    endpoint: String,
    country: String?,
    rx: Long,
    tx: Long,
    total: Long,
    forExport: Boolean = false,
): List<String> = buildList {
    when (packages.size) {
        0 -> add(text(R.string.journal_network_owner_unknown, emptyList()))
        1 -> add(text(R.string.journal_network_app, listOf(packages.single())))
        else -> {
            add(text(R.string.journal_network_shared, listOf(packages.joinToString())))
            add(text(R.string.journal_network_shared_note, emptyList()))
        }
    }
    add(text(R.string.journal_transport, listOf(transport)))
    add(text(R.string.journal_network_endpoint, listOf(endpoint)))
    remotePort?.takeIf { it in 1..65535 }?.let { add(text(R.string.journal_network_port, listOf(it))) }
    add(text(R.string.journal_network_country, listOf(country ?: text(R.string.journal_unknown, emptyList()))))
    add(text(R.string.journal_network_rx, listOf(formatBytes(rx))))
    add(text(R.string.journal_network_tx, listOf(formatBytes(tx))))
    add(text(R.string.journal_network_total, listOf(formatBytes(total))))
    profileId?.takeIf { it > 0L }?.let { id ->
        add(
            text(
                R.string.journal_network_profile,
                listOf(if (forExport) text(R.string.journal_redacted, emptyList()) else id)
            )
        )
    }
    sessionId?.takeIf { it.length <= MAX_NETWORK_VALUE_LENGTH && NETWORK_SESSION.matches(it) }?.let { session ->
        add(
            text(
                R.string.journal_network_session,
                listOf(if (forExport) text(R.string.journal_redacted, emptyList()) else session)
            )
        )
    }
    add(text(R.string.journal_network_observation, emptyList()))
}

internal fun legacyNetworkJournalPresentation(entry: DiagnosticEntry, text: JournalText): NetworkJournalPresentation {
    val safe = entry.redactedJournalExportEntry()
    val title = text(R.string.journal_legacy_title, emptyList())
    val fields = listOf(text(R.string.journal_legacy_note, emptyList()), safe.message)
    return NetworkJournalPresentation(
        row = CliJournalRow(entry.timestamp, title, fields, CliJournalRowTone.DIM),
        exportEntry = safe.copy(message = (listOf(title) + fields).joinToString(" · ")).redactedJournalExportEntry(),
    )
}

internal fun DiagnosticEntry.redactedJournalExportEntry(): DiagnosticEntry = copy(
    tag = DiagnosticSanitizer.sanitizeForExport(tag.take(MAX_EXPORT_TAG_LENGTH)),
    message = DiagnosticSanitizer.sanitizeForExport(message.take(MAX_EXPORT_MESSAGE_LENGTH)),
)

private val NETWORK_SESSION = Regex("[A-Za-z0-9_-]+")
private val NETWORK_PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
private val NETWORK_ENDPOINT = Regex("[A-Za-z0-9._:%\\[\\]-]+")
private const val MAX_NETWORK_VALUE_LENGTH = 256
private const val MAX_NETWORK_PACKAGES = 4
private const val MAX_EXPORT_TAG_LENGTH = 64
private const val MAX_EXPORT_MESSAGE_LENGTH = 4096

internal fun AnomalyEvent.redactedJournalExportEvent(redacted: String): AnomalyEvent = copy(
    packageName = packageName?.let { redacted },
    profileId = profileId?.let { redacted },
    reason = DiagnosticSanitizer.sanitizeForExport(reason.take(MAX_EXPORT_MESSAGE_LENGTH)),
    evidence = evidence.mapValues { (key, value) ->
        value.takeIf {
            key in NUMERIC_ANOMALY_FIELDS && it.length <= MAX_NUMERIC_EVIDENCE_LENGTH &&
                NUMERIC_EVIDENCE.matches(it) && it.toDoubleOrNull()?.isFinite() == true
        } ?: redacted
    },
)

private val NUMERIC_ANOMALY_FIELDS = setOf(
    "tx_per_min", "upload_ratio", "app_share", "bytes_per_min", "robust_z", "traffic_share",
    "blocked_dns", "allowed_dns", "blocked_ratio", "reconnects", "latency_ms", "route_share", "idle_days", "bytes",
)
private val NUMERIC_EVIDENCE = Regex("[+-]?[0-9]+(?:\\.[0-9]+)?")
private const val MAX_NUMERIC_EVIDENCE_LENGTH = 32

internal fun prepareJournalExportEntries(entries: List<DiagnosticEntry>, alreadyRedacted: Boolean): List<DiagnosticEntry> =
    entries.map { entry ->
        if (alreadyRedacted) {
            entry.copy(
                tag = DiagnosticSanitizer.normalizeForStorage(entry.tag.take(MAX_EXPORT_TAG_LENGTH)),
                message = DiagnosticSanitizer.normalizeForStorage(entry.message.take(MAX_EXPORT_MESSAGE_LENGTH)),
            )
        } else {
            entry.redactedJournalExportEntry()
        }
    }
