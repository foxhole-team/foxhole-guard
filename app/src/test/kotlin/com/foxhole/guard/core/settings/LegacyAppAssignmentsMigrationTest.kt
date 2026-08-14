package com.foxhole.guard.core.settings

import com.foxhole.core.model.AppTunnelLane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

// Legacy stores carried two parallel per-app lists; the lane model folds them into one
// `appAssignments` map at the JSON boundary, exactly once, before decoding.
class LegacyAppAssignmentsMigrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun expertJson(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun privacyRouteJson(
        mode: String = "TOR_OVER_VPN",
        scope: String = "SELECTED_APPS",
    ): JsonObject =
        json.parseToJsonElement("""{"mode":"$mode","scope":"$scope"}""").jsonObject

    private fun JsonObject.lane(packageName: String): String? =
        (this["appAssignments"] as? JsonObject)?.get(packageName)?.jsonPrimitive?.content

    @Test
    fun `blocked packages become the BLOCK lane`() {
        val migrated =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"blockedPackages":["com.bad"],"selectedPackages":[]}"""),
                privacyRoute = null,
            )
        assertEquals(AppTunnelLane.BLOCK.name, migrated.lane("com.bad"))
        assertFalse(migrated.containsKey("blockedPackages"))
        assertFalse(migrated.containsKey("selectedPackages"))
    }

    @Test
    fun `selected packages become TOR when a selected-apps tor route was configured`() {
        val migrated =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"selectedPackages":["com.app"]}"""),
                privacyRoute = privacyRouteJson(mode = "TOR_OVER_VPN", scope = "SELECTED_APPS"),
            )
        assertEquals(AppTunnelLane.TOR.name, migrated.lane("com.app"))
    }

    @Test
    fun `selected packages become VPN when tor was off or device-wide`() {
        val torOff =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"selectedPackages":["com.app"]}"""),
                privacyRoute = privacyRouteJson(mode = "OFF", scope = "SELECTED_APPS"),
            )
        assertEquals(AppTunnelLane.VPN.name, torOff.lane("com.app"))
        val allApps =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"selectedPackages":["com.app"]}"""),
                privacyRoute = privacyRouteJson(mode = "TOR_OVER_VPN", scope = "ALL_APPS"),
            )
        assertEquals(AppTunnelLane.VPN.name, allApps.lane("com.app"))
    }

    @Test
    fun `a package in both legacy lists keeps its routing lane`() {
        val migrated =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"selectedPackages":["com.app"],"blockedPackages":["com.app","com.bad"]}"""),
                privacyRoute = null,
            )
        assertEquals(AppTunnelLane.VPN.name, migrated.lane("com.app"))
        assertEquals(AppTunnelLane.BLOCK.name, migrated.lane("com.bad"))
    }

    @Test
    fun `a store already on the lane model is left untouched`() {
        val expert =
            expertJson("""{"appAssignments":{"com.app":"TOR"},"selectedPackages":["ignored"]}""")
        assertSame(expert, migrateLegacyAppAssignments(expert, privacyRoute = null))
    }

    @Test
    fun `empty legacy lists migrate to no assignments and drop the keys`() {
        val migrated =
            migrateLegacyAppAssignments(
                expert = expertJson("""{"selectedPackages":[],"blockedPackages":[],"sniff":true}"""),
                privacyRoute = null,
            )
        assertNull(migrated["appAssignments"])
        assertFalse(migrated.containsKey("selectedPackages"))
        assertFalse(migrated.containsKey("blockedPackages"))
        assertEquals(JsonPrimitive(true), migrated["sniff"])
    }
}
