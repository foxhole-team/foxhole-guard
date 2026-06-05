package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.StatisticsSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppTrafficPrivacyGateTest {
    @Test
    fun `app traffic local storage requires explicit consent and enabled statistics`() {
        val enabled =
            Settings(
                statistics = StatisticsSettings(enabled = true, appTrafficEnabled = true),
                appTrafficStatsEnabled = true,
                appTrafficUsageAccessConsent = true,
            )

        assertTrue(appTrafficLocalStorageAllowed(enabled))
        assertFalse(appTrafficLocalStorageAllowed(enabled.copy(appTrafficUsageAccessConsent = false)))
        assertFalse(appTrafficLocalStorageAllowed(enabled.copy(appTrafficStatsEnabled = false)))
        assertFalse(
            appTrafficLocalStorageAllowed(
                enabled.copy(
                    statistics = enabled.statistics.copy(appTrafficEnabled = false),
                ),
            ),
        )
        assertFalse(appTrafficLocalStorageAllowed(enabled.copy(statistics = enabled.statistics.copy(enabled = false))))
    }

    @Test
    fun `repository app traffic writes are gated at the persistence boundary`() {
        val source = sourceFile("core/anomaly/AnomalyRepository.kt").readText()
        val recordTrafficBlock =
            source
                .substringAfter("suspend fun recordTrafficWindow(")
                .substringBefore("private suspend fun batchedAppHistories(")
        val directRecordBlock =
            source
                .substringAfter("suspend fun recordAppTrafficWindows(")
                .substringBefore("suspend fun recordNetworkActivityEvent(")

        assertTrue(recordTrafficBlock.contains("val retainedAppWindows ="))
        assertTrue(recordTrafficBlock.contains("appTrafficLocalStorageAllowed(settings)"))
        assertTrue(recordTrafficBlock.contains("dao.insertAppTrafficWindows(retainedAppWindows.map"))
        assertFalse(recordTrafficBlock.contains("dao.insertAppTrafficWindows(appWindows.map"))
        assertTrue(directRecordBlock.contains("appTrafficLocalStorageAllowed(settingsRepository.current())"))
        assertTrue(directRecordBlock.contains("return"))
    }

    @Test
    fun `usage access revocation short circuits before sampling and writes`() {
        val samplerSource = sourceFile("core/anomaly/AndroidTrafficSources.kt").readText()
        val recorderSource = sourceFile("core/settings/AppTrafficStatsRecorder.kt").readText()
        val sampleBlock =
            samplerSource
                .substringAfter("suspend fun sampleWindows(")
                .substringBefore("val manager = networkStatsManager")
        val recordSnapshotBlock =
            recorderSource
                .substringAfter("suspend fun recordSnapshot(")
                .substringBefore("suspend fun sampleWindows(")

        assertTrue(sampleBlock.indexOf("if (!hasUsageAccess())") < sampleBlock.indexOf("SampleWatermarkLock"))
        assertTrue(recordSnapshotBlock.indexOf("if (!hasUsageAccess())") < recordSnapshotBlock.indexOf("sampleWindows"))
        assertTrue(recordSnapshotBlock.contains("return"))
    }

    private fun sourceFile(relativePath: String): File =
        listOf(
            File("src/main/kotlin/com/foxhole/beta/$relativePath"),
            File("app/src/main/kotlin/com/foxhole/beta/$relativePath"),
            File("../app/src/main/kotlin/com/foxhole/beta/$relativePath"),
        ).first { file -> file.isFile }
}
