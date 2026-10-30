package com.foxhole.guard.runtime

import com.foxhole.guard.core.security.FakeGuardCrypto
import com.foxhole.guard.guardian.FakeGuardClock
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.GuardJournal
import com.foxhole.guard.guardian.GuardJournalStatus
import com.foxhole.guard.guardian.GuardJournalVerifier
import com.foxhole.guard.traffic.RuntimeAuditDrain
import com.foxhole.guard.traffic.RuntimeAuditEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RuntimeValidationAuditTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `failed preconnected epoch drains once within the native event budget`() {
        val calls = mutableListOf<Int>()
        val observed = collectValidationAudit(
            isCurrentOwner = { true },
            drain = { max ->
                calls += max
                """{"events":[{"type":"blocked","reason":"unattributed","transport":"udp"}],"dropped":0}"""
            },
            retainCritical = { true },
        )
        assertEquals(listOf(128), calls)
        assertEquals("drained", observed?.outcome)
        assertEquals(mapOf(("unattributed" to "udp") to 1), observed?.summary?.blocked)
    }

    @Test
    fun `obsolete epoch never touches the destructive native queue`() {
        var reads = 0
        assertNull(
            collectValidationAudit(
                { false },
                {
                    reads += 1
                    "{}"
                },
                { true },
            ),
        )
        assertEquals(0, reads)
    }

    @Test
    fun `unavailable and malformed data never claim an empty successful read`() {
        assertEquals("unavailable", collectValidationAudit({ true }, { null }, { true })?.outcome)
        assertEquals("invalid", collectValidationAudit({ true }, { "invalid" }, { true })?.outcome)
        assertEquals("invalid", collectValidationAudit({ true }, { "{}" }, { true })?.outcome)
        val empty = collectValidationAudit({ true }, { """{"events":[],"dropped":0}""" }, { true })
        assertEquals("drained", empty?.outcome)
        assertEquals(0, empty?.summary?.events)
    }

    @Test
    fun `reason aggregates cannot carry raw identities addresses or config fields`() {
        val events = listOf(
            blocked("unattributed", "udp", "com.private.alpha"),
            blocked("unattributed", "udp", "com.private.beta"),
            blocked("policy", "tcp", "com.private.gamma"),
            blocked("uid=1234 package=com.private.secret", "udp host=192.0.2.7", "secret"),
        )
        val summary = summarizeValidationAudit(RuntimeAuditDrain(events), { true })
        assertEquals(
            mapOf(("unattributed" to "udp") to 2, ("policy" to "tcp") to 1, ("unknown" to "unknown") to 1),
            summary.blocked,
        )
        val text = summary.toString()
        listOf("com.private", "192.0.2.7", "1234", "secret", "config=").forEach { assertFalse(text.contains(it)) }
    }

    @Test
    fun `critical semantic events round trip through the real normal journal writer`() {
        val crypto = FakeGuardCrypto()
        val keys = crypto.sealedBoxKeypair()
        val directory = temporaryFolder.newFolder()
        val journal = GuardJournal(directory, crypto, FakeGuardClock(), { keys.publicKey }, { -1L })
        val events = criticalEvents()
        val expected = events.mapNotNull(::ungroupedRuntimeAuditGuardEvent)
        val summary = summarizeValidationAudit(RuntimeAuditDrain(events), journal::append)
        val verified = GuardJournalVerifier(directory, crypto).verify(keys.publicKey, keys.privateKey, null)
        assertEquals(GuardJournalStatus.OK, verified.status)
        assertEquals(expected, verified.entries.drop(1).map { it.event })
        assertEquals(0, summary.journalNotRetained)
        assertEquals(GuardEventType.CORE_CONFIG_APPLIED, expected[0].type)
        assertEquals("interruption=network_switch token=987", expected[1].detail)
        assertEquals("target=package scope=com.private.revoked count=3", expected[2].detail)
    }

    @Test
    fun `unavailable journal policy is reported without creating keys or records`() {
        val crypto = FakeGuardCrypto()
        val directory = temporaryFolder.newFolder()
        val journal = GuardJournal(directory, crypto, FakeGuardClock(), { null }, { -1L })
        val summary = summarizeValidationAudit(RuntimeAuditDrain(criticalEvents()), journal::append)
        assertEquals(3, summary.journalNotRetained)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `superseded read preserves old semantic events and suppresses aggregate output`() {
        var current = true
        val retained = mutableListOf<GuardEvent>()
        val observation = collectValidationAudit(
            isCurrentOwner = { current },
            drain = {
                current = false
                """{"events":[{"type":"config_applied","revision":5,"previous_revision":4}],"dropped":0}"""
            },
            retainCritical = {
                retained += it
                true
            },
        )
        assertNull(observation)
        assertEquals(listOf(GuardEvent(GuardEventType.CORE_CONFIG_APPLIED, detail = "previous=4 revision=5")), retained)
    }

    @Test
    fun `normal grouped firewall dns and gap records retain their existing semantics`() {
        val event = blocked("unattributed", "tcp", "com.private.alpha")
        val dns = RuntimeAuditEvent.DnsBlocked("private.example", null, "com.private.beta", "dns_blocklist")
        val records = validationAuditGuardEvents(RuntimeAuditDrain(listOf(event, event, dns), dropped = 2L))
        assertEquals(3, records.size)
        assertEquals(GuardEventType.CORE_FLOW_BLOCKED, records[0].type)
        assertEquals("reason=unattributed transport=tcp count=2", records[0].detail)
        assertEquals(event.uid, records[0].uid)
        assertEquals(event.packageName, records[0].packageName)
        assertEquals(GuardEventType.CORE_DNS_BLOCKED, records[1].type)
        assertEquals("reason=dns_blocklist category=none count=1", records[1].detail)
        assertEquals(GuardEventType.CORE_EVENT_GAP, records[2].type)
        assertEquals("audit_dropped=2 traffic_dropped=0", records[2].detail)
    }

    @Test
    fun `throwing native drain reports failure without replacing the dns result`() = runBlocking {
        val original = IllegalStateException("original DNS failure")
        var observation: ValidationAuditObservation? = null
        val result = Result.failure<Unit>(original).observeValidationFailure(
            observe = {
                observation = collectValidationAudit({ true }, { error("private config secret") }, { true })
            },
            onObservationFailed = { error("ordinary drain exception should become a typed observation") },
        )
        assertSame(original, result.exceptionOrNull())
        assertEquals("read_failed", observation?.outcome)
        assertNull(observation?.summary)
    }

    @Test
    fun `throwing journal records retention failures and preserves original dns failure`() = runBlocking {
        val original = IllegalStateException("original DNS failure")
        var summary: ValidationAuditSummary? = null
        val result = Result.failure<Unit>(original).observeValidationFailure(
            observe = {
                summary = summarizeValidationAudit(RuntimeAuditDrain(criticalEvents())) {
                    error("private provider failure")
                }
            },
            onObservationFailed = { error("journal exception should become retention count") },
        )
        assertSame(original, result.exceptionOrNull())
        assertEquals(3, summary?.journalNotRetained)
    }

    @Test
    fun `unexpected observation exception leaves original result and a safe failed notice`() = runBlocking {
        val original = IllegalStateException("original DNS failure")
        val notices = mutableListOf<String>()
        val result = Result.failure<Unit>(original).observeValidationFailure(
            observe = { error("raw host and package must not be logged") },
            onObservationFailed = { notices += "audit_read=failed" },
        )
        assertSame(original, result.exceptionOrNull())
        assertEquals(listOf("audit_read=failed"), notices)
        val brokenNotice = Result.failure<Unit>(original).observeValidationFailure(
            observe = { error("ordinary observer failure") },
            onObservationFailed = { error("ordinary logger failure") },
        )
        assertSame(original, brokenNotice.exceptionOrNull())
    }

    @Test
    fun `caller cancellation from observation is never changed to a dns result`() = runBlocking {
        val cancellation = CancellationException("caller stop")
        try {
            Result.failure<Unit>(IllegalStateException("original DNS failure")).observeValidationFailure(
                observe = { throw cancellation },
                onObservationFailed = { error("stop cannot become observation failure") },
            )
            error("cancellation was swallowed")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun `cancellation from native drain and journal callback remains cancellation`() {
        val cancellation = CancellationException("caller stop")
        try {
            collectValidationAudit({ true }, { throw cancellation }, { true })
            error("drain cancellation was swallowed")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        try {
            summarizeValidationAudit(RuntimeAuditDrain(criticalEvents())) { throw cancellation }
            error("journal cancellation was swallowed")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
    }

    @Test
    fun `successful validation never executes failure observation`() = runBlocking {
        val original = Result.success(42)
        val result = original.observeValidationFailure(
            observe = { error("successful validation must not drain") },
            onObservationFailed = { error("successful validation has no failed notice") },
        )
        assertEquals(original, result)
    }

    private fun blocked(reason: String, transport: String, packageName: String) =
        RuntimeAuditEvent.Blocked(reason, transport, "192.0.2.7", 53, 1234, packageName)

    private fun criticalEvents(): List<RuntimeAuditEvent> = listOf(
        RuntimeAuditEvent.ConfigApplied(5L, 4L),
        RuntimeAuditEvent.ConfirmationRequired("network_switch", 987L, 120L),
        RuntimeAuditEvent.FlowsRevoked("package", "com.private.revoked", 3),
    )
}
