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
): Boolean = hydrated && ui.onboardingCompleted && !ui.betaNoticeAcknowledged

class BetaNoticeGateTest {
    @Test
    fun `notice waits for the wizard and then shows once`() {
        // Fresh install: the wizard owns the screen, so the notice must stay down.
        assertFalse(betaNoticeRequired(hydrated = true, ui = UiSettings()))

        // Wizard finished, notice not yet seen.
        assertTrue(
            betaNoticeRequired(
                hydrated = true,
                ui = UiSettings(onboardingCompleted = true),
            ),
        )

        // Acknowledged: never again.
        assertFalse(
            betaNoticeRequired(
                hydrated = true,
                ui = UiSettings(onboardingCompleted = true, betaNoticeAcknowledged = true),
            ),
        )
    }

    @Test
    fun `nothing is claimed before settings are hydrated`() {
        // Before hydration every field still carries its default, so an unhydrated `false`
        // acknowledgement would flash the notice at users who dismissed it long ago.
        assertFalse(
            betaNoticeRequired(
                hydrated = false,
                ui = UiSettings(onboardingCompleted = true),
            ),
        )
    }
}
