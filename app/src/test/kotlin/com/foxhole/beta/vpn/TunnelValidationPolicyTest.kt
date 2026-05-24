package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelValidationPolicyTest {
    @Test
    fun `accepts vpn-bound reachability probes as tunnel validation`() {
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_IP_REFRESH))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VPN_VALIDATION_ENDPOINT))
        assertTrue(acceptsTunnelValidationProbe(TunnelValidationProbeKind.VALIDATED_VPN_LITERAL_IP_ENDPOINT))
        assertFalse(acceptsTunnelValidationProbe(TunnelValidationProbeKind.ANDROID_VALIDATED_VPN_NETWORK))
    }

    @Test
    fun `literal ip validation requires private dns opt-in and evidence`() {
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = TunnelValidationPolicyContext(),
            ),
        )
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    TunnelValidationPolicyContext(
                        allowDnsIndependentLiteralIpValidation = true,
                    ),
            ),
        )
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    TunnelValidationPolicyContext(
                        hasDnsIndependentLiteralIpValidationEvidence = true,
                    ),
            ),
        )
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    TunnelValidationPolicyContext(
                        allowDnsIndependentLiteralIpValidation = true,
                        hasDnsIndependentLiteralIpValidationEvidence = true,
                    ),
            ),
        )
    }

    @Test
    fun `strict private dns mode opts in but does not prove literal ip validation`() {
        val strictContext = tunnelValidationPolicyContextFor(PrivateDnsMode.STRICT)

        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context = strictContext,
            ),
        )
        assertTrue(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    strictContext.copy(
                        hasDnsIndependentLiteralIpValidationEvidence = true,
                    ),
            ),
        )
        assertFalse(
            acceptsTunnelValidationProbe(
                kind = TunnelValidationProbeKind.DNS_INDEPENDENT_LITERAL_IP,
                context =
                    tunnelValidationPolicyContextFor(PrivateDnsMode.OFF).copy(
                        hasDnsIndependentLiteralIpValidationEvidence = true,
                    ),
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
    fun `android validated vpn network does not prove tunnel validation by itself`() {
        assertFalse(
            acceptsAndroidValidatedVpnNetwork(
                androidValidated = true,
                evidence = null,
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
