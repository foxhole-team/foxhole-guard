package com.foxhole.beta.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxhole.beta.R
import com.foxhole.beta.core.diagnostics.DiagnosticEntry
import com.foxhole.beta.core.diagnostics.DiagnosticSanitizer
import com.foxhole.beta.core.model.DiagnosticsRetention
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun diagnosticsRetentionLabel(value: DiagnosticsRetention): String =
    when (value) {
        DiagnosticsRetention.HOURS_6 -> stringResource(R.string.diagnostics_retention_hours_6)
        DiagnosticsRetention.HOURS_24 -> stringResource(R.string.diagnostics_retention_hours_24)
        DiagnosticsRetention.DAYS_2 -> stringResource(R.string.diagnostics_retention_days_2)
        DiagnosticsRetention.DAYS_3 -> stringResource(R.string.diagnostics_retention_days_3)
        DiagnosticsRetention.DAYS_7 -> stringResource(R.string.diagnostics_retention_days_7)
        DiagnosticsRetention.DAYS_14 -> stringResource(R.string.diagnostics_retention_days_14)
        DiagnosticsRetention.DAYS_30 -> stringResource(R.string.diagnostics_retention_days_30)
    }

@Composable
internal fun LiveLogsDialog(
    title: String,
    entries: List<DiagnosticEntry>,
    onDismiss: () -> Unit,
    notice: String? = null,
    sanitizeEntries: Boolean = false,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
) {
    val locale = remember { Locale.getDefault() }
    val timeFormat = remember(locale) { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale) }
    val visibleEntries =
        remember(entries, sanitizeEntries) {
            entries
                .asReversed()
                .map { entry ->
                    if (sanitizeEntries) {
                        entry.copy(message = DiagnosticSanitizer.sanitizeForExport(entry.message))
                    } else {
                        entry
                    }
                }
        }

    AlertDialog(
        modifier = Modifier.testTag(LIVE_LOGS_DIALOG_TAG),
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!notice.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier.testTag(LIVE_LOGS_NETWORK_NOTICE_TAG),
                        shape = MaterialTheme.shapes.medium,
                        color = FoxholePositiveAccent.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, FoxholePositiveAccent.copy(alpha = 0.24f)),
                    ) {
                        Text(
                            text = notice,
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
                        modifier = Modifier.testTag(LIVE_LOGS_EMPTY_STATE_TAG),
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
                                    .heightIn(min = 220.dp, max = 520.dp)
                                    .testTag(LIVE_LOGS_LIST_TAG),
                            userScrollEnabled = true,
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
            if (confirmLabel != null && onConfirm != null) {
                FoxholeDialogConfirmButton(
                    onClick = onConfirm,
                    label = confirmLabel,
                )
            }
        },
        dismissButton = {
            FoxholeDialogDismissButton(
                modifier = Modifier.testTag(LIVE_LOGS_CLOSE_ACTION_TAG),
                onClick = onDismiss,
            )
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

internal const val LIVE_LOGS_DIALOG_TAG = "live_logs_dialog"
internal const val LIVE_LOGS_RETENTION_SUMMARY_TAG = "live_logs_retention_summary"
internal const val LIVE_LOGS_NETWORK_NOTICE_TAG = "live_logs_network_notice"
internal const val LIVE_LOGS_EMPTY_STATE_TAG = "live_logs_empty_state"
internal const val LIVE_LOGS_LIST_TAG = "live_logs_list"
internal const val LIVE_LOGS_CLOSE_ACTION_TAG = "live_logs_close_action"
