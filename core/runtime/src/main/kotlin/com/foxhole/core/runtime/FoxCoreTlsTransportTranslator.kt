@file:Suppress("MatchingDeclarationName")

package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.security.SecureRandom

internal data class FoxCoreTlsPlan(
    val tls: JsonObject,
    val reality: JsonObject?,
)

@Suppress("CyclomaticComplexMethod")
internal fun translateFoxCoreTls(
    source: JsonObject?,
    path: String,
    mandatory: Boolean,
    allowReality: Boolean,
): FoxCoreTlsPlan {
    if (source == null) {
        if (mandatory) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
        }
        return FoxCoreTlsPlan(
            tls = buildJsonObject { put("enabled", false) },
            reality = null,
        )
    }
    source.requireOnlyKeys(TLS_KEYS, path)
    val enabled = source.optionalBoolean("enabled", path) ?: mandatory
    val realitySource = source["reality"]?.asFoxCoreObject("$path.reality")
    val realityEnabled = realitySource?.optionalBoolean("enabled", "$path.reality") ?: false
    if (realityEnabled) {
        return translateRealityTls(
            source = source,
            reality = checkNotNull(realitySource),
            path = path,
            enabled = enabled,
            allowReality = allowReality,
        )
    }
    if (!enabled && source.keys.any { it !in setOf("enabled", "ech", "utls", "reality") }) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }
    val fingerprint = normalTlsFingerprint(source, path, enabled)
    validateDisabledOptionalSecurity(source, path, fingerprint)
    if (mandatory && !enabled) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }
    val pins = source.stringList("certificate_public_key_sha256", path)
    val certificatePins = source.stringList("certificate_sha256", path)
    if (certificatePins.size > 1 || certificatePins.isNotEmpty() && pins.isNotEmpty()) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.certificate_sha256")
    }
    if (pins.size > 1) {
        rejectFoxCoreConfig(
            FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
            "$path.certificate_public_key_sha256",
        )
    }
    return FoxCoreTlsPlan(
        tls =
        buildJsonObject {
            put("enabled", enabled)
            fingerprint?.let { put("fingerprint", it) }
            source.optionalString("server_name", path)?.takeIf(String::isNotBlank)?.let {
                put("server_name", it)
            }
            source.optionalBoolean("insecure", path)?.let { put("insecure", it) }
            source.stringList("alpn", path).takeIf(List<String>::isNotEmpty)?.let {
                put("alpn", JsonArray(it.map(::JsonPrimitive)))
            }
            source.optionalString("min_version", path)?.let {
                put("min_version", normalizeTlsVersion(it, "$path.min_version"))
            }
            source.optionalString("max_version", path)?.let {
                put("max_version", normalizeTlsVersion(it, "$path.max_version"))
            }
            source.stringList("curve_preferences", path).takeIf(List<String>::isNotEmpty)?.let { curves ->
                put(
                    "curve_preferences",
                    JsonArray(
                        curves.mapIndexed { index, curve ->
                            JsonPrimitive(
                                normalizeCurve(
                                    curve,
                                    "$path.curve_preferences[$index]",
                                ),
                            )
                        },
                    ),
                )
            }
            pins.singleOrNull()?.let { put("pinned_spki_sha256", it) }
            certificatePins.singleOrNull()?.let { put("pinned_certificate_sha256", it) }
        },
        reality = null,
    )
}

private fun translateRealityTls(
    source: JsonObject,
    reality: JsonObject,
    path: String,
    enabled: Boolean,
    allowReality: Boolean,
): FoxCoreTlsPlan {
    if (!allowReality || !enabled || source.optionalBoolean("insecure", path) == true) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }
    if (source.keys.any {
            it in setOf(
                "alpn",
                "min_version",
                "max_version",
                "curve_preferences",
                "certificate_public_key_sha256",
                "certificate_sha256",
                "ech",
            )
        }
    ) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }
    reality.requireOnlyKeys(REALITY_KEYS, "$path.reality")
    val utls =
        source["utls"]
            ?.asFoxCoreObject("$path.utls")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.utls")
    utls.requireOnlyKeys(setOf("enabled", "fingerprint"), "$path.utls")
    if (utls.optionalBoolean("enabled", "$path.utls") != true) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.utls")
    }
    val fingerprint =
        realityHelloProfile(utls.requiredString("fingerprint", "$path.utls"), "$path.utls")
    return FoxCoreTlsPlan(
        tls = buildJsonObject { put("enabled", false) },
        reality =
        buildJsonObject {
            put("server_name", source.requiredString("server_name", path))
            put("public_key", reality.requiredString("public_key", "$path.reality"))
            reality.optionalString("short_id", "$path.reality")?.let { put("short_id", it) }
            put("fingerprint", fingerprint)
            reality.optionalString("spider_x", "$path.reality")?.let { put("spider_x", it) }
        },
    )
}

private fun realityHelloProfile(
    requested: String,
    path: String,
): String =
    when (val name = requested.trim().lowercase()) {
        RANDOM_HELLO_PROFILE -> randomModernHelloProfile
        else ->
            REALITY_HELLO_PROFILES[name]
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }

private const val RANDOM_HELLO_PROFILE = "random"

private val REALITY_HELLO_PROFILES =
    mapOf(
        "chrome" to "chrome_151",
        "chrome_151" to "chrome_151",
        "chrome_133" to "chrome_133",
        "chrome_131" to "chrome_131",
        "edge" to "edge_85",
        "edge_85" to "edge_85",
        "safari" to "safari_26_3",
        "safari_26_3" to "safari_26_3",
        "ios" to "ios_14",
        "ios_14" to "ios_14",
        "qq" to "qq_11_1",
        "qq_11_1" to "qq_11_1",
        "firefox" to "firefox_153",
        "firefox_153" to "firefox_153",
        "firefox_148" to "firefox_148",
        "randomized" to "randomized",
    )

private val randomModernHelloProfile: String by lazy {
    RANDOM_MODERN_HELLO_PROFILES[SecureRandom().nextInt(RANDOM_MODERN_HELLO_PROFILES.size)]
}

private val RANDOM_MODERN_HELLO_PROFILES =
    listOf("chrome_151", "firefox_153", "edge_85", "safari_26_3", "ios_14")

private fun normalTlsFingerprint(
    source: JsonObject,
    path: String,
    enabled: Boolean,
): String? {
    val utls = source["utls"]?.asFoxCoreObject("$path.utls") ?: return null
    utls.requireOnlyKeys(setOf("enabled", "fingerprint"), "$path.utls")
    if (utls.optionalBoolean("enabled", "$path.utls") == false) return null
    if (!enabled) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.utls")
    }
    return when (utls.requiredString("fingerprint", "$path.utls").trim().lowercase()) {
        "chrome", "chrome_151" -> "chrome_151"
        else ->
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.UNSUPPORTED_SECURITY,
                "$path.utls.fingerprint",
            )
    }
}

private fun validateDisabledOptionalSecurity(
    source: JsonObject,
    path: String,
    fingerprint: String?,
) {
    source["ech"]?.asFoxCoreObject("$path.ech")?.let { ech ->
        ech.requireOnlyKeys(setOf("enabled"), "$path.ech")
        if (ech.optionalBoolean("enabled", "$path.ech") == true) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.ech")
        }
    }
    source["utls"]?.asFoxCoreObject("$path.utls")?.let { utls ->
        utls.requireOnlyKeys(setOf("enabled", "fingerprint"), "$path.utls")
        if (utls.optionalBoolean("enabled", "$path.utls") == true && fingerprint == null) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.utls")
        }
    }
    source["reality"]?.asFoxCoreObject("$path.reality")?.let { reality ->
        reality.requireOnlyKeys(REALITY_KEYS, "$path.reality")
        if (reality.optionalBoolean("enabled", "$path.reality") == true) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, "$path.reality")
        }
    }
}

internal fun translateFoxCoreTransport(
    source: JsonObject?,
    path: String,
): JsonObject {
    source ?: return buildJsonObject { put("type", "raw") }
    val type = source.requiredString("type", path).lowercase()
    return when (type) {
        "tcp", "raw" -> {
            source.requireOnlyKeys(setOf("type"), path)
            buildJsonObject { put("type", "raw") }
        }
        "ws", "websocket" -> translateWebSocketTransport(source, path, "websocket")
        "httpupgrade", "http_upgrade" -> translateHttpUpgradeTransport(source, path)
        "grpc" -> translateGrpcTransport(source, path)
        "xhttp", "splithttp" -> translateXhttpTransport(source, path)
        "http", "http2" -> translateHttp2Transport(source, path)
        else -> rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
}

private fun translateXhttpTransport(source: JsonObject, path: String): JsonObject {
    source.requireOnlyKeys(setOf("type", "path", "host", "mode"), path)
    if (source.optionalString("mode", path) != "stream-one") {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.mode")
    }
    val carrierPath = source.optionalString("path", path)?.takeIf(String::isNotEmpty) ?: "/"
    if (carrierPath.length > 2048 || !carrierPath.startsWith('/') || carrierPath.any { it.code !in 0x21..0x7e || it == '#' }) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.path")
    }
    val host = source.optionalString("host", path)?.takeIf(String::isNotEmpty)
    if (host != null && (host.length > 255 || host.any { it.code !in 0x21..0x7e || it in "/?#@" })) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.host")
    }
    return buildJsonObject {
        put("type", "xhttp")
        put("mode", "stream-one")
        put("path", carrierPath)
        host?.let { put("host", it) }
    }
}

private fun translateWebSocketTransport(
    source: JsonObject,
    path: String,
    targetType: String,
): JsonObject {
    source.requireOnlyKeys(setOf("type", "path", "headers"), path)
    val headers = translateCarrierHeaders(source["headers"], "$path.headers")
    val hostEntry = headers.entries.firstOrNull { it.key.equals("host", ignoreCase = true) }
    val customHeaders = headers.filterKeys { !it.equals("host", ignoreCase = true) }
    return buildJsonObject {
        put("type", targetType)
        put("path", source.optionalString("path", path)?.takeIf(String::isNotBlank) ?: "/")
        hostEntry?.value?.let { put("host", it) }
        if (customHeaders.isNotEmpty()) {
            put("headers", JsonObject(customHeaders))
        }
    }
}

private fun translateHttpUpgradeTransport(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(setOf("type", "path", "host", "headers"), path)
    val headers = translateCarrierHeaders(source["headers"], "$path.headers")
    return buildJsonObject {
        put("type", "http_upgrade")
        put("path", source.optionalString("path", path)?.takeIf(String::isNotBlank) ?: "/")
        source.optionalString("host", path)?.takeIf(String::isNotBlank)?.let { put("host", it) }
        if (headers.isNotEmpty()) {
            put("headers", headers)
        }
    }
}

private fun translateGrpcTransport(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(GRPC_TRANSPORT_KEYS, path)
    val service = readGrpcServiceName(source, path)
    val idle = readGrpcDurationAliases(source, path, "idle_timeout", "idle_timeout_ms")
    val health =
        readGrpcDurationAliases(
            source,
            path,
            "health_check_timeout",
            "ping_timeout",
            "health_check_timeout_ms",
        )
    val authority = readGrpcAuthority(source, path)
    val agent = readGrpcUserAgent(source, path)
    val rpc = source.optionalString("rpc_path", path)?.also { validateGrpcRpcPath(it, "$path.rpc_path") }
    val window = readGrpcInitialWindow(source, path)
    return buildJsonObject {
        put("type", "grpc")
        put("service_name", service)
        source.optionalBoolean("multi_mode", path)?.let { put("multi_mode", it) }
        authority?.takeIf(String::isNotEmpty)?.let { put("authority", it) }
        agent?.let { put("user_agent", it) }
        rpc?.let { put("rpc_path", it) }
        idle?.let { put("idle_timeout_ms", it) }
        health?.let { put("health_check_timeout_ms", it) }
        source.optionalBoolean("permit_without_stream", path)?.let { put("permit_without_stream", it) }
        window?.let { put("initial_windows_size", it) }
    }
}

private fun readGrpcServiceName(
    source: JsonObject,
    path: String,
): String {
    val service = source.optionalString("service_name", path) ?: ""
    if (service.toByteArray(Charsets.UTF_8).size > 2048 || service.any { it.isISOControl() }) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.service_name")
    }
    return service
}

private fun readGrpcDurationAliases(
    source: JsonObject,
    path: String,
    vararg aliases: String,
): Long? {
    val values = aliases.mapNotNull { key -> readGrpcDurationAlias(source, path, key) }
    val present = aliases.filter { it in source }
    val hasMilliseconds = present.any { it.endsWith("_ms") }
    val hasDurationText = present.any { !it.endsWith("_ms") }
    if (hasMilliseconds && hasDurationText) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    if (values.distinct().size > 1) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    return values.firstOrNull()
}

private fun readGrpcDurationAlias(
    source: JsonObject,
    path: String,
    key: String,
): Long? {
    if (key !in source) return null
    val fieldPath = "$path.$key"
    if (key.endsWith("_ms")) {
        val value =
            source.optionalLong(key, path)
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, fieldPath)
        return validateGrpcDurationMillis(value, fieldPath)
    }
    val value =
        source.optionalString(key, path)
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, fieldPath)
    return grpcDurationMillis(value, fieldPath)
}

private fun readGrpcAuthority(
    source: JsonObject,
    path: String,
): String? {
    val authority = source.optionalString("authority", path) ?: return null
    if (authority.isEmpty()) return authority
    if (authority.length > 1024 || authority.any(::invalidGrpcAuthorityCharacter)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.authority")
    }
    return authority
}

private fun invalidGrpcAuthorityCharacter(value: Char): Boolean =
    value.code !in 0x21..0x7e || value in "/?#@"

private fun readGrpcUserAgent(
    source: JsonObject,
    path: String,
): String? {
    val agent = source.optionalString("user_agent", path) ?: return null
    if (agent.toByteArray(Charsets.UTF_8).size > 1024 || agent.any(::invalidGrpcUserAgentCharacter)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.user_agent")
    }
    return agent
}

private fun invalidGrpcUserAgentCharacter(value: Char): Boolean =
    value.code < 0x20 || value.code == 0x7f

private fun readGrpcInitialWindow(
    source: JsonObject,
    path: String,
): Long? {
    val value = source.optionalLong("initial_windows_size", path) ?: return null
    if (value !in 0..2147483647L) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, "$path.initial_windows_size")
    }
    return value
}

private fun grpcDurationMillis(
    value: String,
    path: String,
): Long {
    if (value.length > 128) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    if (value == "0") return 0L
    val pattern = Regex("([0-9]+(?:\\.[0-9]+)?)(ns|us|µs|ms|s|m|h)")
    var offset = 0
    var millis = BigDecimal.ZERO
    for (match in pattern.findAll(value)) {
        if (match.range.first != offset) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
        }
        val factor = grpcDurationUnitFactor(match.groupValues[2])
        millis = millis.add(BigDecimal(match.groupValues[1]).multiply(factor))
        offset = match.range.last + 1
    }
    if (offset == 0 || offset != value.length) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    return exactGrpcDurationMillis(millis, path)
}

private fun grpcDurationUnitFactor(unit: String): BigDecimal =
    BigDecimal(
        when (unit) {
            "ns" -> "0.000001"
            "us", "µs" -> "0.001"
            "ms" -> "1"
            "s" -> "1000"
            "m" -> "60000"
            else -> "3600000"
        },
    )

private fun exactGrpcDurationMillis(
    millis: BigDecimal,
    path: String,
): Long {
    val result =
        try {
            millis.longValueExact()
        } catch (_: ArithmeticException) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
        }
    return validateGrpcDurationMillis(result, path)
}

private fun validateGrpcDurationMillis(
    value: Long,
    path: String,
): Long {
    if (value !in 0..86400000L) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
    return value
}

private fun validateGrpcRpcPath(
    value: String,
    path: String,
) {
    if (!value.startsWith('/') || value.toByteArray(Charsets.UTF_8).size > 8192 || value.any(::invalidGrpcRpcPathCharacter)) {
        rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_TRANSPORT, path)
    }
}

private fun invalidGrpcRpcPathCharacter(value: Char): Boolean =
    when (value) {
        ' ', '?', '#' -> true
        else -> value.isISOControl()
    }

private val GRPC_TRANSPORT_KEYS =
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

private fun translateHttp2Transport(
    source: JsonObject,
    path: String,
): JsonObject {
    source.requireOnlyKeys(setOf("type", "host", "path", "method"), path)
    val hosts =
        when (val host = source["host"]) {
            null -> emptyList()
            is JsonArray ->
                host.mapIndexed { index, item ->
                    item.asFoxCoreString("$path.host[$index]")
                }
            else -> listOf(host.asFoxCoreString("$path.host"))
        }
    return buildJsonObject {
        put("type", "http2")
        if (hosts.isNotEmpty()) {
            put("host", JsonArray(hosts.map(::JsonPrimitive)))
        }
        put("path", source.optionalString("path", path)?.takeIf(String::isNotBlank) ?: "/")
        put("method", source.optionalString("method", path)?.takeIf(String::isNotBlank) ?: "PUT")
    }
}

private fun translateCarrierHeaders(
    element: kotlinx.serialization.json.JsonElement?,
    path: String,
): JsonObject {
    element ?: return JsonObject(emptyMap())
    val source = element.asFoxCoreObject(path)
    val translated =
        source.mapValues { (name, value) ->
            value.asFoxCoreString("$path.$name").let(::JsonPrimitive)
        }
    return JsonObject(translated)
}

private fun normalizeTlsVersion(
    value: String,
    path: String,
): String =
    when (value.trim().lowercase().removePrefix("tls")) {
        "1.2", "v1.2" -> "1.2"
        "1.3", "v1.3" -> "1.3"
        else -> rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }

private fun normalizeCurve(
    value: String,
    path: String,
): String =
    when (value.trim().lowercase()) {
        "x25519" -> "x25519"
        "secp256r1", "p-256", "p256" -> "secp256r1"
        "secp384r1", "p-384", "p384" -> "secp384r1"
        else -> rejectFoxCoreConfig(FoxCoreConfigRejection.UNSUPPORTED_SECURITY, path)
    }

private val TLS_KEYS =
    setOf(
        "enabled",
        "server_name",
        "insecure",
        "alpn",
        "min_version",
        "max_version",
        "curve_preferences",
        "certificate_public_key_sha256",
        "certificate_sha256",
        "ech",
        "utls",
        "reality",
    )

private val REALITY_KEYS =
    setOf(
        "enabled",
        "public_key",
        "short_id",
        "spider_x",
    )
