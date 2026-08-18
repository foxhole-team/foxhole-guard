package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.ThreatIndicatorKind
import com.foxhole.core.sentinel.detection.NETWORK_IOC_COMMAND_AND_CONTROL_SCORE
import com.foxhole.core.sentinel.detection.NETWORK_IOC_MALWARE_DISTRIBUTION_SCORE
import com.foxhole.core.sentinel.detection.NETWORK_IOC_SHARED_INFRASTRUCTURE_SCORE
import com.foxhole.core.sentinel.detection.NETWORK_IOC_UNCLASSIFIED_SCORE
import com.foxhole.core.sentinel.detection.NetworkIocFinding
import com.foxhole.core.sentinel.detection.NetworkIocHit
import com.foxhole.core.sentinel.detection.NetworkIocKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIocAnomalyEventTest {

    private fun finding(
        pkg: String = "com.spy.app",
        indicator: String = "evil.example",
        threatKind: ThreatIndicatorKind = ThreatIndicatorKind.COMMAND_AND_CONTROL,
    ) = NetworkIocFinding(
        packageName = pkg,
        remoteHost = "cdn.evil.example",
        hit = NetworkIocHit(NetworkIocKind.DOMAIN, indicator, threatKind),
        timestampMs = 1_000L,
        profileId = 42L,
        protocol = "tcp",
    )

    @Test
    fun `a command-and-control finding becomes a high-severity journal event with full attribution`() {
        val event = networkIocAnomalyEvent(finding(), nowMs = 5_000L, notificationShown = true)

        assertEquals(AnomalyType.KNOWN_THREAT_DESTINATION, event.type)
        assertEquals(AnomalySeverity.HIGH, event.severity)
        assertEquals(NETWORK_IOC_COMMAND_AND_CONTROL_SCORE, event.score)
        assertEquals("com.spy.app", event.packageName)
        assertEquals("42", event.profileId)
        assertEquals("tcp", event.protocol)
        assertEquals("cdn.evil.example", event.evidence["host"])
        assertEquals("evil.example", event.evidence["indicator"])
        assertEquals("DOMAIN", event.evidence["indicator_kind"])
        assertEquals("COMMAND_AND_CONTROL", event.evidence["threat_kind"])
        assertEquals(5_000L, event.createdAtMs)
        assertTrue(event.notificationShown)
    }

    @Test
    fun `a shared-infrastructure finding is journalled without a high-severity claim`() {
        val event =
            networkIocAnomalyEvent(
                finding(threatKind = ThreatIndicatorKind.SHARED_INFRASTRUCTURE),
                nowMs = 5_000L,
                notificationShown = false,
            )

        assertEquals(AnomalySeverity.ACTIVITY_LOG, event.severity)
        assertEquals(NETWORK_IOC_SHARED_INFRASTRUCTURE_SCORE, event.score)
        assertEquals("SHARED_INFRASTRUCTURE", event.evidence["threat_kind"])
        assertTrue(event.reason.contains("shared infrastructure"))
    }

    @Test
    fun `an unclassified finding stays below a high-severity claim`() {
        val event =
            networkIocAnomalyEvent(
                finding(threatKind = ThreatIndicatorKind.UNCLASSIFIED),
                nowMs = 5_000L,
                notificationShown = false,
            )

        assertNotEquals(AnomalySeverity.HIGH, event.severity)
        assertEquals(NETWORK_IOC_UNCLASSIFIED_SCORE, event.score)
        assertEquals("UNCLASSIFIED", event.evidence["threat_kind"])
    }

    @Test
    fun `a malware-distribution finding sits between the two`() {
        val event =
            networkIocAnomalyEvent(
                finding(threatKind = ThreatIndicatorKind.MALWARE_DISTRIBUTION),
                nowMs = 5_000L,
                notificationShown = false,
            )

        assertEquals(AnomalySeverity.NOTIFICATION, event.severity)
        assertEquals(NETWORK_IOC_MALWARE_DISTRIBUTION_SCORE, event.score)
    }

    @Test
    fun `a profile-less flow keeps a null profile id`() {
        val event =
            networkIocAnomalyEvent(
                finding().copy(profileId = null),
                nowMs = 5_000L,
                notificationShown = false,
            )

        assertEquals(null, event.profileId)
    }

    @Test
    fun `a chatty flow is one sighting per package and indicator`() {
        val deduped =
            dedupeNetworkIocFindings(
                listOf(
                    finding(),
                    finding(),
                    finding(pkg = "com.other.app"),
                    finding(indicator = "c2.example"),
                ),
            )

        assertEquals(3, deduped.size)
    }
}
