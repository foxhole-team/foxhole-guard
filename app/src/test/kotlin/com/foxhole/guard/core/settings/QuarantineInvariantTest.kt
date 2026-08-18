package com.foxhole.guard.core.settings

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.InstalledAppChangeType
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.KnownApplicationIdentity
import com.foxhole.core.model.PendingQuarantineAppDetails
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.core.model.blockedLanePackages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineInvariantTest {
    private val packageName = "com.example.pending"
    private val details =
        PendingQuarantineAppDetails(
            packageName = packageName,
            label = "Pending",
            firstInstallTime = 100L,
            detectedAt = 200L,
            installerPackageName = "com.example.store",
            riskLevel = InstalledAppRiskLevel.HIGH,
            riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
        )

    @Test
    fun `normalization repairs every pending package into an enforced block`() {
        val result =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = false,
                    appAssignments = mapOf(packageName to AppTunnelLane.VPN),
                    pendingQuarantinePackages = listOf(" $packageName ", packageName),
                    pendingQuarantineAppDetails = listOf(details),
                    blockedPackagesEnabled = false,
                    blockAppsAlways = false,
                ),
            ).normalized()

        assertEquals(listOf(packageName), result.expert.pendingQuarantinePackages)
        assertEquals(AppTunnelLane.BLOCK, result.expert.appAssignments[packageName])
        assertTrue(result.expert.firewallEnabled)
        assertTrue(result.expert.blockedPackagesEnabled)
        assertTrue(result.expert.blockAppsAlways)
        assertEquals(listOf(details), result.expert.pendingQuarantineAppDetails)
    }

    @Test
    fun `generic lane edits cannot release or reroute a pending package`() {
        val current = Settings(expert = ExpertSettings().quarantinePackage(packageName, details))

        listOf(AppTunnelLane.VPN, AppTunnelLane.TOR, AppTunnelLane.EXCLUDE).forEach { lane ->
            val result = updateAppLanesIn(current, listOf(packageName), lane)
            assertEquals(AppTunnelLane.BLOCK, result.expert.appAssignments[packageName])
            assertEquals(listOf(packageName), result.expert.pendingQuarantinePackages)
            assertEquals(listOf(details), result.expert.pendingQuarantineAppDetails)
        }
        val removed = updateAppLanesIn(current, listOf(packageName), null)
        assertEquals(AppTunnelLane.BLOCK, removed.expert.appAssignments[packageName])
        assertEquals(listOf(packageName), removed.expert.pendingQuarantinePackages)
    }

    @Test
    fun `firewall cannot be disabled while a decision is pending`() {
        val current = Settings(expert = ExpertSettings().quarantinePackage(packageName, details))

        val result = updateFirewallEnabledIn(current, false)

        assertTrue(result.expert.firewallEnabled)
        assertEquals(listOf(packageName), result.expert.pendingQuarantinePackages)
    }

    @Test
    fun `quarantine facts persist even when statistics and app change history are off`() {
        val current =
            Settings(
                expert = ExpertSettings(
                    firewallEnabled = true,
                    newAppQuarantineEnabled = true,
                ),
            )

        val result =
            recordInstalledAppChangeIn(
                current = current,
                packageName = packageName,
                label = "Pending",
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = 200L,
                firstInstallTime = 100L,
                installerPackageName = "com.example.store",
                riskLevel = InstalledAppRiskLevel.HIGH,
                riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
            )

        assertFalse(result.statistics.enabled)
        assertTrue(result.installedAppInventoryAudit.recentChanges.isEmpty())
        assertEquals(listOf(packageName), result.expert.pendingQuarantinePackages)
        assertEquals(listOf(details), result.expert.pendingQuarantineAppDetails)
        assertEquals(listOf(packageName), result.expert.blockedLanePackages())
    }

    @Test
    fun `preflight risk stays pending until complete enrichment replaces it`() {
        val identity = KnownApplicationIdentity(packageName = packageName, firstSeenAtMs = 100L)
        val preflight =
            recordInstalledAppChangeIn(
                current =
                Settings(
                    anomaly = AnomalySettings(enabled = true),
                    expert = ExpertSettings(firewallEnabled = true, newAppQuarantineEnabled = true),
                    statistics = StatisticsSettings(enabled = true, appChangesEnabled = true),
                ),
                packageName = packageName,
                label = "Pending",
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = 200L,
                firstInstallTime = 100L,
                quarantineAnalysisComplete = false,
            )

        assertFalse(preflight.expert.pendingQuarantineAppDetails.single().analysisComplete)
        val enriched =
            enrichInstalledAppChangeIn(
                current = preflight,
                packageName = packageName,
                detectedAt = 200L,
                firstInstallTime = 100L,
                label = "Analyzed",
                isSystemApp = false,
                installerPackageName = "com.example.store",
                riskLevel = InstalledAppRiskLevel.HIGH,
                riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
                installedIdentity = identity,
            )

        assertTrue(enriched.outcome.pendingAfterCommit)
        assertFalse(enriched.outcome.routeChanged)
        assertTrue(enriched.outcome.analysisCompletedNow)
        assertTrue(enriched.settings.expert.pendingQuarantineAppDetails.single().analysisComplete)
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            enriched.settings.expert.pendingQuarantineAppDetails.single().riskLevel,
        )
        assertEquals(1, enriched.settings.installedAppInventoryAudit.recentChanges.size)
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            enriched.settings.installedAppInventoryAudit.recentChanges.single().riskLevel,
        )
    }

    @Test
    fun `the same analysis result is applied only once`() {
        val identity = KnownApplicationIdentity(packageName = packageName, firstSeenAtMs = 100L)
        val preflight =
            recordInstalledAppChangeIn(
                current = Settings(expert = ExpertSettings(firewallEnabled = true, newAppQuarantineEnabled = true)),
                packageName = packageName,
                label = "Pending",
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = 200L,
                firstInstallTime = 100L,
                quarantineAnalysisComplete = false,
            )
        fun enrich(current: Settings) =
            enrichInstalledAppChangeIn(
                current = current,
                packageName = packageName,
                detectedAt = 200L,
                firstInstallTime = 100L,
                label = "Analyzed",
                isSystemApp = false,
                installerPackageName = null,
                riskLevel = InstalledAppRiskLevel.HIGH,
                riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
                installedIdentity = identity,
            )

        val first = enrich(preflight)
        val second = enrich(first.settings)

        assertTrue(first.outcome.analysisCompletedNow)
        assertTrue(first.outcome.pendingAfterCommit)
        assertFalse(second.outcome.analysisCompletedNow)
        assertFalse(second.outcome.pendingAfterCommit)
        assertFalse(second.outcome.routeChanged)
        assertEquals(first.settings, second.settings)
    }

    @Test
    fun `late enrichment cannot resurrect an allow block decision or removed package`() {
        val identity = KnownApplicationIdentity(packageName = packageName, firstSeenAtMs = 100L)
        val preflight =
            recordInstalledAppChangeIn(
                current = Settings(expert = ExpertSettings(firewallEnabled = true, newAppQuarantineEnabled = true)),
                packageName = packageName,
                label = "Pending",
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = 200L,
                firstInstallTime = 100L,
                quarantineAnalysisComplete = false,
            )
        val resolvedCases =
            listOf(
                resolveQuarantinedAppIn(preflight, packageName, keepBlocked = false, identity = identity),
                resolveQuarantinedAppIn(preflight, packageName, keepBlocked = true, identity = identity),
                recordInstalledAppChangeIn(
                    current = preflight,
                    packageName = packageName,
                    label = "Pending",
                    isSystemApp = false,
                    type = InstalledAppChangeType.REMOVED,
                    detectedAt = 300L,
                ),
            )

        resolvedCases.forEach { resolved ->
            val late =
                enrichInstalledAppChangeIn(
                    current = resolved,
                    packageName = packageName,
                    detectedAt = 200L,
                    firstInstallTime = 100L,
                    label = "Late",
                    isSystemApp = false,
                    installerPackageName = null,
                    riskLevel = InstalledAppRiskLevel.HIGH,
                    riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
                    installedIdentity = identity,
                )
            assertTrue(late.settings.expert.pendingQuarantinePackages.isEmpty())
            assertEquals(resolved.expert.appAssignments[packageName], late.settings.expert.appAssignments[packageName])
        }
    }

    @Test
    fun `system app discovered after fail closed preflight is promoted into baseline`() {
        val identity = KnownApplicationIdentity(packageName = packageName, firstSeenAtMs = 100L)
        val preflight =
            recordInstalledAppChangeIn(
                current = Settings(expert = ExpertSettings(firewallEnabled = true, newAppQuarantineEnabled = true)),
                packageName = packageName,
                label = packageName,
                isSystemApp = false,
                type = InstalledAppChangeType.INSTALLED,
                detectedAt = 200L,
                firstInstallTime = 100L,
                quarantineAnalysisComplete = false,
            )

        val result =
            enrichInstalledAppChangeIn(
                current = preflight,
                packageName = packageName,
                detectedAt = 200L,
                firstInstallTime = 100L,
                label = "System app",
                isSystemApp = true,
                installerPackageName = null,
                riskLevel = InstalledAppRiskLevel.LOW,
                riskSignals = emptyList(),
                installedIdentity = identity,
            )

        assertTrue(result.outcome.routeChanged)
        assertTrue(result.settings.expert.pendingQuarantinePackages.isEmpty())
        assertFalse(packageName in result.settings.expert.appAssignments)
        assertTrue(identity in result.settings.expert.quarantineKnownApplications)
    }

    @Test
    fun `enable time baseline gap becomes pending while prior and assigned apps stay decided`() {
        val known = KnownApplicationIdentity(packageName = "com.example.known", firstSeenAtMs = 10L)
        val assigned = KnownApplicationIdentity(packageName = "com.example.assigned", firstSeenAtMs = 20L)
        val fresh = KnownApplicationIdentity(packageName = packageName, firstSeenAtMs = 100L)
        val current =
            Settings(
                expert =
                ExpertSettings(
                    firewallEnabled = true,
                    newAppQuarantineEnabled = true,
                    quarantineKnownApplications = listOf(known),
                    appAssignments = mapOf(assigned.packageName to AppTunnelLane.BLOCK),
                ),
            )

        val result = quarantineBaselineGapsIn(current, listOf(known, assigned, fresh), detectedAt = 200L)

        assertEquals(listOf(packageName), result.expert.pendingQuarantinePackages)
        assertEquals(AppTunnelLane.BLOCK, result.expert.appAssignments[packageName])
        assertFalse(result.expert.pendingQuarantineAppDetails.single().analysisComplete)
        assertEquals(100L, result.expert.pendingQuarantineAppDetails.single().firstInstallTime)
    }
}
