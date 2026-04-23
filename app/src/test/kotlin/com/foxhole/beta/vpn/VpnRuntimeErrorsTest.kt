package com.foxhole.beta.vpn

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

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
}
