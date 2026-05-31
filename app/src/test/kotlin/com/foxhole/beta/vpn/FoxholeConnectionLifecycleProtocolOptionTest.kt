package com.foxhole.beta.vpn

import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Test

class FoxholeConnectionLifecycleProtocolOptionTest {
    @Test
    fun `optimistic connect snapshot uses explicit matching protocol option`() {
        val selected = profile(selectedProtocolOptionId = "vless").runtimeProtocolOption("hysteria")

        assertEquals("hysteria", selected?.id)
    }

    @Test(expected = IllegalStateException::class)
    fun `optimistic connect snapshot rejects missing explicit protocol option`() {
        profile(selectedProtocolOptionId = "vless").runtimeProtocolOption("trojan")
    }

    @Test(expected = IllegalStateException::class)
    fun `optimistic connect snapshot rejects stale stored selected option`() {
        profile(selectedProtocolOptionId = "trojan").runtimeProtocolOption(null)
    }

    private fun profile(selectedProtocolOptionId: String?) =
        Profile(
            id = 7L,
            name = "demo",
            sourceType = ProfileSourceType.SHARE_URI,
            secretRef = "secret",
            protocolHint = ProtocolHint.VLESS,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions = listOf(option("vless"), option("hysteria")),
            selectedProtocolOptionId = selectedProtocolOptionId,
            isActive = true,
        )

    private fun option(id: String) =
        ProfileProtocolOption(
            id = id,
            displayName = id,
            protocolHint = ProtocolHint.VLESS,
        )
}
