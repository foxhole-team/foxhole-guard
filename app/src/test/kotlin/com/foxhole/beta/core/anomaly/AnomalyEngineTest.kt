package com.foxhole.beta.core.anomaly

import com.foxhole.beta.core.model.AnomalySensitivity
import com.foxhole.beta.core.model.AnomalySeverity
import com.foxhole.beta.core.model.AnomalyType
import com.foxhole.beta.core.model.AppTrafficWindow
import com.foxhole.beta.core.model.NetworkType
import com.foxhole.beta.core.model.TrafficWindow
import com.foxhole.beta.core.model.VpnMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnomalyEngineTest {
    private val engine = AnomalyEngine()

    @Test
    fun `does not alert during first learning period unless extreme anomaly`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(txBytes = 18_000_000),
                appWindows = listOf(appWindow(packageName = "com.test", txBytes = 17_000_000, foreground = false)),
                history = AnomalyHistory(),
            )

        assertEquals(AnomalySeverity.SILENT, assessment.severity)
        assertTrue(assessment.signals.isEmpty())
    }

    @Test
    fun `upload spike triggers app upload signal after baseline`() {
        val history = appHistory(txBytes = 120_000)
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 9_000_000),
                appWindows = listOf(appWindow(packageName = "com.chat", txBytes = 8_500_000, foreground = true)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.chat" to history)),
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.APP_UPLOAD_SPIKE })
    }

    @Test
    fun `background traffic triggers app background signal`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 7_000_000),
                appWindows = listOf(appWindow(packageName = "com.sync", rxBytes = 500_000, txBytes = 6_500_000, foreground = false)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.sync" to appHistory(txBytes = 200_000))),
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.APP_BACKGROUND_TRAFFIC })
    }

    @Test
    fun `new country with tiny traffic does not notify`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(destinationCountries = mapOf("NL" to 64_000L)),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
            )

        assertFalse(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
        assertEquals(AnomalySeverity.SILENT, assessment.severity)
    }

    @Test
    fun `new country with high traffic notifies`() {
        val assessment =
            engine.evaluate(
                current = trafficWindow(rxBytes = 1_000_000, txBytes = 2_000_000, destinationCountries = mapOf("NL" to 2_200_000L)),
                appWindows = emptyList(),
                history = AnomalyHistory(trafficWindows = trafficHistory(country = "DE")),
            )

        assertTrue(assessment.signals.any { it.type == AnomalyType.NEW_DESTINATION_COUNTRY })
        assertTrue(assessment.severity == AnomalySeverity.ACTIVITY_LOG || assessment.shouldNotify)
    }

    @Test
    fun `strict sensitivity lowers score thresholds`() {
        assertEquals(AnomalySeverity.SILENT, AnomalyEngine.severityForScore(35, AnomalySensitivity.NORMAL))
        assertEquals(AnomalySeverity.ACTIVITY_LOG, AnomalyEngine.severityForScore(35, AnomalySensitivity.STRICT))
    }

    @Test
    fun `baseline history is expected to exclude current anomaly window`() {
        val baselineOnlyHistory = appHistory(txBytes = 100_000)
        val contaminatedHistory = baselineOnlyHistory + appWindow(packageName = "com.cloud", txBytes = 8_000_000)

        val clean =
            engine.evaluate(
                current = trafficWindow(rxBytes = 2_000_000, txBytes = 8_000_000),
                appWindows = listOf(appWindow(packageName = "com.cloud", txBytes = 8_000_000)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.cloud" to baselineOnlyHistory)),
            )
        val contaminated =
            engine.evaluate(
                current = trafficWindow(rxBytes = 2_000_000, txBytes = 8_000_000),
                appWindows = listOf(appWindow(packageName = "com.cloud", txBytes = 8_000_000)),
                history = AnomalyHistory(appWindowsByPackage = mapOf("com.cloud" to contaminatedHistory)),
            )

        assertTrue(clean.score >= contaminated.score)
        assertTrue(clean.signals.any { it.type == AnomalyType.APP_UPLOAD_SPIKE })
    }

    @Test
    fun `combine signals saturates instead of summing`() {
        val score =
            AnomalyEngine.combineSignals(
                listOf(
                    com.foxhole.beta.core.model.AnomalySignal(AnomalyType.APP_UPLOAD_SPIKE, 0.60, "a", emptyMap()),
                    com.foxhole.beta.core.model.AnomalySignal(AnomalyType.NEW_DESTINATION_COUNTRY, 0.60, "b", emptyMap()),
                ),
            )

        assertEquals(84, score)
    }

    private fun trafficWindow(
        rxBytes: Long = 1_000_000,
        txBytes: Long = 1_000_000,
        destinationCountries: Map<String, Long> = mapOf("DE" to rxBytes + txBytes),
    ): TrafficWindow =
        TrafficWindow(
            startedAtMs = 1_000_000,
            durationSec = 60,
            networkType = NetworkType.WIFI,
            vpnMode = VpnMode.NORMAL,
            profileId = "1",
            protocol = "vless",
            rxBytes = rxBytes,
            txBytes = txBytes,
            blockedDns = 0,
            allowedDns = 0,
            reconnects = 0,
            latencyMs = 100,
            destinationCountries = destinationCountries,
        )

    private fun appWindow(
        packageName: String,
        rxBytes: Long = 0,
        txBytes: Long,
        foreground: Boolean? = true,
    ): AppTrafficWindow =
        AppTrafficWindow(
            packageName = packageName,
            startedAtMs = 1_000_000,
            durationSec = 60,
            rxBytes = rxBytes,
            txBytes = txBytes,
            foreground = foreground,
            networkType = NetworkType.WIFI,
            uid = 42,
        )

    private fun appHistory(txBytes: Long): List<AppTrafficWindow> =
        (1..14).map { index ->
            appWindow(
                packageName = "com.chat",
                txBytes = txBytes + index,
                foreground = true,
            )
        }

    private fun trafficHistory(country: String): List<TrafficWindow> =
        (1..14).map { index ->
            trafficWindow(destinationCountries = mapOf(country to 1_000_000L + index))
        }
}
