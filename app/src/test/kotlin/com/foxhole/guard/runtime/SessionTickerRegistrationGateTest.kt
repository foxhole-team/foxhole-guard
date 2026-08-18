package com.foxhole.guard.runtime

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.I2pSettings
import com.foxhole.core.model.Settings
import com.foxhole.core.model.StatisticsSettings
import com.foxhole.guard.core.sentinel.anomaly.sentinelTrafficWindowCollectionEnabled
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTickerRegistrationGateTest {

    @Test
    fun `a session without the I2P module never schedules a webconsole read`() {
        val statisticsOnly =
            Settings(
                statistics = StatisticsSettings(enabled = true),
                i2p = I2pSettings(enabled = false),
            )

        assertFalse(i2pTrafficSamplingPossible(statisticsOnly))
    }

    @Test
    fun `a session without statistics consent never schedules a webconsole read`() {
        val moduleOnly =
            Settings(
                statistics = StatisticsSettings(enabled = false),
                i2p = I2pSettings(enabled = true, engaged = true),
            )

        assertFalse(i2pTrafficSamplingPossible(moduleOnly))
    }

    @Test
    fun `a permitted module under statistics consent samples even while the router is paused`() {
        val paused =
            Settings(
                statistics = StatisticsSettings(enabled = true),
                i2p = I2pSettings(enabled = true, engaged = false),
            )

        assertTrue(i2pTrafficSamplingPossible(paused))
    }

    @Test
    fun `the child fallback poll follows the module permission and not its engagement`() {
        assertFalse(childWatchdogFallbackPollNeeded(Settings(i2p = I2pSettings(enabled = false))))
        assertTrue(
            childWatchdogFallbackPollNeeded(Settings(i2p = I2pSettings(enabled = true, engaged = false))),
        )
        assertTrue(
            childWatchdogFallbackPollNeeded(Settings(i2p = I2pSettings(enabled = true, engaged = true))),
        )
    }

    @Test
    fun `the DNS guard window task is scheduled only for a consented consumer`() {
        val noConsumer =
            Settings(
                statistics = StatisticsSettings(enabled = false),
                anomaly = AnomalySettings(enabled = false),
            )
        assertFalse(sentinelTrafficWindowCollectionEnabled(noConsumer))

        assertTrue(
            sentinelTrafficWindowCollectionEnabled(
                noConsumer.copy(anomaly = AnomalySettings(enabled = true)),
            ),
        )
        assertTrue(
            sentinelTrafficWindowCollectionEnabled(
                noConsumer.copy(statistics = StatisticsSettings(enabled = true)),
            ),
        )
    }

    @Test
    fun `Sentinel country analysis cannot keep runtime polling alive while Sentinel is off`() {
        val sentinelCountryMetric =
            Settings(
                anomaly = AnomalySettings(enabled = false, analyzeDestinationCountries = true),
                statistics =
                StatisticsSettings(
                    enabled = true,
                    countryTrafficEnabled = false,
                    anomalyMetricsEnabled = true,
                ),
            )

        assertFalse(destinationCountryTrackingRuntimeEnabled(sentinelCountryMetric))
        assertTrue(
            destinationCountryTrackingRuntimeEnabled(
                sentinelCountryMetric.copy(anomaly = sentinelCountryMetric.anomaly.copy(enabled = true)),
            ),
        )
        assertTrue(
            destinationCountryTrackingRuntimeEnabled(
                sentinelCountryMetric.copy(
                    statistics = sentinelCountryMetric.statistics.copy(countryTrafficEnabled = true),
                ),
            ),
        )
    }

    @Test
    fun `a feature enabled mid-session restarts its task instead of waiting for a reconnect`() {
        val enabledMidSession =
            Settings(
                statistics = StatisticsSettings(enabled = true),
                i2p = I2pSettings(enabled = true, engaged = true),
            )

        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = false,
                gateOpen = i2pTrafficSamplingPossible(enabledMidSession),
            ),
        )
        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = false,
                gateOpen = childWatchdogFallbackPollNeeded(enabledMidSession),
            ),
        )
        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = false,
                gateOpen = sentinelTrafficWindowCollectionEnabled(enabledMidSession),
            ),
        )
    }

    @Test
    fun `a feature disabled mid-session runs the start that unregisters its task`() {
        val withdrawn =
            Settings(
                statistics = StatisticsSettings(enabled = false),
                anomaly = AnomalySettings(enabled = false),
                i2p = I2pSettings(enabled = false),
            )

        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = true,
                gateOpen = i2pTrafficSamplingPossible(withdrawn),
            ),
        )
        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = true,
                gateOpen = childWatchdogFallbackPollNeeded(withdrawn),
            ),
        )
        assertTrue(
            gatedSessionTaskNeedsRestart(
                registered = true,
                gateOpen = sentinelTrafficWindowCollectionEnabled(withdrawn),
            ),
        )
    }

    @Test
    fun `an unchanged gate is left alone so no pass is re-fired and no cursor is dropped`() {
        assertFalse(gatedSessionTaskNeedsRestart(registered = true, gateOpen = true))
        assertFalse(gatedSessionTaskNeedsRestart(registered = false, gateOpen = false))
    }

    @Test
    fun `a reload only re-evaluates the tasks its own session shape wired`() {
        assertEquals(
            ReloadedSessionTaskScope.TUNNEL_TELEMETRY,
            reloadedSessionTaskScope(trafficJobRegistered = true, dnsGuardActive = false),
        )
        assertEquals(
            ReloadedSessionTaskScope.DNS_GUARD_WINDOW,
            reloadedSessionTaskScope(trafficJobRegistered = false, dnsGuardActive = true),
        )
        assertEquals(
            ReloadedSessionTaskScope.TUNNEL_TELEMETRY,
            reloadedSessionTaskScope(trafficJobRegistered = true, dnsGuardActive = true),
        )
        assertEquals(
            ReloadedSessionTaskScope.WATCHDOG_ONLY,
            reloadedSessionTaskScope(trafficJobRegistered = false, dnsGuardActive = false),
        )
    }
}
