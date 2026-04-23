package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeActiveProfileResolverTest {
    @Test
    fun `prefers explicit active profile stream value`() {
        val first = profile(id = 1, name = "first", isActive = false)
        val second = profile(id = 2, name = "second", isActive = true)

        val resolved = HomeActiveProfileResolver.resolve(listOf(first, second), activeProfile = second)

        assertEquals(2L, resolved?.id)
    }

    @Test
    fun `falls back to flagged profile from list`() {
        val resolved =
            HomeActiveProfileResolver.resolve(
                profiles =
                    listOf(
                        profile(id = 1, name = "first", isActive = false),
                        profile(id = 2, name = "second", isActive = true),
                    ),
                activeProfile = null,
            )

        assertEquals(2L, resolved?.id)
    }

    @Test
    fun `falls back to first profile when active flag is missing`() {
        val resolved =
            HomeActiveProfileResolver.resolve(
                profiles =
                    listOf(
                        profile(id = 5, name = "latest", isActive = false),
                        profile(id = 3, name = "older", isActive = false),
                    ),
                activeProfile = null,
            )

        assertEquals(5L, resolved?.id)
    }

    @Test
    fun `uses startup fallback profile while profile stream warms up`() {
        val fallback = profile(id = 7, name = "startup", isActive = true)

        val resolved =
            HomeActiveProfileResolver.resolve(
                profiles = emptyList(),
                activeProfile = null,
                startupFallbackProfile = fallback,
            )

        assertEquals(7L, resolved?.id)
    }

    @Test
    fun `prefers matching profile row over startup fallback copy`() {
        val fallback = profile(id = 7, name = "stale", isActive = true)
        val fresh = profile(id = 7, name = "fresh", isActive = true)

        val resolved =
            HomeActiveProfileResolver.resolve(
                profiles = listOf(fresh),
                activeProfile = null,
                startupFallbackProfile = fallback,
            )

        assertEquals("fresh", resolved?.name)
    }

    @Test
    fun `ignores stale startup fallback when profile list no longer contains it`() {
        val fallback = profile(id = 7, name = "deleted", isActive = true)
        val remaining = profile(id = 9, name = "remaining", isActive = true)

        val resolved =
            HomeActiveProfileResolver.resolve(
                profiles = listOf(remaining),
                activeProfile = null,
                startupFallbackProfile = fallback,
            )

        assertEquals(9L, resolved?.id)
    }

    @Test
    fun `returns null when there are no profiles`() {
        assertNull(HomeActiveProfileResolver.resolve(emptyList(), activeProfile = null))
    }

    private fun profile(
        id: Long,
        name: String,
        isActive: Boolean,
    ) = Profile(
        id = id,
        name = name,
        sourceType = ProfileSourceType.SHARE_URI,
        secretRef = "secret-$id",
        protocolHint = ProtocolHint.VLESS,
        lastUpdatedAt = null,
        lastEtag = null,
        isActive = isActive,
    )
}
