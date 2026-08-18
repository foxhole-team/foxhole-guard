package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CliProfileProtocolFactTest {

    @Test
    fun `a profile whose name differs from its protocol reports the protocol`() {
        val profile = smartProfile(
            name = "Amsterdam nightly",
            profileHint = ProtocolHint.VLESS,
            options = listOf(
                option(id = "opt-1", displayName = "Amsterdam #3", hint = ProtocolHint.TROJAN),
            ),
            selectedOptionId = "opt-1",
        )

        assertEquals("TROJAN", cliProfileProtocolLabel(profile))
    }

    @Test
    fun `the selected option decides, not the first one in the list`() {
        val profile = smartProfile(
            name = "Frankfurt",
            profileHint = ProtocolHint.VLESS,
            options = listOf(
                option(id = "opt-1", displayName = "DE reality", hint = ProtocolHint.VLESS),
                option(id = "opt-2", displayName = "DE hysteria", hint = ProtocolHint.HYSTERIA2),
                option(id = "opt-3", displayName = "DE wg", hint = ProtocolHint.WIREGUARD),
            ),
            selectedOptionId = "opt-2",
        )

        assertEquals("HYSTERIA2", cliProfileProtocolLabel(profile))
    }

    @Test
    fun `each supported protocol reports its own name`() {
        listOf(
            ProtocolHint.VLESS,
            ProtocolHint.VMESS,
            ProtocolHint.TROJAN,
            ProtocolHint.SHADOWSOCKS,
            ProtocolHint.HYSTERIA2,
            ProtocolHint.TUIC,
            ProtocolHint.WIREGUARD,
            ProtocolHint.ANYTLS,
            ProtocolHint.NAIVE,
            ProtocolHint.OUTLINE,
        ).forEach { hint ->
            val profile = smartProfile(
                name = "server-$hint-name-that-is-not-the-protocol",
                profileHint = ProtocolHint.UNKNOWN,
                options = listOf(option(id = "o", displayName = "remark", hint = hint)),
                selectedOptionId = "o",
            )
            assertEquals(hint.name, cliProfileProtocolLabel(profile))
        }
    }

    @Test
    fun `a single-protocol profile falls back to its own hint`() {
        val profile = smartProfile(
            name = "my wireguard box",
            profileHint = ProtocolHint.WIREGUARD,
            options = emptyList(),
            selectedOptionId = null,
        )

        assertEquals("WIREGUARD", cliProfileProtocolLabel(profile))
    }

    @Test
    fun `an unnameable protocol reports nothing`() {
        assertNull(cliProfileProtocolLabel(null))
        assertNull(
            cliProfileProtocolLabel(
                smartProfile(
                    name = "imported config",
                    profileHint = ProtocolHint.CUSTOM_CONFIG,
                    options = emptyList(),
                    selectedOptionId = null,
                ),
            ),
        )
        assertNull(
            cliProfileProtocolLabel(
                smartProfile(
                    name = "mystery",
                    profileHint = ProtocolHint.UNKNOWN,
                    options = emptyList(),
                    selectedOptionId = null,
                ),
            ),
        )
    }

    private fun option(
        id: String,
        displayName: String,
        hint: ProtocolHint,
    ) = ProfileProtocolOption(
        id = id,
        displayName = displayName,
        protocolHint = hint,
        isSelected = false,
    )

    private fun smartProfile(
        name: String,
        profileHint: ProtocolHint,
        options: List<ProfileProtocolOption>,
        selectedOptionId: String?,
    ) = Profile(
        id = 1L,
        name = name,
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret",
        protocolHint = profileHint,
        lastUpdatedAt = null,
        lastEtag = null,
        protocolOptions = options,
        selectedProtocolOptionId = selectedOptionId,
        isActive = true,
    )
}
