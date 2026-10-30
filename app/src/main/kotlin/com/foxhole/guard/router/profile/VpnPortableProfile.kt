package com.foxhole.guard.router.profile

import com.foxhole.core.importer.exportPortableConfigs
import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

private fun hasInsecureTls(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.any { (key, value) ->
        (key.lowercase() in setOf("insecure", "allowinsecure", "allow_insecure") &&
            (value as? JsonPrimitive)?.content?.lowercase() in setOf("true", "1")) || hasInsecureTls(value)
    }
    is JsonArray -> element.any(::hasInsecureTls)
    else -> false
}

internal fun VpnProfile.withSelectedProtocol(optionId: String): VpnProfile {
    val option = protocolOptions.singleOrNull { it.id == optionId && it.enabled }
        ?: throw ProfileImportFailure()
    require(option.protocolHint in PORTABLE_PROTOCOLS) { "unsupported_profile_protocol" }
    if ((option.requiresInsecureTls || hasInsecureTls(Json.parseToJsonElement(option.normalizedConfigJson))) && !insecureTlsConsent) {
        throw InsecureTlsConsentRequired()
    }
    return copy(
        protocol = option.protocolHint.name,
        normalizedConfigJson = option.normalizedConfigJson,
        selectedProtocolOptionId = option.id
    )
}

internal fun exportVpnProfile(profile: VpnProfile, optionIds: Set<String>? = null): String {
    if (profile.protocolOptions.isEmpty()) {
        require(optionIds == null || optionIds.isEmpty()) { "unknown_profile_protocol" }
        return exportPortableConfigs(profile.name, listOf(profile.normalizedConfigJson))
    }
    require(optionIds == null || optionIds.isNotEmpty()) { "empty_profile_protocol_selection" }
    val options = profile.protocolOptions.filter { optionIds == null || it.id in optionIds }
    require(
        options.isNotEmpty() && (optionIds == null || options.map { it.id }.toSet() == optionIds)
    ) { "unknown_profile_protocol" }
    require(options.all { it.enabled && it.protocolHint in PORTABLE_PROTOCOLS }) { "unsupported_profile_protocol" }
    val ordered = options.sortedBy { if (it.id == profile.selectedProtocolOptionId) 0 else 1 }
    return exportPortableConfigs(profile.name, ordered.map { it.normalizedConfigJson })
}

internal fun exportWholeVpnProfile(profile: VpnProfile): String =
    profile.sourceUrl?.takeIf(String::isNotBlank) ?: exportVpnProfile(profile)

private val PORTABLE_PROTOCOLS = setOf(
    ProtocolHint.VLESS, ProtocolHint.VMESS, ProtocolHint.TROJAN,
    ProtocolHint.SHADOWSOCKS, ProtocolHint.OUTLINE, ProtocolHint.WIREGUARD, ProtocolHint.HYSTERIA2,
    ProtocolHint.NAIVE, ProtocolHint.TUIC, ProtocolHint.ANYTLS
)
