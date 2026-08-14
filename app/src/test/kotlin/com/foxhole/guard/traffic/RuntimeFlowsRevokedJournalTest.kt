package com.foxhole.guard.traffic

import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.runtime.ungroupedRuntimeAuditGuardEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `flows_revoked` is the enforcement half of blocking an app: a policy reload leaves open flows
 * alone, so without this call "blocked" only takes effect when the app's sockets happen to close.
 * The core publishes the request whether or not it cut anything, and the app parsed the other seven
 * variants while dropping this one — cutting an app off the network left no journal entry at all.
 */
class RuntimeFlowsRevokedJournalTest {
    @Test
    fun `a package revocation parses with its target, scope and count`() {
        val drain =
            parseRuntimeAuditDrain(
                """
                {
                  "events": [
                    {"type":"flows_revoked","target":"package","scope":"com.example.app","count":3}
                  ],
                  "dropped": 0
                }
                """.trimIndent(),
            )

        assertEquals(
            listOf(
                RuntimeAuditEvent.FlowsRevoked(
                    target = "package",
                    scope = "com.example.app",
                    count = 3,
                ),
            ),
            drain.events,
        )
    }

    @Test
    fun `a revocation that cut nothing is still an event`() {
        val drain =
            parseRuntimeAuditDrain(
                """
                {
                  "events": [
                    {"type":"flows_revoked","target":"package","scope":"com.example.app","count":0}
                  ],
                  "dropped": 0
                }
                """.trimIndent(),
            )

        assertEquals(
            listOf(RuntimeAuditEvent.FlowsRevoked("package", "com.example.app", count = 0)),
            drain.events,
        )
    }

    @Test
    fun `a device-wide revocation carries no scope`() {
        val drain =
            parseRuntimeAuditDrain(
                """
                {"events": [{"type":"flows_revoked","target":"all","count":41}], "dropped": 0}
                """.trimIndent(),
            )

        assertEquals(
            listOf(RuntimeAuditEvent.FlowsRevoked(target = "all", scope = null, count = 41)),
            drain.events,
        )
    }

    @Test
    fun `an event without a target is dropped rather than journalled as an unnamed cut`() {
        val drain =
            parseRuntimeAuditDrain(
                """
                {"events": [{"type":"flows_revoked","scope":"com.example.app","count":3}], "dropped": 0}
                """.trimIndent(),
            )

        assertEquals(emptyList<RuntimeAuditEvent>(), drain.events)
    }

    @Test
    fun `the journal row keeps target, scope and count and attributes the package`() {
        val journalEvent =
            ungroupedRuntimeAuditGuardEvent(
                RuntimeAuditEvent.FlowsRevoked(target = "package", scope = "com.example.app", count = 3),
            )

        assertEquals(GuardEventType.CORE_FLOWS_REVOKED, journalEvent?.type)
        assertEquals("com.example.app", journalEvent?.packageName)
        assertEquals("target=package scope=com.example.app count=3", journalEvent?.detail)
    }

    @Test
    fun `a lane revocation is journalled without inventing a package attribution`() {
        val journalEvent =
            ungroupedRuntimeAuditGuardEvent(
                RuntimeAuditEvent.FlowsRevoked(target = "lane", scope = "tor", count = 0),
            )

        assertEquals(GuardEventType.CORE_FLOWS_REVOKED, journalEvent?.type)
        assertNull(journalEvent?.packageName)
        assertEquals("target=lane scope=tor count=0", journalEvent?.detail)
    }

    @Test
    fun `a device-wide revocation names no scope in the journal`() {
        val journalEvent =
            ungroupedRuntimeAuditGuardEvent(
                RuntimeAuditEvent.FlowsRevoked(target = "all", scope = null, count = 41),
            )

        assertEquals("target=all scope=none count=41", journalEvent?.detail)
    }
}
