package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ParsedSubscriptionProfile
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolOptionRefreshSelectionTest {
    @Test
    fun `subscription refresh keeps selected option when id is unchanged`() {
        val imported =
            profile(
                selectedProtocolOptionId = "vless",
                options = listOf(option("vless", ProtocolHint.VLESS, "server-a")),
            )
        val previous = StoredProfileSecret(
            protocolOptions = listOf(option("vless", ProtocolHint.VLESS, "server-a")),
            selectedProtocolOptionId = "vless",
        )

        assertEquals("vless", imported.resolveRefreshSelectedProtocolOptionId(previous))
    }

    @Test
    fun `subscription refresh recovers renamed selected option by stable config fingerprint`() {
        val imported =
            profile(
                selectedProtocolOptionId = "hysteria2",
                options =
                listOf(
                    option("hysteria2", ProtocolHint.HYSTERIA2, "server-b"),
                    option("vless_2", ProtocolHint.VLESS, "server-a"),
                ),
            )
        val previous =
            StoredProfileSecret(
                protocolOptions =
                listOf(
                    option("vless", ProtocolHint.VLESS, "server-a"),
                    option("hysteria2", ProtocolHint.HYSTERIA2, "server-b"),
                ),
                selectedProtocolOptionId = "vless",
            )

        assertEquals("vless_2", imported.resolveRefreshSelectedProtocolOptionId(previous))
    }

    @Test
    fun `subscription refresh preserves stale selected id when no fingerprint replacement exists`() {
        val imported =
            profile(
                selectedProtocolOptionId = "hysteria2",
                options = listOf(option("hysteria2", ProtocolHint.HYSTERIA2, "server-b")),
            )
        val previous =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", ProtocolHint.VLESS, "server-a")),
                selectedProtocolOptionId = "vless",
            )

        assertEquals("vless", imported.resolveRefreshSelectedProtocolOptionId(previous))
    }

    private fun profile(
        selectedProtocolOptionId: String?,
        options: List<StoredProfileProtocolOption>,
    ) = ParsedSubscriptionProfile(
        displayName = "smart",
        protocolHint = options.first().protocolHint,
        normalizedConfigJson = options.first().normalizedConfigJson,
        protocolOptions = options,
        selectedProtocolOptionId = selectedProtocolOptionId,
    )

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
        server: String,
    ) = StoredProfileProtocolOption(
        id = id,
        displayName = id,
        protocolHint = protocolHint,
        normalizedConfigJson = """{"outbounds":[{"type":"${protocolHint.name.lowercase()}","server":"$server"}]}""",
    )
}
