package com.foxhole.core.importer

internal enum class SubscriptionPayloadFormat {
    URI_LIST,
    BASE64_BLOB,
    SMART_CONFIG,
    SIP008_JSON,
    JSON_DOCUMENT,
    WIREGUARD_TEXT,
    CLASH_YAML,
    EMPTY,
    UNKNOWN,
}

internal fun SubscriptionPayloadFormat.describe(): String =
    when (this) {
        SubscriptionPayloadFormat.URI_LIST -> "share-uri list"
        SubscriptionPayloadFormat.BASE64_BLOB -> "base64 blob"
        SubscriptionPayloadFormat.SMART_CONFIG -> "smart-config document"
        SubscriptionPayloadFormat.SIP008_JSON -> "SIP008 json"
        SubscriptionPayloadFormat.JSON_DOCUMENT -> "json document"
        SubscriptionPayloadFormat.WIREGUARD_TEXT -> "wireguard config"
        SubscriptionPayloadFormat.CLASH_YAML -> "clash yaml"
        SubscriptionPayloadFormat.EMPTY -> "empty payload"
        SubscriptionPayloadFormat.UNKNOWN -> "unrecognized payload"
    }

private val SHARE_URI_LINE_REGEX =
    Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)

private val SIP008_MARKER_REGEX =
    Regex(""""(server_port|method)"\s*:""", RegexOption.IGNORE_CASE)

private val CLASH_MARKER_REGEX =
    Regex("""^\s*(proxies|proxy-groups|proxy-providers)\s*:""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

@Suppress("ReturnCount")
internal fun detectSubscriptionPayloadFormat(
    raw: String,
    decodeBase64: (String) -> String?,
): SubscriptionPayloadFormat {
    val trimmed = raw.trim { character -> character.isWhitespace() || character == '\uFEFF' || character == '\u00A0' }
    if (trimmed.isBlank()) {
        return SubscriptionPayloadFormat.EMPTY
    }
    if (trimmed.contains("[Interface]", ignoreCase = true) && trimmed.contains("[Peer]", ignoreCase = true)) {
        return SubscriptionPayloadFormat.WIREGUARD_TEXT
    }
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
        return classifyJsonPayload(trimmed)
    }
    if (ProfileImportCoreSupport.SMART_CONFIG_HEADING_REGEX.containsMatchIn(trimmed)) {
        return SubscriptionPayloadFormat.SMART_CONFIG
    }
    if (containsShareUriLine(trimmed)) {
        return SubscriptionPayloadFormat.URI_LIST
    }
    decodeBase64(trimmed)?.let { decoded ->
        return if (containsShareUriLine(decoded)) {
            SubscriptionPayloadFormat.BASE64_BLOB
        } else {
            SubscriptionPayloadFormat.UNKNOWN
        }
    }
    if (CLASH_MARKER_REGEX.containsMatchIn(trimmed)) {
        return SubscriptionPayloadFormat.CLASH_YAML
    }
    return SubscriptionPayloadFormat.UNKNOWN
}

private fun classifyJsonPayload(trimmed: String): SubscriptionPayloadFormat =
    when {
        trimmed.contains("\"outbounds\"") -> SubscriptionPayloadFormat.JSON_DOCUMENT
        trimmed.contains("\"servers\"") && SIP008_MARKER_REGEX.containsMatchIn(trimmed) ->
            SubscriptionPayloadFormat.SIP008_JSON
        else -> SubscriptionPayloadFormat.JSON_DOCUMENT
    }

private fun containsShareUriLine(value: String): Boolean =
    value
        .lineSequence()
        .take(MAX_SUBSCRIPTION_ENTRY_LINES)
        .any { line ->
            val normalized = line.trim()
            normalized.isNotEmpty() && !normalized.startsWith("#") && SHARE_URI_LINE_REGEX.containsMatchIn(normalized)
        }
