package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationPolicyTest {
    @Test
    fun `accepts vpn-bound and dns-independent reachability probes as tunnel validation`() {
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_IP_REFRESH))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_VALIDATION_ENDPOINT))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VALIDATED_VPN_LITERAL_IP_ENDPOINT))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.ANDROID_VALIDATED_VPN_NETWORK))
    }

    @Test
    fun `literal ip validation is accepted even without private dns opt-in`() {
        assertTrue(
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
    fun `private dns modes keep literal ip validation available`() {
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = tunnelValidationPolicyContextFor(PrivateDnsMode.STRICT),
            ),
        )
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = tunnelValidationPolicyContextFor(PrivateDnsMode.OFF),
            ),
        )
    }

    @Test
    fun `validated vpn literal ip endpoint requires android validation and healthy tunnel evidence`() {
        assertTrue(
            acceptsValidatedVpnLiteralIpEndpointProbe(
                androidValidated = true,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            ),
        )
        assertFalse(
            acceptsValidatedVpnLiteralIpEndpointProbe(
                androidValidated = false,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            ),
        )
        assertFalse(
            acceptsValidatedVpnLiteralIpEndpointProbe(
                androidValidated = true,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = false),
            ),
        )
        assertFalse(
            acceptsValidatedVpnLiteralIpEndpointProbe(
                androidValidated = true,
                evidence =
                    TunnelValidationEvidence(
                        hasSuccessfulTunnelActivity = true,
                        fatalRuntimeMessage = "authentication failed",
                    ),
            ),
        )
    }

    @Test
    fun `android validated vpn network requires healthy tunnel activity evidence`() {
        assertTrue(
            acceptsAndroidValidatedVpnNetwork(
                androidValidated = true,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            ),
        )
        assertFalse(
            acceptsAndroidValidatedVpnNetwork(
                androidValidated = false,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = true),
            ),
        )
        assertFalse(
            acceptsAndroidValidatedVpnNetwork(
                androidValidated = true,
                evidence = TunnelValidationEvidence(hasSuccessfulTunnelActivity = false),
            ),
        )
        assertFalse(
            acceptsAndroidValidatedVpnNetwork(
                androidValidated = true,
                evidence =
                    TunnelValidationEvidence(
                        hasSuccessfulTunnelActivity = true,
                        fatalRuntimeMessage = "authentication failed",
                    ),
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
