package com.foxhole.guard.ui.cli.home

import com.foxhole.core.model.AnomalySettings
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.ApkScanSettings
import com.foxhole.core.model.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CliSentinelNoticeStatusTest {
    @Test
    fun disabledDetectionMasterDoesNotClaimNotificationReadiness() {
        val settings =
            Settings(
                anomaly = AnomalySettings(
                    notifyUnusualTraffic = true,
                    apkScan = ApkScanSettings(notifyFindings = true)
                )
            )
        val policy = cliSentinelNoticePolicy(settings)
        assertFalse(policy.traffic)
        assertFalse(policy.staticChecks)
        assertEquals(CliSentinelNoticeState.OFF, cliSentinelNoticeSummary(policy, allowed))
    }

    @Test
    fun manualStaticNoticeConsentDoesNotRequireAutomaticDexChecks() {
        val settings =
            Settings(
                anomaly = AnomalySettings(
                    enabled = true,
                    apkScan = ApkScanSettings(notifyFindings = true)
                )
            )
        assertTrue(cliSentinelNoticePolicy(settings).staticChecks)
        assertEquals(
            CliSentinelNoticeState.ALLOWED,
            cliSentinelNoticeSummary(cliSentinelNoticePolicy(settings), allowed)
        )
    }

    @Test
    fun installNoticeReadinessFollowsMonitoringOrExplicitQuarantine() {
        val defaults = Settings()
        assertFalse(cliSentinelNoticePolicy(defaults).appChanges)
        assertTrue(
            cliSentinelNoticePolicy(
                defaults.copy(expert = defaults.expert.copy(newAppQuarantineEnabled = true))
            ).appChanges
        )
        val monitoring = defaults.copy(
            anomaly = AnomalySettings(enabled = true),
            statistics = defaults.statistics.copy(enabled = true, appChangesEnabled = true),
        )
        assertTrue(cliSentinelNoticePolicy(monitoring).appChanges)
        assertFalse(
            cliSentinelNoticePolicy(
                monitoring.copy(statistics = monitoring.statistics.copy(enabled = false))
            ).appChanges
        )
    }

    @Test
    fun androidDenialOutranksOtherwiseEnabledChannels() {
        assertEquals(
            CliSentinelNoticeState.APP_BLOCKED,
            cliSentinelNoticeSummary(allSources, allowed.copy(app = CliNotificationAccess.BLOCKED)),
        )
    }

    @Test
    fun disabledUnusedChannelDoesNotBlockEnabledTrafficNotices() {
        assertEquals(
            CliSentinelNoticeState.ALLOWED,
            cliSentinelNoticeSummary(
                CliSentinelNoticePolicy(traffic = true),
                allowed.copy(installations = CliNotificationAccess.BLOCKED),
            ),
        )
    }

    @Test
    fun mixedChannelPermissionsAreReportedAsPartial() {
        assertEquals(
            CliSentinelNoticeState.PARTLY_BLOCKED,
            cliSentinelNoticeSummary(allSources, allowed.copy(detections = CliNotificationAccess.BLOCKED)),
        )
        assertEquals(
            CliSentinelNoticeState.CHANNEL_BLOCKED,
            cliSentinelNoticeSummary(
                CliSentinelNoticePolicy(traffic = true),
                allowed.copy(detections = CliNotificationAccess.BLOCKED),
            ),
        )
    }

    @Test
    fun uncreatedChannelIsPendingRatherThanClaimedAllowed() {
        assertEquals(
            CliSentinelNoticeState.CHANNEL_PENDING,
            cliSentinelNoticeSummary(allSources, allowed.copy(detections = CliNotificationAccess.NOT_CREATED)),
        )
    }

    @Test
    fun lookupFailureIsVisibleInsteadOfReportedOffOrAllowed() {
        assertEquals(
            CliSentinelNoticeState.UNAVAILABLE,
            cliSentinelNoticeSummary(allSources, allowed.copy(app = CliNotificationAccess.UNKNOWN)),
        )
        assertEquals(
            CliSentinelNoticeState.UNAVAILABLE,
            cliSentinelNoticeSummary(allSources, allowed.copy(installations = CliNotificationAccess.UNKNOWN)),
        )
    }

    @Test
    fun neutralStaticObservationsDoNotBecomeWarningsInExtendedStatus() {
        assertEquals(CliLineTone.INFO, cliSentinelEventTone(AnomalySeverity.ACTIVITY_LOG))
        assertEquals(CliLineTone.WARN, cliSentinelEventTone(AnomalySeverity.NOTIFICATION))
        assertEquals(CliLineTone.ERR, cliSentinelEventTone(AnomalySeverity.HIGH))
    }

    private val allowed = CliSentinelNoticeAccess(
        app = CliNotificationAccess.ALLOWED,
        detections = CliNotificationAccess.ALLOWED,
        installations = CliNotificationAccess.ALLOWED,
    )
    private val allSources = CliSentinelNoticePolicy(traffic = true, staticChecks = true, appChanges = true)
}
