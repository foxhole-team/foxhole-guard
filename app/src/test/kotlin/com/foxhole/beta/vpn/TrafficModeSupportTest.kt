package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.ExpertSettings
import com.foxhole.beta.core.model.LocalAuthSettings
import com.foxhole.beta.core.model.LocalSurfaceSettings
import com.foxhole.beta.core.model.ProxyInboundSettings
import com.foxhole.beta.core.model.Settings
import org.junit.Assert.assertEquals
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
}
