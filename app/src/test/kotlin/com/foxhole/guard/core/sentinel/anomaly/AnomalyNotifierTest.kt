package com.foxhole.guard.core.sentinel.anomaly

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnomalyNotifierTest {
    @Test
    fun blockedAppDoesNotCreateChannelOrSubmit() {
        val delivery = RecordingDelivery(appEnabled = false)
        assertFalse(AnomalyNotifier(delivery).notify(event()))
        assertEquals(listOf("app"), delivery.calls)
    }

    @Test
    fun blockedChannelDoesNotSubmit() {
        val delivery = RecordingDelivery(channelEnabled = false)
        assertFalse(AnomalyNotifier(delivery).notify(event()))
        assertEquals(listOf("app", "channel_create", "channel_check"), delivery.calls)
    }

    @Test
    fun successfulSubmissionRetainsCurrentIdsAndEvent() {
        val delivery = RecordingDelivery()
        val notifier = AnomalyNotifier(delivery)
        val expectedIds = mapOf(
            AnomalyType.APP_UPLOAD_SPIKE to 8400,
            AnomalyType.APP_BACKGROUND_TRAFFIC to 8401,
            AnomalyType.TOTAL_TRAFFIC_SPIKE to 8402,
            AnomalyType.NEW_DESTINATION_COUNTRY to 8403,
            AnomalyType.DNS_BLOCK_RATIO_SPIKE to 8404,
            AnomalyType.RECONNECT_STORM to 8405,
            AnomalyType.LATENCY_SHIFT to 8406,
            AnomalyType.TOR_OR_I2P_ROUTE_MISMATCH to 8407,
            AnomalyType.DORMANT_APP_NETWORK_ACTIVITY to 8408,
            AnomalyType.KNOWN_THREAT_DESTINATION to 8409,
            AnomalyType.APP_STATIC_REFERENCE to 8410,
        )
        assertEquals(AnomalyType.entries.toSet(), expectedIds.keys)
        expectedIds.forEach { (type, id) ->
            val notificationEvent = event(type)
            assertTrue(notifier.notify(notificationEvent))
            assertEquals(id, delivery.submissions.last().first)
            assertSame(notificationEvent, delivery.submissions.last().second)
        }
        notifier.cancelAll()
        assertEquals(expectedIds.values.toSet(), delivery.cancelled.toSet())
        assertEquals(expectedIds.size, delivery.cancelled.size)
    }

    @Test
    fun laterSeverityAndPackageAreSubmittedUnderSameTypeId() {
        val delivery = RecordingDelivery()
        val notifier = AnomalyNotifier(delivery)
        val initial = event()
        val escalated = initial.copy(severity = AnomalySeverity.HIGH, score = 90, packageName = "owned.example")
        assertTrue(notifier.notify(initial))
        assertTrue(notifier.notify(escalated))
        assertEquals(listOf(8402, 8402), delivery.submissions.map { it.first })
        assertSame(escalated, delivery.submissions.last().second)
    }

    @Test
    fun ordinaryChannelLookupFailureDoesNotClaimSubmission() {
        val delivery = RecordingDelivery(failureAt = "channel_check", failure = IOException("owned failure"))
        assertFalse(AnomalyNotifier(delivery).notify(event()))
        assertTrue(delivery.submissions.isEmpty())
    }

    @Test
    fun cancelledSubmissionEscapesWithoutSuccessfulDelivery() {
        val failure = CancellationException("owned cancellation")
        val delivery = RecordingDelivery(failureAt = "submit", failure = failure)
        assertSame(failure, failureFrom { AnomalyNotifier(delivery).notify(event()) })
        assertTrue(delivery.submissions.isEmpty())
    }

    @Test
    fun fatalAvailabilityFailureEscapes() {
        val failure = AssertionError("owned fatal")
        val delivery = RecordingDelivery(failureAt = "app", failure = failure)
        assertSame(failure, failureFrom { AnomalyNotifier(delivery).notify(event()) })
        assertEquals(listOf("app"), delivery.calls)
    }

    private fun event(type: AnomalyType = AnomalyType.TOTAL_TRAFFIC_SPIKE) = AnomalyEvent(
        createdAtMs = 1L,
        type = type,
        severity = AnomalySeverity.NOTIFICATION,
        score = 75,
        reason = "owned fixture",
        evidence = emptyMap(),
    )

    private fun failureFrom(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (failure: Throwable) {
            failure
        }

    private class RecordingDelivery(
        private val appEnabled: Boolean = true,
        private val channelEnabled: Boolean = true,
        private val failureAt: String? = null,
        private val failure: Throwable? = null,
    ) : AnomalyNotificationDelivery {
        val calls = mutableListOf<String>()
        val submissions = mutableListOf<Pair<Int, AnomalyEvent>>()
        val cancelled = mutableListOf<Int>()

        override fun isAppEnabled(): Boolean {
            call("app")
            return appEnabled
        }

        override fun ensureChannel() = call("channel_create")

        override fun isChannelEnabled(): Boolean {
            call("channel_check")
            return channelEnabled
        }

        override fun submit(event: AnomalyEvent, notificationId: Int) {
            call("submit")
            submissions += notificationId to event
        }

        override fun cancel(notificationId: Int) {
            cancelled += notificationId
        }

        private fun call(stage: String) {
            calls += stage
            if (stage == failureAt) failure?.let { throw it }
        }
    }
}
