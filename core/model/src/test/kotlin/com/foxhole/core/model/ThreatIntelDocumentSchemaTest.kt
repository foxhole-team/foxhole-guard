package com.foxhole.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreatIntelDocumentSchemaTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a schema-3 feed parses and every indicator reads as unclassified`() {
        val document =
            json.decodeFromString<ThreatIntelDocument>(
                """
                {
                  "schema": 3,
                  "packages": ["com.spy.app"],
                  "certsSha1": ["${"a".repeat(40)}"],
                  "domains": ["evil.example"],
                  "ips": ["198.51.100.7"]
                }
                """.trimIndent(),
            )

        assertEquals(3, document.schema)
        assertEquals(listOf("evil.example"), document.domains)
        assertTrue(document.indicatorKinds.isEmpty())
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, document.indicatorKind("evil.example"))
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, document.indicatorKind("198.51.100.7"))
    }

    @Test
    fun `a schema-4 feed carries the kind it stated`() {
        val document =
            json.decodeFromString<ThreatIntelDocument>(
                """
                {
                  "schema": 4,
                  "domains": ["c2.evil.example", "cdn.shared.example"],
                  "indicatorKinds": {
                    "c2.evil.example": "COMMAND_AND_CONTROL",
                    "cdn.shared.example": "SHARED_INFRASTRUCTURE"
                  }
                }
                """.trimIndent(),
            )

        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, document.indicatorKind("c2.evil.example"))
        assertEquals(ThreatIndicatorKind.SHARED_INFRASTRUCTURE, document.indicatorKind("cdn.shared.example"))
    }

    @Test
    fun `an unreadable kind costs that indicator and nothing else`() {
        val document =
            json.decodeFromString<ThreatIntelDocument>(
                """
                {
                  "schema": 4,
                  "domains": ["phish.example", "c2.example"],
                  "indicatorKinds": {"phish.example": "PHISHING_KIT", "c2.example": "COMMAND_AND_CONTROL"}
                }
                """.trimIndent(),
            )

        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, document.indicatorKind("phish.example"))
        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, document.indicatorKind("c2.example"))
    }

    @Test
    fun `an indicator lookup normalizes case and a trailing dot`() {
        val document =
            ThreatIntelDocument(
                domains = listOf("c2.evil.example"),
                indicatorKinds = mapOf("c2.evil.example" to "command_and_control"),
            )

        assertEquals(ThreatIndicatorKind.COMMAND_AND_CONTROL, document.indicatorKind("C2.Evil.Example"))
    }

    @Test
    fun `every schema this build has shipped is still readable`() {
        (ThreatIntelDocument.MIN_SUPPORTED_SCHEMA..ThreatIntelDocument.SCHEMA).forEach { schema ->
            assertTrue("schema $schema must stay readable", ThreatIntelDocument.supportsSchema(schema))
        }
    }

    @Test
    fun `a schema this build predates is refused rather than half-read`() {
        assertFalse(ThreatIntelDocument.supportsSchema(ThreatIntelDocument.SCHEMA + 1))
        assertFalse(ThreatIntelDocument.supportsSchema(ThreatIntelDocument.MIN_SUPPORTED_SCHEMA - 1))
    }

    @Test
    fun `an absent or blank kind is unclassified, never the loudest`() {
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, ThreatIndicatorKind.fromWireName(null))
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, ThreatIndicatorKind.fromWireName("  "))
        assertEquals(ThreatIndicatorKind.UNCLASSIFIED, ThreatIndicatorKind.fromWireName("whatever_is_next"))
    }
}
