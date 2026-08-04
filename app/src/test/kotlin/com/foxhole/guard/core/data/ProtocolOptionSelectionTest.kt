package com.foxhole.guard.core.data

import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileProtocolOption
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolOptionSelectionTest {
    private val json = Json { ignoreUnknownKeys = true }

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

    @Test
    fun `persisted selection pointing at a disabled option resolves to the first enabled one`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria")),
                selectedProtocolOptionId = "vless",
            )

        assertEquals("hysteria", secret.selectedStoredProtocolOptionForRuntime()?.id)
    }

    @Test
    fun `resolution without a persisted selection skips disabled options`() {
        val secret = StoredProfileSecret(protocolOptions = listOf(option("vless", enabled = false), option("hysteria")))

        assertEquals("hysteria", secret.selectedStoredProtocolOptionForRuntime()?.id)
    }

    @Test
    fun `resolution never fails when every protocol option is disabled`() {
        val allDisabled =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria", enabled = false)),
                selectedProtocolOptionId = "hysteria",
            )
        val allDisabledWithoutSelection =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria", enabled = false)),
            )

        assertEquals("hysteria", allDisabled.selectedStoredProtocolOptionForRuntime()?.id)
        assertEquals("vless", allDisabledWithoutSelection.selectedStoredProtocolOptionForRuntime()?.id)
    }

    @Test
    fun `legacy options without the enabled flag resolve unchanged`() {
        val legacy =
            json.decodeFromString<StoredProfileSecret>(
                """
                {"protocolOptions":[
                  {"id":"vless","displayName":"vless","protocolHint":"VLESS","normalizedConfigJson":"{}"},
                  {"id":"hysteria","displayName":"hysteria","protocolHint":"HYSTERIA2","normalizedConfigJson":"{}"}
                ],"selectedProtocolOptionId":"hysteria"}
                """.trimIndent(),
            )

        assertTrue(legacy.protocolOptions.all(StoredProfileProtocolOption::enabled))
        assertEquals("hysteria", legacy.selectedStoredProtocolOptionForRuntime()?.id)
    }

    @Test
    fun `explicit runtime override still resolves the requested option`() {
        // Identity wins for an explicit request (a probe, or a reconnect onto the running option):
        // substituting another protocol would connect through something the caller never asked for.
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria")),
                selectedProtocolOptionId = "hysteria",
            )

        assertEquals("vless", secret.selectedStoredProtocolOptionForRuntime("vless")?.id)
    }

    @Test
    fun `a disabled option cannot be selected`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria")),
                selectedProtocolOptionId = "hysteria",
            )

        assertNull(secret.selectableProtocolOptionOrNull("vless"))
        assertNotNull(secret.selectableProtocolOptionOrNull("hysteria"))
    }

    @Test
    fun `an option without a config cannot be selected`() {
        val secret = StoredProfileSecret(protocolOptions = listOf(option("vless", configJson = "  ")))

        assertNull(secret.selectableProtocolOptionOrNull("vless"))
    }

    @Test
    fun `insecure tls requirement is detected from the marker and from the config`() {
        val marked = option("vless").copy(requiresInsecureTls = true)
        val fromConfig = option("trojan", configJson = """{"outbounds":[{"tls":{"insecure":true}}]}""")

        assertTrue(marked.requiresInsecureTlsForRuntime(json))
        assertTrue(fromConfig.requiresInsecureTlsForRuntime(json))
        assertFalse(option("hysteria").requiresInsecureTlsForRuntime(json))
    }

    @Test
    fun `disabling the selected option re-points the selection at the first enabled one`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria"), option("trojan")),
                selectedProtocolOptionId = "vless",
            )

        val update = secret.enabledUpdate(optionId = "vless", enabled = false)

        assertEquals("hysteria", update.reselectedOption?.id)
        assertEquals("hysteria", update.secret.selectedProtocolOptionId)
        assertEquals(
            listOf(false, true, true),
            update.secret.protocolOptions.map(StoredProfileProtocolOption::enabled),
        )
        // The re-pointed selection resolves cleanly for the runtime.
        assertEquals("hysteria", update.secret.selectedStoredProtocolOptionForRuntime()?.id)
    }

    @Test
    fun `disabling an unselected option keeps the selection untouched`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria")),
                selectedProtocolOptionId = "vless",
            )

        val update = secret.enabledUpdate(optionId = "hysteria", enabled = false)

        assertNull(update.reselectedOption)
        assertEquals("vless", update.secret.selectedProtocolOptionId)
    }

    @Test
    fun `re-selection skips options the profile has no insecure tls consent for`() {
        val secret =
            StoredProfileSecret(
                protocolOptions =
                listOf(
                    option("vless"),
                    option("trojan").copy(requiresInsecureTls = true),
                    option("hysteria"),
                ),
                selectedProtocolOptionId = "vless",
            )

        val withoutConsent = secret.enabledUpdate(optionId = "vless", enabled = false)
        val withConsent = secret.enabledUpdate(optionId = "vless", enabled = false, allowInsecureTls = true)

        assertEquals("hysteria", withoutConsent.reselectedOption?.id)
        assertEquals("trojan", withConsent.reselectedOption?.id)
    }

    @Test
    fun `disabling the last enabled option is refused`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless"), option("hysteria", enabled = false)),
                selectedProtocolOptionId = "vless",
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                secret.enabledUpdate(optionId = "vless", enabled = false)
            }

        assertEquals("the last enabled protocol option cannot be disabled", error.message)
    }

    @Test
    fun `re-enabling an option never re-points the selection`() {
        val secret =
            StoredProfileSecret(
                protocolOptions = listOf(option("vless", enabled = false), option("hysteria")),
                selectedProtocolOptionId = "hysteria",
            )

        val update = secret.enabledUpdate(optionId = "vless", enabled = true)

        assertNull(update.reselectedOption)
        assertTrue(update.secret.protocolOptions.all(StoredProfileProtocolOption::enabled))
    }

    @Test
    fun `toggling an unknown option is refused`() {
        val secret = StoredProfileSecret(protocolOptions = listOf(option("vless")))

        assertThrows(IllegalArgumentException::class.java) {
            secret.enabledUpdate(optionId = "missing", enabled = false)
        }
    }

    private fun StoredProfileSecret.enabledUpdate(
        optionId: String,
        enabled: Boolean,
        allowInsecureTls: Boolean = false,
    ): ProtocolOptionEnabledUpdate =
        protocolOptionEnabledUpdate(
            optionId = optionId,
            enabled = enabled,
            json = json,
            allowInsecureTls = allowInsecureTls,
        )

    private fun option(
        id: String,
        enabled: Boolean = true,
        configJson: String = "{}",
    ) = StoredProfileProtocolOption(
        id = id,
        displayName = id,
        protocolHint = ProtocolHint.VLESS,
        normalizedConfigJson = configJson,
        enabled = enabled,
    )
}
