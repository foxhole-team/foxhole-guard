package com.foxhole.guard.ui

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkRuleProfileSupportTest {
    @Test
    fun `multi profile rule keeps explicit concrete protocol`() {
        assertEquals("trojan", profile().resolveNetworkRuleProtocolOptionId(" trojan "))
    }

    @Test
    fun `multi profile rule falls back to selected protocol and never auto`() {
        assertEquals("vless", profile().resolveNetworkRuleProtocolOptionId(null))
        assertEquals("vless", profile().resolveNetworkRuleProtocolOptionId("missing"))
    }

    @Test
    fun `multi profile without valid selection falls back to first protocol`() {
        assertEquals(
            "vless",
            profile(selectedOptionId = "missing").resolveNetworkRuleProtocolOptionId(null),
        )
    }

    @Test
    fun `single profile does not persist redundant protocol pin`() {
        assertNull(
            profile(options = listOf(option("vless", ProtocolHint.VLESS)))
                .resolveNetworkRuleProtocolOptionId("vless"),
        )
    }

    private fun profile(
        selectedOptionId: String? = "vless",
        options: List<ProfileProtocolOption> = listOf(
            option("vless", ProtocolHint.VLESS),
            option("trojan", ProtocolHint.TROJAN),
        ),
    ) = Profile(
        id = 7L,
        name = "multi",
        sourceType = ProfileSourceType.SUBSCRIPTION_URL,
        secretRef = "secret",
        protocolHint = ProtocolHint.VLESS,
        lastUpdatedAt = null,
        lastEtag = null,
        protocolOptions = options,
        selectedProtocolOptionId = selectedOptionId,
        isActive = false,
    )

    private fun option(id: String, protocol: ProtocolHint) = ProfileProtocolOption(
        id = id,
        displayName = id,
        protocolHint = protocol,
    )
}
