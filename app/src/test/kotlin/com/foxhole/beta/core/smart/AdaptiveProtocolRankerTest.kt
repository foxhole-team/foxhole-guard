package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveProtocolRankerTest {
    @Test
    fun `prefers network scoped success memory before global fallback`() {
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("trojan", ProtocolHint.TROJAN),
                        candidate("wireguard", ProtocolHint.WIREGUARD),
                        candidate("vless", ProtocolHint.VLESS),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 7L,
                        lastKnownGoodOptionId = "wireguard",
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "wireguard",
                                    successCount = 2,
                                    lastSuccessAt = 1_000L,
                                    lastLatencyMs = 190L,
                                ),
                            ),
                        networkMemories =
                            listOf(
                                SmartProfileNetworkMemory(
                                    networkFingerprint = "wifi-home",
                                    lastKnownGoodOptionId = "trojan",
                                    lastKnownGoodAt = 2_000L,
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "trojan",
                                                successCount = 3,
                                                lastSuccessAt = 2_000L,
                                                lastLatencyMs = 150L,
                                                lastValidatedAt = 2_000L,
                                                lastTrafficAt = 2_000L,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                networkFingerprintKey = "wifi-home",
                networkContext =
                    NetworkFingerprint(
                        key = "wifi-home",
                        transport = "wifi",
                        privateDnsActive = true,
                        upstreamValidated = true,
                    ),
                now = 3_000L,
            )

        assertEquals(listOf("trojan", "wireguard", "vless"), ranked.map { it.candidate.optionId })
        assertEquals(
            listOf(
                "trojan: v=1: score=77 sr=0.80 lkg=+20 net=+21 lat=-0 fail=-0 val=-0 explore=+0",
                "wireguard: v=1: score=48 sr=0.75 lkg=+8 net=+4 lat=-1 fail=-0 val=-0 explore=+3",
                "vless: v=1: score=31 sr=0.50 lkg=+0 net=+0 lat=-0 fail=-0 val=-0 explore=+8",
            ),
            ranked.map(AdaptiveProtocolCandidateScore::summary),
        )
    }

    @Test
    fun `cooldown pushes recent failing candidate behind healthy fallback`() {
        val now = 10_000L
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("trojan", ProtocolHint.TROJAN),
                        candidate("wireguard", ProtocolHint.WIREGUARD),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 9L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "trojan",
                                    failureCount = 2,
                                    failureStreak = 2,
                                    lastFailureAt = now - 30_000L,
                                    cooldownUntilAt = now + 5L * 60L * 1000L,
                                    lastReasonCode = AutoConnectReasonCode.DNS_FAILURE,
                                ),
                                SmartProfileProtocolMemory(
                                    optionId = "wireguard",
                                    successCount = 2,
                                    lastSuccessAt = now - 120_000L,
                                    lastLatencyMs = 210L,
                                ),
                            ),
                    ),
                now = now,
            )

        assertEquals(listOf("wireguard", "trojan"), ranked.map { it.candidate.optionId })
    }

    @Test
    fun `private dns penalizes dns failing candidate and keeps exploration alive`() {
        val now = 20_000L
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("dns-bad", ProtocolHint.VLESS),
                        candidate("shadowsocks", ProtocolHint.SHADOWSOCKS),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 10L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "dns-bad",
                                    failureCount = 1,
                                    failureStreak = 1,
                                    lastFailureAt = now - 10_000L,
                                    lastReasonCode = AutoConnectReasonCode.DNS_FAILURE,
                                ),
                            ),
                    ),
                networkContext =
                    NetworkFingerprint(
                        key = "mobile",
                        transport = "cellular",
                        privateDnsActive = true,
                        upstreamValidated = true,
                    ),
                now = now,
            )

        assertEquals(listOf("shadowsocks", "dns-bad"), ranked.map { it.candidate.optionId })
    }

    @Test
    fun `higher success evidence monotonically improves score when other features are equal`() {
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("low-success", ProtocolHint.TROJAN),
                        candidate("high-success", ProtocolHint.TROJAN),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 11L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "low-success",
                                    successCount = 1,
                                    failureCount = 1,
                                ),
                                SmartProfileProtocolMemory(
                                    optionId = "high-success",
                                    successCount = 4,
                                    failureCount = 1,
                                ),
                            ),
                    ),
                now = 30_000L,
            )

        assertEquals(listOf("high-success", "low-success"), ranked.map { it.candidate.optionId })
        assertTrue(ranked[0].successRate > ranked[1].successRate)
        assertTrue(ranked[0].score > ranked[1].score)
    }

    private fun candidate(
        optionId: String,
        protocolHint: ProtocolHint,
    ) = AutoConnectProbeCandidate(
        profileId = 1L,
        optionId = optionId,
        protocolHint = protocolHint,
        displayName = optionId,
    )
}
