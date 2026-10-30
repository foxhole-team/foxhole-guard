package com.foxhole.guard.ui

import com.foxhole.core.model.AnomalyEvent
import com.foxhole.core.model.AnomalySeverity
import com.foxhole.core.model.AnomalyType
import com.foxhole.core.model.DiagnosticEntry
import com.foxhole.core.model.IpInfo
import com.foxhole.core.model.NetworkActivityEvent
import com.foxhole.guard.R
import com.foxhole.guard.guardian.GuardEvent
import com.foxhole.guard.guardian.GuardEventType
import com.foxhole.guard.guardian.coreUserFacingMessage
import com.foxhole.guard.guardian.journalResourceText
import com.foxhole.guard.ui.cli.logs.formatRawJournalExport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkJournalPresentationTest {
    @Test
    fun actualEnglishAndRussianResourcesRenderObservedTrafficWithByteDeltas() {
        val en = present(event(), "en")
        val ru = present(event(), "ru")
        assertEquals("Observed traffic", en.row.eventType)
        assertEquals(journalResourceText("ru")(R.string.journal_network_title, emptyList()), ru.row.eventType)
        assertFalse(en.row.eventType == ru.row.eventType)
        assertTrue(en.row.description.contains("Received: 10 B"))
        assertTrue(ru.row.description.contains(journalResourceText("ru")(R.string.journal_network_rx, listOf("10 B"))))
        assertTrue(ru.row.description.contains(journalResourceText("ru")(R.string.journal_network_tx, listOf("20 B"))))
        assertTrue(
            ru.row.description.contains(journalResourceText("ru")(R.string.journal_network_total, listOf("30 B")))
        )
        assertTrue(en.row.description.last().contains("not a successful connection or a close reason"))
        assertTrue(ru.row.description.contains(journalResourceText("ru")(R.string.journal_transport, listOf("TCP"))))
        assertTrue(en.row.description.contains("Profile correlation: 7"))
        assertTrue(en.row.description.contains("Session correlation: SAFE_SESSION"))
    }

    @Test
    fun flagComesFromActualEventOrResolverAndNeverFromTheTranslatedLabel() {
        val actual = present(event(country = "CA"), countryResolver = { "DE" }, countryName = { "Misleading (US)" })
        val resolved = present(event(country = null), countryResolver = { "FR" })
        val absent = present(event(country = null), countryName = { "Canada (CA)" })
        assertEquals("ca", actual.row.flagCountry)
        assertEquals("fr", resolved.row.flagCountry)
        assertEquals(null, absent.row.flagCountry)
    }

    @Test
    fun matchingIpInfoProvidesCountryWithoutAssigningAnotherEndpointsLocation() {
        val info = IpInfo(
            ip = "198.51.100.10",
            countryCode = "JP",
            countryName = "Japan",
            city = "Tokyo",
            isp = "Example",
            fetchedAt = 1
        )
        val matching = event(
            country = null
        ).toNetworkJournalPresentation(journalResourceText("en"), { "$it B" }, ipInfo = info)
        val other = event(
            country = null
        ).copy(
            remoteHost = "198.51.100.11"
        ).toNetworkJournalPresentation(journalResourceText("en"), { "$it B" }, ipInfo = info)
        assertEquals("jp", matching.row.flagCountry)
        assertEquals(null, other.row.flagCountry)
    }

    @Test
    fun ownershipIsExplicitForMissingSingleAndSharedPackages() {
        assertTrue(present(event(packages = emptyList())).row.description.contains("App attribution unavailable"))
        assertTrue(present(event()).row.description.contains("App: com.example.safe"))
        val shared = present(event(packages = listOf("com.example.one", "com.example.two")))
        assertTrue(shared.row.description.first().startsWith("Shared attribution:"))
        assertTrue(shared.row.description.any { it.contains("does not identify a single sender") })
    }

    @Test
    fun unknownTransportAndInvalidEndpointDoNotBecomeAProtocolOrOutcomeClaim() {
        val row = present(
            event().copy(
                protocol = "wireguard",
                remoteHost = "user:password@example.com token=secret",
                remotePort = 70000
            )
        ).row
        assertTrue(row.description.contains("Transport: Not available"))
        assertTrue(row.description.contains("Destination: Not available"))
        assertFalse(
            row.description.any {
                it.contains("wireguard") || it.contains("password") || it.contains("secret") || it.contains("70000")
            }
        )
    }

    @Test
    fun negativeByteDeltasAreClampedAndTotalCannotOverflow() {
        assertTrue(present(event().copy(bytesRx = -10, bytesTx = 20)).row.description.contains("Observed total: 20 B"))
        assertTrue(
            present(
                event().copy(bytesRx = Long.MAX_VALUE, bytesTx = Long.MAX_VALUE)
            ).row.description.contains("Observed total: ${Long.MAX_VALUE} B")
        )
    }

    @Test
    fun exportedSensitiveFieldsAreRedactedInBothLanguagesBeforeLocalizedKeys() {
        listOf("en", "ru").forEach { language ->
            val message = present(
                event().copy(profileId = 6789, sessionId = "SECRET_SESSION"),
                language
            ).exportEntry.message
            assertFalse(message.contains("com.example.safe"))
            assertFalse(message.contains("198.51.100.10"))
            assertFalse(message.contains("CA"))
            assertFalse(message.contains("6789"))
            assertFalse(message.contains("SECRET_SESSION"))
            assertTrue(message.contains("30 B"))
            assertTrue(message.contains(journalResourceText(language)(R.string.journal_redacted, emptyList())))
        }
    }

    @Test
    fun legacyFallbackStaysTechnicalWithNoInferredCountryOrConnectionResult() {
        val entry =
            DiagnosticEntry(
                timestamp = 8,
                tag = "activity",
                message = "App connection: packages=com.example.private • protocol=TCP • endpoint=198.51.100.8 • country=Canada (CA) • password=private_secret • token=secret_token"
            )
        val result = legacyNetworkJournalPresentation(entry, journalResourceText("en"))
        assertEquals("Legacy network diagnostic", result.row.eventType)
        assertEquals(null, result.row.flagCountry)
        assertFalse(result.exportEntry.message.contains("private_secret"))
        assertFalse(result.exportEntry.message.contains("secret_token"))
        assertFalse(result.exportEntry.message.contains("com.example.private"))
        assertFalse(result.exportEntry.message.contains("198.51.100.8"))
        assertTrue(result.row.description.first().contains("not verified"))
    }

    @Test
    fun boundedExportRemovesCredentialUserinfoControlCharactersAndSensitiveTags() {
        val entry =
            DiagnosticEntry(
                timestamp = 1,
                tag = "session=private_tag",
                message = "token=secret password=private https://user:pass@example.com/\n" + "a".repeat(10000)
            )
        val result = entry.redactedJournalExportEntry()
        assertTrue(result.message.length <= 4096)
        assertFalse(result.message.contains("secret"))
        assertFalse(result.message.contains("user:pass"))
        assertFalse(result.message.contains("private_tag"))
        assertFalse(result.tag.contains("private_tag"))
        assertFalse(result.message.contains('\n'))
    }

    @Test
    fun legacyTechnicalFunctionKeepsItsSourceCompatibleContract() {
        val message = event().toNetworkActivityDiagnosticMessage(formatBytes = { "$it B" })
        assertTrue(message.startsWith("App connection: packages=com.example.safe"))
        assertTrue(message.contains("protocol=TCP"))
        assertTrue(message.contains("endpoint=198.51.100.10"))
        assertTrue(message.contains("rx=10 B"))
        assertTrue(message.contains("tx=20 B"))
        assertTrue(message.contains("total=30 B"))
    }

    @Test
    fun typedNetworkAndCoreEntriesFeedTheActualRawExportWithoutSensitiveValues() {
        listOf("en", "ru").forEach { language ->
            val network = present(event(), language).exportEntry
            val core = GuardEvent(
                GuardEventType.CORE_FLOWS_REVOKED,
                packageName = "com.example.private",
                detail = "target=package scope=com.example.private count=3"
            )
            val message = requireNotNull(core.coreUserFacingMessage(journalResourceText(language), forExport = true))
            val security = DiagnosticEntry(timestamp = 2, tag = "guard:core_flows_revoked", message = message)
            val output = formatRawJournalExport(
                "Journal",
                prepareJournalExportEntries(listOf(network, security), alreadyRedacted = true)
            )
            assertTrue(output.contains("Exported entries: 2"))
            assertTrue(output.contains("30 B"))
            assertFalse(output.contains("com.example.private"))
            assertFalse(output.contains("com.example.safe"))
            assertFalse(output.contains("198.51.100.10"))
            assertFalse(output.contains("SAFE_SESSION"))
        }
    }

    @Test
    fun anomalyExportMasksAttributionAndSensitiveEvidenceBeforeLocalization() {
        val event = AnomalyEvent(
            createdAtMs = 1,
            type = AnomalyType.NEW_DESTINATION_COUNTRY,
            severity = AnomalySeverity.ACTIVITY_LOG,
            score = 2,
            reason = "token=secret",
            evidence = mapOf("country" to "CA", "indicator" to "198.51.100.8", "traffic_share" to "0.3"),
            packageName = "com.example.private",
            profileId = "PRIVATE_PROFILE"
        )
        val result = event.redactedJournalExportEvent("[redacted]")
        assertEquals("[redacted]", result.packageName)
        assertEquals("[redacted]", result.profileId)
        assertEquals("[redacted]", result.evidence["country"])
        assertFalse(result.evidence.getValue("indicator").contains("198.51.100.8"))
        assertEquals("0.3", result.evidence["traffic_share"])
        assertFalse(result.reason.contains("secret"))
        assertEquals(event.type, result.type)
        assertEquals(event.createdAtMs, result.createdAtMs)
    }

    @Test
    fun unknownOrNonNumericAnomalyEvidenceCannotSmuggleExportedIdentifiers() {
        val event = AnomalyEvent(
            createdAtMs = 1,
            type = AnomalyType.LATENCY_SHIFT,
            severity = AnomalySeverity.ACTIVITY_LOG,
            score = 2,
            reason = "",
            evidence = mapOf(
                "robust_z" to "account_id",
                "latency_ms" to "NaN",
                "private_payload" to "PRIVATE_VALUE",
                "traffic_share" to "0.3",
                "bytes" to "1000"
            )
        )
        val result = event.redactedJournalExportEvent("[redacted]")
        assertEquals("[redacted]", result.evidence["robust_z"])
        assertEquals("[redacted]", result.evidence["latency_ms"])
        assertEquals("[redacted]", result.evidence["private_payload"])
        assertEquals("0.3", result.evidence["traffic_share"])
        assertEquals("1000", result.evidence["bytes"])
    }

    @Test
    fun trustedLocalizedByteDecimalsSurviveTheActualExportBoundary() {
        val presentation = event().toNetworkJournalPresentation(journalResourceText("en"), formatBytes = { "1.2 MB" })
        val output =
            formatRawJournalExport(
                "Network",
                prepareJournalExportEntries(listOf(presentation.exportEntry), alreadyRedacted = true)
            )
        assertTrue(output.contains("Received: 1.2 MB"))
        assertTrue(output.contains("Sent: 1.2 MB"))
        assertFalse(output.contains("com.example.safe"))
        assertFalse(output.contains("198.51.100.10"))
        assertFalse(output.contains("SAFE_SESSION"))
    }

    @Test
    fun rawDiagnosticsStillUseFullSanitizationAtTheFinalExportBoundary() {
        val entry =
            DiagnosticEntry(
                timestamp = 1,
                tag = "app",
                message = "package=com.example.private token=secret endpoint=198.51.100.8 password=private"
            )
        val output = formatRawJournalExport("App", prepareJournalExportEntries(listOf(entry), alreadyRedacted = false))
        assertFalse(output.contains("com.example.private"))
        assertFalse(output.contains("198.51.100.8"))
        assertFalse(output.contains("secret"))
        assertFalse(output.contains("password=private"))
    }

    private fun present(
        event: NetworkActivityEvent,
        language: String = "en",
        countryResolver: (String) -> String? = { null },
        countryName: (String) -> String = { it },
    ): NetworkJournalPresentation = event.toNetworkJournalPresentation(
        text = journalResourceText(language),
        formatBytes = { "$it B" },
        countryCodeForDestination = countryResolver,
        countryName = countryName,
    )

    private fun event(
        packages: List<String> = listOf(
            "com.example.safe"
        ),
        country: String? = "CA"
    ) = NetworkActivityEvent(
        timestampMs = 1,
        packageNames = packages,
        protocol = "TCP",
        remoteHost = "198.51.100.10",
        remotePort = 443,
        countryCode = country,
        bytesRx = 10,
        bytesTx = 20,
        profileId = 7,
        sessionId = "SAFE_SESSION",
    )
}
