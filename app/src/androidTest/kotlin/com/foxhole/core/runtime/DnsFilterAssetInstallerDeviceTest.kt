package com.foxhole.core.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.foxhole.guard.runtime.DnsFilterAssetInstaller
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DnsFilterAssetInstallerDeviceTest {
    @Test
    fun freshInstallRequiresDownloadedSignedDnsRuleSet() =
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

            assertNull(installer.prepareVerifiedOrNull())
        }

    private companion object {
        const val TARGET_DIR_NAME = "dns-rule-sets"
    }
}
