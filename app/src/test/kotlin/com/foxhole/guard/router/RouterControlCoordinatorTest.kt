package com.foxhole.guard.router

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouterControlCoordinatorTest {
    private val identity = RouterTlsIdentity(RouterEndpoint("192.168.1.1"), "a".repeat(64))
    private val pair = RouterPairing(identity, "pair-" + "b".repeat(32), ByteArray(32) { 7 }, "Phone", true)
    private class PairStore(pair: RouterPairing) : RouterPairingStore {
        private var current = pair.copy()
        override suspend fun read() = current.copy()
        override suspend fun save(pairing: RouterPairing) { current.clear(); current = pairing.copy() }
        override suspend fun remove(expectedId: String) = error("unrelated")
    }
    private class Preferences(var value: RouterControlPreferences) : RouterControlPreferenceStore {
        override suspend fun read(pairingId: String) = value
        override suspend fun save(preferences: RouterControlPreferences) { value = preferences }
    }
    private class Fixture {
        var selected: String? = null
        var revision = 1
        var hold: String? = null
        var delayed: (() -> Unit)? = null
        var cancellations = 0
        var revoked = false
        var proofConnected = true
        var wanCountry: String? = null
        var countries = JsonArray(emptyList())
        val actions = mutableListOf<String>()
        fun state() = buildJsonObject {
            put("schema", 1); put("revision", revision)
            put("settings", buildJsonObject { put("localRules", true); put("localScope", "all") })
            put("countries", countries); put("devices", JsonArray(emptyList())); put("sites", JsonArray(emptyList())); put("clients", JsonArray(emptyList()))
            put("servers", JsonArray(listOf(buildJsonObject { put("id", "server-000000000001"); put("name", "Home"); put("protocol", "hysteria2"); put("endpoint", "vpn.example.com:443") })))
            put("runtime", buildJsonObject { put("desiredServerId", selected?.let(::JsonPrimitive) ?: JsonNull); put("connected", selected != null && proofConnected) })
        }
        fun api(identity: RouterTlsIdentity): RouterPanelApi = RouterPanelApi(identity, Call.Factory { request ->
            val payload = kotlinx.serialization.json.Json.parseToJsonElement(Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()).jsonObject
            val action = payload["action"]!!.jsonPrimitive.content
            actions += action
            object : Call by OkHttpClient().newCall(request) {
                override fun enqueue(responseCallback: Callback) {
                    val reply = {
                        val body = when (action) {
                            "phone.capabilities" -> UpdateDocuments.capabilities()
                            "pair.unlock" -> buildJsonObject { if (revoked) put("_foxhole_error", "unauthorized") else put("token", "p1." + "c".repeat(64)) }
                            "server.start" -> { selected = payload["payload"]!!.jsonObject["id"]!!.jsonPrimitive.content; revision++; state() }
                            "server.stop" -> { selected = null; revision++; state() }
                            "state" -> state()
                            "telemetry" -> buildJsonObject {
                                put("sample", buildJsonObject {
                                    put("wan", buildJsonObject { put("country", wanCountry?.let(::JsonPrimitive) ?: JsonNull) })
                                    put("vpn", buildJsonObject { put("country", "US") })
                                })
                            }
                            "settings" -> { countries = payload["payload"]!!.jsonObject["countries"] as JsonArray; revision++; state() }
                            else -> error("unexpected action $action")
                        }
                        responseCallback.onResponse(this, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                            .body(body.toString().toResponseBody("application/json".toMediaType())).build())
                    }
                    if (hold == action) delayed = reply else reply()
                }
                override fun cancel() { cancellations++ }
            }
        })
    }
    private fun transport(fixture: Fixture) = object : RouterWizardTransport {
        override fun gateway() = "192.168.1.1"
        override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = identity
        override fun api(identity: RouterTlsIdentity) = fixture.api(identity)
    }

    @Test fun closingTheRuntimeDoesNotPoisonTheNextOpenOrErasePairing() = runTest {
        val fixture = Fixture()
        val store = PairStore(pair)
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL"))
        val coordinator = RouterControlCoordinator(transport(fixture), store, preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        assertEquals(RouterControlStage.CONNECTED, coordinator.state.value.stage)
        coordinator.close()
        coordinator.loadPairingState()
        coordinator.open()
        assertEquals(RouterControlStage.CONNECTED, coordinator.state.value.stage)
        assertTrue(preferences.value.enabled)
        val retained = store.read()
        try {
            assertEquals(pair.id, retained.id)
            org.junit.Assert.assertArrayEquals(pair.credential, retained.credential)
        } finally { retained.clear(); coordinator.close() }
    }

    @Test fun storedKeyReopensWithoutAPinAndControlOffDoesNotConnect() = runTest {
        val fixture = Fixture()
        val preferences = Preferences(RouterControlPreferences(pair.id, countryCode = "NL"))
        val provisioner = RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences, provisioner, copyProfile = {})
        coordinator.open()
        assertEquals(RouterControlStage.DISABLED, coordinator.state.value.stage)
        assertTrue(fixture.actions.isEmpty())
        coordinator.setEnabled(true)
        assertEquals(RouterControlStage.CONNECTED, coordinator.state.value.stage)
        assertEquals(listOf("pair.unlock", "state", "phone.capabilities", "telemetry"), fixture.actions)
        assertTrue(preferences.value.enabled)
    }

    @Test fun rejectedPairingCannotEnableManagementFromSettings() = runTest {
        val fixture = Fixture().apply { revoked = true }
        val preferences = Preferences(RouterControlPreferences(pair.id))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.loadPairingState()
        assertTrue(coordinator.state.value.canToggleManagement)
        coordinator.setEnabled(true)
        assertFalse(preferences.value.enabled)
        assertFalse(coordinator.state.value.managementEnabled)
        assertFalse(coordinator.state.value.canToggleManagement)
        assertEquals(RouterControlStage.PAIRING_REQUIRED, coordinator.state.value.stage)
        assertEquals(listOf("pair.unlock"), fixture.actions)
        coordinator.close()
    }

    @Test fun autoCountryUsesRouterWanAndPreservesExplicitManualSelection() = runTest {
        val fixture = Fixture().also { it.wanCountry = "DE" }
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL"))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        assertEquals(RouterControlStage.CONNECTED, coordinator.state.value.stage)
        assertEquals("DE", preferences.value.countryCode)
        assertEquals("DE", coordinator.state.value.routerCountryCode)
        assertEquals(true, coordinator.state.value.countryDirectEffective)
        coordinator.setCountry("GB", true)
        assertFalse(preferences.value.countryAuto)
        fixture.wanCountry = "NL"
        coordinator.refresh()
        assertEquals("GB", preferences.value.countryCode)
        assertEquals("NL", coordinator.state.value.routerCountryCode)
        coordinator.setCountryAuto(true)
        assertTrue(preferences.value.countryAuto)
        assertEquals("NL", preferences.value.countryCode)
    }

    @Test fun enablingControlPreservesTheFreshlyAppliedAutomaticCountry() = runTest {
        val fixture = Fixture().also { it.wanCountry = "NL" }
        val preferences = Preferences(RouterControlPreferences(pair.id, countryCode = "DE"))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        coordinator.setEnabled(true)
        assertEquals(null, coordinator.state.value.error)
        assertEquals("NL", preferences.value.countryCode)
        assertEquals("NL", coordinator.state.value.preferences?.countryCode)
        assertEquals(true, coordinator.state.value.countryDirectEffective)
        coordinator.setEnabled(false)
        fixture.wanCountry = "GB"
        coordinator.setEnabled(true)
        assertEquals("GB", preferences.value.countryCode)
        assertEquals("GB", coordinator.state.value.preferences?.countryCode)
        assertTrue(preferences.value.countryAuto)
        coordinator.close()
    }

    @Test fun unknownWanDoesNotRewriteExistingRulesOrClaimVpnServerCountry() = runTest {
        val fixture = Fixture()
        fixture.countries = JsonArray(listOf(buildJsonObject { put("code", "NL"); put("route", "direct") }))
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL"))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        assertEquals("NL", preferences.value.countryCode)
        assertEquals(null, coordinator.state.value.routerCountryCode)
        assertEquals(null, coordinator.state.value.countryDirectEffective)
        assertFalse(fixture.actions.contains("settings"))
        assertEquals(1, fixture.revision)
    }

    @Test fun offlineWifiCannotFallBackOrInventAConnectedState() = runTest {
        var attempts = 0
        val transport = object : RouterWizardTransport {
            override fun gateway() = "192.168.1.1"
            override suspend fun inspectIdentity(endpoint: RouterEndpoint): RouterTlsIdentity = identity
            override fun api(identity: RouterTlsIdentity): RouterPanelApi { attempts++; throw RouterWifiRequired() }
        }
        val coordinator = RouterControlCoordinator(transport, PairStore(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        assertEquals(1, attempts)
        assertEquals(RouterControlStage.OFFLINE, coordinator.state.value.stage)
        assertEquals(RouterWizardError.WIFI, coordinator.state.value.error)
        assertFalse(coordinator.state.value.controlsAvailable)
    }

    @Test fun vpnActionBecomesCurrentOnlyAfterTheActualAcknowledgment() = runTest {
        val fixture = Fixture()
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        coordinator.selectServer("server-000000000001")
        fixture.hold = "server.start"
        val job = launch { coordinator.setVpn(true) }
        runCurrent()
        assertEquals(null, coordinator.state.value.snapshot?.desiredServerId)
        fixture.delayed!!.invoke()
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals("server-000000000001", coordinator.state.value.snapshot?.desiredServerId)
    }

    @Test fun closingCancelsAnExactRequestAndItsLateReplyCannotOverwriteReopen() = runTest {
        val fixture = Fixture()
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        fixture.hold = "pair.unlock"
        val first = launch { coordinator.open() }
        runCurrent()
        val oldReply = fixture.delayed!!
        coordinator.close()
        runCurrent()
        assertTrue(first.isCompleted); assertFalse(first.isCancelled); assertTrue(fixture.cancellations > 0)
        fixture.hold = null
        coordinator.open()
        val current = coordinator.state.value
        oldReply()
        runCurrent()
        assertEquals(current, coordinator.state.value)
    }

    @Test fun disablingManagementDoesNotStopRouterVpnOrEnableUnprovenAutomation() = runTest {
        val fixture = Fixture().apply { selected = "server-000000000001" }
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL"))
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        coordinator.setHomeAutodetection(true)
        assertEquals(RouterWizardError.UNSUPPORTED, coordinator.state.value.error)
        assertFalse(preferences.value.homeAutodetection)
        coordinator.setEnabled(false)
        assertFalse(preferences.value.enabled)
        assertEquals("server-000000000001", fixture.selected)
        assertFalse(fixture.actions.contains("server.stop"))
    }

    @Test fun aRequestedButNotYetValidatedProfileDoesNotCauseDuplicateStarts() = runTest {
        val fixture = Fixture().apply { proofConnected = false }
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), Preferences(RouterControlPreferences(pair.id, true, "NL")),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        coordinator.setVpn(true)
        coordinator.setVpn(true)
        assertEquals("server-000000000001", coordinator.state.value.snapshot?.desiredServerId)
        assertFalse(requireNotNull(coordinator.state.value.snapshot).connected)
        assertEquals(1, fixture.actions.count { it == "server.start" })
    }

    @Test fun aRevokedKeyRequiresPairingWithoutInventingAPinEntryOrErasingData() = runTest {
        val fixture = Fixture().apply { revoked = true }
        val store = PairStore(pair)
        val coordinator = RouterControlCoordinator(transport(fixture), store, Preferences(RouterControlPreferences(pair.id, true, "NL")),
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)), copyProfile = {})
        coordinator.open()
        assertEquals(RouterControlStage.PAIRING_REQUIRED, coordinator.state.value.stage)
        assertFalse(coordinator.state.value.controlsAvailable)
        assertEquals(pair.id, store.read().id)
        assertFalse(fixture.actions.contains("unlock"))
    }

    @Test fun closingTheSheetAfterAdmittedHomeEnableKeepsTheAuthoritativeFlag() = runTest {
        val fixture = Fixture()
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL"))
        var homeActive = false
        lateinit var coordinator: RouterControlCoordinator
        coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)),
            homeAutomationAvailable = true, applyHomeAutomation = { enabled ->
                preferences.save(preferences.value.copy(homeAutodetection = enabled))
                homeActive = enabled
                coordinator.close()
            }, homeAutomationActive = { homeActive }, copyProfile = {})
        coordinator.open()
        val enable = launch { coordinator.setHomeAutodetection(true) }
        runCurrent()
        assertTrue(enable.isCompleted)
        assertTrue(homeActive)
        assertTrue(preferences.value.homeAutodetection)
        coordinator.open()
        assertTrue(coordinator.state.value.homeAutomationEffective)
        assertTrue(requireNotNull(coordinator.state.value.preferences).homeAutodetection)
        coordinator.close()
    }


    @Test fun backgroundHomeOffUpdatesTheVisibleSwitchWithoutDisturbingABusyVpnAction() = runTest {
        val fixture = Fixture()
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL", homeAutodetection = true))
        var active = true
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)),
            homeAutomationAvailable = true, applyHomeAutomation = {}, homeAutomationActive = { active }, copyProfile = {})
        coordinator.open()
        assertTrue(coordinator.state.value.homeAutomationEffective)
        fixture.hold = "server.start"
        val start = launch { coordinator.setVpn(true) }; runCurrent()
        val before = coordinator.state.value
        active = false
        coordinator.homeMonitoringChanged(false)
        assertFalse(coordinator.state.value.homeAutomationEffective)
        assertTrue(coordinator.state.value.busy)
        assertEquals(before.snapshot, coordinator.state.value.snapshot)
        assertEquals(before.selectedServerId, coordinator.state.value.selectedServerId)
        fixture.delayed!!.invoke(); runCurrent()
        assertTrue(start.isCompleted)
        assertFalse(coordinator.state.value.busy)
        assertFalse(coordinator.state.value.homeAutomationEffective)
        assertEquals("server-000000000001", coordinator.state.value.snapshot?.desiredServerId)
        coordinator.close()
    }


    @Test fun reopeningAPersistedHomePreferenceNeverAdmitsAnInactiveMonitor() = runTest {
        val fixture = Fixture()
        val preferences = Preferences(RouterControlPreferences(pair.id, true, "NL", homeAutodetection = true))
        var admissions = 0
        val coordinator = RouterControlCoordinator(transport(fixture), PairStore(pair), preferences,
            RouterHttpProvisioner(localAddress = { "192.168.1.101" }, io = StandardTestDispatcher(testScheduler)),
            homeAutomationAvailable = true, applyHomeAutomation = { admissions++ }, homeAutomationActive = { false }, copyProfile = {})
        coordinator.open()
        coordinator.close()
        coordinator.open()
        assertEquals(0, admissions)
        assertFalse(coordinator.state.value.homeAutomationEffective)
        assertTrue(preferences.value.homeAutodetection)
        coordinator.close()
    }

}
