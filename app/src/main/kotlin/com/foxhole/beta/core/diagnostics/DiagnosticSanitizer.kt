package com.foxhole.beta.core.diagnostics

internal object DiagnosticSanitizer {
    fun normalizeForStorage(message: String): String {
        return message
            .replace(ANSI_ESCAPE_REGEX, "")
            .replace(CONTROL_CHAR_REGEX, "")
            .replace(LINE_BREAK_REGEX, " ")
            .trim()
    }

    fun sanitize(message: String): String {
        return normalizeForStorage(message)
            .replace(Regex("""https?://[^\s]+"""), "https://[redacted]")
            .replace(HOSTNAME_REGEX, "[host]")
            .replace(Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b"""), "[ip]")
            .replace(Regex("""\b[a-f0-9:]{3,}:[a-f0-9:]+\b""", RegexOption.IGNORE_CASE), "[ip]")
            .replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""), "[uuid]")
            .replace(Regex("""(?i)(password|token|secret|uuid|private_key|public_key|sni|host|server|domain|endpoint|country|city|isp)=([^&\s]+)"""), "$1=[redacted]")
            .replace(Regex("""(?i)(uid|user[_-]?id|package[_-]?name|package|pkg|application)=([^&\s]+)"""), "$1=[redacted]")
            .replace(Regex("""(?i)(profile[_-]?id|profile[_-]?name)=([^&\s]+)"""), "$1=[redacted]")
            .replace(Regex("""(?i)(query|raw_input|config|resolved_config)=([^&\s]+)"""), "$1=[redacted]")
    }

    private val ANSI_ESCAPE_REGEX = Regex("""\u001B\[[0-?]*[ -/]*[@-~]""")
    private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
    private val LINE_BREAK_REGEX = Regex("""(?:\r\n|\r|\n)+""")
    private val HOSTNAME_REGEX =
        Regex(
            """(?<![@\w-])(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}(?![\w-])""",
            RegexOption.IGNORE_CASE,
        )
}
