package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.ConnectionSnapshot
import com.foxhole.beta.core.model.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoConnectFailureClassifierTest {
    @Test
    fun `timeout with vpn network is classified as validation timeout`() {
        assertEquals(
            AutoConnectReasonCode.VALIDATION_TIMEOUT,
            classifyAutoConnectProbeFailure(
                snapshot = null,
                timedOut = true,
                vpnNetworkAvailable = true,
                dnsFailureMessage = "dns failed",
            ),
        )
    }

    @Test
    fun `timeout without vpn network is classified as handshake timeout`() {
        assertEquals(
            AutoConnectReasonCode.HANDSHAKE_TIMEOUT,
            classifyAutoConnectProbeFailure(
                snapshot = null,
                timedOut = true,
                vpnNetworkAvailable = false,
                dnsFailureMessage = "dns failed",
            ),
        )
    }

    @Test
    fun `dns failure message maps to dns failure code`() {
        assertEquals(
            AutoConnectReasonCode.DNS_FAILURE,
            classifyAutoConnectProbeFailure(
                snapshot = ConnectionSnapshot(state = ConnectionState.ERROR, message = "dns failed"),
                timedOut = false,
                vpnNetworkAvailable = true,
                dnsFailureMessage = "dns failed",
            ),
        )
    }

    @Test
    fun `structured error code wins over localized message text`() {
        assertEquals(
            AutoConnectReasonCode.DNS_FAILURE,
            classifyAutoConnectProbeFailure(
                snapshot =
                    ConnectionSnapshot(
                        state = ConnectionState.ERROR,
                        message = "локализованная ошибка проверки сети",
                        reasonCode = AutoConnectReasonCode.DNS_FAILURE,
                    ),
                timedOut = false,
                vpnNetworkAvailable = true,
                dnsFailureMessage = "dns failed",
            ),
        )
    }

    @Test
    fun `other error maps to generic connect error`() {
        assertEquals(
            AutoConnectReasonCode.CONNECT_ERROR,
            classifyAutoConnectProbeFailure(
                snapshot = ConnectionSnapshot(state = ConnectionState.ERROR, message = "permission revoked"),
                timedOut = false,
                vpnNetworkAvailable = false,
                dnsFailureMessage = "dns failed",
            ),
        )
    }
}
