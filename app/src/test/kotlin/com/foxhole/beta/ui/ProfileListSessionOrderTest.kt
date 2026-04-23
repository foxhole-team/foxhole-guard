package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileListSessionOrderTest {
    @Test
    fun `keeps previous on-screen order when active profile changes`() {
        val firstActiveOrder = listOf(profile(id = 9L, isActive = true), profile(id = 7L, isActive = false))
        val sessionOrderIds = reconcileProfilesScreenSessionOrderIds(firstActiveOrder, currentSessionOrderIds = emptyList())

        val reorderedByRepository = listOf(profile(id = 7L, isActive = true), profile(id = 9L, isActive = false))

        val visible =
            visibleProfilesForProfilesSession(
                currentProfiles = reorderedByRepository,
                currentSessionOrderIds = sessionOrderIds,
            )

        assertEquals(listOf(9L, 7L), visible.map(Profile::id))
        assertEquals(listOf(false, true), visible.map(Profile::isActive))
    }

    @Test
    fun `appends new profiles without reshuffling retained ones`() {
        val currentProfiles = listOf(profile(id = 9L, isActive = false), profile(id = 7L, isActive = true), profile(id = 11L, isActive = false))

        val reconciled =
            reconcileProfilesScreenSessionOrderIds(
                currentProfiles = currentProfiles,
                currentSessionOrderIds = listOf(9L, 7L),
            )

        assertEquals(listOf(9L, 7L, 11L), reconciled)
    }

    @Test
    fun `drops deleted profiles from the pinned session order`() {
        val reconciled =
            reconcileProfilesScreenSessionOrderIds(
                currentProfiles = listOf(profile(id = 7L, isActive = true)),
                currentSessionOrderIds = listOf(9L, 7L),
            )

        assertEquals(listOf(7L), reconciled)
    }

    private fun profile(
        id: Long,
        isActive: Boolean,
    ) = Profile(
        id = id,
        name = "Profile $id",
        sourceType = ProfileSourceType.SHARE_URI,
        secretRef = "secret-$id",
        protocolHint = ProtocolHint.VLESS,
        lastUpdatedAt = null,
        lastEtag = null,
        isActive = isActive,
    )
}
