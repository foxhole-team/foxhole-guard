package com.foxhole.guard.ui.cli.profiles

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.guard.ui.HomeViewModel
import com.foxhole.guard.ui.ProfileProtocolConfigEdit
import com.foxhole.guard.ui.getResolvedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class CliEditorSlot(
    val optionId: String?,
    val label: String,
    val enabled: Boolean,
    val root: JsonObject,
    val dirty: Boolean = false,
)

internal data class CliEditorProtocolRef(
    val slotIndex: Int,
    val entryIndex: Int,
    val endpoint: Boolean = false,
)

internal val cliEditorJson = Json { prettyPrint = true }
private val cliEditorCompactJson = Json { prettyPrint = false }

private val CLI_INFRA_OUTBOUND_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

internal fun JsonObject.cliOutbounds(): List<JsonObject> =
    this["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }

internal fun JsonObject.cliEndpoints(): List<JsonObject> =
    this["endpoints"]?.jsonArray.orEmpty().map { it.jsonObject }

internal fun JsonObject.cliProxyOutboundIndices(): List<Int> =
    cliOutbounds()
        .withIndex()
        .filter { (_, outbound) -> outbound.cliOutboundType() !in CLI_INFRA_OUTBOUND_TYPES }
        .map(IndexedValue<JsonObject>::index)

internal fun JsonObject.cliOutboundType(): String =
    (this["type"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()

internal fun JsonObject.cliOutboundTag(): String =
    (this["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun CliEditorSlot.entryAt(ref: CliEditorProtocolRef): JsonObject =
    if (ref.endpoint) root.cliEndpoints()[ref.entryIndex] else root.cliOutbounds()[ref.entryIndex]

internal fun CliEditorSlot.protocolEntryCount(): Int =
    root.cliProxyOutboundIndices().size + root.cliEndpoints().size

internal fun CliEditorSlot.withEntry(
    ref: CliEditorProtocolRef,
    entry: JsonObject,
): CliEditorSlot {
    val current = if (ref.endpoint) root.cliEndpoints() else root.cliOutbounds()
    if (current.getOrNull(ref.entryIndex) == entry) {
        return this
    }
    val updated = current.toMutableList().apply { this[ref.entryIndex] = entry }
    val root = if (ref.endpoint) root.withEndpoints(updated) else root.withOutbounds(updated)
    return copy(root = root, dirty = true)
}

internal fun CliEditorSlot.withoutEntry(ref: CliEditorProtocolRef): CliEditorSlot {
    val removedTag = entryAt(ref).cliOutboundTag()
    val outbounds = root.cliOutbounds()
    val endpoints = root.cliEndpoints()
    val remainingOutbounds =
        if (ref.endpoint) outbounds else outbounds.filterIndexed { position, _ -> position != ref.entryIndex }
    val remainingEndpoints =
        if (ref.endpoint) endpoints.filterIndexed { position, _ -> position != ref.entryIndex } else endpoints
    val fallbackTag =
        remainingOutbounds.firstOrNull { it.cliOutboundType() !in CLI_INFRA_OUTBOUND_TYPES }?.cliOutboundTag()
            ?: remainingEndpoints.firstOrNull()?.cliOutboundTag()
    val repaired = remainingOutbounds.map { outbound -> outbound.withoutGroupMember(removedTag, fallbackTag) }
    return copy(root = root.withOutbounds(repaired).withEndpoints(remainingEndpoints), dirty = true)
}

internal fun CliEditorSlot.serialized(): String =
    cliEditorCompactJson.encodeToString(JsonObject.serializer(), root)

private fun JsonObject.withOutbounds(outbounds: List<JsonObject>): JsonObject =
    JsonObject(this + ("outbounds" to JsonArray(outbounds)))

private fun JsonObject.withEndpoints(endpoints: List<JsonObject>): JsonObject =
    JsonObject(if (endpoints.isEmpty()) this - "endpoints" else this + ("endpoints" to JsonArray(endpoints)))

private fun JsonObject.withoutGroupMember(
    removedTag: String,
    fallbackTag: String?,
): JsonObject {
    val members = this["outbounds"] as? JsonArray ?: return this
    val kept = members.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != removedTag }
    val withMembers = JsonObject(this + ("outbounds" to JsonArray(kept.map(::JsonPrimitive))))
    val default = (this["default"] as? JsonPrimitive)?.contentOrNull
    return when {
        default != removedTag -> withMembers
        fallbackTag != null -> JsonObject(withMembers + ("default" to JsonPrimitive(fallbackTag)))
        else -> JsonObject(withMembers - "default")
    }
}

internal suspend fun loadCliEditorSlots(
    viewModel: HomeViewModel,
    profile: Profile,
): List<CliEditorSlot> {
    val requested =
        profile.protocolOptions
            .map { option -> Triple(option.id, option.displayName, option.enabled) }
            .ifEmpty { listOf(Triple(null, profile.protocolHint.name.lowercase(), true)) }
    return requested.mapNotNull { (optionId, label, enabled) ->
        runCatching { viewModel.getResolvedConfig(profile.id, optionId) }
            .mapCatching { raw -> cliEditorJson.parseToJsonElement(raw).jsonObject }
            .getOrNull()
            ?.let { root -> CliEditorSlot(optionId, label, enabled, root) }
    }
}

internal fun List<CliEditorSlot>.withSlot(
    index: Int,
    transform: (CliEditorSlot) -> CliEditorSlot,
): List<CliEditorSlot> = mapIndexed { position, slot -> if (position == index) transform(slot) else slot }

internal fun List<CliEditorSlot>.withEntryAt(
    ref: CliEditorProtocolRef,
    entry: JsonObject,
): List<CliEditorSlot> = withSlot(ref.slotIndex) { slot -> slot.withEntry(ref, entry) }

internal fun CliEditorSlot.prettyConfigText(): String =
    cliEditorJson.encodeToString(JsonObject.serializer(), root)

internal fun List<CliEditorSlot>.pendingEdits(): List<ProfileProtocolConfigEdit> =
    filter(CliEditorSlot::dirty)
        .map { slot -> ProfileProtocolConfigEdit(slot.optionId, slot.serialized()) }

internal fun cliProfileEditorHasChanges(
    originalName: String,
    draftName: String,
    slots: List<CliEditorSlot>?,
): Boolean = draftName.trim() != originalName || slots.orEmpty().any(CliEditorSlot::dirty)

internal fun List<CliEditorSlot>.protocolRefs(): List<CliEditorProtocolRef> =
    flatMapIndexed { slotIndex, slot ->
        slot.root.cliProxyOutboundIndices().map { CliEditorProtocolRef(slotIndex, it) } +
            slot.root.cliEndpoints().indices.map { CliEditorProtocolRef(slotIndex, it, endpoint = true) }
    }

internal fun cliBlankOutbound(type: String): JsonObject =
    if (type == CLI_WIREGUARD_TYPE || type == CLI_AMNEZIA_WIREGUARD_TYPE) {
        cliBlankWireGuardEndpoint(amnezia = type == CLI_AMNEZIA_WIREGUARD_TYPE)
    } else {
        buildJsonObject {
            put("type", type)
            put("tag", type)
            put("server", "")
            put("server_port", DEFAULT_NEW_PROTOCOL_PORT)
        }
    }

/** Builds the packet-tunnel shape required by the runtime translator. */
private fun cliBlankWireGuardEndpoint(amnezia: Boolean): JsonObject =
    buildJsonObject {
        put("type", CLI_WIREGUARD_TYPE)
        put("tag", CLI_WIREGUARD_TYPE)
        put("private_key", "")
        put("address", JsonArray(DEFAULT_WIREGUARD_ADDRESSES.map(::JsonPrimitive)))
        put(
            "peers",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("address", "")
                        put("port", DEFAULT_WIREGUARD_PORT)
                        put("public_key", "")
                        put("allowed_ips", JsonArray(DEFAULT_WIREGUARD_ALLOWED_IPS.map(::JsonPrimitive)))
                    },
                ),
            ),
        )
        if (amnezia) put("amnezia", buildJsonObject {})
    }

/** Keeps shared infrastructure while replacing protocol-owned endpoints. */
internal fun cliNewProtocolConfig(
    template: JsonObject,
    outbound: JsonObject,
): JsonObject {
    val tag = outbound.cliOutboundTag().ifBlank { outbound.cliOutboundType() }
    val packetTunnel = outbound.cliOutboundType() == CLI_WIREGUARD_TYPE
    val templateOutbounds = template.cliOutbounds()
    val infra =
        templateOutbounds.filter { item ->
            item.cliOutboundType() in CLI_INFRA_OUTBOUND_TYPES && !item.isGroupOutbound()
        }
    val group =
        templateOutbounds.firstOrNull(JsonObject::isGroupOutbound)
            ?: buildJsonObject {
                put("type", "selector")
                put("tag", "proxy")
            }
    val rebuiltGroup =
        JsonObject(
            group +
                ("default" to JsonPrimitive(tag)) +
                ("outbounds" to JsonArray(listOf(JsonPrimitive(tag)))),
        )
    val outbounds = if (packetTunnel) infra + rebuiltGroup else listOf(outbound) + infra + rebuiltGroup
    val rebuilt = (template - "endpoints") + ("outbounds" to JsonArray(outbounds))
    return JsonObject(
        if (packetTunnel) rebuilt + ("endpoints" to JsonArray(listOf(outbound))) else rebuilt,
    )
}

internal fun cliProtocolHintForType(type: String): ProtocolHint =
    when (type) {
        "vless" -> ProtocolHint.VLESS
        "vmess" -> ProtocolHint.VMESS
        "trojan" -> ProtocolHint.TROJAN
        "shadowsocks" -> ProtocolHint.SHADOWSOCKS
        "hysteria2" -> ProtocolHint.HYSTERIA2
        "tuic" -> ProtocolHint.TUIC
        "anytls" -> ProtocolHint.ANYTLS
        "naive" -> ProtocolHint.NAIVE
        "wireguard" -> ProtocolHint.WIREGUARD
        "amneziawg" -> ProtocolHint.WIREGUARD
        else -> ProtocolHint.CUSTOM_CONFIG
    }

private fun JsonObject.isGroupOutbound(): Boolean = cliOutboundType() in setOf("selector", "urltest")

private const val DEFAULT_NEW_PROTOCOL_PORT = 443
private const val DEFAULT_WIREGUARD_PORT = 51820
private val DEFAULT_WIREGUARD_ADDRESSES = listOf("10.0.0.2/32")
private val DEFAULT_WIREGUARD_ALLOWED_IPS = listOf("0.0.0.0/0", "::/0")
