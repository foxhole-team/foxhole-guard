package com.foxhole.guard.core.data

import com.foxhole.core.importer.ProfileImportParser
import com.foxhole.core.model.ProfileSourceType
import com.foxhole.core.model.SubscriptionEntryReport
import com.foxhole.core.model.SubscriptionEntryStatus
import com.foxhole.core.network.testRemoteHostResolver
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
    fun `a grpc and fingerprint subscription reaches the confirm sheet with every node`() {
        val uuid = "11111111-2222-3333-4444-555555555555"
        val payload =
            buildString {
                for (index in 1..10) {
                    append(
                        "vless://$uuid@198.51.100.$index:443?encryption=none&security=tls" +
                            "&sni=n$index.example.net&fp=chrome&type=grpc&serviceName=fox$index" +
                            "&mode=gun#grpc-$index\n",
                    )
                }
                for (index in 1..2) {
                    append(
                        "vless://$uuid@198.51.100.2$index:443?encryption=none&security=tls" +
                            "&sni=t$index.example.net&type=tcp#tcp-$index\n",
                    )
                }
                for (index in 1..3) {
                    append(
                        "hysteria2://import-secret@198.51.100.3$index:443" +
                            "?sni=h$index.example.net&fp=chrome#hy2-fp-$index\n",
                    )
                }
                for (index in 1..2) {
                    append(
                        "hysteria2://import-secret@198.51.100.4$index:443" +
                            "?sni=p$index.example.net#hy2-$index\n",
                    )
                }
            }

        val parsed = parser.parseSubscriptionProfiles(payload, "provider")

        assertEquals(17, parsed.nodesCount)
        assertEquals(
            emptyList<SubscriptionEntryReport>(),
            parsed.entryReports.filter { report -> report.status != SubscriptionEntryStatus.ACCEPTED },
        )
        val grpcConfig = parsed.profiles.first().normalizedConfigJson
        assertTrue(grpcConfig, grpcConfig.contains("\"service_name\""))
        assertTrue(grpcConfig, grpcConfig.contains("fox1"))
    }

    @Test
    fun `subscription url parse remains a single unresolved candidate`() {
        val parsed = parser.parseUserInput("https://vpn.example.com/00000000000000000000000000000000")

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

    @Test
    fun `subscription parse diagnostics summarize smart protocol options`() {
        val source =
            projectFile(
                "src/main/kotlin/com/foxhole/guard/core/data/ProfileRepositoryDomainSupport.kt",
            ).readText()
        val summaryBlock =
            source.substringAfter("internal fun ParsedSubscriptionImport.protocolSummary(): String =")
                .substringBefore("internal fun ParsedSubscriptionImport.hasUnconsentedInsecureTlsProfiles")

        assertTrue(summaryBlock.contains(".flatMap { profile ->"))
        assertTrue(summaryBlock.contains(".protocolOptions"))
        assertTrue(summaryBlock.contains(".map { option -> option.protocolHint }"))
        assertTrue(summaryBlock.contains(".ifEmpty { listOf(profile.protocolHint) }"))
    }

    @Test
    fun `runtime config insecure tls allowance uses stored consent boundary`() {
        val source = projectFile("src/main/kotlin/com/foxhole/guard/core/data/ProfileRepository.kt").readText()
        val runtimeLoadBlock =
            source.substringAfter("suspend fun getResolvedConfig(")
                .substringBefore("private suspend fun loadResolvedConfig(")
        val repairBlock =
            source.substringAfter("private fun repairResolvedConfigIfNeeded(")
                .substringBefore("private fun refreshSubscriptionIfMissing(")
        val editBlock =
            source.substringAfter("suspend fun updateResolvedConfig(")
                .substringBefore("suspend fun getSession(")

        listOf(runtimeLoadBlock, repairBlock, editBlock).forEach { block ->
            assertTrue(block.contains("allowsInsecureTlsForStoredProfileRuntime("))
            assertFalse(block.contains("selectedOption?.requiresInsecureTls"))
        }
        assertFalse(runtimeLoadBlock.contains("repaired.runtimeConfig.requiresInsecureTls(json)"))
        assertFalse(runtimeLoadBlock.contains("selectedOption?.normalizedConfigJson?.requiresInsecureTls(json)"))
        assertFalse(editBlock.contains("selectedOption?.normalizedConfigJson?.requiresInsecureTls(json)"))
    }

    private fun buildSmartConfigPayload(): String =
        """
        # === vless / direct ===
        vless://11111111-1111-1111-1111-111111111111@direct.example.com:443?security=tls&type=tcp#Foxhole smart direct
        # === trojan / tor+i2p ===
        trojan://secret@tor.example.com:443?security=tls&type=tcp#Foxhole smart tor i2p
        """.trimIndent()

    private fun projectFile(path: String): File =
        listOf(File(path), File("app/$path"), File("../app/$path"))
            .first { file -> file.exists() }
}
