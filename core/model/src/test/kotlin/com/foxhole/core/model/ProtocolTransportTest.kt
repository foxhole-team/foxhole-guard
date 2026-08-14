package com.foxhole.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTransportTest {
    @Test
    fun `all QUIC and packet tunnel protocols are classified as UDP transports`() {
        assertTrue(ProtocolHint.HYSTERIA2.isUdpTransport())
        assertTrue(ProtocolHint.TUIC.isUdpTransport())
        assertTrue(ProtocolHint.WIREGUARD.isUdpTransport())
    }

    @Test
    fun `stream protocols stay outside the UDP-only policy`() {
        listOf(
            ProtocolHint.VLESS,
            ProtocolHint.VMESS,
            ProtocolHint.TROJAN,
            ProtocolHint.SHADOWSOCKS,
            ProtocolHint.OUTLINE,
            ProtocolHint.NAIVE,
            ProtocolHint.ANYTLS,
        ).forEach { hint -> assertFalse(hint.name, hint.isUdpTransport()) }
    }
}
