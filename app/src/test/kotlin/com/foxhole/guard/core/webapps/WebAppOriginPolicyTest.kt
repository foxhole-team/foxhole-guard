package com.foxhole.guard.core.webapps

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.TrafficMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WebAppOriginPolicyTest {
    @Test
    fun `configured host and its own subdomains are allowed`() {
        assertTrue(isAllowedWebAppUrl("https://app.example.com/inbox", "https://app.example.com/next"))
        assertTrue(isAllowedWebAppUrl("https://example.com", "https://cdn.example.com/assets"))
    }

    @Test
    fun `parents siblings and public suffix tenants are blocked`() {
        assertFalse(isAllowedWebAppUrl("https://app.example.com", "https://example.com"))
        assertFalse(isAllowedWebAppUrl("https://app.example.com", "https://evil.example.com"))
        assertFalse(isAllowedWebAppUrl("https://alice.github.io", "https://bob.github.io"))
        assertFalse(isAllowedWebAppUrl("https://shop.example.co.uk", "https://evil.co.uk"))
    }

    @Test
    fun `scheme and port changes fail closed`() {
        assertFalse(isAllowedWebAppUrl("https://example.com", "http://example.com"))
        assertFalse(isAllowedWebAppUrl("https://example.com", "https://example.com:8443"))
        assertFalse(isAllowedWebAppUrl("not a url", "https://example.com"))
    }

    @Test
    fun `webview transport requires a connected tun and rejects local proxy mode`() {
        assertTrue(
            isWebAppTunTransportReady(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.TUNNEL),
            ),
        )
        assertFalse(
            isWebAppTunTransportReady(
                ConnectionSnapshot(state = ConnectionState.CONNECTED, trafficMode = TrafficMode.PROXY),
            ),
        )
        assertFalse(isWebAppTunTransportReady(ConnectionSnapshot(state = ConnectionState.RECONNECTING)))
    }
}
