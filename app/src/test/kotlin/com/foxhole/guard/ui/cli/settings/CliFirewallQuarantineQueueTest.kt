package com.foxhole.guard.ui.cli.settings

import com.foxhole.core.model.InstalledAppOption
import com.foxhole.core.model.InstalledAppRiskLevel
import com.foxhole.core.model.InstalledAppRiskSignal
import com.foxhole.core.model.PendingQuarantineAppDetails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CliFirewallQuarantineQueueTest {
    @Test
    fun `queue follows persistent pending order and ignores unrelated inventory`() {
        val installed =
            listOf(
                InstalledAppOption("com.other", "Other", isSystemApp = false),
                InstalledAppOption(
                    packageName = "com.second",
                    label = "Second",
                    isSystemApp = false,
                    versionCode = 12L,
                    firstInstallTime = 23L,
                    lastUpdateTime = 34L,
                    installerPackageName = "com.store",
                ),
            )

        val result = quarantineQueueItems(
            pendingPackages = listOf("com.first", "com.second", "com.first"),
            pendingDetails = listOf(
                PendingQuarantineAppDetails(
                    packageName = "com.second",
                    label = "Second saved",
                    firstInstallTime = 22L,
                    detectedAt = 24L,
                    installerPackageName = "com.saved.store",
                    riskLevel = InstalledAppRiskLevel.HIGH,
                    riskSignals = listOf(InstalledAppRiskSignal.VPN_SERVICE),
                ),
            ),
            installedApps = installed,
        )

        assertEquals(listOf("com.first", "com.second"), result.map { it.packageName })
        assertEquals(listOf("com.first", "Second saved"), result.map { it.label })
        assertEquals(12L, result.last().versionCode)
        assertEquals(34L, result.last().lastUpdateTime)
        assertEquals(22L, result.last().installTime)
        assertEquals("com.saved.store", result.last().installerPackageName)
        assertEquals(InstalledAppRiskLevel.HIGH, result.last().riskLevel)
        assertTrue(result.last().analysisComplete)
        assertTrue(!result.first().analysisComplete)
    }

    @Test
    fun `resolved packages disappear as soon as pending state is empty`() {
        assertTrue(
            quarantineQueueItems(
                pendingPackages = emptyList(),
                pendingDetails = emptyList(),
                installedApps = listOf(InstalledAppOption("com.old", "Old", isSystemApp = false)),
            ).isEmpty(),
        )
    }

    @Test
    fun `firewall queue uses one animated row action and canonical decision sheet`() {
        val source = File("src/main/kotlin/com/foxhole/guard/ui/cli/settings/CliFirewallSubScreen.kt").readText()

        assertTrue(source.contains(".clip(CircleShape)"))
        assertTrue(source.contains(".cliMarchingBorder(colors.firewall)"))
        assertTrue(source.contains(".cliPressable(onClick = onSelect)"))
        assertTrue(source.contains("CliQuarantineDecisionSheet("))
        assertTrue(source.contains("CliSheetActionTone.DESTRUCTIVE"))
        assertTrue(source.contains("onResolve(item.packageName, keepBlocked)"))
        assertTrue(source.indexOf("CliToggleRow(") < source.indexOf("CliQuarantineQueue("))
    }
}
