package com.foxhole.core.importer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal

internal object ProfileImportGrpcSupport {
    private val fields =
        setOf(
            "type",
            "service_name",
            "multi_mode",
            "authority",
            "rpc_path",
            "user_agent",
            "idle_timeout",
            "idle_timeout_ms",
            "health_check_timeout",
            "ping_timeout",
            "health_check_timeout_ms",
            "permit_without_stream",
            "initial_windows_size",
        )
    private val xrayFields =
        setOf(
            "serviceName",
            "authority",
            "multiMode",
            "user_agent",
            "idle_timeout",
            "health_check_timeout",
            "permit_without_stream",
            "initial_windows_size",
        )
    private val millisecondFields = listOf("idle_timeout_ms", "health_check_timeout_ms")
    private val durationFields = listOf("idle_timeout", "health_check_timeout", "ping_timeout")

    private data class QueryTimeouts(
        val milliseconds: Boolean,
        val hasRawValues: Boolean,
        val idleSeconds: Long,
        val healthSeconds: Long,
    )

    fun fromQuery(query: Map<String, String>): JsonObject {
        val service = query.value("serviceName", "service_name", "path").orEmpty()
        val mode = query.value("mode")?.lowercase() ?: "gun"
        require(mode in setOf("", "none", "gun", "multi")) { "unsupported grpc mode" }
        val result =
            linkedMapOf<String, JsonElement>(
                "type" to JsonPrimitive("grpc"),
                "service_name" to JsonPrimitive(service),
                "multi_mode" to JsonPrimitive(mode == "multi"),
            )
        query.value("authority")?.let { result["authority"] = JsonPrimitive(it) }
        query.value("rpc_path")?.let { result["rpc_path"] = JsonPrimitive(it) }
        query.value("user_agent", "userAgent")?.let { result["user_agent"] = JsonPrimitive(it) }
        require(query.value("ping_timeout") == null) { "ping_timeout is a JSON-only grpc duration alias" }
        val timeouts = queryTimeouts(query)
        putQueryNumbers(query, result)
        applyQueryTimeouts(timeouts, result)
        return canonical(JsonObject(result), singBoxPath = false)
    }

    fun fromXray(settings: JsonObject?): JsonObject {
        val source = settings ?: JsonObject(emptyMap())
        require(source.keys.all { it in xrayFields }) { "unsupported grpc setting" }
        val result =
            linkedMapOf<String, JsonElement>(
                "type" to JsonPrimitive("grpc"),
                "service_name" to JsonPrimitive(source.stringValue("serviceName") ?: ""),
            )
        result["multi_mode"] = JsonPrimitive(source.booleanValue("multiMode") ?: false)
        source["authority"]?.let {
            val authority = source.stringValue("authority") ?: throw IllegalArgumentException("invalid grpc authority")
            result["authority"] = JsonPrimitive(authority)
        }
        result["user_agent"] = JsonPrimitive(normalizeXrayUserAgent(source.stringValue("user_agent").orEmpty()))
        putXrayTimeouts(source, result)
        source["permit_without_stream"]?.let {
            val permit = source.booleanValue("permit_without_stream")
                ?: throw IllegalArgumentException("invalid grpc boolean")
            result["permit_without_stream"] = JsonPrimitive(permit)
        }
        source["initial_windows_size"]?.let {
            result["initial_windows_size"] = JsonPrimitive(integerValue(it, "invalid grpc window").coerceAtLeast(0))
        }
        applyXrayIdleMinimum(source, result)
        return canonical(JsonObject(result), singBoxPath = false)
    }

    fun canonical(source: JsonObject, singBoxPath: Boolean): JsonObject {
        require(source.keys.all { it in fields }) { "unsupported grpc setting" }
        val service = source.stringValue("service_name") ?: ""
        validateService(service)
        val multi = source.booleanValue("multi_mode") ?: false
        val result = source.toMutableMap()
        result["type"] = JsonPrimitive("grpc")
        result["service_name"] = JsonPrimitive(service)
        result["multi_mode"] = JsonPrimitive(multi)
        validateAuthority(source, result)
        validateUserAgent(source)
        validateNumbers(source)
        validateTimeouts(source)
        applyRpcPath(source, service, singBoxPath, result)
        return JsonObject(result)
    }

    private fun queryTimeouts(query: Map<String, String>): QueryTimeouts {
        val rawIdle = query.value("idle_timeout")
        val rawHealth = query.value("health_check_timeout")
        val milliseconds = millisecondFields.any { query.value(it) != null }
        val hasRawValues = rawIdle != null || rawHealth != null
        require(!(milliseconds && hasRawValues)) { "conflicting grpc timeout aliases" }
        fun seconds(raw: String?): Long {
            val parsed =
                raw?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("grpc timeout must be integer seconds")
                } ?: 0
            return parsed.coerceAtLeast(0).toLong()
        }
        return QueryTimeouts(milliseconds, hasRawValues, seconds(rawIdle), seconds(rawHealth))
    }

    private fun putQueryNumbers(query: Map<String, String>, result: MutableMap<String, JsonElement>) {
        for (key in millisecondFields) {
            query.value(key)?.let {
                val parsed = it.toLongOrNull() ?: throw IllegalArgumentException("invalid grpc milliseconds")
                result[key] = JsonPrimitive(parsed)
            }
        }
        query.value("permit_without_stream")?.let { result["permit_without_stream"] = JsonPrimitive(uriBoolean(it)) }
        query.value("initial_windows_size")?.let {
            val parsed = it.toIntOrNull() ?: throw IllegalArgumentException("invalid grpc window")
            result["initial_windows_size"] = JsonPrimitive(parsed.coerceAtLeast(0))
        }
    }

    private fun applyQueryTimeouts(timeouts: QueryTimeouts, result: MutableMap<String, JsonElement>) {
        if (timeouts.milliseconds) {
            return
        }
        val permit = result["permit_without_stream"] == JsonPrimitive(true)
        if (!timeouts.hasRawValues && !permit) {
            return
        }
        val active = timeouts.idleSeconds > 0 || timeouts.healthSeconds > 0 || permit
        val idle =
            if (active) {
                timeouts.idleSeconds.coerceAtLeast(10)
            } else {
                0
            }
        val health =
            if (timeouts.healthSeconds == 0L) {
                20
            } else {
                timeouts.healthSeconds
            }
        result["idle_timeout"] = JsonPrimitive("${idle}s")
        result["health_check_timeout"] = JsonPrimitive("${health}s")
    }

    private fun putXrayTimeouts(source: JsonObject, result: MutableMap<String, JsonElement>) {
        for (key in listOf("idle_timeout", "health_check_timeout")) {
            source[key]?.let {
                val seconds = integerValue(it, "invalid grpc timeout")
                val effective =
                    if (key == "health_check_timeout" && seconds <= 0) {
                        20L
                    } else {
                        seconds.coerceAtLeast(0)
                    }
                require(effective <= 86400) { "grpc timeout exceeds limit" }
                result[key] = JsonPrimitive("${effective}s")
            }
        }
    }

    private fun applyXrayIdleMinimum(source: JsonObject, result: MutableMap<String, JsonElement>) {
        val idle = result["idle_timeout"]?.let { durationMillis(it) } ?: 0
        val healthExplicit = (source["health_check_timeout"] as? JsonPrimitive)
            ?.content?.toLongOrNull()?.let { it > 0 } ?: false
        val requested = healthExplicit || source.booleanValue("permit_without_stream") == true
        if (idle in 1..9999L || idle == 0L && requested) {
            result["idle_timeout"] = JsonPrimitive("10s")
        }
    }

    private fun validateService(value: String) {
        require(value.toByteArray(Charsets.UTF_8).size <= 2048 && value.none { it.isISOControl() }) {
            "invalid grpc service"
        }
    }

    private fun validateAuthority(source: JsonObject, result: MutableMap<String, JsonElement>) {
        source.stringValue("authority")?.let {
            val printable = it.all { char -> char.code in 0x21..0x7e }
            val noDelimiters = it.none { char -> char in "/?#@" }
            require(it.length <= 1024 && printable && noDelimiters) { "invalid grpc authority" }
            if (it.isEmpty()) {
                result.remove("authority")
            }
        }
    }

    private fun validateUserAgent(source: JsonObject) {
        source.stringValue("user_agent")?.let {
            val noControls = it.none { char -> char.code < 0x20 || char.code == 0x7f }
            require(it.toByteArray(Charsets.UTF_8).size <= 1024 && noControls) { "invalid grpc user agent" }
        }
    }

    private fun validateNumbers(source: JsonObject) {
        source["permit_without_stream"]?.let {
            source.booleanValue("permit_without_stream") ?: throw IllegalArgumentException("invalid grpc boolean")
        }
        source["initial_windows_size"]?.let {
            val value = integerValue(it, "invalid grpc window")
            require(value in 0..2147483647L) { "invalid grpc window" }
        }
    }

    private fun validateTimeouts(source: JsonObject) {
        for (key in durationFields) {
            source[key]?.let { durationMillis(it) }
        }
        for (key in millisecondFields) {
            source[key]?.let { validateMillisecondTimeout(source, key, it) }
        }
        if (source.containsKey("health_check_timeout") && source.containsKey("ping_timeout")) {
            val health = durationMillis(source.getValue("health_check_timeout"))
            val ping = durationMillis(source.getValue("ping_timeout"))
            require(health == ping) {
                "conflicting grpc timeout aliases"
            }
        }
    }

    private fun validateMillisecondTimeout(source: JsonObject, key: String, element: JsonElement) {
        val value = integerValue(element, "invalid grpc milliseconds")
        require(value in 0..86400000L) { "invalid grpc milliseconds" }
        val aliases =
            if (key == "idle_timeout_ms") {
                setOf("idle_timeout")
            } else {
                setOf("health_check_timeout", "ping_timeout")
            }
        require(aliases.none { source.containsKey(it) }) { "conflicting grpc timeout aliases" }
    }

    private fun applyRpcPath(
        source: JsonObject,
        service: String,
        singBoxPath: Boolean,
        result: MutableMap<String, JsonElement>,
    ) {
        val override = source.stringValue("rpc_path")
        if (override != null) {
            validateRpcPath(override)
        }
        if (singBoxPath && override == null && !source.containsKey("multi_mode")) {
            val actual = "/${escapeServiceSegment(service)}/Tun"
            validateRpcPath(actual)
            result["rpc_path"] = JsonPrimitive(actual)
        }
    }

    private fun integerValue(element: JsonElement, reason: String): Long =
        (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()
            ?: throw IllegalArgumentException(reason)

    private fun normalizeXrayUserAgent(value: String): String {
        // Coherent source-adapter policy; canonical and Mihomo values stay raw.
        val chrome =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36"
        return when (value.lowercase()) {
            "", "chrome" -> chrome
            "edge" -> "$chrome Edg/151.0.0.0"
            "firefox" -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
            "golang" -> ""
            else -> value
        }
    }

    private fun escapeServiceSegment(value: String): String =
        buildString {
            // Go net/url.PathEscape for one segment, not form/query encoding.
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val number = byte.toInt() and 255
                val char = number.toChar()
                val alphanumeric = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
                if (alphanumeric || char in "-_.~$&+:=@") {
                    append(char)
                } else {
                    append('%')
                    append("0123456789ABCDEF"[number ushr 4])
                    append("0123456789ABCDEF"[number and 15])
                }
            }
        }

    fun durationMillis(element: JsonElement): Long {
        val text = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("invalid grpc duration")
        require(text.length <= 128) { "grpc duration exceeds limit" }
        if (text == "0") {
            return 0L
        }
        val pattern = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h)")
        var offset = 0
        var millis = BigDecimal.ZERO
        for (match in pattern.findAll(text)) {
            require(match.range.first == offset) { "invalid grpc duration" }
            val factor =
                when (match.groupValues[2]) {
                    "ns" -> "0.000001"
                    "us", "µs" -> "0.001"
                    "ms" -> "1"
                    "s" -> "1000"
                    "m" -> "60000"
                    else -> "3600000"
                }
            millis = millis.add(BigDecimal(match.groupValues[1]).multiply(BigDecimal(factor)))
            offset = match.range.last + 1
        }
        require(offset > 0 && offset == text.length) { "invalid grpc duration" }
        val value =
            try {
                millis.longValueExact()
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("grpc duration precision is unsupported")
            }
        require(value in 0..86400000L) { "grpc duration exceeds limit" }
        return value
    }

    private fun validateRpcPath(value: String) {
        // RPC overrides are literal HTTP/2 origin-form paths. Percent is not
        // decoded or re-escaped; only actual header/path delimiters are refused.
        val forbidden = value.any { it.isISOControl() || it in " ?#" }
        require(value.startsWith('/') && value.toByteArray(Charsets.UTF_8).size <= 8192 && !forbidden) {
            "invalid grpc RPC path"
        }
    }

    private fun JsonObject.stringValue(key: String): String? =
        this[key]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.contentOrNull
                ?: throw IllegalArgumentException("invalid grpc string")
        }

    private fun JsonObject.booleanValue(key: String): Boolean? =
        this[key]?.let {
            (it as? JsonPrimitive)?.takeIf { value -> !value.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("invalid grpc boolean")
        }

    private fun Map<String, String>.value(vararg names: String): String? =
        names.firstNotNullOfOrNull { this[it] ?: this[it.lowercase()] }

    private fun uriBoolean(value: String): Boolean =
        when (value.lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> throw IllegalArgumentException("invalid grpc boolean")
        }
}
