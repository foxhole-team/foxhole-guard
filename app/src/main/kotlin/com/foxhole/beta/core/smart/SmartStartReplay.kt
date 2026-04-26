package com.foxhole.beta.core.smart

import java.util.Locale

data class SmartStartReplayEvent(
    val timestamp: Long,
    val profileId: Long,
    val optionId: String,
    val protocol: String,
    val outcome: String,
    val rankingLatencyMs: Long?,
    val connectDurationMs: Long?,
    val reasonCode: String?,
    val networkFingerprintPrefix: String?,
)

internal fun SmartStartReplayEvent.toJsonLine(): String =
    buildString {
        append('{')
        appendJsonField("timestamp", timestamp)
        append(',')
        appendJsonField("profileId", profileId)
        append(',')
        appendJsonField("optionId", optionId.sanitizedReplayToken())
        append(',')
        appendJsonField("protocol", protocol.sanitizedReplayToken())
        append(',')
        appendJsonField("outcome", outcome.sanitizedReplayToken().lowercase(Locale.ROOT))
        append(',')
        appendJsonField("rankingLatencyMs", rankingLatencyMs)
        append(',')
        appendJsonField("connectDurationMs", connectDurationMs)
        append(',')
        appendJsonField("reasonCode", reasonCode?.sanitizedReplayToken())
        append(',')
        appendJsonField("networkFingerprintPrefix", networkFingerprintPrefix?.sanitizedReplayToken())
        append('}')
    }

private fun StringBuilder.appendJsonField(
    name: String,
    value: String?,
) {
    append('"')
    append(name)
    append("\":")
    if (value == null) {
        append("null")
    } else {
        append('"')
        append(value.jsonEscaped())
        append('"')
    }
}

private fun StringBuilder.appendJsonField(
    name: String,
    value: Long?,
) {
    append('"')
    append(name)
    append("\":")
    append(value?.toString() ?: "null")
}

private fun String.sanitizedReplayToken(): String =
    trim()
        .filter { char -> char.isLetterOrDigit() || char in setOf('-', '_', '.', ':') }
        .take(64)
        .ifBlank { "unknown" }

private fun String.jsonEscaped(): String =
    buildString {
        this@jsonEscaped.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
    }

