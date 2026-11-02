package com.foxhole.guard.router

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterLanDevicePresentationTest {
    private fun snapshot(publicIp: Boolean?) = RouterControlSnapshot(1, JsonObject(emptyMap()), JsonArray(emptyList()),
        JsonArray(emptyList()), JsonArray(emptyList()), emptyList(), emptyList(), null, false, publicIpv4Available = publicIp)

    @Test fun landingWaitsForAuthenticatedFactsAndNeverTreatsNetworkFailureAsNat() {
        val preferences = RouterControlPreferences("pair-" + "a".repeat(32))
        assertNull(RouterControlState(preferences = preferences, error = RouterWizardError.NETWORK).managementLanding)
        for (available in listOf(false, null)) assertEquals(RouterManagementLanding.SETTINGS,
            RouterControlState(preferences = preferences, snapshot = snapshot(available)).managementLanding)
        val observed = RouterControlState(preferences = preferences, snapshot = snapshot(true))
        assertTrue(observed.hasConfirmedDirectIp); assertEquals(RouterManagementLanding.HUB, observed.managementLanding)
        val manual = RouterControlState(preferences = preferences.copy(publicIpConfirmed = true))
        assertTrue(manual.hasConfirmedDirectIp); assertEquals(RouterManagementLanding.HUB, manual.managementLanding)
        assertFalse(preferences.publicIpConfirmed)
    }

    @Test fun onlyVerifiedOnlineDevicesAppearByDefaultAndOptOutKeepsAllRecords() {
        val devices = listOf(true, false, null).mapIndexed { index, online -> RouterLanDevice("device-" + index,
            "Device", "192.168.1." + (index + 2), null, online, null) }
        assertEquals(listOf(devices[0]), visibleRouterLanDevices(devices, true))
        assertEquals(devices, visibleRouterLanDevices(devices, false))
    }

    @Test fun localExplicitHostnameHintsHaveTypesAndAmbiguousNamesStayGeneric() {
        for (name in listOf("iPhone14", "Smartphone", "Adam-Pixel9")) assertEquals(RouterLanDeviceKind.PHONE, routerLanDeviceKind(name))
        for (name in listOf("iPad", "Galaxy-Tab9", "tablet")) assertEquals(RouterLanDeviceKind.TABLET, routerLanDeviceKind(name))
        for (name in listOf("MacBook-Pro", "desktop", "workstation")) assertEquals(RouterLanDeviceKind.COMPUTER, routerLanDeviceKind(name))
        for (name in listOf("Router", "192.168.1.1", "device-abcdef", "telephone", "Android", "Galaxy")) assertEquals(RouterLanDeviceKind.UNKNOWN, routerLanDeviceKind(name))
    }
    @Test fun inheritedVpnIsRoutingIntentEvenWhileNoVpnProfileRunsAndExplicitDirectIsPreserved() {
        assertTrue(routerLanDeviceUsesVpn(null))
        assertTrue(routerLanDeviceUsesVpn("vpn"))
        assertFalse(routerLanDeviceUsesVpn("direct"))
        assertFalse(snapshot(null).connected)
        assertNull(snapshot(null).desiredServerId)
        try { routerLanDeviceUsesVpn("unknown"); throw AssertionError("Unknown policy accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }

}
