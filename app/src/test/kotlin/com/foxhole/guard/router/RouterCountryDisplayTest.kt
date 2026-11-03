package com.foxhole.guard.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.serialization.json.Json

class RouterCountryDisplayTest {
    @Test fun manualCountryWinsAndDetectedCountryUsesNormalizedIsoCode() {
        val manual = RouterControlPreferences("pair", countryCode = "nl", countryAuto = false)
        assertEquals("NL", routerDisplayCountryCode(manual, "DE", null))
        assertEquals("DE", routerDisplayCountryCode(manual.copy(countryAuto = true), "de", null))
    }
    @Test fun retainsCachedCountryWhileWanDiscoveryIsUnavailable() {
        assertEquals("NL", routerDisplayCountryCode(RouterControlPreferences("pair", countryCode = "NL"), null, null))
    }
    @Test fun displaysTheCountryConfiguredOnTheRouterWhenThePhoneHasNoCountryCache() {
        val rules = Json.parseToJsonElement("""[{"code":"NO","route":"direct"},{"code":"US","route":"vpn"}]""") as kotlinx.serialization.json.JsonArray
        assertEquals("NO", routerDisplayCountryCode(null, null, rules))
        assertNull(RouterControlState().countryDirectEffective)
    }
    @Test fun ambiguousRulesAndUnknownCountryNeverInventALocation() {
        val rules = Json.parseToJsonElement("""[{"code":"NO","route":"direct"},{"code":"NL","route":"direct"}]""") as kotlinx.serialization.json.JsonArray
        assertNull(routerDisplayCountryCode(null, null, rules))
        assertNull(routerDisplayCountryCode(null, "Unknown", null))
    }
}
