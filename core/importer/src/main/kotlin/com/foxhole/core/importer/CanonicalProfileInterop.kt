package com.foxhole.core.importer

import com.foxhole.core.model.ParsedImport
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder

internal const val MAX_PORTABLE_BYTES = 4 * 1024 * 1024
internal const val MAX_PORTABLE_CONFIGS = 256

internal fun unwrapV2rayTun(input: String): String {
    require(input.length <= MAX_PORTABLE_BYTES && input.toByteArray(Charsets.UTF_8).size <= MAX_PORTABLE_BYTES) {
        "profile payload is too large"
    }
    val trimmed = input.trim()
    if (!trimmed.startsWith("v2raytun:", ignoreCase = true)) return input
    val prefix = "v2raytun://import/"
    require(trimmed.startsWith(prefix, ignoreCase = true)) { "unsupported v2rayTun action" }
    val payload = trimmed.substring(prefix.length)
    val unwrapped = if (RAW_IMPORT_PREFIX.containsMatchIn(payload) || payload.startsWith('{') || payload.startsWith('[')) {
        payload
    } else {
        URLDecoder.decode(payload.replace("+", "%2B"), "UTF-8")
    }
    require(unwrapped.isNotBlank() && !unwrapped.trim().startsWith("v2raytun:", ignoreCase = true)) {
        "nested v2rayTun import is not allowed"
    }
    require(unwrapped.toByteArray(Charsets.UTF_8).size <= MAX_PORTABLE_BYTES) { "profile payload is too large" }
    return unwrapped
}

private val RAW_IMPORT_PREFIX = Regex(
    "^(?:vless|vmess|trojan|ss|outline|wireguard|wg|awg|amnezia|amneziawg|hysteria2|hy2|naive|naive\\+https|tuic|anytls|https|http)://",
    RegexOption.IGNORE_CASE
)

internal class CanonicalProfileInterop(private val json: Json) {
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(input: String, allowUnrelatedFormats: Boolean = false, sanitize: (String) -> String): ParsedImport? {
        val trimmed = input.trim()
        if (trimmed.startsWith("[Interface]", ignoreCase = true) || (!trimmed.startsWith('{') && !trimmed.startsWith('['))) return null
        // The library consumes complete JSON values, including pretty printed concatenated documents.
        requireJsonDepth(trimmed)
        val documents = trimmed.byteInputStream(Charsets.UTF_8).use { stream ->
            json.decodeToSequence(stream, JsonObject.serializer()).take(MAX_PORTABLE_CONFIGS + 1).toList()
        }
        require(documents.isNotEmpty() && documents.size <= MAX_PORTABLE_CONFIGS) { "too many profile configurations" }
        if (requiresOtherImporter(documents, allowUnrelatedFormats)) return null
        val configs = documents.map { document ->
            // Alias conversion belongs to Xray; canonical documents use emitted TLS field names.
            requireCanonicalTlsNames(document)
            require(document.keys.all { it in ROOT_FIELDS }) { "unsupported configuration field" }
            require(document["outbounds"] is JsonArray) { "configuration outbounds are missing" }
            val definitions = definitions(document)
            require(
                definitions.isNotEmpty() && definitions.all {
                    it.string("type") in PROTOCOLS.keys + INFRASTRUCTURE_TYPES
                }
            ) { "unsupported outbound type" }
            val grpcDocument =
                JsonObject(
                    document.mapValues { (key, value) ->
                        if (key in setOf("outbounds", "endpoints") && value is JsonArray) {
                            JsonArray(
                                value.map { element ->
                                    val node = element.jsonObject
                                    val transport = node["transport"] as? JsonObject
                                    if (transport?.string("type") in setOf("grpc", "gun")) {
                                        JsonObject(
                                            node + (
                                                "transport" to ProfileImportGrpcSupport.canonical(
                                                    transport!!,
                                                    singBoxPath = true
                                                )
                                                )
                                        )
                                    } else {
                                        node
                                    }
                                }
                            )
                        } else {
                            value
                        }
                    }
                )
            json.parseToJsonElement(sanitize(grpcDocument.toString())).jsonObject
        }
        requireMaterializationBudget(configs)
        val choices = configs.flatMap(::choices)
        require(
            choices.isNotEmpty() && choices.size <= MAX_PORTABLE_CONFIGS
        ) { "configuration has no selectable protocol" }
        val options = options(choices)
        return ParsedImport(
            sourceType = ProfileSourceType.RAW_CONFIG_JSON,
            protocolHint = choices.first().second,
            displayName = "VPN",
            normalizedConfigJson = configs.first().toString(),
            nodesCount = choices.size,
            protocolOptions = options.takeIf { it.size > 1 }.orEmpty(),
            selectedProtocolOptionId = options.first().id.takeIf { options.size > 1 },
        )
    }

    private fun requiresOtherImporter(documents: List<JsonObject>, allowUnrelatedFormats: Boolean): Boolean {
        val single = documents.singleOrNull()
        return when {
            single?.containsKey("proxies") == true -> {
                requireClashOnlyDocument(single)
                true
            }
            allowUnrelatedFormats && documents.none {
                it.containsKey("bootstrap_dns") || it["outbounds"] is JsonArray
            } -> true
            single?.get("servers") is JsonArray ||
                single?.keys?.containsAll(setOf("server", "server_port", "method", "password")) == true ||
                isXrayDocument(documents) -> {
                require(
                    documents.none { it.containsKey("bootstrap_dns") }
                ) { "bootstrap DNS requires canonical profile JSON" }
                true
            }
            else -> false
        }
    }

    fun enrich(parsed: ParsedImport): ParsedImport {
        val normalized = parsed.normalizedConfigJson
        if (parsed.protocolOptions.isNotEmpty() || normalized == null || parsed.nodesCount < 2) return parsed
        val root = json.parseToJsonElement(normalized).jsonObject
        requireMaterializationBudget(listOf(root))
        val choices = choices(root)
        if (choices.size < 2) return parsed
        val options = options(choices)
        return parsed.copy(protocolOptions = options, selectedProtocolOptionId = options.first().id)
    }

    private fun requireCanonicalTlsNames(element: JsonElement) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                if (key.lowercase() in setOf("insecure", "allowinsecure", "allow_insecure")) {
                    require(key == "insecure") { "noncanonical TLS field" }
                }
                requireCanonicalTlsNames(value)
            }
            is JsonArray -> element.forEach(::requireCanonicalTlsNames)
            else -> Unit
        }
    }

    private fun isXrayDocument(documents: List<JsonObject>): Boolean {
        val outbounds = documents.singleOrNull()?.get("outbounds") as? JsonArray ?: return false
        return outbounds.any { it is JsonObject && it.containsKey("protocol") }
    }

    private fun requireMaterializationBudget(configs: List<JsonObject>) {
        var totalBytes = 0L
        var totalChoices = 0
        configs.forEach { root ->
            val finalTag = (root["route"] as? JsonObject)?.string("final")
            val all = definitions(root)
            val target = all.singleOrNull { it.string("tag") == finalTag }
            val count = if (target?.string("type") == "selector") (target["outbounds"] as? JsonArray)?.size ?: 0 else 1
            totalChoices += count
            require(totalChoices <= MAX_PORTABLE_CONFIGS) { "too many profile choices" }
            val bytes = root.toString().toByteArray(Charsets.UTF_8).size.toLong()
            val tagBytes = all.sumOf { (it["tag"]?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0).toLong() }
            totalBytes += bytes * count + tagBytes + 16L * count
            require(totalBytes <= MAX_PORTABLE_BYTES) { "profile choices exceed the materialized size limit" }
        }
    }

    private fun options(configs: List<Pair<JsonObject, ProtocolHint>>): List<StoredProfileProtocolOption> = configs.mapIndexed { index, (config, hint) ->
        StoredProfileProtocolOption(
            id = "${hint.name.lowercase()}-${index + 1}",
            displayName = hint.name,
            protocolHint = hint,
            normalizedConfigJson = config.toString(),
            requiresInsecureTls = hasInsecureTls(config),
        )
    }

    private fun choices(root: JsonObject): List<Pair<JsonObject, ProtocolHint>> {
        val finalTag = (root["route"] as? JsonObject)?.string("final") ?: return emptyList()
        val all = definitions(root)
        require(all.mapNotNull { it.string("tag") }.distinct().size == all.size) { "duplicate outbound tag" }
        val final = all.singleOrNull { it.string("tag") == finalTag } ?: return emptyList()
        val hint = PROTOCOLS[final.string("type")]
        return when {
            hint != null -> listOf(root to hint)
            final.string("type") == "selector" -> selectorChoices(root, final, all)
            else -> emptyList()
        }
    }

    private fun selectorChoices(root: JsonObject, final: JsonObject, all: List<JsonObject>): List<Pair<JsonObject, ProtocolHint>> {
        val tags = requireNotNull(final["outbounds"] as? JsonArray).map { it.jsonPrimitive.content }
        require(tags.distinct().size == tags.size) { "duplicate selector option" }
        val selected = final.string("default") ?: tags.firstOrNull()
        require(selected in tags) { "invalid selected protocol" }
        return (listOfNotNull(selected) + tags.filterNot { it == selected }).map { tag ->
            val node = all.singleOrNull { it.string("tag") == tag } ?: error("selector target is missing")
            val hint = PROTOCOLS[node.string("type")] ?: error("selector target is not a supported protocol")
            val updated = JsonObject(final + ("default" to JsonPrimitive(tag)))
            val outbounds = (root["outbounds"] as JsonArray).map { if (it == final) updated else it }
            JsonObject(root + ("outbounds" to JsonArray(outbounds))) to hint
        }
    }

    private companion object {
        val ROOT_FIELDS =
            setOf(
                "log",
                "dns",
                "inbounds",
                "outbounds",
                "endpoints",
                "route",
                "experimental",
                "bootstrap_dns"
            )
        val INFRASTRUCTURE_TYPES = setOf("selector", "direct", "block")
    }
}

internal val PROTOCOLS = mapOf(
    "vless" to ProtocolHint.VLESS, "vmess" to ProtocolHint.VMESS, "trojan" to ProtocolHint.TROJAN,
    "shadowsocks" to ProtocolHint.SHADOWSOCKS, "hysteria2" to ProtocolHint.HYSTERIA2,
    "tuic" to ProtocolHint.TUIC, "anytls" to ProtocolHint.ANYTLS, "naive" to ProtocolHint.NAIVE,
    "wireguard" to ProtocolHint.WIREGUARD,
)
internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun definitions(root: JsonObject): List<JsonObject> = listOf("outbounds", "endpoints").flatMap { key ->
    (root[key] as? JsonArray).orEmpty().map { it.jsonObject }
}
internal fun hasInsecureTls(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.any { (key, value) ->
        (
            key.lowercase() in setOf("insecure", "allowinsecure", "allow_insecure") &&
                (value as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1")
            ) || hasInsecureTls(value)
    }
    is JsonArray -> element.any(::hasInsecureTls)
    else -> false
}
internal fun requireJsonDepth(raw: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    raw.forEach { ch ->
        if (quoted) {
            if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
        } else {
            when (ch) {
                '"' -> quoted = true
                '{', '[' -> {
                    depth++
                    require(depth <= 64) { "configuration nesting is too deep" }
                }
                '}', ']' -> {
                    depth--
                    require(depth >= 0) { "invalid configuration nesting" }
                }
            }
        }
    }
    require(!quoted && depth == 0) { "incomplete configuration" }
}
