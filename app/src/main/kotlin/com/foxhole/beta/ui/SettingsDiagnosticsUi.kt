package com.foxhole.beta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.model.DiagnosticsRetention
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun diagnosticsRetentionLabel(value: DiagnosticsRetention): String =
    when (value) {
        DiagnosticsRetention.HOURS_6 -> stringResource(R.string.diagnostics_retention_hours_6)
        DiagnosticsRetention.HOURS_24 -> stringResource(R.string.diagnostics_retention_hours_24)
        DiagnosticsRetention.DAYS_3 -> stringResource(R.string.diagnostics_retention_days_3)
        DiagnosticsRetention.DAYS_7 -> stringResource(R.string.diagnostics_retention_days_7)
        DiagnosticsRetention.DAYS_14 -> stringResource(R.string.diagnostics_retention_days_14)
    }

@Composable
internal fun LiveLogsDialog(
    entries: List<DiagnosticEntry>,
    networkActivityLoggingEnabled: Boolean,
    retention: DiagnosticsRetention,
    onDismiss: () -> Unit,
    onShareArchive: () -> Unit,
    onSaveArchive: () -> Unit,
) {
    val locale = remember { Locale.getDefault() }
    val timeFormat = remember(locale) { SimpleDateFormat("HH:mm:ss", locale) }
    val retentionLabel = diagnosticsRetentionLabel(retention)
    val visibleEntries = remember(entries) { entries.asReversed().take(LIVE_LOGS_VISIBLE_ENTRY_LIMIT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.logs_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text =
                        pluralStringResource(
                            R.plurals.logs_dialog_summary,
                            entries.size,
                            visibleEntries.size,
                            entries.size,
                            retentionLabel,
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (networkActivityLoggingEnabled) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = FoxholePositiveAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, FoxholePositiveAccent.copy(alpha = 0.24f)),
                    ) {
                        Text(
                            text = stringResource(R.string.logs_network_activity_notice),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (visibleEntries.isEmpty()) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
                    ) {
                        Text(
                            text = stringResource(R.string.logs_empty),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                        border =
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.52f),
                            ),
                    ) {
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 220.dp, max = 520.dp),
                        ) {
                            itemsIndexed(visibleEntries) { index, entry ->
                                PlainLiveLogEntry(
                                    entry = entry,
                                    timeFormat = timeFormat,
                                )
                                if (index != visibleEntries.lastIndex) {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(horizontal = 12.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSaveArchive) {
                    Text(stringResource(R.string.save_archive))
                }
                Button(onClick = onShareArchive) {
                    Text(stringResource(R.string.share_archive))
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

@Composable
private fun PlainLiveLogEntry(
    entry: DiagnosticEntry,
    timeFormat: java.text.DateFormat,
) {
    val colorScheme = MaterialTheme.colorScheme
    val messageParts = remember(entry.message) { diagnosticMessageParts(entry.message) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onSurfaceVariant,
        )
        Text(
            text = messageParts.headline,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
        )
        messageParts.secondary?.takeIf { it.isNotBlank() }?.let { secondary ->
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant,
            )
        }
        if (messageParts.bulletDetails.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                messageParts.bulletDetails.forEach { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

internal data class DiagnosticMessageParts(
    val headline: String,
    val secondary: String? = null,
    val bulletDetails: List<String> = emptyList(),
)

internal fun diagnosticMessageParts(message: String): DiagnosticMessageParts {
    val trimmed = message.trim()
    if (trimmed.isEmpty()) {
        return DiagnosticMessageParts(headline = "")
    }

    val tokens = trimmed.split(' ').filter(String::isNotBlank)
    val keyValuePairs =
        tokens.mapNotNull { token ->
            val separatorIndex = token.indexOf('=')
            if (separatorIndex <= 0 || separatorIndex == token.lastIndex) {
                null
            } else {
                humanizeDiagnosticKey(token.substring(0, separatorIndex)) to token.substring(separatorIndex + 1)
            }
        }
    if (keyValuePairs.isNotEmpty() && keyValuePairs.size == tokens.size) {
        val primary =
            keyValuePairs.firstOrNull { it.first == "State" }
                ?: keyValuePairs.first()
        return DiagnosticMessageParts(
            headline = "${primary.first}: ${primary.second}",
            bulletDetails = keyValuePairs.filterNot { it == primary }.map { "${it.first}: ${it.second}" },
        )
    }

    val parts = trimmed.split(": ", limit = 2)
    if (parts.size == 2) {
        val details =
            parts[1]
                .split(" • ")
                .map(String::trim)
                .filter(String::isNotBlank)
                .map(::humanizeDiagnosticDetail)
        return if (details.size > 1) {
            DiagnosticMessageParts(
                headline = parts[0].trim().replaceFirstChar(Char::uppercaseChar),
                bulletDetails = details,
            )
        } else {
            DiagnosticMessageParts(
                headline = parts[0].trim().replaceFirstChar(Char::uppercaseChar),
                secondary = details.firstOrNull() ?: parts[1].trim(),
            )
        }
    }

    return DiagnosticMessageParts(headline = trimmed.replaceFirstChar(Char::uppercaseChar))
}

private fun humanizeDiagnosticKey(key: String): String =
    when (key.lowercase(Locale.ROOT)) {
        "uid" -> "UID"
        else ->
            key
                .split('_', '-')
                .joinToString(separator = " ") { part ->
                    part.replaceFirstChar(Char::uppercaseChar)
                }
    }

private fun humanizeDiagnosticDetail(detail: String): String {
    val separatorIndex = detail.indexOf('=')
    if (separatorIndex <= 0 || separatorIndex == detail.lastIndex) {
        return detail
    }
    val key = detail.substring(0, separatorIndex)
    val value = detail.substring(separatorIndex + 1).trim()
    if (value.isBlank()) {
        return humanizeDiagnosticKey(key)
    }
    return "${humanizeDiagnosticKey(key)}: $value"
}

private const val LIVE_LOGS_VISIBLE_ENTRY_LIMIT = 600
