package com.foxhole.beta.vpn

internal enum class TunnelValidationProbeKind {
    VPN_IP_REFRESH,
    VPN_VALIDATION_ENDPOINT,
    TUNNEL_RUNTIME_PROXY_IP_REFRESH,
    TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT,
    VALIDATED_VPN_LITERAL_IP_ENDPOINT,
    DNS_INDEPENDENT_LITERAL_IP,
}

internal data class TunnelValidationPolicyContext(
    val allowDnsIndependentLiteralIpValidation: Boolean = false,
    val hasDnsIndependentLiteralIpValidationEvidence: Boolean = false,
)

internal fun tunnelValidationPolicyContextFor(
    privateDnsMode: PrivateDnsMode?,
): TunnelValidationPolicyContext =
    TunnelValidationPolicyContext(
        allowDnsIndependentLiteralIpValidation = privateDnsMode == PrivateDnsMode.STRICT,
    )

internal fun interface TunnelValidationProbeRule {
    fun accepts(
        kind: TunnelValidationProbeKind,
        context: TunnelValidationPolicyContext,
    ): Boolean?
}

internal class TunnelValidationPolicy(
    private val rules: List<TunnelValidationProbeRule> = DefaultRules,
) {
    fun accepts(
        kind: TunnelValidationProbeKind,
        context: TunnelValidationPolicyContext = TunnelValidationPolicyContext(),
    ): Boolean =
        rules.firstNotNullOfOrNull { rule -> rule.accepts(kind, context) } ?: false

    private companion object {
        val DefaultRules =
            listOf(
                TunnelValidationProbeRule { kind, context ->
                    if (kind == TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP) {
                        context.allowDnsIndependentLiteralIpValidation &&
                            context.hasDnsIndependentLiteralIpValidationEvidence
                    } else {
                        null
                    }
                },
                TunnelValidationProbeRule { kind, _ ->
                    when (kind) {
                        TunnelValidationProbeKind.VPN_IP_REFRESH,
                        TunnelValidationProbeKind.VPN_VALIDATION_ENDPOINT,
                        TunnelValidationProbeKind.VALIDATED_VPN_LITERAL_IP_ENDPOINT,
                        -> true
                        TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_IP_REFRESH,
                        TunnelValidationProbeKind.TUNNEL_RUNTIME_PROXY_VALIDATION_ENDPOINT,
                        TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                        -> null
                    }
                },
            )
    }
}

internal fun acceptsTunnelValidationProbe(
    kind: TunnelValidationProbeKind,
    context: TunnelValidationPolicyContext = TunnelValidationPolicyContext(),
): Boolean = TunnelValidationPolicy().accepts(kind, context)

internal fun acceptsValidatedVpnLiteralIpEndpointProbe(
    androidValidated: Boolean,
    evidence: TunnelValidationEvidence?,
    context: TunnelValidationPolicyContext = TunnelValidationPolicyContext(),
): Boolean =
    androidValidated &&
        evidence?.hasSuccessfulTunnelActivity == true &&
        evidence.fatalRuntimeMessage == null &&
        acceptsTunnelValidationProbe(
            kind = TunnelValidationProbeKind.VALIDATED_VPN_LITERAL_IP_ENDPOINT,
            context = context,
        )

@Suppress("UNUSED_PARAMETER")
internal fun acceptsAndroidValidatedVpnNetwork(
    androidValidated: Boolean,
    evidence: TunnelValidationEvidence?,
    context: TunnelValidationPolicyContext = TunnelValidationPolicyContext(),
): Boolean = false
