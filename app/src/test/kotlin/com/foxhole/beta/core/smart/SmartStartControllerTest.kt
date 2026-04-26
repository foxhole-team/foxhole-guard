package com.foxhole.beta.core.smart

import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_CURRENT
import com.foxhole.beta.core.model.NETWORK_FINGERPRINT_SCHEMA_LEGACY
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.SmartProfileNetworkMemory
import com.foxhole.beta.core.model.SmartProfilePreference
import com.foxhole.beta.core.model.SmartProfileProtocolMemory
import com.foxhole.beta.core.profile.AutoConnectProbeCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartStartControllerTest {
    @Test
    fun `first validated success stops further attempts`() {
        val attempted = mutableListOf<String>()

        val summary =
            SmartStartController.runUntilFirstSuccess(listOf("first", "second", "third")) { candidate ->
                attempted += candidate
                candidate == "second"
            }

        assertEquals(listOf("first", "second"), attempted)
        assertEquals("second", summary.winner)
    }

    @Test
    fun `default attempts are bounded for auto connect`() {
        val attempted = mutableListOf<String>()

        val summary =
            SmartStartController.runUntilFirstSuccess(listOf("one", "two", "three", "four", "five")) { candidate ->
                attempted += candidate
                false
            }

        assertEquals(listOf("one", "two", "three"), attempted)
        assertNull(summary.winner)
    }

    @Test
    fun `manual metrics callers can explicitly scan every candidate`() {
        val attempted = mutableListOf<String>()

        val summary =
            SmartStartController.runUntilFirstSuccess(
                candidates = listOf("one", "two", "three", "four", "five"),
                maxAttempts = Int.MAX_VALUE,
            ) { candidate ->
                attempted += candidate
                false
            }

        assertEquals(listOf("one", "two", "three", "four", "five"), attempted)
        assertNull(summary.winner)
    }

    @Test
    fun `explicit maxAttempts still caps helper attempts when requested`() {
        val attempted = mutableListOf<String>()

        val summary =
            SmartStartController.runUntilFirstSuccess(
                candidates = listOf("one", "two", "three", "four", "five"),
                maxAttempts = 3,
            ) { candidate ->
                attempted += candidate
                false
            }

        assertEquals(listOf("one", "two", "three"), attempted)
        assertNull(summary.winner)
    }

    @Test
    fun `insecure without consent is excluded before ranking`() {
        val eligible =
            SmartStartController.eligibleCandidatesForRanking(
                candidates =
                    listOf(
                        candidate("safe"),
                        candidate("insecure-consented", requiresInsecureTls = true, insecureTlsConsentGranted = true),
                        candidate("insecure-unconsented", requiresInsecureTls = true, insecureTlsConsentGranted = false),
                    ),
                preference = null,
                networkFingerprint = null,
                excludedOptionIds = emptySet(),
                subscriptionExpiresAt = null,
                now = 10_000L,
            )

        assertEquals(listOf("safe", "insecure-consented"), eligible.map(AutoConnectProbeCandidate::optionId))
    }

    @Test
    fun `controlled exploration keeps every eligible candidate`() {
        val attempts =
            SmartStartController.rankedAttempts(
                rankedCandidates =
                    listOf(
                        score("one", 100),
                        score("two", 90),
                        score("three", 80),
                        score("four", 70),
                    ),
                config = AdaptiveProtocolScoringConfig.Default.copy(controlledExplorationEpsilon = 1.0),
                randomDouble = { 0.0 },
                randomIndex = { 1 },
            )

        assertEquals(4, attempts.size)
        assertEquals(listOf("three", "one", "two", "four"), attempts.map { it.candidate.optionId })
    }

    @Test
    fun `schema one scoped memory is ignored for schema two smart start ranking`() {
        val eligible =
            SmartStartController.eligibleCandidatesForRanking(
                candidates = listOf(candidate("old-memory")),
                preference =
                    SmartProfilePreference(
                        profileId = 7L,
                        networkMemories =
                            listOf(
                                SmartProfileNetworkMemory(
                                    networkFingerprint = "wifi",
                                    networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_LEGACY,
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "old-memory",
                                                cooldownUntilAt = 20_000L,
                                                failureStreak = 4,
                                            ),
                                        ),
                                ),
                                SmartProfileNetworkMemory(
                                    networkFingerprint = "wifi-v2",
                                    networkFingerprintSchema = NETWORK_FINGERPRINT_SCHEMA_CURRENT,
                                    protocolMemories =
                                        listOf(
                                            SmartProfileProtocolMemory(
                                                optionId = "other",
                                                cooldownUntilAt = 20_000L,
                                                failureStreak = 4,
                                            ),
                                        ),
                                ),
                            ),
                    ),
                networkFingerprint = "wifi",
                excludedOptionIds = emptySet(),
                subscriptionExpiresAt = null,
                now = 10_000L,
            )

        assertEquals(listOf("old-memory"), eligible.map(AutoConnectProbeCandidate::optionId))
    }

    private fun candidate(
        optionId: String,
        requiresInsecureTls: Boolean = false,
        insecureTlsConsentGranted: Boolean = false,
    ): AutoConnectProbeCandidate =
        AutoConnectProbeCandidate(
            profileId = 1L,
            optionId = optionId,
            protocolHint = ProtocolHint.TROJAN,
            displayName = optionId,
            requiresInsecureTls = requiresInsecureTls,
            insecureTlsConsentGranted = insecureTlsConsentGranted,
        )

    private fun score(
        optionId: String,
        score: Int,
    ): AdaptiveProtocolCandidateScore =
        AdaptiveProtocolCandidateScore(
            candidate = candidate(optionId),
            scoringVersion = AdaptiveProtocolScoringConfig.CURRENT_VERSION,
            score = score,
            successRate = 0.5,
            lastKnownGoodBonus = 0,
            networkMatchBonus = 0,
            latencyPenalty = 0,
            recentFailurePenalty = 0,
            validationFailurePenalty = 0,
            explorationBonus = 0,
        )
}
