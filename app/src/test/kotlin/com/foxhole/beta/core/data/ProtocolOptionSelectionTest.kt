package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.StoredProfileProtocolOption
import com.foxhole.beta.core.model.StoredProfileSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolOptionSelectionTest {
    @Test
    fun `explicit override selects matching protocol option`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria")),
                selectedProtocolOptionId = "vless",
            )

        val selected = secret.selectedStoredProtocolOptionForRuntime("hysteria")

        assertEquals("hysteria", selected?.id)
    }

    @Test(expected = IllegalStateException::class)
    fun `explicit override does not fall back to first protocol option`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria")),
                selectedProtocolOptionId = "vless",
            )

        secret.selectedStoredProtocolOptionForRuntime("missing")
    }

    @Test(expected = IllegalStateException::class)
    fun `stale persisted selected protocol option does not fall back to first option`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria")),
                selectedProtocolOptionId = "trojan",
            )

        secret.selectedStoredProtocolOptionForRuntime()
    }

    @Test
    fun `single raw profile without protocol options stays valid`() {
        assertNull(StoredProfileSecret(resolvedConfigJson = "{}").selectedStoredProtocolOptionForRuntime())
    }

    private fun option(id: String) =
        StoredProfileProtocolOption(
            id = id,
            displayName = id,
            protocolHint = ProtocolHint.VLESS,
            normalizedConfigJson = "{}",
        )
}
