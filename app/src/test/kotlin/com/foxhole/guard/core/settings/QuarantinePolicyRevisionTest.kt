package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class QuarantinePolicyRevisionTest {
    @Test
    fun `two persisted installs advance to the latest revision`() {
        val initial = Settings()
        val first =
            withAdvancedQuarantinePolicyRevision(
                current = initial,
                transformed = initial.withPendingBlock("com.example.one"),
            )
        val second =
            withAdvancedQuarantinePolicyRevision(
                current = first,
                transformed = first.withPendingBlock("com.example.two"),
            )

        assertEquals(1L, first.expert.quarantinePolicyRevision)
        assertEquals(2L, second.expert.quarantinePolicyRevision)
    }

    @Test
    fun `unchanged enforcement identity preserves revision`() {
        val current = Settings(expert = ExpertSettings(quarantinePolicyRevision = 8L))
        val transformed = current.copy(appTrafficStatsEnabled = true)

        assertEquals(8L, withAdvancedQuarantinePolicyRevision(current, transformed).expert.quarantinePolicyRevision)
    }

    private fun Settings.withPendingBlock(packageName: String): Settings =
        copy(
            expert =
            expert.copy(
                pendingQuarantinePackages = expert.pendingQuarantinePackages + packageName,
                appAssignments = expert.appAssignments + (packageName to AppTunnelLane.BLOCK),
            ),
        )
}
