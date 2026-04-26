package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.AutoConnectReasonCode
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
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
                                    networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
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
    fun `ping only scoped memory does not mask global outcome evidence`() {
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("wireguard", ProtocolHint.WIREGUARD),
                        candidate("trojan", ProtocolHint.TROJAN),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 42L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "wireguard",
                                    successCount = 4,
                                    lastSuccessAt = 1_000L,
                                    lastLatencyMs = 180L,
                                ),
                            ),
                        networkMemories =
                            listOf(
                                SmartProfileNetworkMemory(
                                    networkFingerprint = "wifi-home",
                                    networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "wireguard",
                                                lastServerPingMs = 64L,
                                                lastServerPingAt = 2_000L,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                networkFingerprintKey = "wifi-home",
                now = 3_000L,
            )

        val score = ranked.first { it.candidate.optionId == "wireguard" }
        assertEquals(listOf("wireguard", "trojan"), ranked.map { it.candidate.optionId })
        assertTrue(score.successRate > 0.80)
        assertEquals(AdaptiveProtocolScoringConfig.Default.globalMemoryBonus, score.networkMatchBonus)
    }

    @Test
    fun `negative failure streak never becomes a ranking bonus`() {
        val now = 60_000L
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates = listOf(candidate("vless", ProtocolHint.VLESS)),
                preference =
                    SmartProfilePreference(
                        profileId = 43L,
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(
                                    optionId = "vless",
                                    failureStreak = -5,
                                    lastFailureAt = now - 30_000L,
                                ),
                            ),
                    ),
                now = now,
            )

        assertEquals(AdaptiveProtocolScoringConfig.Default.recentFailurePenalty, ranked.single().recentFailurePenalty)
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

    @Test
    fun `controlled exploration can test a non top candidate without losing ranked fallback order`() {
        val ranked =
            AdaptiveProtocolRanker.scoreCandidates(
                candidates =
                    listOf(
                        candidate("best", ProtocolHint.WIREGUARD),
                        candidate("backup", ProtocolHint.TROJAN),
                        candidate("new", ProtocolHint.VLESS),
                    ),
                preference =
                    SmartProfilePreference(
                        profileId = 12L,
                        lastKnownGoodOptionId = "best",
                        protocolMemories =
                            listOf(
                                SmartProfileProtocolMemory(optionId = "best", successCount = 5),
                                SmartProfileProtocolMemory(optionId = "backup", successCount = 2),
                            ),
                    ),
                now = 40_000L,
            )

        val explored =
            AdaptiveProtocolRanker.applyControlledExploration(
                rankedCandidates = ranked,
                config = AdaptiveProtocolScoringConfig.Default.copy(controlledExplorationEpsilon = 1.0),
                randomDouble = { 0.0 },
                randomIndex = { 1 },
            )

        assertEquals(listOf("new", "best", "backup"), explored.map { it.candidate.optionId })
    }

    @Test
    fun `replay history keeps network scoped winner stable`() {
        data class ReplayEvent(
            val networkKey: String,
            val expectedChoice: String,
            val preference: SmartProfilePreference,
        )

        val events =
            listOf(
                ReplayEvent(
                    networkKey = "wifi-home",
                    expectedChoice = "wireguard",
                    preference =
                        SmartProfilePreference(
                            profileId = 13L,
                            networkMemories =
                                listOf(
                                    SmartProfileNetworkMemory(
                                        networkFingerprint = "wifi-home",
                                        networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                                        lastKnownGoodOptionId = "wireguard",
                                        protocolMemories =
                                            listOf(
                                                SmartProfileProtocolMemory(
                                                    optionId = "wireguard",
                                                    successCount = 4,
                                                    lastLatencyMs = 72L,
                                                    lastValidatedAt = 1_000L,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                ),
                ReplayEvent(
                    networkKey = "cellular",
                    expectedChoice = "trojan",
                    preference =
                        SmartProfilePreference(
                            profileId = 13L,
                            networkMemories =
                                listOf(
                                    SmartProfileNetworkMemory(
                                        networkFingerprint = "cellular",
                                        networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                                        lastKnownGoodOptionId = "trojan",
                                        protocolMemories =
                                            listOf(
                                                SmartProfileProtocolMemory(
                                                    optionId = "trojan",
                                                    successCount = 3,
                                                    lastLatencyMs = 118L,
                                                    lastValidatedAt = 1_000L,
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                ),
            )

        events.forEach { event ->
            val choice =
                AdaptiveProtocolRanker
                    .scoreCandidates(
                        candidates =
                            listOf(
                                candidate("wireguard", ProtocolHint.WIREGUARD),
                                candidate("trojan", ProtocolHint.TROJAN),
                                candidate("vless", ProtocolHint.VLESS),
                            ),
                        preference = event.preference,
                        networkFingerprintKey = event.networkKey,
                        networkContext =
                            NetworkFingerprint(
                                key = event.networkKey,
                                transport = event.networkKey,
                                upstreamValidated = true,
                            ),
                        now = 50_000L,
                    ).first()
                    .candidate
                    .optionId

            assertEquals(event.expectedChoice, choice)
        }
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
