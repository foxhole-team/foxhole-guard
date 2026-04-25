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

    @Test
    fun `fallback matching skips profiles already consumed by exact key`() {
        val plan =
            planSubscriptionRefresh(
                existingProfiles =
                    listOf(
                        ExistingSubscriptionProfile(
                            id = 10L,
                            name = "alpha",
                            protocolHint = ProtocolHint.TROJAN,
                        ),
                        ExistingSubscriptionProfile(
                            id = 11L,
                            name = "beta",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                    ),
                importedProfiles =
                    listOf(
                        ImportedSubscriptionProfile(
                            displayName = "beta",
                            protocolHint = ProtocolHint.VLESS,
                        ),
                        ImportedSubscriptionProfile(
                            displayName = "new",
                            protocolHint = ProtocolHint.SHADOWSOCKS,
                        ),
                    ),
            )

        assertEquals(listOf(11L, 10L), plan.assignments.map(SubscriptionRefreshAssignment::existingProfileId))
        assertEquals(emptyList<Long>(), plan.deletedProfileIds)
    }

    @Test
    fun `plans large reordered subscriptions without dropping matches`() {
        val existingProfiles =
            (1L..10_000L).map { id ->
                ExistingSubscriptionProfile(
                    id = id,
                    name = "profile-$id",
                    protocolHint = ProtocolHint.VLESS,
                )
            }
        val importedProfiles =
            (10_000L downTo 1L).map { id ->
                ImportedSubscriptionProfile(
                    displayName = "profile-$id",
                    protocolHint = ProtocolHint.VLESS,
                )
            }

        val plan =
            planSubscriptionRefresh(
                existingProfiles = existingProfiles,
                importedProfiles = importedProfiles,
            )

        assertEquals((10_000L downTo 1L).toList(), plan.assignments.map(SubscriptionRefreshAssignment::existingProfileId))
        assertEquals(emptyList<Long>(), plan.deletedProfileIds)
    }
}
