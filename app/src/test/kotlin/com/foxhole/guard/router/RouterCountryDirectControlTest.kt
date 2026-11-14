package com.foxhole.guard.router

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RouterCountryDirectControlTest {
    private val pair = "pair-" + "a".repeat(32)
    private val preferences = RouterControlPreferences(pair, enabled = true)
    private fun snapshot(rules: List<Pair<String,String>>, enabled: Boolean = true) = RouterControlSnapshot(
        1, buildJsonObject { put("localRules", enabled); put("localScope", "all") },
        JsonArray(rules.map { (code, route) -> buildJsonObject { put("code", code); put("route", route) } }),
        JsonArray(emptyList()), JsonArray(emptyList()), emptyList(), emptyList(), null, false)
    @Test fun defaultsNeverInventAnEnabledBypassWithoutAuthoritativeRules() {
        val value = routerCountryDirectControl(preferences, null, snapshot(emptyList()))
        assertFalse(value.checked); assertFalse(value.canChange)
        assertNull(routerCountryDirectControl(preferences, null, null).disableCode)
    }
    @Test fun unknownCountryCanOnlyTurnOffOneExplicitDirectRule() {
        val value = routerCountryDirectControl(preferences, null, snapshot(listOf("RU" to "direct")))
        assertTrue(value.checked); assertTrue(value.canChange)
        assertEquals("RU", value.disableCode); assertNull(value.enableCode)
    }
    @Test fun multipleDirectCountriesCannotBeGuessed() {
        val value = routerCountryDirectControl(preferences, null, snapshot(listOf("RU" to "direct", "DE" to "direct")))
        assertFalse(value.checked); assertFalse(value.canChange)
    }
    @Test fun disablingGlobalRuleEvaluationDoesNotClaimDirectTrafficFromAStoredRule() {
        val value = routerCountryDirectControl(preferences, null, snapshot(listOf("RU" to "direct"), false))
        assertFalse(value.checked); assertFalse(value.canChange)
    }
    @Test fun explicitManualCountryCanEnableAgainAfterOffWithoutClaimingWanLocation() {
        val value = routerCountryDirectControl(preferences.copy(countryAuto = false, countryCode = "RU"), null,
            snapshot(listOf("RU" to "vpn")))
        assertFalse(value.checked); assertTrue(value.canChange); assertEquals("RU", value.enableCode)
    }
    @Test fun knownWanCountryNeverUsesAnUnrelatedDirectCountryRule() {
        val value = routerCountryDirectControl(preferences, "DE", snapshot(listOf("RU" to "direct")))
        assertFalse(value.checked); assertEquals("DE", value.enableCode); assertNull(value.disableCode)
    }
}
