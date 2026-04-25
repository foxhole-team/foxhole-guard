package com.foxhole.beta.vpn

internal enum class TunnelValidationProbeKind {
    VPN_IP_REFRESH,
    VPN_VALIDATION_ENDPOINT,
    DNS_INDEPENDENT_LITERAL_IP,
}

internal data class TunnelValidationPolicyContext(
    val allowDnsIndependentLiteralIpValidation: Boolean = false,
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
                        context.allowDnsIndependentLiteralIpValidation
                    } else {
                        null
                    }
                },
                TunnelValidationProbeRule { kind, _ ->
                    when (kind) {
                        TunnelValidationProbeKind.VPN_IP_REFRESH,
                        TunnelValidationProbeKind.VPN_VALIDATION_ENDPOINT,
                        -> true
                        TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP -> null
                    }
                },
            )
    }
}

internal fun acceptsTunnelValidationProbe(
    kind: TunnelValidationProbeKind,
    context: TunnelValidationPolicyContext = TunnelValidationPolicyContext(),
): Boolean = TunnelValidationPolicy().accepts(kind, context)
