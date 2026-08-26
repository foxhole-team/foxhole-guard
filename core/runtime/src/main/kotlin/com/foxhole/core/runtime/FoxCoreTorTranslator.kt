package com.foxhole.core.runtime

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val ARTI_CLIENT_STATE_DIRECTORY = "arti-state"
internal const val ARTI_CLIENT_CACHE_DIRECTORY = "arti-cache"

internal object FoxCoreTorTranslator {
    fun translateOverlay(
        sources: List<LegacyOutbound>,
        torExpected: Boolean,
        upstream: JsonObject,
        upstreamIsPacketTunnel: Boolean,
    ): OverlayTranslation {
        val candidates =
            sources.filter { source ->
                source.type == "tor" || source.tag == TOR_OVER_VPN_OUTBOUND_TAG
            }
        if (!torExpected) {
            if (candidates.isNotEmpty()) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                    candidates.first().path,
                )
            }
            return OverlayTranslation(emptyList(), emptySet(), emptySet())
        }
        if (candidates.isEmpty()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_UNAVAILABLE, "$.outbounds")
        }
        if (candidates.size != 1) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.outbounds")
        }
        val source = candidates.single()
        if (source.type != "tor" || source.tag != TOR_OVER_VPN_OUTBOUND_TAG) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                source.path,
            )
        }
        return OverlayTranslation(
            named =
            listOf(
                buildJsonObject {
                    put("id", "tor")
                    put(
                        "outbound",
                        translateOutbound(
                            source = source,
                            upstream = upstream,
                            upstreamIsPacketTunnel = upstreamIsPacketTunnel,
                        ),
                    )
                },
            ),
            overlays = setOf(FoxCoreOverlay.TOR),
            consumedTags = setOf(source.tag),
        )
    }

    fun translateOutbound(
        source: LegacyOutbound,
        upstream: JsonObject? = null,
        upstreamIsPacketTunnel: Boolean = false,
    ): JsonObject {
        source.value.requireOnlyKeys(TOR_LEGACY_KEYS, source.path)
        source.value["extra_args"]?.asFoxCoreArray("${source.path}.extra_args")?.let { extraArgs ->
            if (extraArgs.isNotEmpty()) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                    "${source.path}.extra_args",
                )
            }
        }
        val detour = source.value.optionalString("detour", source.path)
        if (invalidTorDetour(detour, upstream, upstreamIsPacketTunnel)) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                "${source.path}.detour",
            )
        }
        val bridges = source.value.stringList("bridges", source.path)
        if (invalidTorBridges(bridges)) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                "${source.path}.bridges",
            )
        }
        val transports = translateTransports(source)
        if (transports.isNotEmpty() && bridges.isEmpty()) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                "${source.path}.pluggable_transports",
            )
        }
        val dataDirectory =
            requireSafeLegacyPath(
                source.value.requiredString("data_directory", source.path),
                "${source.path}.data_directory",
            ).trimEnd('/')
        return buildJsonObject {
            put("type", "tor")
            put("state_dir", "$dataDirectory/$ARTI_CLIENT_STATE_DIRECTORY")
            put("cache_dir", "$dataDirectory/$ARTI_CLIENT_CACHE_DIRECTORY")
            put("isolate_streams", true)
            if (detour == "proxy") {
                put(
                    "upstream",
                    concreteTorUpstream(
                        checkNotNull(upstream),
                        "${source.path}.detour",
                    ),
                )
            }
            if (bridges.isNotEmpty()) {
                put("bridges", JsonArray(bridges.map(::JsonPrimitive)))
            }
            if (transports.isNotEmpty()) {
                put("transports", JsonArray(transports))
            }
        }
    }

    private fun invalidTorDetour(
        detour: String?,
        upstream: JsonObject?,
        upstreamIsPacketTunnel: Boolean,
    ): Boolean =
        when {
            detour == null -> false
            detour != "proxy" -> true
            upstream == null -> true
            else -> upstreamIsPacketTunnel
        }

    private fun concreteTorUpstream(
        upstream: JsonObject,
        path: String,
    ): JsonObject {
        if (upstream.requiredString("type", path) != "selector") {
            return upstream
        }
        val members =
            upstream["members"]
                ?.asFoxCoreArray("$path.members")
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.members")
        val selectedId = upstream.requiredString("default", path)
        val selected =
            members
                .mapIndexed { index, value ->
                    value.asFoxCoreObject("$path.members[$index]")
                }.singleOrNull { member ->
                    member.requiredString("id", path) == selectedId
                } ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.default")
        return selected["outbound"]?.asFoxCoreObject("$path.upstream")
            ?: rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$path.upstream")
    }

    private fun translateTransports(source: LegacyOutbound): List<JsonObject> {
        val values =
            source.value["pluggable_transports"]
                ?.asFoxCoreArray("${source.path}.pluggable_transports")
                ?: return emptyList()
        if (values.size > MAX_TOR_TRANSPORTS) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                "${source.path}.pluggable_transports",
            )
        }
        val protocols = mutableSetOf<String>()
        return values.mapIndexed { index, value ->
            val path = "${source.path}.pluggable_transports[$index]"
            val transport = value.asFoxCoreObject(path)
            transport.requireOnlyKeys(TOR_TRANSPORT_KEYS, path)
            val transportProtocols = transport.stringList("protocols", path)
            val arguments = transport.stringList("arguments", path)
            val executable = requireSafeLegacyPath(transport.requiredString("path", path), "$path.path")
            if (invalidTorTransport(transportProtocols, arguments, protocols)) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED, path)
            }
            buildJsonObject {
                put("protocols", JsonArray(transportProtocols.map(::JsonPrimitive)))
                put("path", executable)
                if (arguments.isNotEmpty()) {
                    put("arguments", JsonArray(arguments.map(::JsonPrimitive)))
                }
                put("run_on_startup", transport.optionalBoolean("run_on_startup", path) ?: true)
            }
        }
    }

    private fun invalidTorBridges(bridges: List<String>): Boolean {
        val tooMany = bridges.size > MAX_TOR_BRIDGES
        val duplicated = bridges.distinct().size != bridges.size
        val malformed =
            bridges.any { bridge ->
                bridge.length > MAX_TOR_BRIDGE_LENGTH || bridge.any(Char::isISOControl)
            }
        return tooMany || duplicated || malformed
    }

    private fun invalidTorTransport(
        protocols: List<String>,
        arguments: List<String>,
        seenProtocols: MutableSet<String>,
    ): Boolean {
        val invalidProtocolCount =
            protocols.isEmpty() || protocols.size > MAX_TOR_PROTOCOLS_PER_TRANSPORT
        val invalidProtocols =
            protocols.any { protocol ->
                !TOR_TRANSPORT_PROTOCOL_PATTERN.matches(protocol) || !seenProtocols.add(protocol)
            }
        val invalidArgumentCount = arguments.size > MAX_TOR_TRANSPORT_ARGUMENTS
        val invalidArguments =
            arguments.any { argument ->
                argument.length > MAX_TOR_TRANSPORT_ARGUMENT_LENGTH || argument.any(Char::isISOControl)
            }
        return listOf(
            invalidProtocolCount,
            invalidProtocols,
            invalidArgumentCount,
            invalidArguments,
        ).any { it }
    }

    private fun requireSafeLegacyPath(
        value: String,
        path: String,
    ): String {
        val basicShapeInvalid =
            !value.startsWith('/') ||
                value.length > MAX_LEGACY_PATH_LENGTH ||
                value.contains('\u0000')
        if (basicShapeInvalid || value.split('/').any { segment -> segment == ".." }) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.OVERLAY_CONFIGURATION_UNSUPPORTED,
                path,
            )
        }
        return value
    }

    private const val MAX_LEGACY_PATH_LENGTH = 4_096
    private const val MAX_TOR_BRIDGES = 16
    private const val MAX_TOR_BRIDGE_LENGTH = 1_024
    private const val MAX_TOR_TRANSPORTS = 8
    private const val MAX_TOR_PROTOCOLS_PER_TRANSPORT = 16
    private const val MAX_TOR_TRANSPORT_ARGUMENTS = 32
    private const val MAX_TOR_TRANSPORT_ARGUMENT_LENGTH = 2_048
    private val TOR_LEGACY_KEYS =
        setOf(
            "type",
            "tag",
            "data_directory",
            "extra_args",
            "detour",
            "bridges",
            "pluggable_transports",
        )
    private val TOR_TRANSPORT_KEYS =
        setOf(
            "protocols",
            "path",
            "arguments",
            "run_on_startup",
        )
    private val TOR_TRANSPORT_PROTOCOL_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
}
