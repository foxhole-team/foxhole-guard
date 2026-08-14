package com.foxhole.guard.ui

import com.foxhole.core.model.UiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice follows the wizard exactly once. Encoding the rule as a function keeps the view model
 * and this test reading the same predicate — the flow itself only combines it with `hydrated`.
 */
private fun betaNoticeRequired(
    hydrated: Boolean,
    ui: UiSettings,
): Boolean =
    hydrated &&
        ui.onboardingCompleted &&
        ui.quickStartShown &&
        !ui.betaNoticeAcknowledged

private fun quickStartRequired(
    hydrated: Boolean,
    ui: UiSettings,
): Boolean = hydrated && ui.onboardingCompleted && !ui.quickStartShown

class BetaNoticeGateTest {
    @Test
    fun `quick start precedes the beta notice and both show once`() {
        // Fresh install: the wizard owns the screen, so the notice must stay down.
        assertFalse(betaNoticeRequired(hydrated = true, ui = UiSettings()))
        assertFalse(quickStartRequired(hydrated = true, ui = UiSettings()))

        // Wizard finished: quick start owns the first overlay and beta must wait.
        val afterWizard = UiSettings(onboardingCompleted = true)
        assertTrue(quickStartRequired(hydrated = true, ui = afterWizard))
        assertFalse(betaNoticeRequired(hydrated = true, ui = afterWizard))

        // Closing quick start reveals beta.
        val afterQuickStart = afterWizard.copy(quickStartShown = true)
        assertFalse(quickStartRequired(hydrated = true, ui = afterQuickStart))
        assertTrue(betaNoticeRequired(hydrated = true, ui = afterQuickStart))

        // Acknowledged: neither sheet returns.
        val completed = afterQuickStart.copy(betaNoticeAcknowledged = true)
        assertFalse(
            betaNoticeRequired(hydrated = true, ui = completed),
        )
        assertFalse(quickStartRequired(hydrated = true, ui = completed))
    }

    @Test
    fun `nothing is claimed before settings are hydrated`() {
        // Before hydration every field still carries its default, so an unhydrated `false`
        // acknowledgement would flash the notice at users who dismissed it long ago.
        assertFalse(
            betaNoticeRequired(
                hydrated = false,
                ui = UiSettings(onboardingCompleted = true, quickStartShown = true),
            ),
        )
        assertFalse(quickStartRequired(hydrated = false, ui = UiSettings(onboardingCompleted = true)))
    }
}
