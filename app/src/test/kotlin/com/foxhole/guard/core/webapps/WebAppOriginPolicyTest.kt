package com.foxhole.guard.core.webapps

import com.foxhole.core.model.ConnectionSnapshot
import com.foxhole.core.model.ConnectionState
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.WebAppRoute
import com.foxhole.core.model.storedWebAppRoute
import org.junit.Assert.assertEquals
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

    @Test
    fun `isolation off passes the route gate with or without a tunnel`() {
        assertTrue(webAppRouteSatisfied(isolationEnabled = false, tunnelReady = false))
        assertTrue(webAppRouteSatisfied(isolationEnabled = false, tunnelReady = true))
    }

    @Test
    fun `isolation on demands a ready tunnel`() {
        assertFalse(webAppRouteSatisfied(isolationEnabled = true, tunnelReady = false))
        assertTrue(webAppRouteSatisfied(isolationEnabled = true, tunnelReady = true))
    }

    @Test
    fun `isolation ships disabled so web apps work without a tunnel out of the box`() {
        assertFalse(com.foxhole.core.model.WebAppsSettings().isolationEnabled)
    }

    @Test
    fun `explicit direct ignores the global tunnel gate while block never opens`() {
        val idle = ConnectionSnapshot()

        assertTrue(
            webAppRouteSatisfied(
                route = WebAppRoute.DIRECT,
                blockWithoutTunnel = true,
                snapshot = idle,
                i2pReady = false,
            ),
        )
        assertFalse(
            webAppRouteSatisfied(
                route = WebAppRoute.BLOCK,
                blockWithoutTunnel = false,
                snapshot = idle,
                i2pReady = true,
            ),
        )
    }

    @Test
    fun `strict routes require their exact live lane and never fall back`() {
        val vpn =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 7L,
                protocolHint = ProtocolHint.VLESS,
            )
        assertTrue(webAppRouteSatisfied(WebAppRoute.VPN, true, vpn, i2pReady = false))
        assertFalse(webAppRouteSatisfied(WebAppRoute.TOR, true, vpn, i2pReady = false))
        assertFalse(webAppRouteSatisfied(WebAppRoute.I2P, true, vpn, i2pReady = false))

        val tor = vpn.copy(torActive = true)
        assertTrue(webAppRouteSatisfied(WebAppRoute.TOR, true, tor, i2pReady = false))
        assertTrue(webAppRouteSatisfied(WebAppRoute.I2P, true, tor, i2pReady = true))
    }

    @Test
    fun `wireguard cannot be misrepresented as an http proxy route`() {
        val wireGuard =
            ConnectionSnapshot(
                state = ConnectionState.CONNECTED,
                trafficMode = TrafficMode.TUNNEL,
                profileId = 9L,
                protocolHint = ProtocolHint.WIREGUARD,
            )

        assertFalse(webAppRouteSatisfied(WebAppRoute.VPN, true, wireGuard, i2pReady = false))
    }

    @Test
    fun `unknown stored route degrades only to global default`() {
        assertEquals(WebAppRoute.DEFAULT, storedWebAppRoute("future-route"))
        assertEquals(WebAppRoute.TOR, storedWebAppRoute("tor"))
    }
}
