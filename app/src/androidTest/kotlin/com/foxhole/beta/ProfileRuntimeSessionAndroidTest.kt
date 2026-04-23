package com.foxhole.beta

import android.net.VpnService
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.foxhole.beta.core.model.PerAppRoutingMode
import com.foxhole.beta.core.model.Settings
import com.foxhole.beta.core.model.TrafficMode
import com.foxhole.beta.core.model.TunStack
import com.foxhole.beta.core.model.ConnectionState
import com.foxhole.beta.vpn.TunnelValidationEvidenceClassifier
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileRuntimeSessionAndroidTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun logsCurrentActiveProfileRuntimeSummary() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val profiles = app.container.profileDatabase.profileDao().observeProfiles().first()
            Log.d(
                TEST_TAG,
                "profiles=${profiles.joinToString { "${it.id}:${it.name}:${it.isActive}" }}",
            )

            val active = app.container.profileRepository.getActiveProfile()
            if (active == null) {
                Log.d(TEST_TAG, "active profile is missing")
                return@runBlocking
            }

            val session = app.container.profileRepository.getSession(active.id)
            val root = json.parseToJsonElement(session.configJson).jsonObject
            val primary = root["outbounds"]!!.jsonArray.first().jsonObject
            val serverPort = primary["server_port"]!!.jsonPrimitive.content
            val outboundType = primary["type"]!!.jsonPrimitive.content

            Log.d(
                TEST_TAG,
                "activeProfileId=${active.id} name=${active.name} outboundType=$outboundType serverPort=$serverPort",
            )
            assertTrue(serverPort.isNotBlank())
        }

    @Test
    fun freshExactImportPreservesUserServerPort() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported = app.container.profileRepository.importProfile(EXACT_USER_XRAY_CONFIG)
            app.container.connectionController.setActiveProfile(imported.id)

            val session = app.container.profileRepository.getSession(imported.id)
            val root = json.parseToJsonElement(session.configJson).jsonObject
            val primary = root["outbounds"]!!.jsonArray.first().jsonObject
            val routeRules = root["route"]!!.jsonObject["rules"]!!.jsonArray

            assertEquals("43000", primary["server_port"]!!.jsonPrimitive.content)
            assertEquals(false, routeRules[0].jsonObject["port"]!!.jsonPrimitive.isString)

            Log.d(
                TEST_TAG,
                "freshImport profileId=${imported.id} serverPort=${primary["server_port"]!!.jsonPrimitive.content}",
            )
        }
    }

    @Test
    fun freshDirectShareImportsPreserveProvidedRuntimeFields() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            resetRelevantSettings(app)

            clearProfiles(app)
            val importedVless = app.container.profileRepository.importProfile(DIRECT_VLESS_REALITY_URI)
            val vlessSession = app.container.profileRepository.getSession(importedVless.id)
            val vlessRoot = json.parseToJsonElement(vlessSession.configJson).jsonObject
            val vlessOutbound = vlessRoot["outbounds"]!!.jsonArray.first().jsonObject
            val vlessTls = vlessOutbound["tls"]!!.jsonObject
            val vlessReality = vlessTls["reality"]!!.jsonObject
            val vlessUtls = vlessTls["utls"]!!.jsonObject
            assertEquals("axn666.nl", vlessOutbound["server"]!!.jsonPrimitive.content)
            assertEquals("8447", vlessOutbound["server_port"]!!.jsonPrimitive.content)
            assertEquals("www.microsoft.com", vlessTls["server_name"]!!.jsonPrimitive.content)
            assertEquals("chrome", vlessUtls["fingerprint"]!!.jsonPrimitive.content)
            assertEquals(
                "WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM",
                vlessReality["public_key"]!!.jsonPrimitive.content,
            )
            assertEquals("03d0b309d56c352b", vlessReality["short_id"]!!.jsonPrimitive.content)

            clearProfiles(app)
            val importedHysteria2 = app.container.profileRepository.importProfile(DIRECT_HYSTERIA2_URI)
            val hysteria2Session = app.container.profileRepository.getSession(importedHysteria2.id)
            val hysteria2Root = json.parseToJsonElement(hysteria2Session.configJson).jsonObject
            val hysteria2Outbound = hysteria2Root["outbounds"]!!.jsonArray.first().jsonObject
            val hysteria2Tls = hysteria2Outbound["tls"]!!.jsonObject

            assertEquals("axn666.nl", hysteria2Outbound["server"]!!.jsonPrimitive.content)
            assertEquals("8443", hysteria2Outbound["server_port"]!!.jsonPrimitive.content)
            assertEquals("axn666.nl", hysteria2Tls["server_name"]!!.jsonPrimitive.content)
            assertEquals(
                "RLS3MLv81RhMPNr5xHRnqGmEPUkIFxxI1fTbAqzmZ+s=",
                hysteria2Outbound["password"]!!.jsonPrimitive.content,
            )
        }
    }

    @Test
    fun manualFreshImportConnectsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveConnect") != "1") {
            Log.d(TEST_TAG, "manual live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            if (VpnService.prepare(app) != null) {
                Log.d(TEST_TAG, "manual live connect skipped: vpn permission missing")
                return@runBlocking
            }
            resetRelevantSettings(app)
            clearProfiles(app)

            val imported = app.container.profileRepository.importProfile(EXACT_USER_XRAY_CONFIG)
            app.container.connectionController.setActiveProfile(imported.id)
            Log.d(TEST_TAG, "manual live connect imported profileId=${imported.id}")

            app.container.connectionController.connect(imported.id)
            delay(20_000)
            app.container.connectionController.disconnect()
            delay(3_000)
        }
    }

    @Test
    fun manualDirectShareLinksLogTerminalStateAndDiagnostics() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveDirectLinks") != "1") {
            Log.d(TEST_TAG, "manual direct-link live connect skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            if (VpnService.prepare(app) != null) {
                Log.d(TEST_TAG, "manual direct-link live connect skipped: vpn permission missing")
                return@runBlocking
            }
            resetRelevantSettings(app)

            DIRECT_LINK_CASES.forEach { linkCase ->
                clearProfiles(app)
                val imported = app.container.profileRepository.importProfile(linkCase.rawLink)
                app.container.connectionController.setActiveProfile(imported.id)
                val startedAt = System.currentTimeMillis()
                Log.d(TEST_TAG, "liveDirectLink start label=${linkCase.label} profileId=${imported.id}")

                app.container.connectionController.connect(imported.id)
                val terminalState: ConnectionState? =
                    withTimeoutOrNull(35_000) {
                        waitForTerminalState(app)
                    }
                delay(2_000)
                val evidence =
                    TunnelValidationEvidenceClassifier.classify(
                        entries = app.container.diagnosticsLogger.entries.value,
                        sinceMs = startedAt,
                    )
                val terminalMessage = app.container.connectionController.snapshot.value.message
                val terminalStateLabel = terminalState?.name ?: "TIMEOUT"
                Log.d(
                    TEST_TAG,
                    "liveDirectLink result label=${linkCase.label} terminalState=$terminalStateLabel message=${terminalMessage.orEmpty()} fatal=${evidence.fatalRuntimeMessage.orEmpty()} successTraffic=${evidence.hasSuccessfulTunnelActivity}",
                )
                app.container.connectionController.disconnect()
                delay(3_000)
            }
        }
    }

    @Test
    fun restoreBaselineRuntimeSettingsWhenRequested() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.restoreRuntimeBaseline") != "1") {
            Log.d(TEST_TAG, "restore runtime baseline skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            baselineRuntimeSettings(app)
            Log.d(TEST_TAG, "restore runtime baseline applied")
        }
    }

    @Test
    fun liveOptionProbeMatrixLogsVpnBoundIpResults() {
        if (InstrumentationRegistry.getArguments().getString("foxhole.liveOptionProbe") != "1") {
            Log.d(TEST_TAG, "live option probe skipped")
            return
        }
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<FoxholeApplication>()
            val active = app.container.profileRepository.getActiveProfile()
            if (active == null) {
                Log.d(TEST_TAG, "live option probe skipped: active profile is missing")
                return@runBlocking
            }
            if (VpnService.prepare(app) != null) {
                Log.d(TEST_TAG, "live option probe skipped: vpn permission missing")
                return@runBlocking
            }
            val settingsRepository = app.container.settingsRepository
            val previousSettings = settingsRepository.current()
            try {
                baselineRuntimeSettings(app)
                val targetOptions =
                    active.protocolOptions.filter { option ->
                        option.displayName.contains("VLESS", ignoreCase = true) ||
                            option.displayName.contains("TROJAN", ignoreCase = true) ||
                            option.displayName.contains("SHADOWSOCKS", ignoreCase = true)
                    }
                if (targetOptions.isEmpty()) {
                    Log.d(TEST_TAG, "live option probe skipped: target options are missing")
                    return@runBlocking
                }
                val variants =
                    listOf(
                        ProbeVariant(name = "system-strict", tunStack = TunStack.SYSTEM, strictRoute = true),
                        ProbeVariant(name = "system-relaxed", tunStack = TunStack.SYSTEM, strictRoute = false),
                        ProbeVariant(name = "gvisor-strict", tunStack = TunStack.GVISOR, strictRoute = true),
                        ProbeVariant(name = "gvisor-relaxed", tunStack = TunStack.GVISOR, strictRoute = false),
                    )
                targetOptions.forEach { option ->
                    variants.forEach { variant ->
                        app.container.connectionController.disconnect()
                        delay(3_000)
                        applyProbeVariant(app, variant)
                        app.container.connectionController.connect(active.id, protocolOptionId = option.id)
                        val terminalState =
                            withTimeoutOrNull(35_000) {
                                while (true) {
                                    val state = app.container.connectionController.snapshot.value.state
                                    if (state.name == "CONNECTED" || state.name == "ERROR" || state.name == "IDLE") {
                                        return@withTimeoutOrNull state.name
                                    }
                                    delay(250)
                                }
                            } ?: "TIMEOUT"
                        val ipResult =
                            runCatching {
                                app.container.connectionController.refreshIpInfo()
                            }
                        val message =
                            buildString {
                                append("probe option=")
                                append(option.displayName)
                                append(" variant=")
                                append(variant.name)
                                append(" terminalState=")
                                append(terminalState)
                                append(" ipResult=")
                                if (ipResult.isSuccess) {
                                    append("ok:")
                                    append(ipResult.getOrThrow().ipv4 ?: ipResult.getOrThrow().ipv6 ?: "unknown")
                                } else {
                                    append("fail:")
                                    append(ipResult.exceptionOrNull()?.message.orEmpty())
                                }
                            }
                        Log.d(TEST_TAG, message)
                        app.container.connectionController.disconnect()
                        delay(3_000)
                    }
                }
            } finally {
                restoreSettings(settingsRepository, previousSettings)
            }
        }
    }

    private suspend fun resetRelevantSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateTrafficMode(TrafficMode.TUNNEL)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
        }
    }

    private suspend fun baselineRuntimeSettings(app: FoxholeApplication) {
        with(app.container.settingsRepository) {
            updateTrafficMode(TrafficMode.TUNNEL)
            updateTunStack(TunStack.SYSTEM)
            updatePerAppRoutingMode(PerAppRoutingMode.FULL_TUNNEL)
            updateSelectedPackages(emptyList())
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updateAllowPrivateOutboundHosts(false)
            updateStrictRoute(true)
            updatePreferIpv6(false)
        }
    }

    private suspend fun applyProbeVariant(
        app: FoxholeApplication,
        variant: ProbeVariant,
    ) {
        with(app.container.settingsRepository) {
            updateTunStack(variant.tunStack)
            updateStrictRoute(variant.strictRoute)
            updateSniff(false)
            updateRouteOnly(false)
            updateBypassLan(false)
            updatePreferIpv6(false)
        }
    }

    private suspend fun restoreSettings(
        repository: com.foxhole.beta.core.settings.SettingsRepository,
        settings: Settings,
    ) {
        repository.replaceForTests(settings)
    }

    private suspend fun waitForTerminalState(app: FoxholeApplication): ConnectionState {
        while (true) {
            val state = app.container.connectionController.snapshot.value.state
            if (state == ConnectionState.CONNECTED || state == ConnectionState.ERROR || state == ConnectionState.IDLE) {
                return state
            }
            delay(250)
        }
    }

    private fun clearProfiles(app: FoxholeApplication) {
        app.container.profileDatabase.clearAllTables()
        deleteChildren(File(app.filesDir, "profile-secrets"))
    }

    private fun deleteChildren(dir: File) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteChildren(child)
            }
            child.delete()
        }
    }

    companion object {
        private const val TEST_TAG = "FoxholeSessionTest"
        private data class DirectLinkCase(
            val label: String,
            val rawLink: String,
        )

        private val DIRECT_LINK_CASES =
            listOf(
                DirectLinkCase(
                    label = "vless-reality",
                    rawLink = DIRECT_VLESS_REALITY_URI,
                ),
                DirectLinkCase(
                    label = "hysteria2",
                    rawLink = DIRECT_HYSTERIA2_URI,
                ),
            )

        private const val DIRECT_VLESS_REALITY_URI =
            "vless://6e9f4de4c1423d55cff3968370533d73@axn666.nl:8447?type=tcp&encryption=none&security=reality&sni=www.microsoft.com&pbk=WG2E71GvJTVvpUigKJ7UgC0-XyarAVTkvPQMbH8h2iM&sid=03d0b309d56c352b&fp=chrome&spx=/#axnet_secure_core_direct_37792e"

        private const val DIRECT_HYSTERIA2_URI =
            "hysteria2://RLS3MLv81RhMPNr5xHRnqGmEPUkIFxxI1fTbAqzmZ+s=@axn666.nl:8443?sni=axn666.nl"

        private const val EXACT_USER_XRAY_CONFIG =
            """
            {
              "dns": {
                "hosts": {
                  "domain:googleapis.cn": "googleapis.com",
                  "dot.pub": [
                    "1.12.12.12",
                    "120.53.53.53"
                  ],
                  "dns.alidns.com": [
                    "223.5.5.5",
                    "223.6.6.6",
                    "2400:3200::1",
                    "2400:3200:baba::1"
                  ],
                  "one.one.one.one": [
                    "1.1.1.1",
                    "1.0.0.1",
                    "2606:4700:4700::1111",
                    "2606:4700:4700::1001"
                  ],
                  "dns.cloudflare.com": [
                    "104.16.132.229",
                    "104.16.133.229",
                    "2606:4700::6810:84e5",
                    "2606:4700::6810:85e5"
                  ],
                  "cloudflare-dns.com": [
                    "104.16.248.249",
                    "104.16.249.249",
                    "2606:4700::6810:f8f9",
                    "2606:4700::6810:f9f9"
                  ],
                  "dns.google": [
                    "8.8.8.8",
                    "8.8.4.4",
                    "2001:4860:4860::8888",
                    "2001:4860:4860::8844"
                  ],
                  "dns.quad9.net": [
                    "9.9.9.9",
                    "149.112.112.112",
                    "2620:fe::fe",
                    "2620:fe::9"
                  ],
                  "common.dot.dns.yandex.net": [
                    "77.88.8.8",
                    "77.88.8.1",
                    "2a02:6b8::feed:0ff",
                    "2a02:6b8:0:1::feed:0ff"
                  ]
                },
                "servers": [
                  "8.8.8.8"
                ]
              },
              "inbounds": [
                {
                  "listen": "127.0.0.1",
                  "port": 10808,
                  "protocol": "socks",
                  "settings": {
                    "auth": "noauth",
                    "udp": true,
                    "userLevel": 8
                  },
                  "sniffing": {
                    "destOverride": [
                      "http",
                      "tls"
                    ],
                    "enabled": true,
                    "routeOnly": false
                  },
                  "tag": "socks"
                },
                {
                  "listen": "127.0.0.1",
                  "port": 10809,
                  "protocol": "http",
                  "settings": {
                    "userLevel": 8
                  },
                  "tag": "http"
                }
              ],
              "log": {
                "loglevel": "warning"
              },
              "outbounds": [
                {
                  "mux": {
                    "concurrency": -1,
                    "enabled": false
                  },
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "37.139.40.59",
                        "port": 43000,
                        "users": [
                          {
                            "encryption": "none",
                            "flow": "xtls-rprx-vision",
                            "id": "1ab729d2-fd63-493d-bf9c-2ee83c01ee3b",
                            "level": 8
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "tcp",
                    "realitySettings": {
                      "allowInsecure": false,
                      "fingerprint": "chrome",
                      "publicKey": "IiUOQtR3zqUg32FfqorRXwUVSz9e1CSPFsJnafcFVmE",
                      "serverName": "api-maps.yandex.ru",
                      "shortId": "736acf61",
                      "show": false,
                      "spiderX": "/"
                    },
                    "security": "reality",
                    "tcpSettings": {
                      "header": {
                        "type": "none"
                      }
                    }
                  },
                  "tag": "proxy"
                },
                {
                  "protocol": "freedom",
                  "settings": {
                    "domainStrategy": "UseIP"
                  },
                  "tag": "direct"
                },
                {
                  "protocol": "blackhole",
                  "settings": {
                    "response": {
                      "type": "http"
                    }
                  },
                  "tag": "block"
                }
              ],
              "policy": {
                "levels": {
                  "8": {
                    "connIdle": 300,
                    "downlinkOnly": 1,
                    "handshake": 4,
                    "uplinkOnly": 1
                  }
                },
                "system": {
                  "statsOutboundUplink": true,
                  "statsOutboundDownlink": true
                }
              },
              "remarks": "💫 Игровой белый интернет",
              "routing": {
                "domainStrategy": "AsIs",
                "rules": [
                  {
                    "ip": [
                      "8.8.8.8"
                    ],
                    "outboundTag": "proxy",
                    "port": "53",
                    "type": "field"
                  },
                  {
                    "ip": [
                      "223.5.5.5"
                    ],
                    "outboundTag": "direct",
                    "port": "53",
                    "type": "field"
                  }
                ]
              },
              "stats": {}
            }
            """
    }

    private data class ProbeVariant(
        val name: String,
        val tunStack: TunStack,
        val strictRoute: Boolean,
    )
}
