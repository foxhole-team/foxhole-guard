package com.foxhole.guard.core.sentinel.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ScannerRuleCatalogTest {
    private val rules = "rule owned { condition: true }".toByteArray()
    private fun catalog(hash: String = scannerRuleDigest(rules)): ByteArray =
        """{"schema":1,"bundleId":"owned.v1","scope":"verified_classic_dex_only","semantics":"reference_presence_only","license":"GPL-3.0-or-later","maxSourceBytes":262144,"sourceSha256":"$hash","rules":[{"id":"owned","observation":"DEX_CLASS_LOADER_REFERENCE","candidateMitre":[],"claim":"reference only"}],"mitreCatalogVersion":"19.2","mitreCatalogSource":"https://attack.mitre.org/resources/versions/"}""".toByteArray()

    @Test fun exactOwnedRulesAndCatalogHashProduceOnlyKnownRuleIds() {
        val value = validateScannerRuleCatalog(catalog(), rules)
        assertEquals(setOf("owned"), value.ruleIds)
        assertEquals(scannerRuleDigest(rules), value.sha256)
    }

    @Test fun wrongHashUnknownScopeUnknownFieldsAndDuplicateKeysAreRejected() {
        listOf(
            catalog("0".repeat(64)),
            catalog().decodeToString().replace("verified_classic_dex_only", "full_malware_scan").toByteArray(),
            catalog().decodeToString().replace("\"schema\":1", "\"schema\":1,\"schema\":1").toByteArray(),
            catalog().decodeToString().replace("\"schema\":1", "\"schema\":1,\"extra\":true").toByteArray(),
            catalog().decodeToString().replace("\"schema\":1", "\"schema\":\"1\"").toByteArray(),
        ).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { validateScannerRuleCatalog(bytes, rules) }
        }
    }

    @Test fun overlargeAndOvernestedDocumentsCannotEnterParser() {
        assertThrows(IllegalArgumentException::class.java) { validateScannerRuleCatalog(ByteArray(262145), rules) }
        assertThrows(IllegalArgumentException::class.java) {
            validateScannerRuleCatalog("[".repeat(10000).toByteArray(), rules)
        }
    }
}
