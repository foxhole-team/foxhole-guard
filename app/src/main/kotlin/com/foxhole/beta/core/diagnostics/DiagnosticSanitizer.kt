package com.foxhole.beta.core.diagnostics

internal object DiagnosticSanitizer {
    fun normalizeForStorage(message: String): String {
        return message
            .replace(ANSI_ESCAPE_REGEX, "")
            .replace(CONTROL_CHAR_REGEX, "")
            .replace(LINE_BREAK_REGEX, " ")
            .trim()
    }

    fun sanitizeForPersistence(message: String): String = sanitizeInternal(message)

    fun sanitizeForExport(message: String): String = sanitizeInternal(message)

    fun sanitize(message: String): String = sanitizeForExport(message)

    private fun sanitizeInternal(message: String): String {
        return normalizeForStorage(message)
            .replace(PROXY_PROFILE_URI_REGEX, "[profile-uri-redacted]")
            .replace(Regex("""https?://[^\s]+"""), "https://[redacted]")
            .replace(ESCAPED_JSON_SENSITIVE_FIELD_REGEX) { result ->
                "${result.groupValues[1]}\\\"[redacted]\\\""
            }.replace(JSON_SENSITIVE_FIELD_REGEX) { result ->
                "${result.groupValues[1]}\"[redacted]\""
            }
            .replace(KEY_VALUE_SENSITIVE_FIELD_REGEX) { result ->
                "${result.groupValues[1]}${result.groupValues[2]}[redacted]"
            }
            .replace(HOSTNAME_REGEX, "[host]")
            .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""), "[ip]")
            .replace(Regex("""\b[a-f0-9:]{3,}:[a-f0-9:]+\b""", RegexOption.IGNORE_CASE), "[ip]")
            .replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""), "[uuid]")
    }

    private const val SENSITIVE_FIELD_NAMES =
        "password|token|secret|uuid|private_key|public_key|privatekey|publickey|" +
            "pbk|sid|short_id|shortid|server_name|servername|sni|cert|fingerprint|" +
            "host|server|domain|endpoint|address|ip|ipv4|ipv6|country|city|isp|asn|" +
            "uid|user[_-]?id|app|apps|application|package[_-]?name|package[_-]?names|packages|package|pkg|" +
            "profile[_-]?id|profileid|profile[_-]?name|option[_-]?id|optionid|session[_-]?id|sessionid|local|remote|" +
            "source[_-]?host|sourcehost|destination[_-]?host|destinationhost|query|raw_input|config|resolved_config"
    private val KEY_VALUE_SENSITIVE_FIELD_REGEX =
        Regex("""(?i)(?<![\w-])($SENSITIVE_FIELD_NAMES)(\s*(?:=>|=|:)\s*)("[^"]*"|'[^']*'|[^\s&]+)""")
    private val JSON_SENSITIVE_FIELD_REGEX =
        Regex("""(?i)("($SENSITIVE_FIELD_NAMES)"\s*:\s*)("[^"]*"|-?\d+(?:\.\d+)?|true|false|null)""")
    private val ESCAPED_JSON_SENSITIVE_FIELD_REGEX =
        Regex("""(?i)(\\\"($SENSITIVE_FIELD_NAMES)\\\"\s*:\s*)(\\\"[^\\\"]*\\\"|-?\d+(?:\.\d+)?|true|false|null)""")
    private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val LINE_BREAK_REGEX = Regex("""(?:\r\n|\r|\n)+""")
    private val PROXY_PROFILE_URI_REGEX =
        Regex(
            """(?i)\b(vless|vmess|trojan|ss|ssr|hysteria2?|hy2|tuic|wireguard)://[^\s"'<>]+""",
        )
    private val HOSTNAME_REGEX =
        Regex(
            """(?<![@\w-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?![\w-])""",
            RegexOption.IGNORE_CASE,
        )
}
