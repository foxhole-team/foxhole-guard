package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterHomePolicyTest {
    private val phone = RouterPhoneSession("profile", 7, 22)
    private val proof = RouterHomeProof("paired", "server", "generation", 100, true, true)
    private fun RouterHomePolicy.action(
        home: Boolean = true, actualProof: RouterHomeProof? = proof, now: Long = 100,
        current: RouterPhoneSession? = phone, selected: String = "profile", revision: Long = 7,
        idle: Boolean = false, enabled: Boolean = true,
        confirmedOff: RouterHomeOffEvidence? = null,
    ) = evaluate(enabled, "paired", home, actualProof, now, current, selected, revision, idle, confirmedOff)

    @Test fun mereWifiOrAnExpiredProofCannotPauseThePhone() {
        assertEquals(RouterHomeAction.None, RouterHomePolicy().action(actualProof = null))
        assertEquals(RouterHomeAction.None, RouterHomePolicy().action(now = 5101))
        assertEquals(RouterHomeAction.None, RouterHomePolicy().action(now = 99))
        assertEquals(RouterHomeAction.None, RouterHomePolicy().action(actualProof = proof.copy(thisPhoneRouted = false)))
        assertEquals(RouterHomeAction.None, RouterHomePolicy().action(actualProof = proof.copy(pairingId = "other")))
    }

    @Test fun OnlyTheAutomaticallyPausedProfileCanResumeAfterLeaving() {
        val policy = RouterHomePolicy()
        assertTrue(policy.action() is RouterHomeAction.Pause)
        assertEquals(RouterHomeAction.None, policy.action())
        assertEquals(RouterHomeAction.None, policy.action(current = null, idle = true))
        assertTrue(policy.action(home = false, actualProof = null, current = null, idle = true) is RouterHomeAction.Resume)
        assertEquals(RouterHomeAction.None, policy.action(home = false, actualProof = null, current = null, idle = true))
    }

    @Test fun manualStopOrSelectionDiscardsAutomaticOwnership() {
        val stopped = RouterHomePolicy()
        stopped.action()
        stopped.manualPhoneAction()
        assertEquals(RouterHomeAction.None, stopped.action(home = false, current = null, idle = true))
        val changed = RouterHomePolicy()
        changed.action()
        assertEquals(RouterHomeAction.None, changed.action(home = false, current = null, selected = "other", idle = true))
        val revised = RouterHomePolicy()
        revised.action()
        assertEquals(RouterHomeAction.None, revised.action(home = false, current = null, revision = 8, idle = true))
    }

    @Test fun disablingOrFailingThePauseCannotStartAnUnrequestedVpn() {
        val disabled = RouterHomePolicy()
        disabled.action()
        assertEquals(RouterHomeAction.None, disabled.action(home = false, current = null, idle = true, enabled = false))
        val failed = RouterHomePolicy()
        val paused = failed.action() as RouterHomeAction.Pause
        failed.pauseFailed(paused.ownership)
        assertEquals(RouterHomeAction.None, failed.action(home = false, current = null, idle = true))
    }

    @Test fun aTransientProofFailureDoesNotFlapButExtendedLossResumesItsOwner() {
        val policy = RouterHomePolicy()
        policy.action()
        assertEquals(RouterHomeAction.None, policy.action(actualProof = null, now = 6000, current = null, idle = true))
        assertEquals(RouterHomeAction.None, policy.action(actualProof = null, now = 29000, current = null, idle = true))
        assertTrue(policy.action(actualProof = null, now = 30100, current = null, idle = true) is RouterHomeAction.Resume)
        val restored = RouterHomePolicy()
        restored.action()
        restored.action(actualProof = null, now = 6000, current = null, idle = true)
        restored.action(actualProof = proof.copy(verifiedAtElapsedMs = 7000), now = 7000, current = null, idle = true)
        assertEquals(RouterHomeAction.None, restored.action(actualProof = null, now = 30100, current = null, idle = true))
    }

    @Test fun authenticatedOffNeedsSustainedEvidenceAndCannotOverrideManualStop() {
        val policy = RouterHomePolicy()
        policy.action()
        assertEquals(RouterHomeAction.None, policy.action(actualProof = null, now = 1000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 1000)))
        assertEquals(RouterHomeAction.None, policy.action(actualProof = null, now = 2500, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 2500)))
        assertTrue(policy.action(actualProof = null, now = 3000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 3000)) is RouterHomeAction.Resume)
        val stopped = RouterHomePolicy()
        stopped.action()
        stopped.manualPhoneAction()
        assertEquals(RouterHomeAction.None, stopped.action(actualProof = null, now = 50000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 50000)))
    }

    @Test fun aManualStartAtHomeIsNotImmediatelyPausedAgain() {
        val policy = RouterHomePolicy()
        policy.action()
        policy.manualPhoneAction()
        val manual = phone.copy(connectionId = 23)
        assertEquals(RouterHomeAction.None, policy.action(current = manual))
        assertEquals(RouterHomeAction.None, policy.action(home = false, actualProof = null, current = manual))
        assertTrue(policy.action(current = manual) is RouterHomeAction.Pause)
    }

    @Test fun explicitAutomationReenableReleasesTheManualOverride() {
        val policy = RouterHomePolicy()
        policy.manualPhoneAction()
        assertEquals(RouterHomeAction.None, policy.action())
        assertEquals(RouterHomeAction.None, policy.action(enabled = false))
        assertTrue(policy.action(enabled = true) is RouterHomeAction.Pause)
    }

    @Test fun repeatedOldRouterObservationCannotTriggerTheFastResume() {
        val policy = RouterHomePolicy()
        policy.action()
        policy.action(actualProof = null, now = 1000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 1000, 2000))
        assertEquals(RouterHomeAction.None, policy.action(actualProof = null, now = 3000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 3000, 2000)))
        assertTrue(policy.action(actualProof = null, now = 4000, current = null, idle = true,
            confirmedOff = RouterHomeOffEvidence("paired", 4000, 5000)) is RouterHomeAction.Resume)
    }
}
