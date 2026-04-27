package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationPolicyTest {
    @Test
    fun `accepts only vpn-bound dns-capable probes as tunnel validation`() {
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_IP_REFRESH))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_VALIDATION_ENDPOINT))
        assertFalse(acceptsTunnelValidationProbe(TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP))
    }

    @Test
    fun `policy context must explicitly opt into literal ip validation`() {
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = TunnelValidationPolicyContext(),
            ),
        )
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    TunnelValidationPolicyContext(
                        allowDnsIndependentLiteralIpValidation = true,
                    ),
            ),
        )
    }

    @Test
    fun `strict private dns opts into literal ip validation`() {
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = tunnelValidationPolicyContextFor(PrivateDnsMode.STRICT),
            ),
        )
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = tunnelValidationPolicyContextFor(PrivateDnsMode.OFF),
            ),
        )
    }

    @Test
    fun `prefers ipv4 validation for ipv4 only wireguard config`() {
        assertTrue(
            shouldPreferIpv4TunnelValidation(
                protocolHint = ProtocolHint.WIREGUARD,
                configJson =
                    """
                    {
                      "endpoints": [
                        {
                          "type": "wireguard",
                          "address": ["10.0.0.2/32"],
                          "peers": [
                            { "address": "wg.example.com", "port": 51820, "public_key": "public" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
            ),
        )
    }

    @Test
    fun `keeps dual stack wireguard validation when config has ipv6 local address`() {
        assertFalse(
            shouldPreferIpv4TunnelValidation(
                protocolHint = ProtocolHint.WIREGUARD,
                configJson =
                    """
                    {
                      "endpoints": [
                        {
                          "type": "wireguard",
                          "address": ["10.0.0.2/32", "fd00::2/128"],
                          "peers": [
                            { "address": "wg.example.com", "port": 51820, "public_key": "public" }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
            ),
        )
    }
}
