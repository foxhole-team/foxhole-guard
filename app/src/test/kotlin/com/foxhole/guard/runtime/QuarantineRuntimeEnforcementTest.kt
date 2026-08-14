package com.foxhole.guard.runtime

import com.foxhole.core.model.AppTunnelLane
import com.foxhole.core.model.ExpertSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.RuntimeCommandQueueSnapshot
import com.foxhole.core.runtime.RuntimeState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuarantineRuntimeEnforcementTest {
    @Test
    fun `idle runtime needs no dispatch`() =
        runBlocking {
            var dispatched = false
            val coordinator = coordinator(snapshot = idleSnapshot()) {
                dispatched = true
                null
            }

            assertEquals(QuarantineEnforcementOutcome.GENUINELY_IDLE, coordinator.enforce(1L))
            assertTrue(!dispatched)
        }

    @Test
    fun `queued start can provide the exact applied ack`() =
        runBlocking {
            var current = queuedStartSnapshot(generation = 7L)
            val owner = QuarantineRuntimeOwner.Profile(profileId = 5L, correlationId = "session-5")
            val coordinator =
                QuarantineEnforcementCoordinator(
                    snapshot = { current },
                    currentReceipt = { null },
                    dispatch = { true },
                    awaitReceipt = {
                        current = activeSnapshot(owner = owner, generation = 8L)
                        receipt(revision = 1L, owner = owner, generation = 8L)
                    },
                )

            assertEquals(QuarantineEnforcementOutcome.APPLIED, coordinator.enforce(1L))
        }

    @Test
    fun `accepted dispatch without applied ack retries`() =
        runBlocking {
            val owner = QuarantineRuntimeOwner.Profile(profileId = 5L, correlationId = "session-5")
            val coordinator = coordinator(snapshot = activeSnapshot(owner, 4L)) { null }

            assertEquals(QuarantineEnforcementOutcome.RETRY, coordinator.enforce(1L))
        }

    @Test
    fun `matching applied ack succeeds without another dispatch`() =
        runBlocking {
            val owner = QuarantineRuntimeOwner.Profile(profileId = 5L, correlationId = "session-5")
            val snapshot = activeSnapshot(owner, 4L)
            var dispatches = 0
            val coordinator =
                QuarantineEnforcementCoordinator(
                    snapshot = { snapshot },
                    currentReceipt = { receipt(3L, owner, 4L) },
                    dispatch = {
                        dispatches += 1
                        true
                    },
                    awaitReceipt = { null },
                )

            assertEquals(QuarantineEnforcementOutcome.APPLIED, coordinator.enforce(3L))
            assertEquals(0, dispatches)
        }

    @Test
    fun `profile switch rejects stale ack`() =
        runBlocking {
            val oldOwner = QuarantineRuntimeOwner.Profile(profileId = 5L, correlationId = "old")
            val newOwner = QuarantineRuntimeOwner.Profile(profileId = 6L, correlationId = "new")
            var current = activeSnapshot(oldOwner, 9L)
            val coordinator =
                QuarantineEnforcementCoordinator(
                    snapshot = { current },
                    currentReceipt = { null },
                    dispatch = { true },
                    awaitReceipt = {
                        current = activeSnapshot(newOwner, 10L)
                        receipt(revision = 4L, owner = oldOwner, generation = 9L)
                    },
                )

            assertEquals(QuarantineEnforcementOutcome.RETRY, coordinator.enforce(4L))
        }

    @Test
    fun `two installs require the latest revision`() =
        runBlocking {
            val owner = QuarantineRuntimeOwner.Profile(profileId = 5L, correlationId = "session-5")
            val snapshot = activeSnapshot(owner, 12L)
            val dispatched = mutableListOf<Long>()
            val coordinator =
                QuarantineEnforcementCoordinator(
                    snapshot = { snapshot },
                    currentReceipt = { receipt(1L, owner, 12L) },
                    dispatch = { revision ->
                        dispatched += revision
                        true
                    },
                    awaitReceipt = { revision -> receipt(revision, owner, 12L) },
                )

            assertEquals(QuarantineEnforcementOutcome.APPLIED, coordinator.enforce(2L))
            assertEquals(listOf(2L), dispatched)
        }

    @Test
    fun `process restart clears ack and persisted block rearms work`() {
        val previousProcess = QuarantineRuntimeEnforcementTracker()
        previousProcess.acknowledge(session(revision = 6L), runtimeGeneration = 20L)
        val restartedProcess = QuarantineRuntimeEnforcementTracker()
        val settings =
            Settings(
                expert =
                ExpertSettings(
                    quarantinePolicyRevision = 6L,
                    pendingQuarantinePackages = listOf("com.example.new"),
                    appAssignments = mapOf("com.example.new" to AppTunnelLane.BLOCK),
                ),
            )

        assertNull(restartedProcess.applied.value)
        assertTrue(shouldRearmQuarantineRuntimeEnforcement(settings))
    }

    private fun coordinator(
        snapshot: QuarantineRuntimeEnforcementSnapshot,
        await: suspend (Long) -> AppliedQuarantinePolicy?,
    ) =
        QuarantineEnforcementCoordinator(
            snapshot = { snapshot },
            currentReceipt = { null },
            dispatch = { true },
            awaitReceipt = await,
        )

    private fun idleSnapshot() =
        QuarantineRuntimeEnforcementSnapshot(
            owner = null,
            runtimeGeneration = 0L,
            commandQueue = RuntimeCommandQueueSnapshot.EMPTY.copy(closed = false),
            nativeSnapshot = NativeRuntimeSnapshot.NONE,
            retiredRuntimePresent = false,
        )

    private fun queuedStartSnapshot(generation: Long) =
        QuarantineRuntimeEnforcementSnapshot(
            owner = null,
            runtimeGeneration = generation,
            commandQueue =
            RuntimeCommandQueueSnapshot(
                closed = false,
                normalBuffered = 0,
                priorityBuffered = 0,
                pending = 0,
                running = true,
                runningPriority = RuntimeCommandPriority.SWITCH.name.lowercase(),
                runningReason = "connect:5:default",
                lastSequence = generation,
            ),
            nativeSnapshot = NativeRuntimeSnapshot.NONE.copy(nativeState = RuntimeState.PREPARING),
            retiredRuntimePresent = false,
        )

    private fun activeSnapshot(
        owner: QuarantineRuntimeOwner,
        generation: Long,
    ) =
        QuarantineRuntimeEnforcementSnapshot(
            owner = owner,
            runtimeGeneration = generation,
            commandQueue = RuntimeCommandQueueSnapshot.EMPTY.copy(closed = false),
            nativeSnapshot =
            NativeRuntimeSnapshot.NONE.copy(
                hasEngineHandle = true,
                hasConfig = true,
                nativeGeneration = generation,
                nativeState = RuntimeState.RUNNING,
            ),
            retiredRuntimePresent = false,
        )

    private fun receipt(
        revision: Long,
        owner: QuarantineRuntimeOwner,
        generation: Long,
    ) =
        AppliedQuarantinePolicy(
            revision = revision,
            runtimeFingerprint = revision.toInt(),
            runtimeGeneration = generation,
            owner = owner,
        )

    private fun session(revision: Long) =
        VpnSession(
            profileId = 5L,
            profileName = "test",
            protocolHint = ProtocolHint.VLESS,
            configJson = "{}",
            correlationId = "session-5",
            runtimeConfigFingerprint = 17,
            quarantinePolicyRevision = revision,
        )
}
