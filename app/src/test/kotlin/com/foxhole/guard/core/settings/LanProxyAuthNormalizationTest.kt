package com.foxhole.guard.core.settings

import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.LocalAuthSettings
import com.foxhole.core.model.LocalSurfaceSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class LanProxyAuthNormalizationTest {
    @Test
    fun `stored lan auth flag is forced back on`() {
        val lanAuth =
            settingsWithLanAuth(LocalAuthSettings(enabled = false, username = "boxy", password = "hunter2"))
                .normalized()
                .expert
                .localSurfaces
                .lanAuth

        assertTrue(lanAuth.enabled)
        assertEquals("boxy", lanAuth.username)
        assertEquals("hunter2", lanAuth.password)
    }

    @Test
    fun `lan credentials stay the user's own across a second normalization`() {
        val once = settingsWithLanAuth(LocalAuthSettings(username = "boxy", password = "hunter2")).normalized()
        val twice = once.normalized()

        assertEquals(once.expert.localSurfaces.lanAuth, twice.expert.localSurfaces.lanAuth)
        assertTrue(twice.expert.localSurfaces.lanAuth.enabled)
    }

    @Test
    fun `blank lan password is replaced by a generated one instead of an anonymous surface`() {
        val lanAuth =
            settingsWithLanAuth(LocalAuthSettings(enabled = false, username = "boxy", password = "   "))
                .normalized()
                .expert
                .localSurfaces
                .lanAuth

        assertTrue(lanAuth.enabled)
        assertTrue(lanAuth.password.isNotBlank())
    }

    @Test
    fun `the loopback proxy auth flag is left alone`() {
        // Only the LAN leg is forced: the loopback surface is not reachable from the network, so its
        // own toggle keeps whatever the user chose.
        val localSurfaces =
            settingsWithLanAuth(LocalAuthSettings(username = "boxy", password = "hunter2"))
                .let { settings ->
                    settings.copy(
                        expert =
                        settings.expert.copy(
                            localSurfaces =
                            settings.expert.localSurfaces.copy(auth = LocalAuthSettings(enabled = false)),
                        ),
                    )
                }
                .normalized()
                .expert
                .localSurfaces

        assertEquals(false, localSurfaces.auth.enabled)
        assertTrue(localSurfaces.lanAuth.enabled)
    }

    // Safe mode rebuilds ExpertSettings from a whitelist that drops localSurfaces entirely, and the
    // LAN surface can only be enabled with safe mode already off (updateLocalProxyLanAccess clears
    // it), so the fixture starts from there.
    private fun settingsWithLanAuth(lanAuth: LocalAuthSettings): Settings =
        Settings(
            connection = ConnectionSettings(safeModeEnabled = false),
            expert =
            ExpertSettings(
                localSurfaces = LocalSurfaceSettings(allowLanAccess = true, lanAuth = lanAuth),
            ),
        )
}
