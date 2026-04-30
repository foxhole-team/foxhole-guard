package com.foxhole.beta.core.data

import com.foxhole.beta.core.importer.ProfileImportParser
import com.foxhole.beta.core.model.ProfileSourceType
import com.foxhole.beta.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileImportPlanTest {
    private val json =
        Json {
            prettyPrint = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }

    private val parser = ProfileImportParser(json, remoteHostResolver = testRemoteHostResolver())

    @Test
    fun `local smart config import splits route profiles instead of keeping one aggregate card`() {
        val payload = buildSmartConfigPayload()
        val parsed = parser.parseUserInput(payload)
        val localProfiles = parser.parseSubscriptionProfiles(payload, "Foxhole")

        val plan = resolveImportProfilePlan(parsed, localProfiles)

        assertTrue(plan is ImportProfilePlan.Multi)
        val multi = plan as ImportProfilePlan.Multi
        assertEquals(
            listOf("Foxhole smart direct", "Foxhole smart tor i2p"),
            multi.parsed.profiles.map { it.displayName },
        )
    }

    @Test
    fun `subscription url parse remains a single unresolved candidate`() {
        val parsed = parser.parseUserInput("https://x-sec-net.nl/c96c6edd9d566c260bb2aa16e930825b")

        val plan = resolveImportProfilePlan(parsed, localParsedProfiles = null)

        assertTrue(plan is ImportProfilePlan.Single)
        val single = plan as ImportProfilePlan.Single
        assertEquals(ProfileSourceType.SUBSCRIPTION_URL, single.parsed.sourceType)
    }

    @Test
    fun `local single route smart config keeps single smart profile import plan`() {
        val rawInput =
            """
            # === vless / direct ===
            vless://11111111-1111-1111-1111-111111111111@direct.example.com:443?security=tls&type=tcp#Foxhole direct
            # === trojan / direct ===
            trojan://secret@trojan.example.com:443?security=tls&type=tcp#Foxhole direct
            """.trimIndent()
        val parsed = parser.parseUserInput(rawInput)
        val localProfiles = parser.parseSubscriptionProfiles(rawInput, "Foxhole")

        val plan = resolveImportProfilePlan(parsed, localProfiles)

        assertTrue(plan is ImportProfilePlan.Single)
        val single = plan as ImportProfilePlan.Single
        assertEquals(2, single.parsed.protocolOptions.size)
    }

    @Test
    fun `single share uri keeps single import plan`() {
        val rawInput = "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls&type=tcp#edge"
        val parsed =
            parser.parseUserInput(rawInput)
        val localProfiles = parser.parseSubscriptionProfiles(rawInput, "edge")

        val plan = resolveImportProfilePlan(parsed, localProfiles)

        assertTrue(plan is ImportProfilePlan.Single)
    }

    private fun buildSmartConfigPayload(): String =
        """
        # === vless / direct ===
        vless://11111111-1111-1111-1111-111111111111@direct.example.com:443?security=tls&type=tcp#Foxhole smart direct
        # === trojan / tor+i2p ===
        trojan://secret@tor.example.com:443?security=tls&type=tcp#Foxhole smart tor i2p
        """.trimIndent()
}
