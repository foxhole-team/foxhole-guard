package com.foxhole.guard.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DnsFilterDownloadMandatoryContractTest {
    @Test
    fun `production APK carries no DNS data set and installer has no bundled success fallback`() {
        val assets = sequenceOf(File("src/main/assets"), File("app/src/main/assets")).first(File::isDirectory)
        val installer =
            sequenceOf(
                File("src/main/kotlin/com/foxhole/guard/runtime/DnsFilterAssetInstaller.kt"),
                File("app/src/main/kotlin/com/foxhole/guard/runtime/DnsFilterAssetInstaller.kt"),
            ).first(File::isFile).readText()

        assertFalse(File(assets, "rule-sets/adguard-dns-filter.fhds").exists())
        assertFalse(File(assets, "rule-sets/adguard-dns-filter.source.json").exists())
        assertFalse(installer.contains("installEmbeddedRuleSet"))
        assertFalse(installer.contains("bundledDnsFilterManifest"))
        assertTrue(installer.contains("loadSignedBundleOrNull(targetDir) ?: return@withContext null"))
    }
}
