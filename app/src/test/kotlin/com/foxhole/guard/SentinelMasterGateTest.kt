package com.foxhole.guard

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.ConnectionSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SentinelMasterGateTest {
    @Test
    fun `threat intelligence background work follows both master switches`() {
        val enabled =
            Settings(
                anomaly = AnomalySettings(enabled = true),
                connection = ConnectionSettings(componentUpdateCheckEnabled = true),
            )

        assertTrue(threatIntelBackgroundUpdateEnabled(enabled))
        assertFalse(threatIntelBackgroundUpdateEnabled(enabled.copy(anomaly = AnomalySettings(enabled = false))))
        assertFalse(
            threatIntelBackgroundUpdateEnabled(
                enabled.copy(connection = enabled.connection.copy(componentUpdateCheckEnabled = false)),
            ),
        )
    }

    @Test
    fun `scheduled work and sealed monitoring recheck the Sentinel master`() {
        val worker = source("runtime/ThreatIntelUpdateWorker.kt")
        val security = source("core/security/FoxholeSecurityComponents.kt")
        val toggle = source("ui/HomeViewModelDiagnosticsSettingsSupport.kt")

        assertTrue(worker.contains("threatIntelBackgroundUpdateEnabled(dependencies.settingsRepository.current())"))
        assertTrue(security.contains("settingsRepository.settings.value.anomaly.enabled &&"))
        assertTrue(toggle.contains("syncGuardMonitoringLifecycle()"))
        assertTrue(toggle.contains("SentinelDetectionNotifier(app).cancelAll()"))
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/kotlin/com/foxhole/guard/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/guard/$relativePath"),
        ).first(File::isFile).readText()
}
