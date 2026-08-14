package com.foxhole.core.runtime

import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.ProxyInboundSettings
import com.foxhole.core.model.ProxySurfaceMode
import com.foxhole.core.model.Settings
import com.foxhole.core.runtime.network.ProxyAccessType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class TrafficModeSupportTest {
    @Test
    fun `preferred app proxy access omits credentials when auth toggle is off`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, port = 10809),
                        auth =
                        LocalAuthSettings(
                            enabled = false,
                            username = "foxhole-user",
                            password = "foxhole-pass",
                        ),
                    ),
                ),
            )

        val access = requireNotNull(settings.preferredAppProxyAccess())

        assertEquals("127.0.0.1", access.host)
        assertEquals(10809, access.port)
        assertNull(access.username)
        assertNull(access.password)
    }

    @Test
    fun `preferred app proxy access ignores lan proxy auth`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, port = 10809),
                        auth =
                        LocalAuthSettings(
                            username = "local-user",
                            password = "local-pass",
                        ),
                        lanAuth =
                        LocalAuthSettings(
                            username = "lan-user",
                            password = "lan-pass",
                        ),
                    ),
                ),
            )

        val access = requireNotNull(settings.preferredAppProxyAccess())

        assertEquals("local-user", access.username)
        assertEquals("local-pass", access.password)
    }

    @Test
    fun `tunnel runtime proxy uses internal http access`() {
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    localSurfaces =
                    LocalSurfaceSettings(
                        proxyMode = ProxySurfaceMode.HTTP,
                        http = ProxyInboundSettings(enabled = true, port = 10809),
                    ),
                ),
            )

        val access = settings.tunnelRuntimeProxyAccess()

        assertEquals("127.0.0.1", access.host)
        assertEquals(10809, access.port)
        assertEquals(ProxyAccessType.HTTP, access.type)
        assertEquals(runtimeLoopbackProxyAuth().username, access.username)
        assertEquals(runtimeLoopbackProxyAuth().password, access.password)
        assertFalse(access.password.isNullOrBlank())
    }
}
