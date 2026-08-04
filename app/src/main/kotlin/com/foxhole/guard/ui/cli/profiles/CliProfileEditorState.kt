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

/**
 * One editable unit of a profile: the full normalized config behind a protocol option (or, on a
 * profile that never got options, the profile's single resolved config). The whole config tree is
 * kept as the parsed [root] and edited in place — dns/route/inbounds/experimental and any unknown
 * key ride along untouched, and only [dirty] slots are written back on save.
 */
internal data class CliEditorSlot(
    val optionId: String?,
    val label: String,
    val enabled: Boolean,
    val root: JsonObject,
    val dirty: Boolean = false,
)

/** A protocol card of the editor: one proxy outbound inside one slot. */
internal data class CliEditorProtocolRef(
    val slotIndex: Int,
    val outboundIndex: Int,
)

internal val cliEditorJson = Json { prettyPrint = true }
private val cliEditorCompactJson = Json { prettyPrint = false }

/** Outbound types that are plumbing rather than a protocol the user picked. */
private val CLI_INFRA_OUTBOUND_TYPES = setOf("selector", "urltest", "direct", "block", "dns")

internal fun JsonObject.cliOutbounds(): List<JsonObject> =
    this["outbounds"]?.jsonArray.orEmpty().map { it.jsonObject }

internal fun JsonObject.cliProxyOutboundIndices(): List<Int> =
    cliOutbounds()
        .withIndex()
        .filter { (_, outbound) -> outbound.cliOutboundType() !in CLI_INFRA_OUTBOUND_TYPES }
        .map(IndexedValue<JsonObject>::index)

internal fun JsonObject.cliOutboundType(): String =
    (this["type"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()

internal fun JsonObject.cliOutboundTag(): String =
    (this["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun CliEditorSlot.outboundAt(index: Int): JsonObject = root.cliOutbounds()[index]

internal fun CliEditorSlot.withOutbound(
    index: Int,
    outbound: JsonObject,
): CliEditorSlot {
    val updated = root.cliOutbounds().toMutableList().apply { this[index] = outbound }
    return copy(root = root.withOutbounds(updated), dirty = true)
}

/**
 * Drops one proxy outbound from this slot's config and un-references its tag from every group
 * outbound (`selector`/`urltest`), re-pointing a `default` that named it — otherwise validation would
 * refuse the config for a dangling tag.
 */
internal fun CliEditorSlot.withoutOutbound(index: Int): CliEditorSlot {
    val outbounds = root.cliOutbounds()
    val removedTag = outbounds[index].cliOutboundTag()
    val remaining = outbounds.filterIndexed { position, _ -> position != index }
    val fallbackTag =
        remaining
            .firstOrNull { it.cliOutboundType() !in CLI_INFRA_OUTBOUND_TYPES }
            ?.cliOutboundTag()
    val repaired = remaining.map { outbound -> outbound.withoutGroupMember(removedTag, fallbackTag) }
    return copy(root = root.withOutbounds(repaired), dirty = true)
}

internal fun CliEditorSlot.serialized(): String =
    cliEditorCompactJson.encodeToString(JsonObject.serializer(), root)

private fun JsonObject.withOutbounds(outbounds: List<JsonObject>): JsonObject =
    JsonObject(this + ("outbounds" to JsonArray(outbounds)))

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

/**
 * Reads every protocol of [profile] through the repository's own resolved-config path, so the forms
 * show exactly what the runtime would start. Slots whose config cannot be read are skipped rather
 * than failing the whole editor; an empty result means the profile has nothing editable.
 */
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

internal fun List<CliEditorSlot>.withOutboundAt(
    ref: CliEditorProtocolRef,
    outbound: JsonObject,
): List<CliEditorSlot> = withSlot(ref.slotIndex) { slot -> slot.withOutbound(ref.outboundIndex, outbound) }

internal fun List<CliEditorSlot>.pendingEdits(): List<ProfileProtocolConfigEdit> =
    filter(CliEditorSlot::dirty)
        .map { slot -> ProfileProtocolConfigEdit(slot.optionId, slot.serialized()) }

internal fun List<CliEditorSlot>.protocolRefs(): List<CliEditorProtocolRef> =
    flatMapIndexed { slotIndex, slot ->
        slot.root.cliProxyOutboundIndices().map { CliEditorProtocolRef(slotIndex, it) }
    }

/** A minimal outbound of [type] — the starting point of the "blank protocol" add. */
internal fun cliBlankOutbound(type: String): JsonObject =
    buildJsonObject {
        put("type", type)
        put("tag", type)
        put("server", "")
        put("server_port", DEFAULT_NEW_PROTOCOL_PORT)
    }

/**
 * The config a newly added protocol starts from: [template]'s infrastructure (log/dns/route/inbounds
 * plus the direct/block/selector outbounds) with its proxy outbounds replaced by [outbound], so the
 * new protocol inherits the profile's routing instead of a guessed default. `endpoints` (WireGuard)
 * is dropped because it belongs to the template's own protocol.
 */
internal fun cliNewProtocolConfig(
    template: JsonObject,
    outbound: JsonObject,
): JsonObject {
    val tag = outbound.cliOutboundTag().ifBlank { outbound.cliOutboundType() }
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
    return JsonObject((template - "endpoints") + ("outbounds" to JsonArray(listOf(outbound) + infra + rebuiltGroup)))
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
        else -> ProtocolHint.CUSTOM_CONFIG
    }

private fun JsonObject.isGroupOutbound(): Boolean = cliOutboundType() in setOf("selector", "urltest")

private const val DEFAULT_NEW_PROTOCOL_PORT = 443
