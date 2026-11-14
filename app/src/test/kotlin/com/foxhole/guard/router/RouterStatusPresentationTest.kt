package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class RouterStatusPresentationTest {
    private fun state(connected: Boolean = true, local: Boolean = true): RouterControlState = RouterControlState(
        stage = RouterControlStage.CONNECTED,
        preferences = RouterControlPreferences("pair-" + "a".repeat(32), enabled = true, countryCode = "RU", countryAuto = false),
        snapshot = RouterControlSnapshot(1,
            buildJsonObject { put("localRules", local) },
            JsonArray(listOf(buildJsonObject { put("code", "RU"); put("route", "direct") })),
            JsonArray(emptyList()), JsonArray(emptyList()),
            listOf(buildJsonObject { put("id", "p1"); put("name", "Home") }), emptyList(), "p1", connected),
    )
    @Test fun desiredProfileDoesNotMeanConnected() {
        val shown = routerStatusPresentation(state(connected = false))
        assertEquals(false, shown.connected)
        assertEquals("Home", shown.profileName)
    }
    @Test fun offlineSnapshotCannotClaimCurrentConnectionOrLocation() {
        val shown = routerStatusPresentation(state().copy(stage = RouterControlStage.OFFLINE))
        assertNull(shown.connected); assertNull(shown.profileName); assertNull(shown.countryCode)
    }
    @Test fun localFlagRequiresActualEnabledCountryRouting() {
        assertEquals("RU", routerStatusPresentation(state()).countryCode)
        val off = routerStatusPresentation(state(local = false))
        assertEquals(false, off.localTraffic); assertNull(off.countryCode)
    }
    @Test fun noActiveProfileDoesNotFallBackToAnUnappliedSelection() {
        val current = state(); val old = current.snapshot!!
        val stopped = current.copy(selectedServerId = "p1", snapshot = RouterControlSnapshot(old.revision, old.settings,
            old.countries, old.devices, old.sites, old.servers, old.clients, null, false))
        assertNull(routerStatusPresentation(stopped).profileName)
    }
    @Test fun authenticatedCountryRuleSurvivesPendingAutomaticGeolocation() {
        val shown = routerStatusPresentation(state().copy(
            preferences = RouterControlPreferences("pair-" + "a".repeat(32), enabled = true, countryAuto = true),
            routerCountryCode = null,
        ))
        assertEquals("RU", shown.countryCode)
        assertEquals(true, shown.localTraffic)
    }
    @Test fun locationDoesNotUseUnappliedCountryPreferencesAsRoutingProof() {
        assertNull(routerStatusPresentation(state().copy(preferences = RouterControlPreferences(
            "pair-" + "a".repeat(32), enabled = true, countryCode = "US", countryAuto = false))).countryCode)
    }

    @Test fun automaticPendingCountryUsesExactActiveRuleInsteadOfDifferentCachedCode() {
        val current = state().copy(preferences = RouterControlPreferences("pair-" + "a".repeat(32),
            enabled = true, countryAuto = true, countryCode = "US"), routerCountryCode = null)
        val shown = routerStatusPresentation(current)
        assertEquals(true, shown.localTraffic); assertEquals("RU", shown.countryCode)
    }

}
