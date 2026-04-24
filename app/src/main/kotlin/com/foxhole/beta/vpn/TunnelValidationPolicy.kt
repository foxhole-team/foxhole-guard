package com.foxhole.beta.vpn

internal enum class TunnelValidationProbeKind {
    VPN_IP_REFRESH,
    VPN_VALIDATION_ENDPOINT,
    DNS_INDEPENDENT_LITERAL_IP,
}

internal fun acceptsTunnelValidationProbe(kind: TunnelValidationProbeKind): Boolean =
    kind != TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP
