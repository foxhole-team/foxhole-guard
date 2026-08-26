package com.foxhole.core.model

import java.net.Inet6Address
import java.net.InetAddress

object DiagnosticSanitizer {
    fun normalizeForStorage(message: String): String =
        message
            .replace(ANSI_ESCAPE_REGEX, "")
            .replace(CONTROL_CHAR_REGEX, "")
            .replace(LINE_BREAK_REGEX, " ")
            .trim()

    fun sanitizeForPersistence(message: String): String = sanitizeInternal(message)

    fun sanitizeForExport(message: String): String = sanitizeInternal(message)

    fun sanitize(message: String): String = sanitizeForExport(message)

    private fun sanitizeInternal(message: String): String {
        var sanitized = normalizeForStorage(message)
        sanitized = sanitized.replace(PROXY_PROFILE_URI_REGEX, "[profile-uri-redacted]")
        sanitized = sanitized.replace(URI_REGEX) { match ->
            "${match.groupValues[1].lowercase()}://[redacted]"
        }
        sanitized = sanitized.replace(PROTOCOL_RELATIVE_URI_REGEX, "//[redacted]")
        sanitized = sanitized.replace(AUTH_HEADER_REGEX) { match ->
            "${match.groupValues[1]}[redacted]"
        }
        sanitized = sanitized.replace(BEARER_TOKEN_REGEX, "Bearer [redacted]")
        sanitized = redactJsonFields(sanitized, ESCAPED_JSON_SENSITIVE_PREFIX_REGEX, escaped = true)
        sanitized = redactJsonFields(sanitized, JSON_SENSITIVE_PREFIX_REGEX, escaped = false)
        sanitized = redactPlainFields(sanitized)
        sanitized = sanitized.replace(USERINFO_AUTHORITY_REGEX, "[userinfo-redacted]")
        sanitized = sanitized.replace(EMAIL_REGEX, "[email]")
        sanitized = sanitized.replace(UUID_REGEX, "[uuid]")
        sanitized = sanitized.replace(COMPACT_UUID_REGEX, "[uuid]")
        sanitized = redactIpv6(sanitized)
        sanitized = redactIpv4(sanitized)
        return redactHostnames(sanitized)
    }

    private fun redactJsonFields(input: String, prefixRegex: Regex, escaped: Boolean): String {
        val output = StringBuilder(input.length)
        var cursor = 0
        while (cursor < input.length) {
            val match = prefixRegex.find(input, cursor)
                ?: return output.append(input, cursor, input.length).toString()
            val valueStart = match.range.last + 1
            val valueEnd = structuredValueEnd(input, valueStart, stopAtJsonDelimiter = true)
            output.append(input, cursor, valueStart)
            if (valueEnd > valueStart) {
                output.append(if (escaped) "\\\"[redacted]\\\"" else "\"[redacted]\"")
                cursor = valueEnd
            } else {
                cursor = valueStart
            }
        }
        return output.toString()
    }

    private fun redactPlainFields(input: String): String {
        val output = StringBuilder(input.length)
        var cursor = 0
        while (cursor < input.length) {
            val match = KEY_VALUE_SENSITIVE_PREFIX_REGEX.find(input, cursor)
                ?: return output.append(input, cursor, input.length).toString()
            val valueStart = match.range.last + 1
            val headerValue = match.groupValues[1].lowercase() in SENSITIVE_HEADER_FIELDS
            val valueEnd = structuredValueEnd(
                input = input,
                start = valueStart,
                stopAtJsonDelimiter = false,
                stopAtComma = headerValue,
            )
            output.append(input, cursor, valueStart)
            if (valueEnd > valueStart) {
                output.append("[redacted]")
                cursor = valueEnd
            } else {
                cursor = valueStart
            }
        }
        return output.toString()
    }

    private fun structuredValueEnd(
        input: String,
        start: Int,
        stopAtJsonDelimiter: Boolean,
        stopAtComma: Boolean = false,
    ): Int {
        if (start >= input.length) return start
        return when {
            input.startsWith("\\\"", start) -> quotedValueEnd(input, start, escapedDelimiter = true)
            input[start] == '"' || input[start] == '\'' ->
                quotedValueEnd(input, start, escapedDelimiter = false)
            input[start] == '[' || input[start] == '{' -> balancedValueEnd(input, start)
            else -> unquotedValueEnd(input, start, stopAtJsonDelimiter, stopAtComma)
        }
    }

    private fun unquotedValueEnd(
        input: String,
        start: Int,
        stopAtJsonDelimiter: Boolean,
        stopAtComma: Boolean,
    ): Int {
        var end = start
        while (end < input.length && !isUnquotedValueDelimiter(input[end], stopAtJsonDelimiter, stopAtComma)) {
            end += 1
        }
        return end
    }

    private fun isUnquotedValueDelimiter(
        char: Char,
        stopAtJsonDelimiter: Boolean,
        stopAtComma: Boolean,
    ): Boolean {
        if (char.isWhitespace() || char == '&') return true
        if (stopAtJsonDelimiter && char in JSON_VALUE_DELIMITERS) return true
        return stopAtComma && char == ','
    }

    private fun quotedValueEnd(input: String, start: Int, escapedDelimiter: Boolean): Int {
        val quote = if (escapedDelimiter) '"' else input[start]
        var cursor = start + if (escapedDelimiter) 2 else 1
        while (cursor < input.length) {
            if (input[cursor] == quote) {
                val slashCount = precedingSlashCount(input, cursor)
                val closes = if (escapedDelimiter) slashCount % 4 == 1 else slashCount % 2 == 0
                if (closes) return cursor + 1
            }
            cursor += 1
        }
        return input.length
    }

    private fun balancedValueEnd(input: String, start: Int): Int {
        var depth = StructuredDepth()
        var quoteMode = QuoteMode.NONE
        var cursor = start
        while (cursor < input.length) {
            val char = input[cursor]
            val slashCount = if (char == '"' || char == '\'') precedingSlashCount(input, cursor) else 0
            if (quoteMode == QuoteMode.NONE) {
                quoteMode = openingQuoteMode(char, slashCount)
                if (quoteMode == QuoteMode.NONE) {
                    depth = depth.advance(char)
                }
            } else if (quoteCloses(quoteMode, char, slashCount)) {
                quoteMode = QuoteMode.NONE
            }
            cursor += 1
            if (quoteMode == QuoteMode.NONE && depth.isClosed) return cursor
        }
        return input.length
    }

    private fun openingQuoteMode(char: Char, slashCount: Int): QuoteMode = when {
        char == '"' && slashCount == 0 -> QuoteMode.DOUBLE
        char == '"' && slashCount % 4 == 1 -> QuoteMode.ESCAPED_DOUBLE
        char == '\'' && slashCount % 2 == 0 -> QuoteMode.SINGLE
        else -> QuoteMode.NONE
    }

    private fun quoteCloses(mode: QuoteMode, char: Char, slashCount: Int): Boolean = when (mode) {
        QuoteMode.DOUBLE -> char == '"' && slashCount % 2 == 0
        QuoteMode.ESCAPED_DOUBLE -> char == '"' && slashCount % 4 == 1
        QuoteMode.SINGLE -> char == '\'' && slashCount % 2 == 0
        QuoteMode.NONE -> false
    }

    private fun precedingSlashCount(input: String, index: Int): Int {
        var cursor = index - 1
        while (cursor >= 0 && input[cursor] == '\\') cursor -= 1
        return index - cursor - 1
    }

    private fun redactIpv4(input: String): String =
        IPV4_CANDIDATE_REGEX.replace(input) { match ->
            val candidate = match.value
            val prefixStart = maxOf(0, match.range.first - VERSION_PREFIX_LOOKBEHIND)
            val prefix = input.substring(prefixStart, match.range.first)
            val versionLiteral = VERSION_PREFIX_REGEX.containsMatchIn(prefix)
            if (isValidIpv4(candidate) && !versionLiteral) "[ip]" else candidate
        }

    private fun isValidIpv4(candidate: String): Boolean {
        val parts = candidate.split('.')
        if (parts.size !in 2..4) return false
        val values = parts.map { parseIpv4Part(it) ?: return false }
        return when (values.size) {
            2 -> values[0] <= OCTET_MAX && values[1] <= IPV4_THREE_BYTE_MAX
            3 -> values[0] <= OCTET_MAX && values[1] <= OCTET_MAX && values[2] <= IPV4_TWO_BYTE_MAX
            4 -> values.all { it <= OCTET_MAX }
            else -> false
        }
    }

    private fun parseIpv4Part(raw: String): Long? {
        val (digits, radix) = when {
            raw.startsWith("0x", ignoreCase = true) -> raw.drop(2) to HEX_RADIX
            raw.length > 1 && raw.startsWith('0') && raw.drop(1).all { it in '0'..'7' } -> raw to OCTAL_RADIX
            else -> raw to DECIMAL_RADIX
        }
        return digits.toLongOrNull(radix)
    }

    private fun redactIpv6(input: String): String =
        IPV6_CANDIDATE_REGEX.replace(input) { match ->
            if (isValidIpv6(match.value)) "[ip]" else match.value
        }

    private fun isValidIpv6(raw: String): Boolean {
        val unwrapped = raw.removePrefix("[").removeSuffix("]").substringBefore('%')
        return runCatching { InetAddress.getByName(unwrapped) is Inet6Address }.getOrDefault(false)
    }

    private fun redactHostnames(input: String): String =
        HOSTNAME_CANDIDATE_REGEX.replace(input) { match ->
            val candidate = match.value
            when {
                !hasHostnameTld(candidate) -> candidate
                isJavaClassName(candidate) -> candidate
                isLikelyDiagnosticFile(candidate, input, match.range) -> candidate
                else -> "[host]"
            }
        }

    private fun hasHostnameTld(candidate: String): Boolean {
        val labels = candidate.split(HOST_SEPARATOR_REGEX)
        if (labels.size < 2) return false
        if (labels.any(String::isEmpty)) return false
        if (labels.any { label -> label.startsWith('-') || label.endsWith('-') }) return false
        val tld = labels.last()
        return tld.equals("i2p", ignoreCase = true) ||
            tld.equals("onion", ignoreCase = true) ||
            tld.startsWith("xn--", ignoreCase = true) ||
            (tld.length >= MIN_TLD_LENGTH && tld.all(Char::isLetter))
    }

    private fun isJavaClassName(candidate: String): Boolean {
        val labels = candidate.split('.')
        return labels.size >= MIN_JAVA_CLASS_LABELS &&
            labels.last().firstOrNull()?.isUpperCase() == true &&
            labels.dropLast(1).all { label -> label.firstOrNull()?.isLowerCase() == true }
    }

    private fun isLikelyDiagnosticFile(candidate: String, input: String, range: IntRange): Boolean {
        val labels = candidate.split('.')
        if (labels.size != 2) return false
        val base = labels.first().lowercase()
        return when (labels.last().lowercase()) {
            "so" -> base.startsWith("lib")
            "zip" -> {
                val end = minOf(input.length, range.last + FILE_CONTEXT_LENGTH)
                val suffix = input.substring(range.last + 1, end)
                (range.first > 0 && input[range.first - 1] in FILE_PATH_SEPARATORS) ||
                    FILE_CONTEXT_REGEX.containsMatchIn(suffix)
            }
            in LOG_FILE_EXTENSIONS -> true
            else -> false
        }
    }

    private data class StructuredDepth(
        val square: Int = 0,
        val brace: Int = 0,
    ) {
        val isClosed: Boolean get() = square == 0 && brace == 0

        fun advance(char: Char): StructuredDepth = when (char) {
            '[' -> copy(square = square + 1)
            ']' -> copy(square = square - 1)
            '{' -> copy(brace = brace + 1)
            '}' -> copy(brace = brace - 1)
            else -> this
        }
    }

    private enum class QuoteMode { NONE, DOUBLE, ESCAPED_DOUBLE, SINGLE }

    private const val SENSITIVE_FIELD_NAMES =
        "password|token|secret|uuid|private[_-]?key|public[_-]?key|privatekey|publickey|" +
            "pbk|sid|short[_-]?id|server[_-]?name|sni|cert|fingerprint|network[_-]?(?:fp|fingerprint)|" +
            "host|server|domain|endpoint|address|ip|ipv4|ipv6|country|city|isp|asn|" +
            "uid|user[_-]?id|username|user|email|access[_-]?token|id[_-]?token|api[_-]?key|" +
            "authorization|bearer|cookie|session(?:[_-]?(?:id|ids))?|history|" +
            "app|apps|application|package[_-]?name|package[_-]?names|packages|package|pkg|package[_-]?hash|" +
            "profile(?:[_-]?(?:id|name))?|option(?:[_-]?id)?|local|remote|" +
            "source[_-]?host|destination[_-]?host|query|raw_input|config|resolved_config"

    private val JSON_SENSITIVE_PREFIX_REGEX = Regex("""(?i)"(?:$SENSITIVE_FIELD_NAMES)"\s*:\s*""")
    private val ESCAPED_JSON_SENSITIVE_PREFIX_REGEX =
        Regex("""(?i)\\"(?:$SENSITIVE_FIELD_NAMES)\\"\s*:\s*""")
    private val KEY_VALUE_SENSITIVE_PREFIX_REGEX =
        Regex("""(?i)(?<![\w-])($SENSITIVE_FIELD_NAMES)(\s*(?:=>|=|:)\s*)""")
    private val AUTH_HEADER_REGEX =
        Regex("""(?i)(\b(?:authorization|cookie)\s*:\s*)(?:"(?:\\.|[^"])*"|'(?:\\.|[^'])*'|[^,]+)""")
    private val BEARER_TOKEN_REGEX = Regex("""(?i)\bbearer\s+[a-z0-9._~+/=-]+""")
    private val URI_REGEX = Regex("""(?i)\b(https?|ftp|wss?)://[^\s"'<>(),;]+""")
    private val PROTOCOL_RELATIVE_URI_REGEX =
        Regex("""(?i)(?<![:\w])//(?=[^\s/"'<>(),;]+(?:@|\.|\[))[^\s"'<>(),;]+""")
    private val USERINFO_AUTHORITY_REGEX =
        Regex(
            """(?i)(?<![\w@])[\p{L}\p{N}._%+-]+:[^@\s,;]+@(?:\[[0-9a-f:.%]+]|[\p{L}\p{N}][\p{L}\p{N}.-]*)(?::\d{1,5})?""",
        )
    private val EMAIL_REGEX =
        Regex("""(?i)(?<![\w@])[\p{L}\p{N}._%+-]+@(?:[\p{L}\p{N}-]+\.)+[\p{L}a-z]{2,63}(?![\w@])""")
    private val UUID_REGEX =
        Regex("""(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])""")
    private val COMPACT_UUID_REGEX = Regex("""(?i)(?<![0-9a-f])[0-9a-f]{32}(?![0-9a-f])""")
    private val IPV4_CANDIDATE_REGEX =
        Regex("""(?<![\w.])(?:0[xX][0-9a-fA-F]+|\d+)(?:\.(?:0[xX][0-9a-fA-F]+|\d+)){1,3}(?![\w.])""")
    private val IPV6_CANDIDATE_REGEX =
        Regex("""(?<![\w:])(?:\[[0-9a-fA-F:.%]+]|[0-9a-fA-F:.]*:[0-9a-fA-F:.%:]+)(?![\w:])""")
    private val VERSION_PREFIX_REGEX = Regex("""(?i)\b(?:version|ver|v)\s*[=:]?\s*$""")
    private val HOSTNAME_CANDIDATE_REGEX =
        Regex(
            """(?<![@\p{L}\p{N}_-])(?:[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,62})?[.\u3002\uFF0E\uFF61])+[\p{L}\p{N}](?:[\p{L}\p{N}-]{0,62})?(?![\p{L}\p{N}_-])""",
        )
    private val HOST_SEPARATOR_REGEX = Regex("""[.\u3002\uFF0E\uFF61]""")
    private val FILE_CONTEXT_REGEX = Regex("""(?i)^\s*(?:loaded|opened|read|written|extracted|failed|archive|file)\b""")
    private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val LINE_BREAK_REGEX = Regex("""(?:\r\n|\r|\n)+""")
    private val PROXY_PROFILE_URI_REGEX =
        Regex("""(?i)\b(vless|vmess|trojan|ss|ssr|hysteria2?|hy2|tuic|wireguard)://[^\s"'<>]+""")

    private val JSON_VALUE_DELIMITERS = setOf(',', '}', ']')
    private val SENSITIVE_HEADER_FIELDS = setOf("authorization", "cookie")
    private val FILE_PATH_SEPARATORS = setOf('/', '\\')
    private val LOG_FILE_EXTENSIONS =
        setOf(
            "db",
            "json",
            "log",
            "txt",
            "xml",
            "apk",
            "bin",
            "dat",
            "conf",
            "cfg",
            "pem",
            "crt",
            "srs",
            "mmdb",
            "tmp",
            "lock",
        )

    private const val MIN_TLD_LENGTH = 2
    private const val MIN_JAVA_CLASS_LABELS = 3
    private const val VERSION_PREFIX_LOOKBEHIND = 24
    private const val FILE_CONTEXT_LENGTH = 24
    private const val HEX_RADIX = 16
    private const val OCTAL_RADIX = 8
    private const val DECIMAL_RADIX = 10
    private const val OCTET_MAX = 0xffL
    private const val IPV4_TWO_BYTE_MAX = 0xffffL
    private const val IPV4_THREE_BYTE_MAX = 0xffffffL
}
