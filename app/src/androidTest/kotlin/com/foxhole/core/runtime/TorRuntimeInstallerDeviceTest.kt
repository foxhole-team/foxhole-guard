package com.foxhole.core.runtime

import android.system.Os
import androidx.test.core.app.ApplicationProvider
import com.foxhole.core.model.PrivacyRouteMode
import com.foxhole.core.model.PrivacyRouteScope
import com.foxhole.core.model.PrivacyRouteSettings
import com.foxhole.core.model.ProtocolHint
import com.foxhole.core.model.Settings
import com.foxhole.core.model.VpnSession
import com.foxhole.guard.FoxholeApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorRuntimeInstallerDeviceTest {
    @Test
    fun prepareCreatesWritableArtiStateWithoutExternalTorExecutable() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val paths = TorRuntimeInstaller(context).prepare()
            val stateDirectory = File(paths.dataDirectory)

            assertTrue(stateDirectory.isDirectory)
            assertTrue(stateDirectory.canWrite())
            assertTrue(stateDirectory.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            assertTrue(paths.bridges.isNotEmpty())
            assertTrue(paths.pluggableTransports.isNotEmpty())
            assertTrue(paths.pluggableTransports.all { transport -> File(transport.executablePath).isFile })
            assertFalse(File(context.applicationInfo.nativeLibraryDir, "libTor.so").exists())
        }
    }

    @Test
    fun assembledTorOverVpnConfigTranslatesToFoxCoreArti() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val paths = TorRuntimeInstaller(context).prepare()
            val config =
                assembler().assemble(
                    baseConfigJson = TOR_RUNTIME_BASE_CONFIG,
                    settings = torSettings(),
                    activePreset = null,
                    torRuntimePaths = paths,
                    vpnProtocolHint = ProtocolHint.VLESS,
                )

            val engine = translate(config, ProtocolHint.VLESS).engineConfigJson.asObject()
            val named = engine.getValue("outbounds").jsonArray.single().jsonObject
            assertEquals("tor", named.getValue("id").jsonPrimitive.content)
            assertEquals(
                "tor",
                named.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content,
            )
        }
    }

    @Test
    fun assembledTorOnlyConfigTranslatesToPrimaryFoxCoreArti() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val paths = TorRuntimeInstaller(context).prepare()
            val config =
                assembler().assembleTorOnly(
                    settings = torSettings(),
                    activePreset = null,
                    torRuntimePaths = paths,
                )

            val engine = translate(config, ProtocolHint.TOR).engineConfigJson.asObject()
            assertEquals("tor", engine.getValue("outbound").jsonObject.getValue("type").jsonPrimitive.content)
        }
    }

    @Test
    fun prepareIsIdempotentForArtiState() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val installer = TorRuntimeInstaller(context)
            val first = installer.prepare()
            val sentinel = File(first.dataDirectory, ".prepare-sentinel").apply { writeText("keep") }

            val second = installer.prepare()

            assertEquals(first, second)
            assertTrue(sentinel.isFile)
            assertEquals("keep", sentinel.readText())
        }
    }

    @Test
    fun cachedPrepareRepairsUnsafeArtiDirectoryPermissions() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val installer = TorRuntimeInstaller(context)
            val prepared = installer.prepare()
            val first = prepared.withIdentityVersion(TEST_IDENTITY_VERSION)
            val unrelatedDirectory = File(context.filesDir, "tor-installer-permission-test-unrelated")
            check(unrelatedDirectory.mkdirs() || unrelatedDirectory.isDirectory)
            val directories =
                listOf(
                    context.filesDir,
                    File(context.filesDir, "foxcore"),
                    File(context.filesDir, "foxcore/arti"),
                    File(prepared.dataDirectory),
                    File(first.dataDirectory),
                    File(first.dataDirectory, TEST_ARTI_STATE_DIRECTORY),
                    File(first.dataDirectory, TEST_ARTI_CACHE_DIRECTORY),
                )

            try {
                Os.chmod(unrelatedDirectory.absolutePath, UNSAFE_DIRECTORY_MODE)
                directories.forEach { directory -> Os.chmod(directory.absolutePath, UNSAFE_DIRECTORY_MODE) }

                assertEquals(first, installer.prepare().withIdentityVersion(TEST_IDENTITY_VERSION))
                assertPrivatePermissions(directories)

                directories.forEach { directory -> Os.chmod(directory.absolutePath, RESTRICTIVE_DIRECTORY_MODE) }
                assertEquals(first, installer.prepare().withIdentityVersion(TEST_IDENTITY_VERSION))
                assertPrivatePermissions(directories)

                assertEquals(
                    "an unrelated app-private directory must not be changed",
                    UNSAFE_DIRECTORY_MODE,
                    Os.stat(unrelatedDirectory.absolutePath).st_mode and PERMISSION_BITS_MASK,
                )
            } finally {
                directories.filter(File::exists).forEach { directory ->
                    Os.chmod(directory.absolutePath, PRIVATE_DIRECTORY_MODE)
                }
                if (unrelatedDirectory.exists()) {
                    Os.chmod(unrelatedDirectory.absolutePath, PRIVATE_DIRECTORY_MODE)
                    unrelatedDirectory.deleteRecursively()
                }
            }
        }
    }

    private fun assertPrivatePermissions(directories: List<File>) {
        directories.forEach { directory ->
            assertEquals(
                directory.absolutePath,
                PRIVATE_DIRECTORY_MODE,
                Os.stat(directory.absolutePath).st_mode and PERMISSION_BITS_MASK,
            )
        }
    }

    private companion object {
        const val PRIVATE_DIRECTORY_MODE = 448
        const val RESTRICTIVE_DIRECTORY_MODE = 365
        const val UNSAFE_DIRECTORY_MODE = 511
        const val PERMISSION_BITS_MASK = 511
        const val TEST_ARTI_STATE_DIRECTORY = "arti-state"
        const val TEST_ARTI_CACHE_DIRECTORY = "arti-cache"
        const val TEST_IDENTITY_VERSION = 9_876_543L

        val json =
            Json {
                ignoreUnknownKeys = false
                explicitNulls = false
            }

        fun assembler(): RuntimeConfigAssembler = RuntimeConfigAssembler(json)

        fun torSettings(): Settings =
            Settings(
                privacyRoute =
                    PrivacyRouteSettings(
                        permitted = true,
                        mode = PrivacyRouteMode.TOR_OVER_VPN,
                        scope = PrivacyRouteScope.ALL_APPS,
                    ),
            )

        fun translate(
            config: String,
            hint: ProtocolHint,
        ) = FoxCoreConfigTranslator(json).translate(
            VpnSession(
                profileId = 11L,
                profileName = "Arti device contract",
                protocolHint = hint,
                configJson = config,
                correlationId = "arti-device-contract",
                torActive = true,
            ),
        )

        fun String.asObject() = json.parseToJsonElement(this).jsonObject

        val TOR_RUNTIME_BASE_CONFIG =
            """
            {
              "inbounds": [
                {
                  "type": "tun",
                  "tag": "tun-in",
                  "interface_name": "foxhole",
                  "mtu": 1500,
                  "auto_route": true,
                  "strict_route": true,
                  "sniff": false,
                  "stack": "system",
                  "address": [
                    "172.19.0.1/30",
                    "fdfe:dcba:9876::1/126"
                  ]
                }
              ],
              "outbounds": [
                {
                  "type": "vless",
                  "tag": "proxy",
                  "server": "203.0.113.10",
                  "server_port": 443,
                  "uuid": "00000000-0000-4000-8000-000000000001",
                  "tls": {
                    "enabled": true,
                    "server_name": "edge.example"
                  }
                },
                { "type": "direct", "tag": "direct" },
                { "type": "block", "tag": "block" }
              ],
              "dns": {
                "strategy": "prefer_ipv4"
              },
              "route": {
                "rules": [],
                "final": "proxy"
              }
            }
            """.trimIndent()
    }
}
