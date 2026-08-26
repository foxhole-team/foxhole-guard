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

    private fun settingsWithLanAuth(lanAuth: LocalAuthSettings): Settings =
        Settings(
            connection = ConnectionSettings(safeModeEnabled = false),
            expert =
            ExpertSettings(
                localSurfaces = LocalSurfaceSettings(allowLanAccess = true, lanAuth = lanAuth),
            ),
        )
}
