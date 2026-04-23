package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.network.NetworkFingerprint
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import org.junit.Assert.assertEquals
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
