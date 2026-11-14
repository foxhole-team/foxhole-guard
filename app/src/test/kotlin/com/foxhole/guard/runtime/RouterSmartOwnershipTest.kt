package com.foxhole.guard.runtime

import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.TrafficMode
import com.foxhole.core.model.VpnSession
import com.foxhole.core.runtime.RuntimeCommand
import com.foxhole.core.runtime.RuntimeCommandPriority
import com.foxhole.core.runtime.priority
import org.junit.Assert.*
import org.junit.Test

class RouterSmartOwnershipTest {
    private val token = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
    private val owner = RouterOwnedResume(token, 7L, "hysteria2", 4L, 9L)
    private val session = VpnSession(7L, "Home", ProtocolHint.HYSTERIA2, "hysteria2", "{}", "session-a", runtimeConfigFingerprint = 17)
    private fun accepts(supplied: RouterOwnedResume = owner, request: Long = 4L, selected: Long? = 7L,
        material: String? = "secret-generation-a", selection: Long = 2L, authority: Long = 9L,
        idle: Boolean = true, cellular: Boolean = true, anotherVpn: Boolean = false, consent: Boolean = true) =
        routerSmartResumeOwnerMatches(owner, supplied, request, selected, material, "secret-generation-a", selection, 2L,
            authority, idle, cellular, anotherVpn, consent)

    @Test fun exactPausedMaterialAndOwnerMayStartOnlyOnIdleCellular() { assertTrue(accepts()) }
    @Test fun changedOrMissingOwnerTokensAreRejectedWithoutAdoptingTheNewSession() {
        assertFalse(accepts(supplied = owner.copy(token = "ffffffff-bbbb-cccc-dddd-eeeeeeeeeeee")))
        assertFalse(accepts(supplied = owner.copy(token = "")))
        assertFalse(accepts(supplied = owner.copy(profileId = 8L)))
        assertFalse(accepts(supplied = owner.copy(protocolOptionId = "vless")))
    }
    @Test fun newerManualRequestProfileSelectionAndMaterialGenerationsInvalidateQueuedStart() {
        assertFalse(accepts(request = 5L)); assertFalse(accepts(selected = 8L)); assertFalse(accepts(selected = null))
        assertFalse(accepts(selection = 3L)); assertFalse(accepts(material = "secret-generation-b")); assertFalse(accepts(material = null))
        assertFalse(accepts(authority = 10L))
    }
    @Test fun busyRuntimeOtherVpnWifiOrRevokedConsentNeverStarts() {
        assertFalse(accepts(idle = false)); assertFalse(accepts(cellular = false))
        assertFalse(accepts(anotherVpn = true)); assertFalse(accepts(consent = false))
    }
    @Test fun guardPauseNeverStopsMixedTorI2pFirewallOrDnsProtection() {
        val settings = Settings()
        assertTrue(routerSmartVpnOnly(settings, session))
        assertFalse(routerSmartVpnOnly(settings.copy(privacyRoute = settings.privacyRoute.copy(mode = PrivacyRouteMode.TOR_OVER_VPN)), session))
        assertFalse(routerSmartVpnOnly(settings.copy(i2p = settings.i2p.copy(enabled = true)), session))
        assertFalse(routerSmartVpnOnly(settings.copy(expert = settings.expert.copy(firewallEnabled = true)), session))
        assertFalse(routerSmartVpnOnly(settings.copy(statistics = settings.statistics.copy(dnsFilteringEnabled = true)), session))
        assertFalse(routerSmartVpnOnly(settings.copy(traffic = settings.traffic.copy(mode = TrafficMode.PROXY)), session))
        assertFalse(routerSmartVpnOnly(settings, session.copy(torActive = true)))
        assertFalse(routerSmartVpnOnly(settings, session.copy(i2pEndpointGeneration = 1L)))
        assertFalse(routerSmartVpnOnly(settings, session.copy(quarantineNewApps = true)))
        assertFalse(routerSmartVpnOnly(settings, session.copy(runtimeConfigFingerprint = null)))
        assertFalse(routerSmartVpnOnly(settings, null))
    }
    @Test fun ownedStartCannotUsePreemptiveManualStartOrStopPriority() {
        val command = routerOwnedResumeCommand()
        assertTrue(command is RuntimeCommand.Reload)
        assertEquals(RuntimeCommandPriority.NORMAL, command.priority)
        assertTrue(command.priority.value < RuntimeCommandPriority.STOP.value)
    }
    @Test fun aQueuedOwnedResumeCannotPreemptOrReplaceANewerManualStart() = kotlinx.coroutines.runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val supervisor = com.foxhole.core.runtime.RuntimeSupervisor(scope, diagnosticsLogger = null,
            emergencyKill = { error("Owned resume must not kill another runtime") })
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val consumed = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val request = java.util.concurrent.atomic.AtomicLong(4L)
        try {
            supervisor.dispatch(RuntimeCommand.StartTunnel(8L, source = com.foxhole.core.runtime.RuntimeCommandSource.USER)) {
                entered.complete(Unit)
                release.await()
                request.set(5L)
            }
            kotlinx.coroutines.withTimeout(3_000L) { entered.await() }
            supervisor.dispatch(routerOwnedResumeCommand()) { consumed.complete(accepts(request = request.get(), selected = 8L)) }
            assertFalse(consumed.isCompleted)
            release.complete(Unit)
            assertFalse(kotlinx.coroutines.withTimeout(3_000L) { consumed.await() })
        } finally { supervisor.close(); scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    @Test fun ownedCommandFieldsRequireCompleteTypedReceipts() {
        assertEquals(owner, routerOwnedResumeFromFields(token, 7, "hysteria2", 4, 9))
        assertNull(routerOwnedResumeFromFields(null, 7, "hysteria2", 4, 9))
        assertNull(routerOwnedResumeFromFields("stale", 7, "hysteria2", 4, 9))
        assertNull(routerOwnedResumeFromFields(token, -1, "hysteria2", 4, 9))
        assertNull(routerOwnedResumeFromFields(token, 7, "hysteria2", -1, 9))
        assertNull(routerOwnedResumeFromFields(token, 7, "hysteria2", 4, -1))
        assertNull(routerOwnedResumeFromFields(token, 7, "x\n", 4, 9))
        assertNull(routerOwnedResumeFromFields(token, 7, "x".repeat(129), 4, 9))
    }
}
