package com.foxhole.core.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class VpnRuntimeErrorsTest {
    @Test
    fun `unwraps invocation target failures to root cause`() {
        val root = IllegalStateException("bridge init failed")
        val wrapped = InvocationTargetException(root)

        val unwrapped = unwrapVpnRuntimeFailure(wrapped)

        assertSame(root, unwrapped)
    }

    @Test
    fun `describes failures with class name when message is blank`() {
        val error = IllegalArgumentException()

        assertEquals("IllegalArgumentException", describeVpnRuntimeFailure(error))
    }

    @Test
    fun `describes tor udp compatibility failures`() {
        val error = IllegalStateException("tor udp is not supported")

        assertEquals(
            "TOR mode does not support UDP for this profile; switch TOR UDP policy to Proxy or Block.",
            describeVpnRuntimeFailure(error),
        )
    }

    @Test
    fun `describes fakeip strict private dns compatibility failures`() {
        val error = IllegalStateException("fake-ip mode conflicts with strict private dns")

        assertEquals(
            "Strict Private DNS is incompatible with FakeIP DNS mode; use Secure DNS Auto/Remote mode.",
            describeVpnRuntimeFailure(error),
        )
    }

    @Test
    fun `describes firewall proxy compatibility failures`() {
        val error = IllegalStateException("firewall tproxy is not available")

        assertEquals(
            "Firewall app rules are unavailable in the current proxy mode; use Tunnel mode for per-app firewall.",
            describeVpnRuntimeFailure(error),
        )
    }
}
