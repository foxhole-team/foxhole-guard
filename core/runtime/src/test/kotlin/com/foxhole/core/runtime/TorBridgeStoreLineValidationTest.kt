package com.foxhole.core.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorBridgeStoreLineValidationTest {
    @Test
    fun `accepts well-formed transport bridge lines`() {
        assertTrue(TorBridgeStore.isBridgeLine("obfs4 37.218.245.14:38224 D9A82D2F cert=abc iat-mode=0"))
        assertTrue(TorBridgeStore.isBridgeLine("snowflake 192.0.2.3:80 2B280B23 url=https://x"))
        assertTrue(TorBridgeStore.isBridgeLine("meek_lite 192.0.2.20:80 url=https://cdn"))
    }

    @Test
    fun `rejects lines without a host-port pair`() {
        assertFalse(TorBridgeStore.isBridgeLine("obfs4 not-a-host cert=abc"))
        assertFalse(TorBridgeStore.isBridgeLine(""))
        assertFalse(TorBridgeStore.isBridgeLine("# a comment"))
    }
}
