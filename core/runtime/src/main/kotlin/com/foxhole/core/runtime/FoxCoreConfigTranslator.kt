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

    PACKET_TUNNEL_DNS_MISSING,
}

class FoxCoreConfigTranslationException internal constructor(
    val rejection: FoxCoreConfigRejection,
    val path: String,

    val explanation: String? = null,
) : IllegalArgumentException(
    "${rejection.name.lowercase()} at $path" + explanation?.let { ": $it" }.orEmpty(),
)

// Rejections contain repository-owned codes and schema paths only, never values from provider configs.
class FoxCoreConfigTranslator(
    private val json: Json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    },
) {
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
        if (!session.coreTuning.isValid()) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.core_tuning")
        }
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
        val bootstrap = translateBootstrapDns(root)

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
            ).withCoreTuning(session.coreTuning)
        val advertisedDnsServers =
            if (outboundPlan.primaryIsPacketTunnel) {
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
                projectProfileBootstrapDns(
                    bootstrap,
                    listOf(outboundPlan.primary) + outboundPlan.named.map { it.asFoxCoreObject("$.outbounds") },
                )
                    ?.let { put("bootstrap_dns", it) }
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
                val runtime =
                    foxCoreRuntimeConfig(
                        session.coreTuning,
                        session.protocolHint == ProtocolHint.LOCAL_GUARD,
                        controlProxy
                    )
                if (runtime.isNotEmpty()) put("runtime", runtime)
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

    private fun translateBootstrapDns(root: JsonObject): JsonObject? =
        try {
            normalizeProfileBootstrapDns(root)
        } catch (_: IllegalArgumentException) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.INVALID_SHAPE, "$.bootstrap_dns")
        } catch (_: IllegalStateException) {
            rejectFoxCoreConfig(FoxCoreConfigRejection.POLICY_UNREPRESENTABLE, "$.bootstrap_dns")
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
                "bootstrap_dns",
            )
    }
}
