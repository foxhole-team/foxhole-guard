package com.foxhole.beta.vpn

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DnsFilterAssetInstallerDeviceTest {
    @Test
    fun freshInstallPreparesBundledDnsRuleSetForRuntime() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val targetDir = File(context.filesDir, TARGET_DIR_NAME)
            targetDir.deleteRecursively()

            val installer =
                DnsFilterAssetInstaller(
                    appContext = context,
                    json =
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                )

            val paths = installer.prepareVerifiedOrNull()
            assertNotNull(paths)
            val installedRuleSet = File(requireNotNull(paths).adGuardDnsFilterPath)
            val installedManifest = File(targetDir, VERIFIED_DNS_FILTER_MANIFEST_NAME)
            val bundledRuleSet =
                context.assets.open(ADGUARD_DNS_FILTER_ASSET_PATH).use { input ->
                    input.readBytes()
                }

            assertEquals(VERIFIED_DNS_FILTER_FILE_NAME, installedRuleSet.name)
            assertTrue(installedRuleSet.isFile)
            assertTrue(installedManifest.isFile)
            assertEquals(bundledRuleSet.size.toLong(), installedRuleSet.length())
            assertArrayEquals(bundledRuleSet, installedRuleSet.readBytes())
        }

    private companion object {
        const val TARGET_DIR_NAME = "dns-rule-sets"
        const val ADGUARD_DNS_FILTER_ASSET_PATH = "rule-sets/adguard-dns-filter.srs"
        const val VERIFIED_DNS_FILTER_FILE_NAME = "adguard-dns-filter.verified.srs"
        const val VERIFIED_DNS_FILTER_MANIFEST_NAME = "adguard-dns-filter.verified.manifest.json"
    }
}
