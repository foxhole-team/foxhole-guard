package com.foxhole.guard.ui

import com.foxhole.core.model.UiSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertFalse(betaNoticeRequired(hydrated = true, ui = UiSettings()))
        assertFalse(quickStartRequired(hydrated = true, ui = UiSettings()))

        val afterWizard = UiSettings(onboardingCompleted = true)
        assertTrue(quickStartRequired(hydrated = true, ui = afterWizard))
        assertFalse(betaNoticeRequired(hydrated = true, ui = afterWizard))

        val afterQuickStart = afterWizard.copy(quickStartShown = true)
        assertFalse(quickStartRequired(hydrated = true, ui = afterQuickStart))
        assertTrue(betaNoticeRequired(hydrated = true, ui = afterQuickStart))

        val completed = afterQuickStart.copy(betaNoticeAcknowledged = true)
        assertFalse(
            betaNoticeRequired(hydrated = true, ui = completed),
        )
        assertFalse(quickStartRequired(hydrated = true, ui = completed))
    }

    @Test
    fun `nothing is claimed before settings are hydrated`() {
        assertFalse(
            betaNoticeRequired(
                hydrated = false,
                ui = UiSettings(onboardingCompleted = true, quickStartShown = true),
            ),
        )
        assertFalse(quickStartRequired(hydrated = false, ui = UiSettings(onboardingCompleted = true)))
    }
}
