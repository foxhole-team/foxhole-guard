package com.foxhole.guard.ui

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.TrafficSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeProxySurfaceResolverTest {
    @Test
    fun `proxy mode exposes enabled local proxy surface on home`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.PROXY),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, port = 10809),
                            ),
                        ),
                    ),
                ),
            )

        assertNotNull(surface)
        assertEquals("HTTP", surface?.label)
        assertEquals(10809, surface?.settings?.port)
    }

    @Test
    fun `tunnel mode hides local proxy surface on home`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                http = ProxyInboundSettings(enabled = true, port = 10809),
                                allowLanAccess = true,
                            ),
                        ),
                    ),
                ),
            )

        assertNull(surface)
    }

    @Test
    fun `lan proxy surface stays visible on home in tunnel mode when lan access is enabled`() {
        val surface =
            activeLanProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                lanProxyMode = ProxySurfaceMode.HTTP,
                                http = ProxyInboundSettings(enabled = true, port = 10809),
                                allowLanAccess = true,
                            ),
                        ),
                    ),
                ),
            )

        assertNotNull(surface)
        assertEquals("HTTP", surface?.label)
        assertEquals(10809, surface?.settings?.port)
    }

    @Test
    fun `resolver follows explicit proxy surface mode`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.PROXY),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                proxyMode = ProxySurfaceMode.ALL,
                                http = ProxyInboundSettings(enabled = true, port = 10809),
                                socks = ProxyInboundSettings(enabled = true, port = 10808),
                                mixed = ProxyInboundSettings(enabled = true, port = 10810),
                            ),
                        ),
                    ),
                ),
            )

        assertNotNull(surface)
        assertEquals("ALL", surface?.label)
    }

    @Test
    fun `enabled local socks surface is exposed on the protected tunnel`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                        expert =
                        ExpertSettings(
                            localSurfaces =
                            LocalSurfaceSettings(
                                socks = ProxyInboundSettings(enabled = true, port = 10808),
                                allowLanAccess = true,
                            ),
                        ),
                    ),
                ),
            )

        assertNotNull(surface)
        assertEquals("SOCKS5", surface?.label)
        assertEquals(10808, surface?.settings?.port)
        assertEquals(false, surface?.lanOnly)
    }

    @Test
    fun `resolver hides proxy surface when every proxy endpoint is disabled`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                    Settings(
                        traffic = TrafficSettings(mode = TrafficMode.TUNNEL),
                    ),
                ),
            )

        assertNull(surface)
    }
}
