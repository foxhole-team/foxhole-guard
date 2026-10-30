package com.foxhole.core.importer

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object ProfileImportXhttpSupport {
    private val queryFields = setOf(
        "type", "mode", "path", "host", "security", "sni", "fp", "pbk", "sid", "encryption", "flow",
        "network", "packetencoding", "packet_encoding", "packet-encoding", "alpn", "allowinsecure",
        "allow_insecure", "insecure", "min_version", "max_version", "curve_preferences", "ech",
    )

    fun fromQuery(query: Map<String, String>): JsonObject {
        require(query.keys.all { it.lowercase() in queryFields }) { "unsupported XHTTP option" }
        return build(query["host"], query["path"], query["mode"], query["security"])
    }

    fun fromXray(source: JsonObject?, security: String?): JsonObject {
        val settings = source ?: JsonObject(emptyMap())
        require(settings.keys.all { it in setOf("host", "path", "mode") }) { "unsupported XHTTP setting" }
        fun string(key: String): String? = settings[key]?.let {
            require(it is JsonPrimitive && it.isString) { "invalid XHTTP setting" }
            it.content
        }
        return build(string("host"), string("path"), string("mode"), security)
    }

    private fun build(host: String?, path: String?, mode: String?, security: String?): JsonObject {
        val requested = mode?.trim()?.lowercase().orEmpty()
        require(requested == "stream-one" || requested in setOf("", "auto") && security.equals("reality", true)) {
            "XHTTP implements stream-one H2; auto requires REALITY"
        }
        val normalizedPath = path?.takeIf(String::isNotEmpty)?.let { if (it.startsWith('/')) it else "/$it" } ?: "/"
        require(normalizedPath.length <= 2048 && normalizedPath.all { it.code in 0x21..0x7e && it != '#' }) {
            "invalid XHTTP path"
        }
        val authority = host?.takeIf(String::isNotEmpty)
        require(authority == null || authority.length <= 255 && authority.all { it.code in 0x21..0x7e && it !in "/?#@" }) {
            "invalid XHTTP authority"
        }
        return buildJsonObject {
            put("type", "xhttp")
            put("mode", "stream-one")
            put("path", normalizedPath)
            authority?.let { put("host", it) }
        }
    }
}
