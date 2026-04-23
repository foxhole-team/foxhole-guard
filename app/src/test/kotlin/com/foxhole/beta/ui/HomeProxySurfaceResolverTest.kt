package com.foxhole.beta.ui

import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TrafficSettings
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
                                        com.foxhole.beta.core.model.LocalSurfaceSettings(
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
                                        com.foxhole.beta.core.model.LocalSurfaceSettings(
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
                                        com.foxhole.beta.core.model.LocalSurfaceSettings(
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
    fun `resolver keeps surface priority http then socks then mixed`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                        Settings(
                            traffic = TrafficSettings(mode = TrafficMode.PROXY),
                            expert =
                                ExpertSettings(
                                    localSurfaces =
                                        com.foxhole.beta.core.model.LocalSurfaceSettings(
                                            http = ProxyInboundSettings(enabled = true, port = 10809),
                                            socks = ProxyInboundSettings(enabled = true, port = 10808),
                                            mixed = ProxyInboundSettings(enabled = true, port = 10810),
                                        ),
                                ),
                        ),
                ),
            )

        assertNotNull(surface)
        assertEquals("HTTP", surface?.label)
    }

    @Test
    fun `lan proxy fallback exposes default http surface in proxy mode`() {
        val surface =
            activeProxySurface(
                HomeRouteUiState(
                    settings =
                        Settings(
                            traffic = TrafficSettings(mode = TrafficMode.PROXY),
                            expert =
                                ExpertSettings(
                                    localSurfaces =
                                        com.foxhole.beta.core.model.LocalSurfaceSettings(
                                            allowLanAccess = true,
                                        ),
                                ),
                        ),
                ),
            )

        assertNotNull(surface)
        assertEquals("HTTP", surface?.label)
        assertEquals(10809, surface?.settings?.port)
        assertEquals(true, surface?.lanOnly)
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
