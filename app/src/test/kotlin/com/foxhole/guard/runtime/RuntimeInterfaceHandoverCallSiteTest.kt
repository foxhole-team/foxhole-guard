package com.foxhole.guard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RuntimeInterfaceHandoverCallSiteTest {
    private val connectSource =
        File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceConnectSupport.kt").readText()
    private val localGuardSource =
        File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceLocalGuardSupport.kt").readText()
    private val teardownSource =
        File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnServiceTeardownSupport.kt").readText()
    private val serviceSource =
        File("src/main/kotlin/com/foxhole/guard/runtime/FoxholeVpnService.kt").readText()

    @Test
    fun `the connect path retires the guard, establishes, and only then closes the old interface`() {
        val retire = connectSource.indexOf("retireActiveLocalGuardForTunnelHandover()")
        val handover = connectSource.indexOf("establishNextInterfaceThenStopRetiredRuntimes(")
        val nativeStart = connectSource.indexOf("startRuntimeWithHealthMetrics(session = session, owner = \"vpn\")")

        assertTrue("connect must retire the local guard", retire > 0)
        assertTrue("connect must run the handover", handover > retire)
        assertTrue("the native start must sit inside the handover window", nativeStart > handover)
        assertTrue(
            "validation must be scheduled only after the handover helper returns",
            connectSource.indexOf("pendingTunnelValidation?.let") > handover,
        )
        val startResultBody =
            connectSource
                .substringAfter("private suspend fun FoxholeVpnService.handleRuntimeStartResult")
                .substringBefore("private fun FoxholeVpnService.scheduleTunnelValidationAfterHandoff")
        assertEquals(
            "native start completion must return a pending validation instead of launching it",
            0,
            startResultBody.occurrencesOf("scheduleValidation("),
        )
    }

    @Test
    fun `the local guard path retires the tunnel or the previous guard before establishing`() {
        val retireTunnel = localGuardSource.indexOf("retireActiveTunnelForLocalGuardHandover(mode)")
        val retireGuard = localGuardSource.indexOf("retireRuntimeForInterfaceHandover(\"local_guard_restart\")")
        val handover = localGuardSource.indexOf("establishNextInterfaceThenStopRetiredRuntimesWithProof(")
        val guardStartCall = "startRuntimeWithHealthMetrics(session = session, owner = \"local_guard\")"
        val nativeStart = localGuardSource.indexOf(guardStartCall)

        assertTrue("the guard start must retire an active tunnel", retireTunnel > 0)
        assertTrue("a guard restart must retire the previous guard", retireGuard > retireTunnel)
        assertTrue("the guard start must run the handover", handover > retireGuard)
        assertTrue("the native start must sit inside the handover window", nativeStart > handover)
        assertTrue(
            "guard readiness must exclude the retired VPN network",
            localGuardSource.indexOf("excludedHandle = replacedVpnNetworkHandle") > nativeStart,
        )
        assertTrue(
            "a reused Android network handle must be disambiguated by its TUN interface",
            localGuardSource.indexOf("excludedInterfaceName = replacedVpnInterfaceName") > nativeStart,
        )
        val closeProof = localGuardSource.indexOf("if (!handover.retiredTunClosed)")
        val finalPublish = localGuardSource.indexOf("completeLocalGuardActivation(pending)")
        assertTrue("guard success must wait for the retired TUN close proof", closeProof > handover)
        assertTrue("guard success must be published only after the close proof", finalPublish > closeProof)
    }

    @Test
    fun `neither switch path stops a runtime before the replacement interface exists`() {
        listOf("connect" to connectSource, "local guard" to localGuardSource).forEach { (path, source) ->
            val code = source.withoutComments()
            assertEquals("$path must not stop a runtime directly", 0, code.occurrencesOf("stopRuntimeFailClosed("))
            assertEquals("$path must not close a session directly", 0, code.occurrencesOf("closeRuntimeSession("))
        }
    }

    @Test
    fun `clean profile stop does not publish idle before the firewall is ready`() {
        val disconnectBody = serviceSource
            .substringAfter("internal suspend fun disconnect(")
            .substringBefore("companion object")
        val guardHandover = disconnectBody.indexOf("startLocalGuardAfterProfileDisconnect(")
        val destructiveClose = disconnectBody.indexOf("closeRuntimeSession(")
        val stoppedSnapshot = disconnectBody.indexOf("stoppedRuntimeSnapshot(")

        assertTrue("disconnect must have a firewall handover", guardHandover > 0)
        assertTrue(
            "the firewall handover must run before the ordinary destructive close",
            guardHandover < destructiveClose,
        )
        assertTrue(
            "the handover must return before a stopped snapshot can be published",
            guardHandover < stoppedSnapshot,
        )
        assertTrue(
            "the disconnect handover must bypass the ordinary active-profile defer gate",
            localGuardSource.contains(
                "!preserveDisconnectingState &&\n        shouldDeferLocalGuardStartForActiveProfileRuntime",
            ),
        )
    }

    @Test
    fun `retiring quiesces the native owner without stopping runtime helpers`() {
        val retireBody =
            teardownSource
                .withoutComments()
                .substringAfter("internal suspend fun FoxholeVpnService.retireRuntimeForInterfaceHandover")
                .substringBefore("internal suspend fun FoxholeVpnService.retireActiveLocalGuardForTunnelHandover")
        assertTrue("the retire function must exist", retireBody.isNotBlank())
        assertEquals(1, retireBody.occurrencesOf("quiesceAndRetireCurrent()"))
        assertEquals(
            "retiring must not stop the runtime helpers",
            0,
            retireBody.occurrencesOf("stopRuntimeHelpersIfNeeded("),
        )
        assertEquals(
            "retiring must not close the held master TUN",
            0,
            retireBody.occurrencesOf("stopRuntimeFailClosed("),
        )

        val closeBody =
            teardownSource
                .withoutComments()
                .substringAfter("internal suspend fun FoxholeVpnService.closeRuntimeSession")
                .substringBefore("internal suspend fun FoxholeVpnService.retireRuntimeForInterfaceHandover")
        val destructiveStop =
            closeBody.substringAfter("if (interfaceDisposition == RuntimeInterfaceDisposition.STOP)")
        assertTrue(destructiveStop.contains("markCarrierUnavailable()"))
        assertTrue(destructiveStop.contains("stopRuntimeHelpersIfNeeded("))
        assertTrue(
            "I2P must survive the local-guard to VPN handover and be reused by the new session",
            closeBody.indexOf("if (interfaceDisposition == RuntimeInterfaceDisposition.STOP)") <
                closeBody.indexOf("markCarrierUnavailable()"),
        )
    }

    private fun String.occurrencesOf(needle: String): Int = split(needle).size - 1

    private fun String.withoutComments(): String =
        lineSequence()
            .filterNot { line -> line.trimStart().startsWith("//") || line.trimStart().startsWith("*") }
            .joinToString("\n")
}
