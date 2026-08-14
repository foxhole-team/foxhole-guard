package com.foxhole.core.profile

import com.foxhole.core.model.Profile
import com.foxhole.core.model.ProfileProtocolOption
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiProtocolProfileSupportTest {
    @Test
    fun `supported options exclude unknown and custom config carrier rows`() {
        val profile =
            profile(
                options =
                listOf(
                    option("vless", ProtocolHint.VLESS),
                    option("carrier", ProtocolHint.CUSTOM_CONFIG),
                    option("unknown", ProtocolHint.UNKNOWN),
                    option("ss", ProtocolHint.SHADOWSOCKS),
                ),
            )

        assertEquals(
            listOf("vless", "ss"),
            MultiProtocolProfileSupport.supportedOptions(profile).map(ProfileProtocolOption::id),
        )
    }

    @Test
    fun `selected option falls back to first supported protocol`() {
        val profile =
            profile(
                selectedOptionId = "missing",
                options =
                listOf(
                    option("wireguard", ProtocolHint.WIREGUARD),
                    option("trojan", ProtocolHint.TROJAN),
                ),
            )

        assertEquals("wireguard", MultiProtocolProfileSupport.selectedOption(profile)?.id)
    }

    @Test
    fun `multi protocol gate requires two supported options`() {
        assertFalse(
            MultiProtocolProfileSupport.hasMultipleSupportedOptions(
                profile(options = listOf(option("vless", ProtocolHint.VLESS)))
            )
        )
        assertTrue(
            MultiProtocolProfileSupport.hasMultipleSupportedOptions(
                profile(
                    options =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("ss", ProtocolHint.SHADOWSOCKS),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `smart start can probe ordinary single protocol profile`() {
        val profile =
            Profile(
                id = 1,
                name = "Foxhole vpn direct",
                sourceType = ProfileSourceType.SUBSCRIPTION_URL,
                secretRef = "secret",
                protocolHint = ProtocolHint.VLESS,
                lastUpdatedAt = null,
                lastEtag = null,
                protocolOptions = emptyList(),
                selectedProtocolOptionId = null,
                isActive = true,
            )

        val candidates = MultiProtocolProfileSupport.smartStartFullScanCandidates(profile)

        assertEquals(listOf("vless"), candidates.map(AutoConnectProbeCandidate::optionId))
        assertEquals(listOf(ProtocolHint.VLESS), candidates.map(AutoConnectProbeCandidate::protocolHint))
    }

    @Test
    fun `smart start does not treat insecure option marker as consent`() {
        val eligible =
            MultiProtocolProfileSupport.smartStartFullScanCandidates(
                profile =
                profile(
                    requiresInsecureTls = false,
                    options =
                    listOf(
                        option("safe", ProtocolHint.TROJAN),
                        option("insecure", ProtocolHint.VLESS, requiresInsecureTls = true),
                    ),
                ),
            )

        assertEquals(listOf("safe"), eligible.map(AutoConnectProbeCandidate::optionId))
    }

    @Test
    fun `smart start accepts insecure options only with profile or global consent`() {
        val profile =
            profile(
                requiresInsecureTls = true,
                insecureTlsConsentGranted = true,
                options =
                listOf(
                    option("safe", ProtocolHint.TROJAN),
                    option("insecure", ProtocolHint.VLESS, requiresInsecureTls = true),
                ),
            )
        val globalConsentProfile =
            profile(
                requiresInsecureTls = false,
                options =
                listOf(
                    option("safe", ProtocolHint.TROJAN),
                    option("insecure", ProtocolHint.VLESS, requiresInsecureTls = true),
                ),
            )

        assertEquals(
            listOf("safe", "insecure"),
            MultiProtocolProfileSupport.smartStartFullScanCandidates(profile).map(AutoConnectProbeCandidate::optionId),
        )
        assertEquals(
            listOf("safe", "insecure"),
            MultiProtocolProfileSupport
                .smartStartFullScanCandidates(globalConsentProfile, allowInsecureTlsGlobally = true)
                .map(AutoConnectProbeCandidate::optionId),
        )
    }

    @Test
    fun `supported options exclude protocols switched off in the smart profile`() {
        val profile =
            profile(
                options =
                listOf(
                    option("vless", ProtocolHint.VLESS, enabled = false),
                    option("ss", ProtocolHint.SHADOWSOCKS),
                ),
            )

        assertEquals(
            listOf("ss"),
            MultiProtocolProfileSupport.supportedOptions(profile).map(ProfileProtocolOption::id),
        )
        assertFalse(MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile))
    }

    @Test
    fun `selected option falls back when the stored selection is switched off`() {
        val profile =
            profile(
                selectedOptionId = "vless",
                options =
                listOf(
                    option("vless", ProtocolHint.VLESS, enabled = false),
                    option("ss", ProtocolHint.SHADOWSOCKS),
                ),
            )

        assertEquals("ss", MultiProtocolProfileSupport.selectedOption(profile)?.id)
    }

    @Test
    fun `selected option still resolves when every protocol is switched off`() {
        val profile =
            profile(
                selectedOptionId = "ss",
                options =
                listOf(
                    option("vless", ProtocolHint.VLESS, enabled = false),
                    option("ss", ProtocolHint.SHADOWSOCKS, enabled = false),
                ),
            )

        assertEquals("ss", MultiProtocolProfileSupport.selectedOption(profile)?.id)
    }

    @Test
    fun `smart start never probes a protocol switched off`() {
        val candidates =
            MultiProtocolProfileSupport.smartStartFullScanCandidates(
                profile =
                profile(
                    options =
                    listOf(
                        option("vless", ProtocolHint.VLESS, enabled = false),
                        option("ss", ProtocolHint.SHADOWSOCKS),
                    ),
                ),
            )

        assertEquals(listOf("ss"), candidates.map(AutoConnectProbeCandidate::optionId))
    }

    @Test
    fun `smart start has nothing to scan when every protocol is switched off`() {
        val candidates =
            MultiProtocolProfileSupport.smartStartFullScanCandidates(
                profile =
                profile(
                    options =
                    listOf(
                        option("vless", ProtocolHint.VLESS, enabled = false),
                        option("ss", ProtocolHint.SHADOWSOCKS, enabled = false),
                    ),
                ),
            )

        assertTrue(candidates.isEmpty())
    }

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
        selected: Boolean = false,
        requiresInsecureTls: Boolean = false,
        enabled: Boolean = true,
    ): ProfileProtocolOption =
        ProfileProtocolOption(
            id = id,
            displayName = id,
            protocolHint = protocolHint,
            requiresInsecureTls = requiresInsecureTls,
            isSelected = selected,
            enabled = enabled,
        )

    private fun profile(
        selectedOptionId: String? = null,
        requiresInsecureTls: Boolean = false,
        insecureTlsConsentGranted: Boolean = false,
        options: List<ProfileProtocolOption>,
    ): Profile =
        Profile(
            id = 1,
            name = "multi",
            sourceType = ProfileSourceType.SUBSCRIPTION_URL,
            secretRef = "secret",
            protocolHint = options.firstOrNull()?.protocolHint ?: ProtocolHint.UNKNOWN,
            lastUpdatedAt = null,
            lastEtag = null,
            protocolOptions = options,
            selectedProtocolOptionId = selectedOptionId,
            requiresInsecureTls = requiresInsecureTls,
            insecureTlsConsentGranted = insecureTlsConsentGranted,
            isActive = true,
        )
}
