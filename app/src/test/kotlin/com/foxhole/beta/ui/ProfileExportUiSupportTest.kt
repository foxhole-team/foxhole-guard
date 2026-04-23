package com.foxhole.beta.ui

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileExportUiSupportTest {
    @Test
    fun `single profile toggle creates one ready export request`() {
        val profile = profile(id = 7L, protocolOptions = emptyList(), protocolHint = ProtocolHint.WIREGUARD)

        val state = ProfilesExportSelectionState().toggleSingleProfile(profile)

        assertTrue(state.isReady())
        assertEquals(
            listOf(
                ProfileExportSelectionRequest(
                    profileId = 7L,
                    selectionKeys = setOf("__current__"),
                ),
            ),
            state.requests(),
        )
    }

    @Test
    fun `smart profile tracks partial and full selection separately`() {
        val profile =
            profile(
                id = 11L,
                protocolOptions =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("ss", ProtocolHint.SHADOWSOCKS),
                    ),
            )

        val partial =
            ProfilesExportSelectionState()
                .toggleSmartProfileChoice(profile, "vless")

        assertEquals(SmartProfileExportSelectionState.PARTIAL, partial.smartProfileSelectionState(profile))
        assertEquals(setOf("vless"), partial.selectedKeys(11L))

        val full = partial.toggleSmartProfileAll(profile)

        assertEquals(SmartProfileExportSelectionState.ALL, full.smartProfileSelectionState(profile))
        assertEquals(setOf("ss", "vless"), full.selectedKeys(11L))
    }

    @Test
    fun `prune drops removed profiles and stale selection keys`() {
        val keptProfile =
            profile(
                id = 1L,
                protocolOptions = listOf(option("vless", ProtocolHint.VLESS)),
            )
        val pruned =
            ProfilesExportSelectionState(
                selectedKeysByProfileId =
                    mapOf(
                        1L to setOf("vless", "stale"),
                        2L to setOf("__current__"),
                    ),
                expandedSmartProfileIds = setOf(1L, 2L),
            ).pruneTo(listOf(keptProfile))

        assertEquals(mapOf(1L to setOf("vless")), pruned.selectedKeysByProfileId)
        assertEquals(setOf(1L), pruned.expandedSmartProfileIds)
        assertFalse(2L in pruned.expandedSmartProfileIds)
    }

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
    ) = ProfileProtocolOption(
        id = id,
        displayName = id,
        protocolHint = protocolHint,
        isSelected = false,
    )

    private fun profile(
        id: Long,
        protocolOptions: List<ProfileProtocolOption>,
        protocolHint: ProtocolHint = ProtocolHint.SING_BOX,
    ) = Profile(
        id = id,
        name = "Profile $id",
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret-$id",
        protocolHint = protocolHint,
        lastUpdatedAt = null,
        lastEtag = null,
        protocolOptions = protocolOptions,
        selectedProtocolOptionId = protocolOptions.firstOrNull()?.id,
        isActive = false,
    )
}
