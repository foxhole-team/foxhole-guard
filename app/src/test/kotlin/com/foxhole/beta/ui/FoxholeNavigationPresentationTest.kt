package com.foxhole.beta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FoxholeNavigationPresentationTest {
    @Test
    fun `detail transition uses subtle material-style offset instead of full page slide`() {
        assertEquals(151, detailTransitionOffsetPx(1080))
        assertEquals(1, detailTransitionOffsetPx(1))
    }

    @Test
    fun `settings detail gate rejects same route`() {
        val gate = SettingsDetailNavigationGate(clock = { 1_000L })

        val decision =
            gate.tryAccept(
                currentRoute = "settings/dns",
                targetRoute = "settings/dns",
            )

        assertEquals(
            SettingsDetailNavigationDecision.Rejected(1_000L, "same_route"),
            decision,
        )
    }

    @Test
    fun `settings detail gate suppresses duplicate rapid taps`() {
        var nowMs = 1_000L
        val gate = SettingsDetailNavigationGate(clock = { nowMs })

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(1_000L),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )

        nowMs += SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS - 1

        assertEquals(
            SettingsDetailNavigationDecision.Rejected(nowMs, "duplicate_tap"),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )
    }

    @Test
    fun `settings detail gate accepts after suppression window`() {
        var nowMs = 1_000L
        val gate = SettingsDetailNavigationGate(clock = { nowMs })

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(1_000L),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )

        nowMs += SETTINGS_DETAIL_DUPLICATE_TAP_SUPPRESSION_MS

        assertEquals(
            SettingsDetailNavigationDecision.Accepted(nowMs),
            gate.tryAccept(currentRoute = "settings", targetRoute = "settings/traffic"),
        )
    }
}
