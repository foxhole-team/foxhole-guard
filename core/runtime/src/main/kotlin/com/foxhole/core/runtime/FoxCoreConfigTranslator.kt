package com.foxhole.core.runtime

import com.foxhole.core.model.FoxCoreDnsRuleSetBootstrap
import com.foxhole.core.model.FoxCoreSessionConfig
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.VpnSession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

typealias FoxCoreTranslatedConfig = FoxCoreSessionConfig

/**
 * A one-way preparation result. The normalized import document is deliberately not retained by the
 * native data plane: a successful preparation leaves only FoxCore's strict schema-v1 documents and
 * the address plan Android must use
 * when it establishes the TUN.
 */
/**
 * Stable, non-secret refusal categories suitable for UI/error-code mapping.
 *
 * [FoxCoreConfigTranslationException] never includes values from the source document in its
 * message. Profile credentials, endpoints and arbitrary JSON therefore cannot leak if the caller
 * records the exception.
 */
enum class FoxCoreConfigRejection {
    PREPARED_CONFIG_MISSING,
    MALFORMED_JSON,
    PROFILE_KIND_UNSUPPORTED,
    PROTOCOL_UNSUPPORTED,
    PROTOCOL_MISMATCH,
    INVALID_SHAPE,
    UNSUPPORTED_FIELD,
    UNSUPPORTED_TRANSPORT,
    UNSUPPORTED_SECURITY,
    EFFECTFUL_CONFIG_UNSUPPORTED,
    OVERLAY_UNAVAILABLE,
    OVERLAY_CONFIGURATION_UNSUPPORTED,
    POLICY_UNREPRESENTABLE,

    /**
     * A WireGuard/AmneziaWG profile that carries no resolver of its own.
     *
     * An L3 packet tunnel has no stream outbound to resolve through, so this app does not intercept
     * DNS on such a profile at all: it hands the resolver the profile declares to Android and lets
     * the device's queries ride the tunnel as ordinary IP packets. A profile that declares none
     * leaves nothing to advertise, and the only remaining answers — resolving beside the tunnel on
     * the underlying network, or inventing a public resolver — both put the user's lookups where
     * the user did not ask for them. Refused instead.
     */
    PACKET_TUNNEL_DNS_MISSING,
}

class FoxCoreConfigTranslationException internal constructor(
    val rejection: FoxCoreConfigRejection,
    val path: String,
    /**
     * A fixed sentence written by this repository, never a value read out of the source document.
     * It exists because a rejection name and a JSON path say what was refused but not why, and the
     * "why" is the part a user can act on.
     */
    val explanation: String? = null,
) : IllegalArgumentException(
    "${rejection.name.lowercase()} at $path" + explanation?.let { ": $it" }.orEmpty(),
)

/**
 * Strict normalized-profile → FoxCore schema-v1 preparation.
 *
 * This is not a compatibility runtime and has no fallback. It accepts only shapes for which every
 * effect can be represented in FoxCore. Unknown fields and known-but-unrepresentable behaviour are
 * refused before a native engine is involved.
 */
class FoxCoreConfigTranslator(
    private val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    },
) {
    /**
     * Runtime-only entry point. The native data plane must never parse the normalized import
     * document carried for profile editing and diagnostics.
     */
    fun requirePrepared(
        session: VpnSession,
        expectedPolicyRevision: Long? = null,
    ): FoxCoreSessionConfig =
        session.foxCoreConfig
            ?.withExpectedPolicyRevision(expectedPolicyRevision)
            ?: rejectFoxCoreConfig(
                FoxCoreConfigRejection.PREPARED_CONFIG_MISSING,
                "$.foxcore_config",
            )

    fun translate(
        session: VpnSession,
        expectedPolicyRevision: Long? = null,
        dnsRuleSetBootstrap: FoxCoreDnsRuleSetBootstrap? = null,
    ): FoxCoreSessionConfig {
        session.foxCoreConfig?.let { prepared ->
            return prepared.withExpectedPolicyRevision(expectedPolicyRevision)
        }
        requireSupportedProfileKind(session.protocolHint)
        if (expectedPolicyRevision != null && expectedPolicyRevision < 0L) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.INVALID_SHAPE,
                "$.expected_policy_revision",
            )
        }
        val root = parseRoot(session.configJson)
        root.requireOnlyKeys(
            ROOT_KEYS,
            "$",
        )
        validateNonNetworkSurfaces(root)

        val tun = translateFoxCoreTun(root)
        val controlProxy = translateFoxCoreControlProxy(root)
        val outboundPlan =
            FoxCoreOutboundTranslator.translate(
                root = root,
                protocolHint = session.protocolHint,
                torExpected = session.torActive,
            )
        val policy =
            FoxCorePolicyTranslator.translate(
                root = root,
                primaryIsPacketTunnel = outboundPlan.primaryIsPacketTunnel,
                overlays = outboundPlan.overlays,
                expectedRevision = expectedPolicyRevision,
                dnsAdvertiseOverride = tun.advertisedDnsServers.singleOrNull(),
                dnsRuleSetBootstrap = dnsRuleSetBootstrap,
                forceFakeIpDns = session.forceFakeIpDns,
                quarantineNewApps = session.quarantineNewApps,
                knownApplications = session.knownApplications,
            )
        val advertisedDnsServers =
            if (outboundPlan.primaryIsPacketTunnel) {
                // A packet tunnel carries IP packets and offers no stream outbound to resolve
                // through, so the engine no longer intercepts DNS on this shape at all — see
                // FoxCorePolicyTranslator. The resolver the profile itself declares is therefore
                // the only one there is, and Android has to be told about it: it goes on the TUN,
                // and the device's queries reach it as ordinary packets inside the tunnel, exactly
                // as any WireGuard client behaves. The policy translator has already refused a
                // profile that declares none, so this is never empty here.
                listOfNotNull(policy.packetTunnelDnsAdvertise)
            } else {
                tun.advertisedDnsServers.ifEmpty {
                    listOfNotNull(
                        policy.dns["advertise"]
                            ?.let { value -> value as? kotlinx.serialization.json.JsonPrimitive }
                            ?.content,
                    )
                }
            }
        val resolvedTun = tun.copy(advertisedDnsServers = advertisedDnsServers)
        if (resolvedTun.advertisedDnsServers.isEmpty()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.dns.advertise")
        }
        val engine =
            buildJsonObject {
                put("schema_version", FOXCORE_CONFIG_SCHEMA_VERSION)
                put("outbound", outboundPlan.primary)
                if (outboundPlan.named.isNotEmpty()) {
                    put("outbounds", outboundPlan.named)
                }
                put(
                    "tun",
                    buildJsonObject {
                        put("mtu", resolvedTun.mtu)
                        put("ipv4", resolvedTun.ipv4Address)
                        resolvedTun.ipv6Address?.let { put("ipv6", it) }
                    },
                )
                if (session.protocolHint == ProtocolHint.LOCAL_GUARD || controlProxy != null) {
                    put(
                        "runtime",
                        buildJsonObject {
                            if (session.protocolHint == ProtocolHint.LOCAL_GUARD) {
                                put("local_guard", true)
                            }
                            controlProxy?.let { put("control_proxy", it) }
                        },
                    )
                }
                put("dns", policy.dns)
                if (policy.routes.isNotEmpty()) {
                    put("routes", policy.routes)
                }
                put("traffic", policy.traffic)
            }
        return FoxCoreSessionConfig(
            engineConfigJson = json.encodeToString(JsonObject.serializer(), engine),
            policyConfigJson = json.encodeToString(JsonObject.serializer(), policy.document),
            tunPlan = resolvedTun,
            dnsRuleSetBootstrap = dnsRuleSetBootstrap,
        )
    }

    private fun FoxCoreSessionConfig.withExpectedPolicyRevision(
        expectedPolicyRevision: Long?,
    ): FoxCoreSessionConfig {
        if (expectedPolicyRevision == null) {
            return this
        }
        if (expectedPolicyRevision < 0L) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.INVALID_SHAPE,
                "$.expected_policy_revision",
            )
        }
        val policy =
            try {
                json.parseToJsonElement(policyConfigJson) as? JsonObject
                    ?: rejectFoxCoreConfig(FoxCoreConfigRejection.MALFORMED_JSON, "$.policy")
            } catch (error: FoxCoreConfigTranslationException) {
                throw error
            } catch (_: Exception) {
                rejectFoxCoreConfig(FoxCoreConfigRejection.MALFORMED_JSON, "$.policy")
            }
        val revised =
            buildJsonObject {
                put("expected_revision", expectedPolicyRevision)
                policy.forEach { (key, value) ->
                    if (key != "expected_revision") {
                        put(key, value)
                    }
                }
            }
        return copy(
            policyConfigJson = json.encodeToString(JsonObject.serializer(), revised),
        )
    }

    private fun parseRoot(source: String): JsonObject {
        if (source.isBlank() || source.toByteArray(Charsets.UTF_8).size > MAX_NORMALIZED_CONFIG_BYTES) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.MALFORMED_JSON, "$")
        }
        return try {
            json.parseToJsonElement(source) as? JsonObject
                ?: rejectFoxCoreConfig(FoxCoreConfigRejection.MALFORMED_JSON, "$")
        } catch (error: FoxCoreConfigTranslationException) {
            throw error
        } catch (_: Exception) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.MALFORMED_JSON, "$")
        }
    }

    private fun requireSupportedProfileKind(protocolHint: ProtocolHint) {
        if (protocolHint == ProtocolHint.CUSTOM_CONFIG || protocolHint == ProtocolHint.UNKNOWN) {
            rejectFoxCoreConfig(
                FoxCoreConfigRejection.PROFILE_KIND_UNSUPPORTED,
                "$.protocol_hint",
            )
        }
    }

    private fun validateNonNetworkSurfaces(root: JsonObject) {
        root["experimental"]?.asFoxCoreObject("$.experimental")?.let { experimental ->
            if (experimental.isNotEmpty()) {
                rejectFoxCoreConfig(
                    FoxCoreConfigRejection.EFFECTFUL_CONFIG_UNSUPPORTED,
                    "$.experimental",
                )
            }
        }
        root["log"]?.asFoxCoreObject("$.log")?.requireOnlyKeys(
            setOf("level", "timestamp"),
            "$.log",
        )
    }

    private companion object {
        const val FOXCORE_CONFIG_SCHEMA_VERSION = 1
        const val MAX_NORMALIZED_CONFIG_BYTES = 1024 * 1024

        val ROOT_KEYS =
            setOf(
                "log",
                "dns",
                "inbounds",
                "outbounds",
                "endpoints",
                "route",
                "experimental",
            )
    }
}
