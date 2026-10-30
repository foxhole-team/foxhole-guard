package com.foxhole.guard.router

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal data class RouterCompactBasePackage(val name: String, val file: String, val sha256: String, val packageIdentity: String)
internal data class RouterCompactClient(
    val protocol: RouterInstallProtocol,
    val storage: RouterStorageMode,
    val entry: JsonObject,
    val producer: RouterInstallAsset,
    val capabilities: RouterInstallAsset,
    val slotManifest: RouterInstallAsset,
) {
    val packageName: String get() = entry.compactText("packageName")
    val packageFile: String get() = entry.compactText("packageUrl").substringAfterLast('/')
}
internal data class RouterCompactServerPackage(val name: String, val file: String, val sha256: String)
internal data class RouterCompactServer(val producer: RouterInstallAsset, val modes: Map<RouterStorageMode, RouterCompactServerPackage>)
internal data class RouterCompactPlatform(
    val id: String,
    val architecture: String,
    val supportedOpenWrt: Set<String>,
    val supportedTargets: Set<String>,
    val basePackage: RouterCompactBasePackage,
    val engineCatalog: RouterInstallAsset,
    val packageUniverse: RouterInstallAsset,
    val clients: List<RouterCompactClient>,
    val server: RouterCompactServer?,
) {
    fun modes(protocols: Set<RouterInstallProtocol>, includeServer: Boolean): Set<RouterStorageMode> =
        RouterStorageMode.entries.filter { mode -> protocols.isNotEmpty() &&
            protocols.all { id -> clients.any { it.protocol == id && it.storage == mode } } &&
            (!includeServer || server?.modes?.containsKey(mode) == true)
        }.toSet()
}
internal data class RouterCompactInstallBinding(
    val platform: RouterCompactPlatform,
    val modules: List<RouterCompactClient>,
    val server: JsonObject?,
    val budgetSha256: String,
)

internal fun parseRouterCompactPlatforms(value: JsonObject): List<RouterCompactPlatform> {
    value.compactExact("schema", "kind", "development", "defaultProtocols", "allProtocols", "platforms")
    require(value.compactLong("schema") == 2L && value.compactText("kind") == "compact-client-engines-v1" && !value.compactBoolean("development"))
    require(value.compactStrings("defaultProtocols", 1..1) == listOf("hysteria2") &&
        value.compactStrings("allProtocols", 12..12) == RouterInstallProtocol.entries.map { it.canonical }.sorted())
    val platforms = value.compactObjects("platforms", 1..16).map { platform ->
        platform.compactExact("id", "architecture", "supportedTargets", "supportedOpenWrt", "basePackage", "engineCatalog", "packageUniverse", "clients", "server")
        val architecture = platform.compactText("architecture")
        val target = requireNotNull(routerInstallTarget(architecture))
        val targets = platform.compactStrings("supportedTargets", 1..1).also { require(it == listOf(target)) }
        val versions = platform.compactStrings("supportedOpenWrt", 1..32).also { list ->
            require(list.all { it.matches(Regex("[0-9]{2}\\.[0-9]{2}\\.[0-9]{1,3}")) })
        }
        val base = platform.compactObject("basePackage")
        base.compactExact("name", "file", "sha256", "packageIdentity")
        require(base.compactText("name") == "foxhole-openwrt-client")
        val clients = platform.compactObjects("clients", 0..24).map { parseRouterCompactClient(it, architecture) }
        require(clients.map { it.protocol to it.storage }.distinct().size == clients.size)
        val server = platform["server"].takeUnless { it == JsonNull }?.let { raw ->
            val document = raw as? JsonObject ?: error("compact_server")
            document.compactExact("producer", "modes")
            val modes = document.compactObject("modes")
            require(modes.isNotEmpty() && modes.keys.all { it in setOf("flash", "ram") })
            RouterCompactServer(document.compactAsset("producer"), modes.map { (name, item) ->
                val mode = compactStorage(name)
                val fields = item as? JsonObject ?: error("compact_server_mode")
                fields.compactExact("name", "file", "sha256")
                val expected = if (mode == RouterStorageMode.FLASH) "hysteria" else "hysteria-server-ram"
                require(fields.compactText("name") == expected)
                mode to RouterCompactServerPackage(expected, fields.compactFile("file"), fields.compactHash("sha256"))
            }.toMap())
        }
        RouterCompactPlatform(platform.compactFile("id"), architecture, versions.toSet(), targets.toSet(),
            RouterCompactBasePackage(base.compactText("name"), base.compactFile("file"), base.compactHash("sha256"), base.compactHash("packageIdentity")),
            platform.compactAsset("engineCatalog"), platform.compactAsset("packageUniverse"), clients, server)
    }
    require(platforms.map { it.id }.distinct().size == platforms.size)
    val coverage = mutableSetOf<List<String>>()
    for (platform in platforms) for (target in platform.supportedTargets) for (version in platform.supportedOpenWrt) {
        require(coverage.add(listOf(platform.architecture, target, version)))
    }
    return platforms
}

internal fun parseRouterCompactClient(value: JsonObject, architecture: String): RouterCompactClient {
    value.compactExact("id", "storage", "entry", "producer", "capabilities", "slotManifest")
    val protocol = RouterInstallProtocol.entries.singleOrNull { it.canonical == value.compactText("id") } ?: error("compact_protocol")
    val storage = compactStorage(value.compactText("storage"))
    val entry = value.compactObject("entry")
    entry.compactExact(*COMPACT_ENTRY_FIELDS.toTypedArray())
    require(entry.compactText("id") == protocol.canonical && entry.compactText("storage") == storage.name && entry.compactText("architecture") == architecture)
    val version = entry.compactText("version").also { require(it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[+-][A-Za-z0-9.-]+)?"))) }
    require(entry.compactText("sourceCommit").matches(Regex("[a-f0-9]{40}")))
    for (name in COMPACT_ENTRY_HASHES) entry.compactHash(name)
    for (name in listOf("binaryBytes", "archiveBytes", "indexBytes", "installedBytes", "runtimeMemoryBytes", "reserveBytes")) entry.compactLong(name, 1L..2147483648L)
    require(entry.compactLong("binaryBytes") <= 67108864L && entry.compactLong("archiveBytes") <= 134217728L &&
        entry.compactLong("indexBytes") <= 8388608L && entry.compactLong("installedBytes") <= 536870912L && entry.compactLong("reserveBytes") >= 16777216L)
    if (storage == RouterStorageMode.FLASH) require(entry.compactHash("binarySha256") == entry.compactHash("executableSha256"))
    val packageName = "foxcore-engine-${protocol.canonical}-${storage.name.lowercase()}-${entry.compactHash("manifestSha256")}"
    val packageVersion = entry.compactText("packageVersion").also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.~+-]{0,159}"))) }
    val base = "https://github.com/foxhole-team/foxhole-openwrt-client/releases/download/v$version/"
    require(entry.compactText("packageName") == packageName && entry.compactText("packageUrl") == "$base$packageName-$packageVersion.apk" &&
        entry.compactText("indexUrl") == "${base}engines.adb")
    val producer = value.compactAsset("producer")
    val capabilities = value.compactAsset("capabilities")
    val slot = value.compactAsset("slotManifest")
    require(producer.sha256 == entry.compactHash("producerSha256") && capabilities.sha256 == entry.compactHash("capabilitiesSha256") && slot.sha256 == entry.compactHash("manifestSha256"))
    return RouterCompactClient(protocol, storage, JsonObject(entry.toMap()), producer, capabilities, slot)
}

internal fun RouterInstallCatalog.matchesCompact(binding: RouterInstallBinding, version: String): Boolean {
    val compact = binding.compact ?: return false
    if (listOf(binding.coreProducerSha256, binding.coreBinarySha256, binding.coreSourceSha256, binding.capabilitiesSha256).any { it != null }) return false
    val platform = compactPlatforms.singleOrNull { it.id == binding.variantId } ?: return false
    val modes = platform.modes(binding.selection.protocols, binding.selection.includeServer)
    val modules = platform.clients.filter { it.protocol in binding.selection.protocols && it.storage == binding.storageMode }.sortedBy { it.protocol.canonical }
    val server = compact.server
    if (server != null) {
        val serverRelease = platform.server ?: return false
        val declared = serverRelease.modes[binding.storageMode] ?: return false
        if (server.compactText("name") != declared.name || server.compactFile("file") != declared.file ||
            server.compactHash("sha256") != declared.sha256 || server.compactHash("producerSha256") != serverRelease.producer.sha256) return false
    }
    return compact.platform == platform && platform.architecture == binding.architecture && version in platform.supportedOpenWrt &&
        binding.target in platform.supportedTargets && binding.storageMode in modes && compact.modules == modules &&
        compact.budgetSha256 == binding.budgetSha256 && binding.budgetSha256.matches(COMPACT_HASH) &&
        (compact.server != null) == binding.selection.includeServer
}

internal fun compactStorage(value: String): RouterStorageMode = when (value) {
    "flash" -> RouterStorageMode.FLASH
    "ram" -> RouterStorageMode.RAM
    else -> error("compact_storage")
}
internal fun JsonObject.compactExact(vararg fields: String) { require(keys == fields.toSet()) }
internal fun JsonObject.compactObject(name: String): JsonObject = get(name) as? JsonObject ?: error("compact_object")
internal fun JsonObject.compactText(name: String): String = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("compact_text")
internal fun JsonObject.compactHash(name: String): String = compactText(name).also { require(it.matches(COMPACT_HASH)) }
internal fun JsonObject.compactFile(name: String): String = compactText(name).also { require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,159}"))) }
internal fun JsonObject.compactLong(name: String, bounds: LongRange = 0L..9007199254740991L): Long =
    ((get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: error("compact_number")).also { require(it in bounds) }
internal fun JsonObject.compactBoolean(name: String): Boolean = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: error("compact_bool")
internal fun JsonObject.compactStrings(name: String, bounds: IntRange): List<String> =
    ((get(name) as? JsonArray) ?: error("compact_strings")).also { require(it.size in bounds) }.map {
        (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content ?: error("compact_string")
    }.also { require(it.distinct().size == it.size) }
internal fun JsonObject.compactObjects(name: String, bounds: IntRange): List<JsonObject> =
    ((get(name) as? JsonArray) ?: error("compact_objects")).also { require(it.size in bounds) }.map { it as? JsonObject ?: error("compact_object") }
internal fun JsonObject.compactAsset(name: String): RouterInstallAsset = compactObject(name).let {
    it.compactExact("file", "sha256"); RouterInstallAsset(it.compactFile("file"), it.compactHash("sha256"))
}
internal val COMPACT_HASH = Regex("[a-f0-9]{64}")
private val COMPACT_ENTRY_HASHES = setOf("binarySha256", "executableSha256", "producerSha256", "capabilitiesSha256", "legalProvenanceSha256", "manifestSha256", "packageSha256", "indexSha256")
private val COMPACT_ENTRY_FIELDS = setOf("id", "storage", "version", "architecture", "sourceCommit", "binaryBytes", "archiveBytes", "indexBytes", "installedBytes", "runtimeMemoryBytes", "reserveBytes", "packageName", "packageVersion", "packageUrl", "indexUrl") + COMPACT_ENTRY_HASHES

internal fun routerCompactDocument(bytes: ByteArray, maximum: Int): JsonObject {
    require(bytes.size in 1..maximum)
    requireBoundedRouterJson(bytes, maxDepth = 16, maxNodes = 131072)
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
    CompactJsonKeys(text).validate()
    return Json.parseToJsonElement(text) as? JsonObject ?: error("compact_document")
}

/** Duplicate keys are rejected before decoding authenticated compact metadata. */
private class CompactJsonKeys(private val text: String) {
    private var offset = 0
    fun validate() { value(); whitespace(); require(offset == text.length) }
    private fun whitespace() { while (offset < text.length && text[offset].isWhitespace()) offset++ }
    private fun string(): String {
        whitespace(); require(offset < text.length && text[offset] == '"'); val start = offset++
        var escaped = false
        while (offset < text.length) {
            val char = text[offset++]
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') return (Json.parseToJsonElement(text.substring(start, offset)) as JsonPrimitive).content
        }
        error("compact_json_string")
    }
    private fun value() {
        whitespace(); require(offset < text.length)
        when (text[offset]) {
            '{' -> {
                offset++; whitespace(); val keys = mutableSetOf<String>()
                if (offset < text.length && text[offset] == '}') { offset++; return }
                while (true) {
                    require(keys.add(string())); whitespace(); require(offset < text.length && text[offset++] == ':'); value(); whitespace()
                    require(offset < text.length); val next = text[offset++]; if (next == '}') return; require(next == ',')
                }
            }
            '[' -> {
                offset++; whitespace(); if (offset < text.length && text[offset] == ']') { offset++; return }
                while (true) { value(); whitespace(); require(offset < text.length); val next = text[offset++]; if (next == ']') return; require(next == ',') }
            }
            '"' -> { string() }
            else -> { val start = offset; while (offset < text.length && text[offset] !in ",]} \t\r\n") offset++; require(offset > start) }
        }
    }
}
