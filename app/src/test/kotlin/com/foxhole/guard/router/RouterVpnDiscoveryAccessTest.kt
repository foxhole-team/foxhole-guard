package com.foxhole.guard.router

import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterVpnDiscoveryAccessTest {
    @Test fun stoppedPreflightClosesBeforeProtectionOrBind() {
        val operation = RouterIoOperation()
        operation.close()
        val socket = Socket()
        var protected = 0
        var bound = 0
        val failure = runCatching {
            createRouterWifiSocket({ true }, RouterSocketProtection { protected++; true }, { bound++ },
                { socket.also { operation.register(it) } })
        }.exceptionOrNull()
        assertTrue(failure is java.io.IOException)
        assertTrue(socket.isClosed)
        assertEquals(0, protected)
        assertEquals(0, bound)
    }

    @Test fun stopDuringPhysicalBindClosesTheRegisteredProbe() {
        val operation = RouterIoOperation()
        val socket = Socket()
        createRouterWifiSocket({ true }, RouterSocketProtection { true }, { operation.close() },
            { socket.also { operation.register(it) } }).use {
            assertTrue(it.isClosed)
        }
    }

    @Test fun noVpnAllowsThePhysicalSocketWithoutProtection() {
        var bound = false
        createRouterWifiSocket({ false }, null, { bound = true }).use { assertFalse(it.isClosed) }
        assertTrue(bound)
    }

    @Test fun ownedVpnAllowsAProtectedPhysicalSocket() {
        var protected = 0
        var bound = 0
        createRouterWifiSocket({ true }, RouterSocketProtection { protected++; true }, { bound++ }).use {
            assertFalse(it.isClosed)
        }
        assertEquals(1, protected)
        assertEquals(1, bound)
    }

    @Test fun foreignVpnIsRejectedBeforePhysicalBind() {
        val socket = Socket()
        var bound = false
        val failure = runCatching { createRouterWifiSocket({ true }, RouterSocketProtection { false },
            { bound = true }, { socket }) }.exceptionOrNull()
        assertTrue(failure is RouterVpnSocketUnavailable)
        assertTrue(socket.isClosed)
        assertFalse(bound)
    }

    @Test fun revokedOwnerEpochFailsBeforePhysicalBind() {
        val socket = Socket()
        var epoch = 4
        var bound = false
        val failure = runCatching { createRouterWifiSocket({ true }, RouterSocketProtection {
            val protectedEpoch = epoch
            epoch++
            protectedEpoch == epoch
        }, { bound = true }, { socket }) }.exceptionOrNull()
        assertTrue(failure is RouterVpnSocketUnavailable)
        assertTrue(socket.isClosed)
        assertFalse(bound)
    }

    @Test fun vpnAppearingDuringBindRejectsTheUnprotectedSocket() {
        val socket = Socket()
        var vpn = false
        val failure = runCatching { createRouterWifiSocket({ vpn }, null, { vpn = true }, { socket }) }.exceptionOrNull()
        assertTrue(failure is RouterVpnSocketUnavailable)
        assertTrue(socket.isClosed)
    }
}
