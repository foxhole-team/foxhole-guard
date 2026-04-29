package com.foxhole.beta.core.profile

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.beta.core.model.Profile
import com.foxhole.beta.core.model.ProfileProtocolOption
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiProtocolProfileSupportTest {
    @Test
    fun `supported options exclude unknown and sing box carrier rows`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("carrier", ProtocolHint.SING_BOX),
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
        assertFalse(MultiProtocolProfileSupport.hasMultipleSupportedOptions(profile(options = listOf(option("vless", ProtocolHint.VLESS)))))
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

        assertTrue(MultiProtocolProfileSupport.hasSupportedAutoConnectOption(profile))
        assertEquals(listOf("vless"), candidates.map(AutoConnectProbeCandidate::optionId))
        assertEquals(listOf(ProtocolHint.VLESS), candidates.map(AutoConnectProbeCandidate::protocolHint))
    }

    @Test
    fun `smart start does not treat insecure option marker as consent`() {
        val eligible =
            MultiProtocolProfileSupport.smartStartEligibleProbeCandidates(
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
            MultiProtocolProfileSupport.smartStartEligibleProbeCandidates(profile).map(AutoConnectProbeCandidate::optionId),
        )
        assertEquals(
            listOf("safe", "insecure"),
            MultiProtocolProfileSupport
                .smartStartEligibleProbeCandidates(globalConsentProfile, allowInsecureTlsGlobally = true)
                .map(AutoConnectProbeCandidate::optionId),
        )
    }

    @Test
    fun `fastest successful probe ignores failures and chooses lowest latency`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("vless", ProtocolHint.VLESS),
                        option("ss", ProtocolHint.SHADOWSOCKS),
                        option("wg", ProtocolHint.WIREGUARD),
                    ),
            )
        val candidates = MultiProtocolProfileSupport.probeCandidates(profile)
        val chosen =
            MultiProtocolProfileSupport.fastestSuccessfulProbe(
                listOf(
                    AutoConnectProbeResult(
                        candidates[0],
                        success = false,
                        latencyMs = 100,
                        failureReason = "reset",
                        reasonCode = AutoConnectReasonCode.CONNECT_ERROR,
                    ),
                    AutoConnectProbeResult(candidates[1], success = true, latencyMs = 82),
                    AutoConnectProbeResult(candidates[2], success = true, latencyMs = 51),
                ),
            )

        assertEquals("wg", chosen?.candidate?.optionId)
    }

    @Test
    fun `fastest successful probe returns null when every candidate fails`() {
        val candidate =
            AutoConnectProbeCandidate(
                profileId = 1,
                optionId = "vless",
                protocolHint = ProtocolHint.VLESS,
                displayName = "VLESS",
            )

        assertNull(
            MultiProtocolProfileSupport.fastestSuccessfulProbe(
                listOf(
                    AutoConnectProbeResult(
                        candidate,
                        success = false,
                        latencyMs = 20,
                        failureReason = "timeout",
                        reasonCode = AutoConnectReasonCode.HANDSHAKE_TIMEOUT,
                    ),
                ),
            ),
        )
    }

    @Test
    fun `fallback ranked probe does not outrank measured latency result`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("measured", ProtocolHint.WIREGUARD),
                        option("fallback", ProtocolHint.TROJAN),
                    ),
            )
        val candidates = MultiProtocolProfileSupport.probeCandidates(profile)

        val chosen =
            MultiProtocolProfileSupport.fastestSuccessfulProbe(
                listOf(
                    AutoConnectProbeResult(candidates[0], success = true, latencyMs = 420),
                    AutoConnectProbeResult(
                        candidates[1],
                        success = true,
                        latencyMs = 120,
                        rankingLatencyMs = 870,
                        reasonCode = AutoConnectReasonCode.LATENCY_ENDPOINT_BLOCKED,
                    ),
                ),
            )

        assertEquals("measured", chosen?.candidate?.optionId)
    }

    @Test
    fun `manual metrics can recommend udp when validated connect is fastest`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("trojan", ProtocolHint.TROJAN),
                        option("wireguard", ProtocolHint.WIREGUARD),
                    ),
            )
        val candidates = MultiProtocolProfileSupport.probeCandidates(profile)

        val winner =
            MultiProtocolProfileSupport.fastestSuccessfulProbe(
                listOf(
                    AutoConnectProbeResult(
                        candidate = candidates.first { it.optionId == "trojan" },
                        success = true,
                        latencyMs = 220L,
                        rankingLatencyMs = 220L,
                        displayLatencyMs = 220L,
                        connectDurationMs = 900L,
                    ),
                    AutoConnectProbeResult(
                        candidate = candidates.first { it.optionId == "wireguard" },
                        success = true,
                        latencyMs = 140L,
                        rankingLatencyMs = 140L,
                        displayLatencyMs = null,
                        connectDurationMs = 140L,
                    ),
                ),
            )

        assertEquals("wireguard", winner?.candidate?.optionId)
    }

    @Test
    fun `probe candidates prefer last known good then successful low latency history`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("trojan", ProtocolHint.TROJAN),
                        option("wireguard", ProtocolHint.WIREGUARD),
                        option("vless", ProtocolHint.VLESS),
                    ),
            )

        val ordered =
            MultiProtocolProfileSupport
                .probeCandidates(
                    profile,
                    SmartProfilePreference(
                        profileId = profile.id,
                        lastKnownGoodOptionId = "wireguard",
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "trojan",
                                    lastSuccessAt = 1_000L,
                                    lastLatencyMs = 320L,
                                ),
                                SmartProfileProtocolMemory(
                                    optionId = "wireguard",
                                    lastSuccessAt = 2_000L,
                                    lastLatencyMs = 210L,
                                ),
                            ),
                    ),
                ).map(AutoConnectProbeCandidate::optionId)

        assertEquals(listOf("wireguard", "trojan", "vless"), ordered)
    }

    @Test
    fun `probe candidates prefer network scoped memory before profile fallback`() {
        val profile =
            profile(
                options =
                    listOf(
                        option("trojan", ProtocolHint.TROJAN),
                        option("wireguard", ProtocolHint.WIREGUARD),
                        option("vless", ProtocolHint.VLESS),
                    ),
            )

        val preference =
            SmartProfilePreference(
                profileId = profile.id,
                lastKnownGoodOptionId = "wireguard",
                protocolMemories =
                    listOf(
                        SmartProfileProtocolMemory(
                            optionId = "wireguard",
                            lastSuccessAt = 1_000L,
                            lastLatencyMs = 240L,
                        ),
                    ),
                networkMemories =
                    listOf(
                        SmartProfileNetworkMemory(
                            networkFingerprint = "wifi-home",
                            networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                            lastKnownGoodOptionId = "trojan",
                            protocolMemories =
                                listOf(
                                    SmartProfileProtocolMemory(
                                        optionId = "trojan",
                                        lastSuccessAt = 2_000L,
                                        lastLatencyMs = 160L,
                                    ),
                                ),
                        ),
                    ),
            )

        val homeOrder =
            MultiProtocolProfileSupport
                .probeCandidates(profile, preference, networkFingerprint = "wifi-home")
                .map(AutoConnectProbeCandidate::optionId)
        val fallbackOrder =
            MultiProtocolProfileSupport
                .probeCandidates(profile, preference, networkFingerprint = "cellular")
                .map(AutoConnectProbeCandidate::optionId)

        assertEquals(listOf("trojan", "wireguard", "vless"), homeOrder)
        assertEquals(listOf("wireguard", "trojan", "vless"), fallbackOrder)
    }

    private fun option(
        id: String,
        protocolHint: ProtocolHint,
        selected: Boolean = false,
        requiresInsecureTls: Boolean = false,
    ): ProfileProtocolOption =
        ProfileProtocolOption(
            id = id,
            displayName = id,
            protocolHint = protocolHint,
            requiresInsecureTls = requiresInsecureTls,
            isSelected = selected,
        )

    private fun profile(
        selectedOptionId: String? = null,
        requiresInsecureTls: Boolean = false,
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
            isActive = true,
        )
}
