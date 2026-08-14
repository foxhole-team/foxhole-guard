package com.foxhole.core.runtime

import com.foxhole.core.model.ProtocolHint
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class FoxCoreOverlay {
    TOR,
    I2P,
}

internal data class FoxCoreOutboundPlan(
    val primary: JsonObject,
    val named: JsonArray,
    val primaryIsPacketTunnel: Boolean,
    val overlays: Set<FoxCoreOverlay>,
)

internal object FoxCoreOutboundTranslator {
    fun translate(
        root: JsonObject,
        protocolHint: ProtocolHint,
        torExpected: Boolean,
    ): FoxCoreOutboundPlan {
        val sources = readSources(root)
        val primary = translatePrimary(sources, root, protocolHint)
        val tor =
            if (protocolHint == ProtocolHint.TOR) {
                if (!torExpected) {
                    rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, "$.outbounds")
                }
                OverlayTranslation(
                    named = emptyList(),
                    overlays = setOf(FoxCoreOverlay.TOR),
                    consumedTags = emptySet(),
                )
            } else {
                FoxCoreTorTranslator.translateOverlay(
                    sources = sources,
                    torExpected = torExpected,
                    upstream = primary.outbound,
                    upstreamIsPacketTunnel = primary.packetTunnel,
                )
            }
        val overlays = translateI2pOverlay(sources)
        validateNoOrphanedEffects(
            sources = sources,
            primaryTags = primary.consumedTags,
            overlayTags = tor.consumedTags + overlays.consumedTags,
        )
        return FoxCoreOutboundPlan(
            primary = primary.outbound,
            named = JsonArray(tor.named + overlays.named),
            primaryIsPacketTunnel = primary.packetTunnel,
            overlays = tor.overlays + overlays.overlays,
        )
    }

    private fun readSources(root: JsonObject): List<LegacyOutbound> {
        val result = mutableListOf<LegacyOutbound>()
        root["outbounds"]?.asFoxCoreArray("$.outbounds")?.forEachIndexed { index, element ->
            result +=
                readSource(
                    value = element.asFoxCoreObject("$.outbounds[$index]"),
                    path = "$.outbounds[$index]",
                    endpoint = false,
                )
        }
        root["endpoints"]?.asFoxCoreArray("$.endpoints")?.forEachIndexed { index, element ->
            result +=
                readSource(
                    value = element.asFoxCoreObject("$.endpoints[$index]"),
                    path = "$.endpoints[$index]",
                    endpoint = true,
                )
        }
        val tags = mutableSetOf<String>()
        if (result.any { !tags.add(it.tag) }) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.outbounds")
        }
        return result
    }

    private fun readSource(
        value: JsonObject,
        path: String,
        endpoint: Boolean,
    ): LegacyOutbound {
        val type = value.requiredString("type", path).lowercase()
        val tag = value.requiredString("tag", path)
        if (endpoint && type != "wireguard") {
            rejectFoxCoreConfig(FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED, path)
        }
        if (!endpoint && type == "wireguard") {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, path)
        }
        return LegacyOutbound(value, path, tag, type, endpoint)
    }

    private fun translatePrimary(
        sources: List<LegacyOutbound>,
        root: JsonObject,
        protocolHint: ProtocolHint,
    ): PrimaryTranslation {
        val finalTag =
            root["route"]
                ?.asFoxCoreObject("$.route")
                ?.optionalString("final", "$.route")
                ?: "proxy"
        val acceptedFinalTags =
            when (protocolHint) {
                ProtocolHint.LOCAL_GUARD -> setOf("direct")
                // Standalone Tor with SELECTED_APPS is a mixed policy: the selected packages have
                // explicit TCP/UDP rules to the primary Tor outbound and every other package is
                // intentionally direct. Refusing route.final=direct here killed the session before
                // the policy translator could preserve those package rules. VPN profiles still may
                // not silently acquire a direct default through this exception.
                ProtocolHint.TOR -> setOf("proxy", "direct", TOR_OVER_VPN_OUTBOUND_TAG, "tor")
                // A protocol TEST keeps the selected primary outbound but freezes every other
                // application with route.final=block. The policy translator represents that
                // default independently from the outbound registry, so rejecting `block` here
                // made every smart-profile TEST fail before native start while the same options
                // connected normally one by one.
                else -> setOf("proxy", "block", TOR_OVER_VPN_OUTBOUND_TAG, "tor")
            }
        if (finalTag !in acceptedFinalTags) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$.route.final")
        }
        val primaryTag =
            if (protocolHint == ProtocolHint.LOCAL_GUARD) {
                "direct"
            } else {
                "proxy"
            }
        val primarySource =
            sources.singleOrNull { it.tag == primaryTag }
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.outbounds")
        return if (primarySource.type == "selector") {
            translateSelector(primarySource, sources, protocolHint)
        } else {
            requireProtocolMatches(primarySource.type, protocolHint, primarySource.path)
            PrimaryTranslation(
                outbound = translateSourceOutbound(primarySource),
                packetTunnel = primarySource.type == "wireguard",
                consumedTags = setOf(primarySource.tag),
            )
        }
    }

    private fun translateSelector(
        selector: LegacyOutbound,
        sources: List<LegacyOutbound>,
        protocolHint: ProtocolHint,
    ): PrimaryTranslation {
        selector.value.requireOnlyKeys(
            setOf("type", "tag", "outbounds", "default"),
            selector.path,
        )
        val memberTags = selector.value.stringList("outbounds", selector.path)
        if (memberTags.isEmpty() || memberTags.size > MAX_SELECTOR_MEMBERS || memberTags.distinct().size != memberTags.size) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "${selector.path}.outbounds")
        }
        val members =
            memberTags.mapIndexed { index, tag ->
                sources.singleOrNull { it.tag == tag }
                    ?: rejectFoxCoreConfig(
                        FoxCoreConfigRejection.INVALID_SHAPE,
                        "${selector.path}.outbounds[$index]",
                    )
            }
        members.forEach { member ->
            requireProtocolMatches(member.type, protocolHint, member.path)
        }
        val defaultTag = selector.value.optionalString("default", selector.path) ?: memberTags.first()
        val defaultIndex = memberTags.indexOf(defaultTag)
        if (defaultIndex < 0) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "${selector.path}.default")
        }
        val packetKinds = members.map { it.type == "wireguard" }.distinct()
        if (packetKinds.size != 1) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                selector.path,
            )
        }
        if (packetKinds.single()) {
            if (members.size != 1) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                    selector.path,
                )
            }
            return PrimaryTranslation(
                outbound = translateSourceOutbound(members.single()),
                packetTunnel = true,
                consumedTags = memberTags.toSet() + selector.tag,
            )
        }
        val targetIds = members.indices.map { index -> "member-${index + 1}" }
        return PrimaryTranslation(
            outbound =
            buildJsonObject {
                put("type", "selector")
                put(
                    "members",
                    buildJsonArray {
                        members.forEachIndexed { index, member ->
                            add(
                                buildJsonObject {
                                    put("id", targetIds[index])
                                    put("outbound", translateSourceOutbound(member))
                                },
                            )
                        }
                    },
                )
                put("default", targetIds[defaultIndex])
            },
            packetTunnel = packetKinds.single(),
            consumedTags = memberTags.toSet() + selector.tag,
        )
    }

    private fun translateSourceOutbound(source: LegacyOutbound): JsonObject =
        when {
            source.endpoint -> translateFoxCoreWireGuard(source.value, source.path)
            source.type == "direct" -> {
                source.value.requireOnlyKeys(setOf("type", "tag"), source.path)
                buildJsonObject { put("type", "direct") }
            }
            source.type == "tor" -> FoxCoreTorTranslator.translateOutbound(source)
            else -> translateFoxCoreProxyOutbound(source.value, source.path)
        }

    private fun translateI2pOverlay(sources: List<LegacyOutbound>): OverlayTranslation {
        val candidates = sources.filter { it.tag == I2P_OUTBOUND_TAG }
        if (candidates.isEmpty()) {
            return OverlayTranslation(emptyList(), emptySet(), emptySet())
        }
        if (candidates.size != 1) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.outbounds")
        }
        val source = candidates.single()
        if (source.type != "socks") {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                source.path,
            )
        }
        source.value.requireOnlyKeys(
            setOf(
                "type",
                "tag",
                "server",
                "server_port",
                "version",
                "network",
                "username",
                "password",
            ),
            source.path,
        )
        val username = source.value.optionalString("username", source.path)
        val password = source.value.optionalString("password", source.path)
        validateI2pCredentials(username, password, source.path)
        val host = source.value.requiredString("server", source.path)
        val port = source.value.requiredPort("server_port", source.path)
        validateI2pEndpoint(source.value, host, source.path)
        val socketAddress = if (host.contains(':')) "[$host]:$port" else "$host:$port"
        return OverlayTranslation(
            named =
            listOf(
                buildJsonObject {
                    put("id", "i2p")
                    put(
                        "outbound",
                        buildJsonObject {
                            put("type", "i2p")
                            put("socks_address", socketAddress)
                            username?.let { put("username", it) }
                            password?.let { put("password", it) }
                        },
                    )
                },
            ),
            overlays = setOf(FoxCoreOverlay.I2P),
            consumedTags = setOf(source.tag),
        )
    }

    private fun validateI2pCredentials(
        username: String?,
        password: String?,
        path: String,
    ) {
        if ((username == null) != (password == null)) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                path,
            )
        }
        val usernameBytes = username?.toByteArray(Charsets.UTF_8)?.size
        val passwordBytes = password?.toByteArray(Charsets.UTF_8)?.size
        if (username?.isBlank() == true || password?.isBlank() == true) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
        }
        if (usernameBytes?.let { it > UBYTE_MAX } == true ||
            passwordBytes?.let { it > UBYTE_MAX } == true
        ) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
        }
    }

    private fun validateI2pEndpoint(
        source: JsonObject,
        host: String,
        path: String,
    ) {
        if (host !in setOf("127.0.0.1", "::1")) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
        }
        if (source.optionalString("version", path) !in setOf(null, "5")) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
        }
        if (source.optionalString("network", path) !in setOf(null, "tcp")) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
        }
    }

    private fun validateNoOrphanedEffects(
        sources: List<LegacyOutbound>,
        primaryTags: Set<String>,
        overlayTags: Set<String>,
    ) {
        sources.forEach { source ->
            if (source.tag in primaryTags || source.tag in overlayTags) {
                return@forEach
            }
            if (source.type in setOf("direct", "block")) {
                source.value.requireOnlyKeys(setOf("type", "tag"), source.path)
                return@forEach
            }
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                source.path,
            )
        }
    }

    private fun requireProtocolMatches(
        type: String,
        hint: ProtocolHint,
        path: String,
    ) {
        if (type !in SUPPORTED_LEGACY_PROTOCOL_TYPES) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.PROTOCOL_UNSUPPORTED, path)
        }
        val matches = type in LEGACY_TYPES_BY_PROTOCOL_HINT[hint].orEmpty()
        if (!matches) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.PROTOCOL_MISMATCH, path)
        }
    }

    private const val MAX_SELECTOR_MEMBERS = 64
    private const val UBYTE_MAX = 255
    private val SUPPORTED_LEGACY_PROTOCOL_TYPES =
        setOf(
            "vless",
            "vmess",
            "trojan",
            "shadowsocks",
            "hysteria2",
            "tuic",
            "naive",
            "anytls",
            "wireguard",
            "direct",
            "tor",
        )
    private val LEGACY_TYPES_BY_PROTOCOL_HINT =
        mapOf(
            ProtocolHint.VLESS to setOf("vless"),
            ProtocolHint.TROJAN to setOf("trojan"),
            ProtocolHint.SHADOWSOCKS to setOf("shadowsocks"),
            ProtocolHint.WIREGUARD to setOf("wireguard"),
            ProtocolHint.HYSTERIA2 to setOf("hysteria2"),
            ProtocolHint.VMESS to setOf("vmess"),
            ProtocolHint.OUTLINE to setOf("shadowsocks"),
            ProtocolHint.NAIVE to setOf("naive"),
            ProtocolHint.TUIC to setOf("tuic"),
            ProtocolHint.ANYTLS to setOf("anytls"),
            ProtocolHint.TOR to setOf("tor"),
            ProtocolHint.LOCAL_GUARD to setOf("direct"),
        )
}

internal data class LegacyOutbound(
    val value: JsonObject,
    val path: String,
    val tag: String,
    val type: String,
    val endpoint: Boolean,
)

private data class PrimaryTranslation(
    val outbound: JsonObject,
    val packetTunnel: Boolean,
    val consumedTags: Set<String>,
)

internal data class OverlayTranslation(
    val named: List<JsonObject>,
    val overlays: Set<FoxCoreOverlay>,
    val consumedTags: Set<String>,
)
