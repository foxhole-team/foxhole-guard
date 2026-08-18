package com.foxhole.guard.core.data

import com.foxhole.core.model.ParsedSubscriptionImport
import com.foxhole.core.model.ParsedSubscriptionProfile
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.StoredProfileSecret
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionRefreshZeroChurnTest {
    private val json =
        Json {
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    private val sourceUrl = "https://subs.example.com/team"

    private val importedProfile =
        ParsedSubscriptionProfile(
            displayName = "Alpha",
            protocolHint = ProtocolHint.VLESS,
            normalizedConfigJson =
            """{"outbounds":[{"type":"vless","tag":"alpha-1a2b3c4d","server":"a.example.com"}]}""",
        )

    @Test
    fun `unchanged subscription reuses the stored secret ref and stages no write`() {
        val initial = prepare(existing = null)
        assertFalse(initial.reusesExistingSecret)

        val refreshed = prepare(existing = storedMember(initial.stagedSecretWrite.value))

        assertTrue(refreshed.reusesExistingSecret)
        assertEquals("ref-alpha", refreshed.stagedSecretWrite.secretRef)
        assertEquals(emptyList<StagedProfileSecretWrite>(), listOf(refreshed).stagedSecretWrites())
        assertEquals(emptyList<String>(), listOf(refreshed).replacedSecretRefs())
    }

    @Test
    fun `changed subscription stages a fresh secret and cleans up the replaced ref`() {
        val initial = prepare(existing = null)

        val changed =
            prepare(
                existing = storedMember(initial.stagedSecretWrite.value),
                response = response(expiresAt = 1_999_999_999_000),
            )

        assertFalse(changed.reusesExistingSecret)
        assertNotEquals("ref-alpha", changed.stagedSecretWrite.secretRef)
        assertEquals(listOf(changed.stagedSecretWrite), listOf(changed).stagedSecretWrites())
        assertEquals(listOf("ref-alpha"), listOf(changed).replacedSecretRefs())
    }

    private fun prepare(
        existing: SubscriptionGroupMember?,
        response: SubscriptionResponse = response(),
    ): PreparedSubscriptionRefreshProfile =
        prepareSubscriptionRefreshProfiles(
            sourceUrl = sourceUrl,
            parsed = ParsedSubscriptionImport(displayName = "Team", profiles = listOf(importedProfile)),
            response = response,
            refreshPlan =
            SubscriptionRefreshPlan(
                assignments =
                listOf(
                    SubscriptionRefreshAssignment(
                        existingProfileId = existing?.entity?.id,
                        importedProfile =
                        ImportedSubscriptionProfile(
                            displayName = importedProfile.displayName,
                            protocolHint = importedProfile.protocolHint,
                        ),
                    ),
                ),
                deletedProfileIds = emptyList(),
            ),
            subscriptionGroupById = existing?.let { member -> mapOf(member.entity.id to member) }.orEmpty(),
            grantInsecureTlsConsent = false,
            json = json,
        ).single()

    private fun response(expiresAt: Long? = null) =
        SubscriptionResponse(
            body = "irrelevant",
            etag = "etag-1",
            subscriptionExpiresAt = expiresAt,
        )

    private fun storedMember(secret: StoredProfileSecret) =
        SubscriptionGroupMember(
            entity =
            ProfileEntity(
                id = 7L,
                name = "Alpha",
                sourceType = "SUBSCRIPTION_URL",
                secretRef = "ref-alpha",
                protocolHint = ProtocolHint.VLESS.name,
                lastUpdatedAt = 1L,
                lastEtag = "etag-0",
                isActive = true,
            ),
            storedSecret = secret,
        )
}
