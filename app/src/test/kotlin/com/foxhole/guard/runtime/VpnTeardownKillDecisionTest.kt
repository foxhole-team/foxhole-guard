package com.foxhole.guard.runtime

import com.foxhole.core.runtime.NativeForceStopOutcome
import com.foxhole.core.runtime.NativeForceStopPoisonedException
import com.foxhole.core.runtime.NativeRuntimeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VpnTeardownKillDecisionTest {
    @Test
    fun `confirmed process tun keeps fail closed process escalation`() {
        assertTrue(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.OPEN,
            ),
        )
    }

    @Test
    fun `stale native ownership without a positive foreign tun probe cannot kill process`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE.copy(hasEngineHandle = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE.copy(hasTunFileDescriptor = true),
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
    }

    @Test
    fun `unknown proc result cannot kill a resource free runtime`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.UNKNOWN,
            ),
        )
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
            ),
        )
    }

    @Test
    fun `released force stop plus connectivity manager lag cannot kill without foreign tun`() {
        assertFalse(
            shouldTerminateProcessForStuckTunnel(
                nativeSnapshot = NativeRuntimeSnapshot.NONE,
                tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
                forceStopOutcome = NativeForceStopOutcome.RELEASED,
            ),
        )
    }

    @Test
    fun `poisoned force stop kills independently of foreign tun`() {
        listOf(
            NativeForceStopOutcome.QUARANTINED,
            NativeForceStopOutcome.FAILED,
            NativeForceStopOutcome.CALL_TIMED_OUT,
        ).forEach { outcome ->
            assertTrue(
                shouldTerminateProcessForStuckTunnel(
                    nativeSnapshot = NativeRuntimeSnapshot.NONE,
                    tunDescriptorProbe = ProcessTunDescriptorProbe.CLOSED,
                    forceStopOutcome = outcome,
                ),
            )
        }
    }

    @Test
    fun `poisoned force stop has a short bounded settle before termination`() {
        assertEquals(0L, nativeForceStopTerminationSettleMs(NativeForceStopOutcome.RELEASED))
        val settleMs = nativeForceStopTerminationSettleMs(NativeForceStopOutcome.QUARANTINED)
        assertTrue(settleMs in 1L..1_000L)
    }

    @Test
    fun `old and successor service paths share one process termination claim`() {
        val gate = NativeForceStopTerminationGate()
        val claims =
            listOf(
                gate.tryClaim(NativeForceStopOutcome.QUARANTINED),
                gate.tryClaim(NativeForceStopOutcome.FAILED),
                gate.tryClaim(NativeForceStopOutcome.CALL_TIMED_OUT),
            )

        assertEquals(1, claims.count { claimed -> claimed })
        assertFalse(gate.tryClaim(NativeForceStopOutcome.RELEASED))
    }

    @Test
    fun `typed poison helper terminates once for every poisoned outcome`() =
        assertPoisonStopsFallback()

    @Test
    fun `reload call sites handle poison before stale checks and recovery`() {
        val reloadSource = runtimeSource("FoxholeVpnServiceReloadSupport.kt")
        val vpnReload =
            reloadSource.functionSection(
                "private suspend fun FoxholeVpnService.reloadRuntime(",
                "internal fun runtimeReloadPendingSnapshot(",
            )
        assertBefore(
            vpnReload,
            "handleNativeForceStopPoison(result)",
            "!isCurrentRuntimeTransition(transitionGeneration, \"reload_result\")",
        )
        assertBefore(vpnReload, "handleNativeForceStopPoison(result)", "handleRuntimeReloadFailure(")

        val localGuardReload =
            reloadSource.functionSection(
                "private suspend fun FoxholeVpnService.reloadLocalGuardRuntime(",
                "private suspend fun FoxholeVpnService.handleRuntimeReloadFailure(",
            )
        assertBefore(
            localGuardReload,
            "handleNativeForceStopPoison(result)",
            "!isCurrentRuntimeTransition(transitionGeneration, \"local_guard_reload_result\")",
        )
        assertBefore(localGuardReload, "handleNativeForceStopPoison(result)", "scheduleLocalGuardHeal(")

        val restore =
            reloadSource.functionSection(
                "private suspend fun FoxholeVpnService.restorePreviousRuntimeConfigAfterReloadFailure(",
                "private fun FoxholeVpnService.handlePreviousRuntimeConfigRestoreResult(",
            )
        assertBefore(
            restore,
            "handleNativeForceStopPoison(restoreResult)",
            "!isCurrentRuntimeTransition(transitionGeneration, \"reload_restore_result\")",
        )
        assertBefore(
            restore,
            "handleNativeForceStopPoison(restoreResult)",
            "handlePreviousRuntimeConfigRestoreResult(",
        )

        val splitSource = runtimeSource("RuntimeReconnectCoordinator.kt")
        val splitReload =
            splitSource.functionSection(
                "private suspend fun FoxholeVpnService.reloadSplitVpnRuntime(",
                "internal fun FoxholeVpnService.publishSplitVpnUnavailable(",
            )
        assertBefore(
            splitReload,
            "handleNativeForceStopPoison(result)",
            "!isCurrentRuntimeTransition(transitionGeneration, \"split_vpn_recovery_result\")",
        )
        assertBefore(splitReload, "handleNativeForceStopPoison(result)", "scheduleAutoReconnect(")
    }

    @Test
    fun `fail closed process state summary is fixed and platform bounded`() {
        val first = failClosedProcessStateSummaryBytes()
        val second = failClosedProcessStateSummaryBytes()

        assertTrue(first.contentEquals(second))
        assertTrue(first.isNotEmpty())
        assertTrue(first.size <= 128)
        assertEquals("foxhole_fail_closed_teardown", first.toString(Charsets.UTF_8))
    }

    private fun assertPoisonStopsFallback() =
        runBlocking {
            listOf(
                NativeForceStopOutcome.QUARANTINED,
                NativeForceStopOutcome.FAILED,
                NativeForceStopOutcome.CALL_TIMED_OUT,
            ).forEach { outcome ->
                var terminations = 0
                var fallbacks = 0
                val result =
                    Result.failure<Unit>(
                        NativeForceStopPoisonedException(outcome, "reload_test"),
                    )

                val handled =
                    handleNativeForceStopPoison(result) {
                        terminations += 1
                    }
                if (!handled) {
                    fallbacks += 1
                }

                assertEquals(1, terminations)
                assertEquals(0, fallbacks)
            }
        }

    private fun runtimeSource(name: String): String =
        File("src/main/kotlin/com/foxhole/guard/runtime/$name").readText()

    private fun String.functionSection(
        start: String,
        end: String,
    ): String {
        assertTrue("missing function start: $start", contains(start))
        val section = substringAfter(start)
        assertTrue("missing function end: $end", section.contains(end))
        return section.substringBefore(end)
    }

    private fun assertBefore(
        source: String,
        first: String,
        second: String,
    ) {
        val firstIndex = source.indexOf(first)
        val secondIndex = source.indexOf(second)
        assertTrue("missing source marker: $first", firstIndex >= 0)
        assertTrue("missing source marker: $second", secondIndex >= 0)
        assertTrue("$first must precede $second", firstIndex < secondIndex)
    }
}
