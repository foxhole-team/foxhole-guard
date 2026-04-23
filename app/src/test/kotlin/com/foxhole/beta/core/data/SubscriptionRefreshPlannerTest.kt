package com.foxhole.beta.core.data

import com.foxhole.beta.core.model.ProtocolHint
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionRefreshPlannerTest {
    @Test
    fun `expands single stored subscription profile into all imported profiles`() {
        val plan =
            planSubscriptionRefresh(
                existingProfiles =
                    listOf(
                        ExistingSubscriptionProfile(
                            id = 10L,
                            name = "example.org",
                            protocolHint = ProtocolHint.UNKNOWN,
                        ),
                    ),
                importedProfiles =
                    listOf(
                        ImportedSubscriptionProfile(
                            displayName = "trojan",
                            protocolHint = ProtocolHint.TROJAN,
                        ),
                        ImportedSubscriptionProfile(
                            displayName = "edge",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                    ),
            )

        assertEquals(listOf(10L, null), plan.assignments.map(SubscriptionRefreshAssignment::existingProfileId))
        assertEquals(emptyList<Long>(), plan.deletedProfileIds)
    }

    @Test
    fun `matches imported profiles by name and protocol before falling back to order`() {
        val plan =
            planSubscriptionRefresh(
                existingProfiles =
                    listOf(
                        ExistingSubscriptionProfile(
                            id = 10L,
                            name = "trojan",
                            protocolHint = ProtocolHint.TROJAN,
                        ),
                        ExistingSubscriptionProfile(
                            id = 11L,
                            name = "edge",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                    ),
                importedProfiles =
                    listOf(
                        ImportedSubscriptionProfile(
                            displayName = "edge",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                        ImportedSubscriptionProfile(
                            displayName = "trojan",
                            protocolHint = ProtocolHint.TROJAN,
                        ),
                    ),
            )

        assertEquals(listOf(11L, 10L), plan.assignments.map(SubscriptionRefreshAssignment::existingProfileId))
        assertEquals(emptyList<Long>(), plan.deletedProfileIds)
    }

    @Test
    fun `marks stale profiles for deletion when subscription shrinks`() {
        val plan =
            planSubscriptionRefresh(
                existingProfiles =
                    listOf(
                        ExistingSubscriptionProfile(
                            id = 10L,
                            name = "trojan",
                            protocolHint = ProtocolHint.TROJAN,
                        ),
                        ExistingSubscriptionProfile(
                            id = 11L,
                            name = "edge",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                    ),
                importedProfiles =
                    listOf(
                        ImportedSubscriptionProfile(
                            displayName = "edge",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                    ),
            )

        assertEquals(listOf(11L), plan.assignments.map(SubscriptionRefreshAssignment::existingProfileId))
        assertEquals(listOf(10L), plan.deletedProfileIds)
    }
}
