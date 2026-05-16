package com.foxhole.beta.vpn

import androidx.test.core.app.ApplicationProvider
import com.foxhole.beta.FoxholeApplication
import com.foxhole.beta.core.model.PrivacyRouteMode
import com.foxhole.beta.core.model.PrivacyRouteScope
import com.foxhole.beta.core.model.PrivacyRouteSettings
import com.foxhole.beta.core.model.ProtocolHint
import com.foxhole.beta.core.model.Settings
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorRuntimeInstallerDeviceTest {
    @Test
    fun prepareCopiesTorBundleAndReturnsExecutableAssetPath() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val paths = TorRuntimeInstaller(context).prepare()
            val executable = File(paths.executablePath)

            assertTrue(executable.isFile)
            assertTrue(executable.canExecute())
            assertTrue(paths.executablePath.contains(context.applicationInfo.nativeLibraryDir))
            assertTrue(paths.executablePath.endsWith("/libTor.so"))
            assertTrue(paths.dataDirectory.contains("/files/tor-data/"))
            assertTrue(File(paths.geoIpFilePath.orEmpty()).isFile)
            assertTrue(File(paths.geoIpv6FilePath.orEmpty()).isFile)
            val torrcDefaults = File(paths.torrcDefaultsFilePath.orEmpty())
            assertTrue(torrcDefaults.isFile)
            assertTrue(torrcDefaults.readText().contains("/tor/pluggable_transports/lyrebird"))
        }
    }

    @Test
    fun assembledTorOverVpnConfigIsAcceptedByLibbox() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val paths = TorRuntimeInstaller(context).prepare()
            val config =
                RuntimeConfigAssembler(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                ).assemble(
                    baseConfigJson = TOR_RUNTIME_BASE_CONFIG,
                    settings =
                        Settings(
                            privacyRoute =
                                PrivacyRouteSettings(
                                    mode = PrivacyRouteMode.TOR_OVER_VPN,
                                    scope = PrivacyRouteScope.ALL_APPS,
                                ),
                        ),
                    activePreset = null,
                    torRuntimePaths = paths,
                    vpnProtocolHint = ProtocolHint.VLESS,
                )
            val reflection =
                LibboxReflection(
                    context = context,
                    diagnosticsLogger = context.appGraph.diagnosticsLogger,
                    isNetworkActivityLoggingEnabled = { false },
                )
            assumeTrue("libbox is unavailable on this device", reflection.isAvailable())
            reflection.setupIfNeeded()
            val host =
                object : RuntimeServiceHost {
                    override val runtimeContext = context

                    override fun stopRuntimeService() = Unit

                    override fun protectSocket(socket: Int): Boolean = true
                }
            val monitor = DefaultNetworkMonitor(context, reflection, context.appGraph.diagnosticsLogger)
            val server =
                reflection.newCommandServer(
                    reflection.commandServerHandlerProxy(
                        onReload = {},
                        onStop = {},
                        onDebug = {},
                    ),
                    reflection.platformProxy(host, monitor) { _, _ ->
                        error("Tor config validation must not open a TUN device")
                    },
                )
            try {
                reflection.startServer(server)
                reflection.checkConfig(server, config)
            } finally {
                runCatching { reflection.closeServer(server) }
            }
        }
    }

    @Test
    fun prepareIsIdempotentForInstalledTorBundle() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val installer = TorRuntimeInstaller(context)
            val first = installer.prepare()
            val abi = File(first.dataDirectory).name
            val sentinel = File(context.filesDir, "tor/$abi/.prepare-sentinel").apply { writeText("keep") }

            val second = installer.prepare()

            assertEquals(first, second)
            assertTrue(sentinel.isFile)
            assertEquals("keep", sentinel.readText())
        }
    }

    private companion object {
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
                { "type": "direct", "tag": "proxy" },
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
