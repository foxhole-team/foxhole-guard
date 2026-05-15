package com.foxhole.beta.core.security

import com.foxhole.beta.core.model.InstalledAppRiskLevel
import com.foxhole.beta.core.model.InstalledAppRiskSignal
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledAppSecurityAnalyzerTest {
    @Test
    fun `sensitive Android services are high risk`() {
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(
                listOf(InstalledAppRiskSignal.ACCESSIBILITY_SERVICE),
            ),
        )
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(
                listOf(InstalledAppRiskSignal.VPN_SERVICE),
            ),
        )
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(
                listOf(InstalledAppRiskSignal.OVERLAY_PERMISSION),
            ),
        )
    }

    @Test
    fun `multiple softer signals escalate to high risk`() {
        assertEquals(
            InstalledAppRiskLevel.HIGH,
            installedAppRiskLevel(
                listOf(
                    InstalledAppRiskSignal.UNKNOWN_INSTALLER,
                    InstalledAppRiskSignal.AUTOSTART,
                    InstalledAppRiskSignal.SYSTEM_LIKE_NAME,
                ),
            ),
        )
    }

    @Test
    fun `single softer signal is medium risk and empty signals are low risk`() {
        assertEquals(
            InstalledAppRiskLevel.MEDIUM,
            installedAppRiskLevel(listOf(InstalledAppRiskSignal.UNKNOWN_INSTALLER)),
        )
        assertEquals(InstalledAppRiskLevel.LOW, installedAppRiskLevel(emptyList()))
    }
}
