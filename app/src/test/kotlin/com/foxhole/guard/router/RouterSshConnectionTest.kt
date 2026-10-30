package com.foxhole.guard.router

import java.net.InetSocketAddress
import java.net.InetAddress
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterSshConnectionTest {
    private fun connection(trusted: Boolean) = RouterSshConnection(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 2222), "owned",
        if (trusted) byteArrayOf(1, 2, 3) else null,
        RouterSocketProvider { throw RouterSshFailure() },
    )

    @Test fun passwordCannotBeSubmittedToAnUntrustedIdentityAndIsCleared() {
        val value = "fixture-password".toCharArray()
        connection(false).use { client ->
            assertThrows(IllegalArgumentException::class.java) { client.connect(value) }
        }
        assertArrayEquals(CharArray(value.size), value)
    }

    @Test fun cancellationBeforeConnectStillClearsTheSecret() {
        val value = "fixture-password".toCharArray()
        val client = connection(true)
        client.close()
        assertThrows(IllegalStateException::class.java) { client.connect(value) }
        assertArrayEquals(CharArray(value.size), value)
    }

    @Test fun transportFailureClearsThePasswordAndCannotLeaveAReadableSession() {
        val value = "fixture-password".toCharArray()
        connection(true).use { client ->
            assertThrows(RouterSshFailure::class.java) { client.connect(value) }
            assertThrows(RouterSshFailure::class.java) { client.read(RouterReadCommand.BOARD) }
        }
        assertArrayEquals(CharArray(value.size), value)
    }
}
