package com.foxhole.guard.router
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class RouterCoreUpdateContractsTest {
    @Test fun invalidCohortKindCannotBeTreatedAsBaseFallback() {
        rejected { parseRouterUpdateStatus(UpdateDocuments.status() plusField
            ("deviceDownloadKind" to JsonPrimitive("FIRMWARE"))) }
    }
    @Test fun unknownCoreRelayAbiFailsClosed() {
        rejected { parseRouterUpdateStatus(UpdateDocuments.status() plusField
            ("coreCohortRelay" to JsonPrimitive(2))) }
    }
    @Test fun capabilityExtensionIsOptionalButCannotAdvertiseAnUnknownAbi() {
        assertEquals(0, parseRouterPanelCapabilities(UpdateDocuments.capabilities()).coreCohortRelay)
        assertEquals(1, parseRouterPanelCapabilities(UpdateDocuments.capabilities() plusField ("coreCohortRelay" to JsonPrimitive(1))).coreCohortRelay)
        rejected { parseRouterPanelCapabilities(UpdateDocuments.capabilities() plusField ("coreCohortRelay" to JsonPrimitive(2))) }
    }
    @Test fun malformedSignedCohortIdentityCannotBeIgnored() {
        rejected { parseRouterUpdateStatus(UpdateDocuments.status() plusField
            ("coreCohortSha256" to JsonPrimitive("bad-hash"))) }
    }
    @Test fun coreCurrentNeverClaimsThatBaseWasChecked() {
        val status = parseRouterUpdateStatus(UpdateDocuments.status() plusField ("availability" to JsonPrimitive("NOT_CHECKED")) plusField
            ("availableVersion" to JsonNull) plusField ("coreAvailability" to JsonPrimitive("CURRENT")) plusField
            ("coreCohortSha256" to JsonPrimitive("a".repeat(64))))
        assertEquals(RouterUpdateAvailability.NOT_CHECKED, status.availability)
        assertEquals(RouterCoreUpdateAvailability.CURRENT, status.coreAvailability)
    }
    @Test fun coreFallbackRequiresExplicitAbiAndClosedCohortKind() {
        val value = UpdateDocuments.status() plusField ("deviceDownloadRequired" to JsonPrimitive(true)) plusField
            ("deviceDownloadKind" to JsonPrimitive("CORE"))
        rejected { parseRouterUpdateStatus(value) }
        assertEquals(RouterUpdateCohortKind.CORE,
            parseRouterUpdateStatus(value plusField ("coreCohortRelay" to JsonPrimitive(1))).deviceDownloadKind)
        assertEquals(0, parseRouterUpdateStatus(UpdateDocuments.status()).coreCohortRelay)
    }
    @Test fun discoveryAcceptsOnlyOneOfficialStableCatalogAndNeverComparesBaseToCore() {
        fun metadata(tag: String = "v1.0.4", url: String = "https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/core-catalog.adb",
            duplicate: Boolean = false, prerelease: Boolean = false): ByteArray {
            val asset = """{"name":"core-catalog.adb","size":10,"browser_download_url":"$url"}"""
            return """{"draft":false,"prerelease":$prerelease,"tag_name":"$tag","assets":[$asset${if (duplicate) ",$asset" else ""}]}""".toByteArray()
        }
        val discovered = parseRouterCoreRelease(metadata())
        assertEquals("1.0.4", discovered.version)
        assertNull(discovered.index.sha256)
        for (bytes in listOf(metadata(url = "https://evil.invalid/core-catalog.adb"), metadata(duplicate = true),
            metadata(prerelease = true), metadata(tag = "latest"))) {
            try { parseRouterCoreRelease(bytes); fail("untrusted Core release accepted") } catch (_: Exception) { }
        }
    }
    @Test fun cohortAssetsCannotCarryLocalPathsOtherOriginsOrUnboundModuleHashes() {
        fun asset(url: String, sha: String? = "b".repeat(64)) = RouterCoreAsset("asset-${"b".repeat(64)}", sha, 10, url, RouterCoreAssetKind.PACKAGE)
        for (url in listOf("http://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/a.apk",
            "https://github.com/other/foxhole-core/releases/download/v1.0.4/a.apk", "file:///tmp/a.apk",
            "https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/a.apk?url=private")) {
            try { asset(url); fail("untrusted Core asset accepted") } catch (_: IllegalArgumentException) { }
        }
        try { asset("https://github.com/foxhole-team/foxhole-core/releases/download/v1.0.4/a.apk", null); fail("missing module hash accepted") }
        catch (_: IllegalArgumentException) { }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("invalid Core cohort accepted") }
        catch (failure: RouterApiFailure) { assertEquals(RouterApiError.INVALID_RESPONSE, failure.kind) }
    }
}
